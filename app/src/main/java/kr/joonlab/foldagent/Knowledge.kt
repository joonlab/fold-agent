package kr.joonlab.foldagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 에이전트의 기억. 세 층이다.
 *   knowledge.md   — 사람·Claude Code 가 로그를 분석해 다듬는 노트. 시스템 프롬프트에 통째로 들어간다(맥에서 push).
 *   learned.json   — 폰이 실행 중에 스스로 배운 사실: 앱 별칭 · 좌표 탭이 필요한 앱.
 *   recipes.jsonl  — 성공한 실행의 경로. 비슷한 요청이 오면 참고로 보여 준다(👎 받은 건 제외).
 * 전부 /sdcard/Android/data/kr.joonlab.foldagent/files/ 에 있어 adb 로 읽고 쓸 수 있다.
 */
class Knowledge(ctx: Context) {

    private val dir = ctx.getExternalFilesDir(null)!!
    private val notesFile = File(dir, "knowledge.md")
    private val learnedFile = File(dir, "learned.json")
    private val recipesFile = File(dir, "recipes.jsonl")
    private val appsDir = File(dir, "apps")   // apps/<pkg>.md — 그 앱에 들어갈 때만 모델에게 보여 준다
    private val curatedAliasFile = File(dir, "aliases.json")   // 맥에서 만든 별칭(유튜브→패키지). learned.json 과 분리해 서로 덮어쓰지 않는다

    init {
        if (!notesFile.exists()) notesFile.writeText(SEED_NOTES)
        if (!learnedFile.exists()) learnedFile.writeText(
            JSONObject().put("aliases", JSONObject().put(norm("티맵"), "com.skt.tmap.ku")).put("gesturePkgs", JSONObject()).toString(2)
        )
    }

    fun notes(): String = runCatching { notesFile.readText() }.getOrDefault("")

    /** 앱별 상세 요령(화면 구조·하는 법·딥링크·주의). 없으면 null. */
    fun appNotes(pkg: String): String? =
        File(appsDir, "$pkg.md").takeIf { it.exists() }?.let { runCatching { it.readText().trim() }.getOrNull() }
    fun version(): String = "notes=${notesFile.length()}@${notesFile.lastModified()} learned=${learnedFile.lastModified()} apps=${appsDir.list()?.size ?: 0}@${appsDir.lastModified()}"

    /** knowledge.md 의 「## 음성 인식 힌트」 절 → 음성 인식기 바이어싱 단어. */
    fun biasTerms(): List<String> {
        val lines = notes().lines()
        val start = lines.indexOfFirst { it.trim().startsWith("## 음성 인식 힌트") }
        if (start < 0) return emptyList()
        return lines.drop(start + 1).takeWhile { !it.trim().startsWith("## ") }
            .filter { it.trim().startsWith("-") }
            .flatMap { it.trim().removePrefix("-").split(',', '·') }
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(100)
    }

    // ------------------------------------------------------------ learned.json

    @Synchronized private fun learned(): JSONObject =
        runCatching { JSONObject(learnedFile.readText()) }.getOrDefault(JSONObject())

    @Synchronized private fun update(block: (JSONObject) -> Unit) {
        val j = learned(); block(j); learnedFile.writeText(j.toString(2))
    }

    fun aliasPkg(name: String): String? =
        learned().optJSONObject("aliases")?.optString(norm(name))?.takeIf { it.isNotEmpty() }
            ?: runCatching { JSONObject(curatedAliasFile.readText()).optString(norm(name)) }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * 요청 문장에 나온 앱(별칭 포함) — 앞에 나온 순서로. 앱 요령(딥링크·★ 안내)은 그 앱이 앞에 떠야 주입돼서,
     * 모델이 요령을 보려고 딥링크 앞에 open_app 을 먼저 불렀다(2026-09-26 L3·X1·X2). 첫 메시지에 미리 넣으려고 쓴다.
     */
    fun mentionedPkgs(text: String, max: Int = 2): List<String> {
        val t = norm(text)
        val all = HashMap<String, String>()
        runCatching { JSONObject(curatedAliasFile.readText()).let { j -> j.keys().forEach { k -> all[k] = j.getString(k) } } }
        learned().optJSONObject("aliases")?.let { j -> j.keys().forEach { k -> all[k] = j.getString(k) } }
        // 「노트북」의 「노트」처럼 다른 낱말 속에 든 이름은 뺀다 — 이름 뒤가 끝·한글 아님·조사/동사일 때만(2026-09-26 L4 노트 요령 오주입)
        val after = listOf("에서", "에", "으로", "로", "앱", "의", "을", "를", "이", "가", "은", "는", "한테", "에게", "도", "까지", "랑", "와", "과", "열", "켜", "들어", "틀", "좀", "끝", "목록")
        fun ok(k: String): Int {
            var i = t.indexOf(k)
            while (i >= 0) {
                val rest = t.substring(i + k.length)
                if (rest.isEmpty() || rest[0] !in '가'..'힣' || after.any { rest.startsWith(it) }) return i
                i = t.indexOf(k, i + 1)
            }
            return -1
        }
        return all.filter { (k, _) -> k.length >= 2 }
            .map { (k, pkg) -> ok(k) to pkg }.filter { it.first >= 0 }
            .sortedBy { it.first }.map { it.second }
            .filter { File(appsDir, "$it.md").exists() }
            .distinct().take(max)
    }

    fun addAlias(name: String, pkg: String) = update { j ->
        (j.optJSONObject("aliases") ?: JSONObject().also { j.put("aliases", it) }).put(norm(name), pkg)
    }

    fun prefersGesture(pkg: String): Boolean = learned().optJSONObject("gesturePkgs")?.has(pkg) == true

    fun markGesture(pkg: String) = update { j ->
        (j.optJSONObject("gesturePkgs") ?: JSONObject().also { j.put("gesturePkgs", it) }).put(pkg, System.currentTimeMillis())
    }

    fun learnedSummary(): String {
        val j = learned()
        val a = j.optJSONObject("aliases")
        val g = j.optJSONObject("gesturePkgs")
        val sb = StringBuilder()
        if (a != null && a.length() > 0) sb.append("- 앱 별칭(사용자 호칭 → 패키지): ")
            .append(a.keys().asSequence().joinToString(", ") { "$it→${a.getString(it)}" }).append('\n')
        if (g != null && g.length() > 0) sb.append("- 접근성 클릭이 무시돼 좌표 탭을 쓰는 앱: ")
            .append(g.keys().asSequence().joinToString(", ")).append('\n')
        return sb.toString()
    }

    // ------------------------------------------------------------ recipes.jsonl

    // recipes.jsonl 은 기록 영구 삭제(records/Purge)가 통째로 다시 쓴다 — 인스턴스 락(@Synchronized)으로는
    // 서비스의 Knowledge 와 삭제 쪽이 서로를 못 막아서, 파일 단위 전역 락(RECIPES_LOCK)을 같이 쓴다.
    fun addRecipe(r: JSONObject) = synchronized(RECIPES_LOCK) {
        runCatching { recipesFile.appendText(r.toString() + "\n") }
        Unit
    }

    fun markBad(runId: String) = synchronized(RECIPES_LOCK) {
        runCatching { recipesFile.appendText(JSONObject().put("run", runId).put("bad", true).toString() + "\n") }
        Unit
    }

    /** 요청과 글자 2-gram 이 가장 많이 겹치는 과거 성공 경로 k 개. */
    fun similar(request: String, k: Int = 2): List<JSONObject> = synchronized(RECIPES_LOCK) {
        if (!recipesFile.exists()) return emptyList()
        val rows = recipesFile.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        val bad = rows.filter { it.optBoolean("bad") }.map { it.optString("run") }.toSet()
        val q = grams(request)
        if (q.isEmpty()) return emptyList()
        return rows.filter { !it.optBoolean("bad") && it.optString("run") !in bad && it.has("request") }
            .map { it to jaccard(q, grams(it.getString("request"))) }
            .filter { it.second >= 0.3 }
            .sortedByDescending { it.second }
            .distinctBy { it.first.getString("request") }
            .take(k).map { it.first }
    }

    companion object {
        private val RECIPES_LOCK = Any()

        fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), "")

        /**
         * 영구 삭제한 기록의 run 줄(성공·bad 모두)을 recipes.jsonl 에서 빼고 다시 쓴다. 뺀 줄 수를 돌려준다.
         * bad 줄을 덧붙이는 방식은 쓰지 않는다 — 요청 문구(개인 내용)가 파일에 그대로 남고,
         * analyze.py 가 bad 를 👎로 세서 평가 통계가 오염된다(research_records §3.3).
         * 맥 스킬이 adb 로 줄을 덧붙이는 것과는 파일 잠금이 없다 → 쓰기 직전 크기가 읽을 때와 다르면 처음부터 다시(3회).
         */
        fun removeRecipeRuns(ctx: Context, runs: Set<String>): Int = synchronized(RECIPES_LOCK) {
            if (runs.isEmpty()) return 0
            val f = File(ctx.getExternalFilesDir(null), "recipes.jsonl")
            repeat(3) {
                if (!f.exists()) return 0
                val before = f.length()
                val lines = f.readLines()
                val kept = withoutRuns(lines, runs)
                val removed = lines.size - kept.size
                if (removed == 0) return 0
                val tmp = File(f.parentFile, ".recipes.jsonl.tmp")
                java.io.FileOutputStream(tmp).use { out ->
                    out.write(kept.joinToString("") { it + "\n" }.toByteArray())
                    out.fd.sync()
                }
                if (f.length() != before) { tmp.delete(); Thread.sleep(200); return@repeat }   // 그 사이 누가 덧붙였다
                if (!tmp.renameTo(f)) { tmp.delete(); return 0 }
                return removed
            }
            0
        }

        /** 순수 함수 — run 이 runs 에 든 줄을 뺀다. 빈 줄은 버리고, 파싱 못 하는 줄은 그대로 둔다(남의 형식을 지우지 않게) */
        fun withoutRuns(lines: List<String>, runs: Set<String>): List<String> = lines.filter { line ->
            if (line.isBlank()) return@filter false
            val run = runCatching { JSONObject(line).optString("run") }.getOrNull() ?: return@filter true
            run !in runs
        }

        private fun grams(s: String): Set<String> {
            val t = norm(s)
            return if (t.length < 2) setOf(t) else (0 until t.length - 1).map { t.substring(it, it + 2) }.toSet()
        }

        private fun jaccard(a: Set<String>, b: Set<String>): Double =
            if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

        fun steps(arr: JSONArray): String = (0 until arr.length()).joinToString(" → ") {
            val s = arr.getJSONObject(it); "${s.optString("tool")}(${s.optString("summary")})"
        }

        private val SEED_NOTES = """
            # 폴드 에이전트 — 이 폰에 대해 아는 것
            (맥의 Claude Code 가 실행 기록을 분석해 다듬는 노트. 폰이 스스로 배운 것은 learned.json · recipes.jsonl 에 따로 쌓인다.)

            ## 사용자
            - 한국어로 말한다. 음성 인식이 고유명사를 자주 틀린다(예: 아파트·가게 이름) — 문맥으로 바로잡아 검색한다.

            ## 앱별 요령
            - TMAP(com.skt.tmap.ku): 사용자는 「티맵」이라 부른다. 접근성 클릭이 무시되는 버튼이 있다(「길찾기」 등). 화면 변화가 없으면 좌표 탭이 자동으로 시도된다.
            - 네이버지도(com.nhn.android.nmap): 장소 검색 = 상단 검색창 탭 → type_text(submit=true) → 결과 항목 탭.

            ## 음성 인식 힌트
            - 티맵, TMAP, 네이버지도
        """.trimIndent() + "\n"
    }
}
