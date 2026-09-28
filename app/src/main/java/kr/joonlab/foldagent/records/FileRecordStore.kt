package kr.joonlab.foldagent.records

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kr.joonlab.foldagent.AgentService
import kr.joonlab.foldagent.Knowledge
import kr.joonlab.foldagent.Prefs
import kr.joonlab.foldagent.Session
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 작업 기록 저장소(DESIGN §3-5 · research_records §3). 세션 파일이 정본, 인덱스는 언제든 다시 만드는 캐시.
 *
 *   files/sessions/<id>.json          세션(채팅방) 하나 — 스키마 v2(옛 v1 은 opt* 기본값으로 읽고, 쓸 때 v2 로)
 *   files/sessions/.trash/<id>.json   휴지통(trashedAt>0). 디렉터리라 sessions 의 *.json 목록에 안 걸린다
 *   files/sessions/.corrupt/          파싱 못 한 파일을 치워 두는 곳(지우지 않는다)
 *   files/records/index.json          목록 캐시. ※ sessions/ 안에 두면 옛 Session.all 이 id 「index」 세션으로 읽는다
 *   files/records/purged.jsonl        영구 삭제 원장 {run, session, t}
 *
 * 모든 쓰기 = 전역 락(LOCK) 안에서 「디스크에서 다시 읽기 → 바꾸기 → 임시 파일 + rename」.
 * 예전 Session.save 는 실행 시작 때 잡은 메모리 사본 전체를 끝에 덮어써서, 실행 도중 UI 에서 바꾼 제목·고정이 지워졌고,
 * writeText 도중 죽으면 파일이 깨진 채 다음 턴이 빈 세션으로 옛 턴을 전부 덮어썼다(research_records §3.5) — 둘 다 여기서 막는다.
 */
class FileRecordStore(ctx: Context) : RecordStore {

    private val ctx = ctx.applicationContext
    private val base = ctx.getExternalFilesDir(null)!!
    private val sessDir = File(base, "sessions")
    private val trashDir = File(sessDir, ".trash")
    private val corruptDir = File(sessDir, ".corrupt")
    private val recDir = File(base, "records")
    private val indexFile = File(recDir, "index.json")
    private val purgedFile = File(recDir, "purged.jsonl")
    private val runsDir = File(base, "runs")

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(RecordEvent) -> Unit>()

    /** 인덱스 항목 — 키는 활성이면 id, 휴지통이면 ".trash/<id>"(시험 세션 이름이 휴지통과 겹칠 수 있다). LOCK 안에서만 만진다 */
    private val idx = HashMap<String, JSONObject>()
    private var idxLoaded = false

    companion object {
        private const val TAG = "FoldAgent"
        private val LOCK = Any()
        private const val INDEX_V = 2   // 2: v1 세션의 규칙 제목·결과 요약 채움(재구축 유도)
        private const val FULL_TURNS = 3
        private const val TRASH_KEY = ".trash/"
        private const val LLM_TIMEOUT_MS = 8_000
        // 사용자 세션 id 는 Session.newId() 형식. 그 밖(adb-test·qa10 처럼 --es session 이름)은 전부 adb 시험이었다(실측 68/68)
        private val USER_ID = Regex("\\d{8}-\\d{6}")
        // 확인 게이트는 40초 무응답이면 거부로 끝난다(AgentService.confirm). trace 의 confirm 이벤트엔 ok 만 있어서,
        // 앞 이벤트와 39초 넘게 벌어진 거부를 「시간 초과」로 본다(표시용 추정 — 판정에는 쓰지 않는다)
        private const val CONFIRM_TIMEOUT_GUESS_MS = 39_000L
    }

    private fun fire(e: RecordEvent) { main.post { listeners.forEach { runCatching { it(e) } } } }

    // ================================================================ 파일

    private fun activeFile(id: String) = File(sessDir, "$id.json")
    private fun trashFile(id: String) = File(trashDir, "$id.json")
    private fun keyOf(f: File) = if (f.parentFile == trashDir) TRASH_KEY + f.name.removeSuffix(".json") else f.name.removeSuffix(".json")
    private fun jsonFiles(d: File): List<File> =
        (d.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.startsWith(".") } ?: emptyArray()).toList()

    /** 파싱 실패는 .corrupt 로 치우고 null. 읽기 자체의 IO 오류는 던진다 — null 로 삼키면 다음 쓰기가 옛 턴을 덮어쓴다 */
    private fun read(f: File): JSONObject? {
        if (!f.exists()) return null
        val text = f.readText()
        return try { JSONObject(text) } catch (e: org.json.JSONException) {
            quarantine(f); null
        }
    }

    private fun quarantine(f: File) {
        corruptDir.mkdirs()
        val dst = File(corruptDir, "${f.name.removeSuffix(".json")}-${System.currentTimeMillis()}.json")
        Log.w(TAG, "records: 깨진 세션 파일 ${f.name} → .corrupt/${dst.name}")
        if (!f.renameTo(dst)) Log.w(TAG, "records: .corrupt 로 못 옮김 ${f.name}")
        idx.remove(keyOf(f))
    }

    /** 임시 파일에 다 쓰고 fsync 한 뒤 rename — 도중에 죽어도 옛 파일이 온전히 남는다 */
    private fun writeAtomic(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")   // 점으로 시작 + .tmp — 세션 목록(*.json)에 안 걸린다
        FileOutputStream(tmp).use { it.write(text.toByteArray()); it.fd.sync() }
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw IOException("rename 실패: ${f.name}")
        }
    }

    private fun writeSession(f: File, j: JSONObject) {
        writeAtomic(f, j.toString())
        idx[keyOf(f)] = entryOf(j, f)
        saveIndex()
    }

    // ================================================================ 스키마

    /** 옛 파일(v1)을 v2 모양으로(메모리에서만 — 저장은 쓰기 때). 이미 있는 필드와 모르는 필드는 그대로 둔다 */
    private fun normalize(j: JSONObject, id: String): JSONObject {
        // v1 파일은 제목 = 요청 앞 40자, 설명 없음 — 목록에서 제목과 설명이 같은 요청 원문으로 두 번 보였다(2026-09-26 실기).
        // 규칙 제목·마지막 결과 요약으로 채운다(사용자가 고친 제목은 v1 에 없으므로 덮어도 된다).
        if (!j.has("v")) {
            val turns = j.optJSONArray("turns")
            val firstReq = turns?.optJSONObject(0)?.optString("request").orEmpty()
            if (firstReq.isNotEmpty() && j.optString("title") == firstReq.take(40)) j.put("title", TitleMaker.rule(firstReq))
            val lastFinal = turns?.optJSONObject(turns.length() - 1)?.optString("final").orEmpty()
            if (j.optString("summary").isEmpty() && lastFinal.isNotBlank()) j.put("summary", TitleMaker.summary(lastFinal))
        }
        j.put("v", 2).put("id", id)
        if (j.optString("titleSource").isEmpty()) j.put("titleSource", "rule")   // 옛 제목은 request.take(40) 규칙이었다
        if (!j.has("summary")) j.put("summary", "")
        if (j.optString("source").isEmpty()) j.put("source", inferSource(id, j))
        if (!j.has("pinned")) j.put("pinned", false)
        if (!j.has("archived")) j.put("archived", false)
        if (!j.has("trashedAt")) j.put("trashedAt", 0L)
        if (!j.has("turns")) j.put("turns", JSONArray())
        if (!j.has("created")) j.put("created", System.currentTimeMillis())
        if (!j.has("updated")) j.put("updated", j.optLong("created"))
        return j
    }

    /** v1 파일엔 source 가 없다 — 이름 id 는 adb, 타임스탬프 id 는 첫 턴 run 의 run_start.source(없으면 voice) */
    private fun inferSource(id: String, j: JSONObject): String {
        if (!USER_ID.matches(id)) return "adb"
        val first = j.optJSONArray("turns")?.optJSONObject(0) ?: return "voice"
        val run = first.optString("run")
        val f = runFile(run) ?: return "voice"
        val line = runCatching { f.bufferedReader().use { it.readLine() } }.getOrNull() ?: return "voice"
        val d = runCatching { JSONObject(line) }.getOrNull()?.takeIf { it.optString("type") == "run_start" }?.optJSONObject("data")
        return d?.optString("source")?.ifEmpty { null } ?: "voice"
    }

    private fun entryOf(j: JSONObject, f: File): JSONObject {
        val turns = j.optJSONArray("turns") ?: JSONArray()
        val n = turns.length()
        val last = if (n > 0) turns.optJSONObject(n - 1) ?: JSONObject() else JSONObject()
        val steps = last.optJSONArray("steps")
        val lastTool = if (steps != null && steps.length() > 0) steps.optJSONObject(steps.length() - 1)?.optString("tool").orEmpty() else ""
        val trashed = f.parentFile == trashDir
        val id = f.name.removeSuffix(".json")
        return JSONObject()
            .put("id", id)
            .put("title", j.optString("title").ifEmpty { TitleMaker.rule(turns.optJSONObject(0)?.optString("request").orEmpty()) })
            .put("titleSource", j.optString("titleSource").ifEmpty { "rule" })
            .put("summary", j.optString("summary"))
            .put("source", j.optString("source").ifEmpty { inferSource(id, j) })
            .put("pinned", j.optBoolean("pinned"))
            .put("archived", j.optBoolean("archived"))
            // 휴지통 폴더에 있는데 trashedAt 이 없으면(맥에서 옮긴 경우 등) 파일 시각으로
            .put("trashedAt", if (trashed) j.optLong("trashedAt").takeIf { it > 0 } ?: f.lastModified() else 0L)
            .put("created", j.optLong("created"))
            .put("updated", j.optLong("updated"))
            .put("turns", n)
            .put("lastStatus", last.optString("status"))
            .put("lastRequest", last.optString("request"))
            .put("lastTool", lastTool)
            .put("runs", JSONArray((0 until n).map { turns.optJSONObject(it)?.optString("run").orEmpty() }.filter { it.isNotEmpty() }))
            .put("mtime", f.lastModified())
            .put("size", f.length())
    }

    private fun summaryOf(e: JSONObject) = RecordSummary(
        id = e.optString("id"), title = e.optString("title"), titleSource = e.optString("titleSource"), summary = e.optString("summary"),
        source = e.optString("source"), pinned = e.optBoolean("pinned"), archived = e.optBoolean("archived"), trashedAt = e.optLong("trashedAt"),
        created = e.optLong("created"), updated = e.optLong("updated"), turns = e.optInt("turns"),
        lastStatus = e.optString("lastStatus"), lastRequest = e.optString("lastRequest"), lastTool = e.optString("lastTool"),
    )

    // ================================================================ 인덱스

    private fun ensureIndex() {
        if (idxLoaded) return
        val ok = runCatching {
            val j = JSONObject(indexFile.readText())
            require(j.optInt("v") == INDEX_V)
            val s = j.getJSONObject("sessions")
            s.keys().forEach { k -> idx[k] = s.getJSONObject(k) }
        }.isSuccess
        idxLoaded = true
        if (!ok) { idx.clear(); rebuildLocked() } else reconcile()
    }

    /** 파일 목록·mtime·크기를 인덱스와 대조해 어긋난 것만 다시 읽는다(맥에서 adb 로 고친 파일·옛 빌드가 쓴 파일) */
    private fun reconcile() {
        var changed = false
        val seen = HashSet<String>()
        for (f in jsonFiles(sessDir) + jsonFiles(trashDir)) {
            val key = keyOf(f)
            seen += key
            val e = idx[key]
            if (e != null && e.optLong("mtime") == f.lastModified() && e.optLong("size") == f.length()) continue
            val j = runCatching { read(f) }.getOrNull() ?: continue
            idx[key] = entryOf(normalize(j, f.name.removeSuffix(".json")), f)
            changed = true
        }
        if (idx.keys.retainAll(seen)) changed = true
        if (changed) saveIndex()
    }

    private fun rebuildLocked() {
        idx.clear()
        for (f in jsonFiles(sessDir) + jsonFiles(trashDir)) {
            val j = runCatching { read(f) }.getOrNull() ?: continue
            idx[keyOf(f)] = entryOf(normalize(j, f.name.removeSuffix(".json")), f)
        }
        saveIndex()
    }

    private fun saveIndex() {
        runCatching {
            val s = JSONObject()
            idx.forEach { (k, v) -> s.put(k, v) }
            writeAtomic(indexFile, JSONObject().put("v", INDEX_V).put("sessions", s).toString())
        }.onFailure { Log.w(TAG, "records: 인덱스 저장 실패 $it") }
    }

    override fun rebuildIndex() {
        synchronized(LOCK) { idxLoaded = true; rebuildLocked() }
        fire(RecordEvent.Rebuilt)
    }

    /** Records.init 이 백그라운드에서 부른다 — 첫 목록이 인덱스 구축을 기다리지 않게 */
    fun warmUp() { synchronized(LOCK) { ensureIndex() } }

    // ================================================================ 읽기

    override fun list(filter: RecordFilter): List<RecordSummary> {
        val entries = synchronized(LOCK) {
            ensureIndex(); reconcile()
            idx.values.map { JSONObject(it.toString()) }
        }
        val q = filter.query.trim()
        return entries.asSequence()
            .map { summaryOf(it) }
            .filter { s ->
                when (filter.shelf) {
                    Shelf.ACTIVE -> s.trashedAt == 0L && !s.archived
                    Shelf.ARCHIVED -> s.trashedAt == 0L && s.archived
                    Shelf.TRASH -> s.trashedAt > 0L
                }
            }
            .filter { filter.includeTests || it.source != "adb" }
            .filter { filter.status == null || it.lastStatus == filter.status }
            .filter { !filter.pinnedOnly || it.pinned }
            .filter { it.updated >= filter.since }
            .filter { it.turns > 0 }
            .filter { q.isEmpty() || matches(it, q) }
            .sortedWith(compareByDescending<RecordSummary> { it.pinned }.thenByDescending { it.updated })
            .toList()
    }

    /** 검색: 목록 값(제목·설명·마지막 요청)에서 먼저, 없으면 파일을 열어 모든 턴의 요청·결과·턴 제목에서 */
    private fun matches(s: RecordSummary, q: String): Boolean {
        if (s.title.contains(q, true) || s.summary.contains(q, true) || s.lastRequest.contains(q, true)) return true
        val f = if (s.trashedAt > 0) trashFile(s.id) else activeFile(s.id)
        val turns = runCatching { JSONObject(f.readText()).optJSONArray("turns") }.getOrNull() ?: return false
        return (0 until turns.length()).any { i ->
            val t = turns.optJSONObject(i) ?: return@any false
            t.optString("request").contains(q, true) || t.optString("final").contains(q, true) || t.optString("title").contains(q, true)
        }
    }

    /** Session 어댑터용 — 세션 원본(활성 파일만, 없으면 null). 계약 밖 내부 함수 */
    fun raw(id: String): JSONObject? = synchronized(LOCK) { runCatching { read(activeFile(id)) }.getOrNull() }?.let { normalize(it, id) }

    /**
     * 활성 방은 없고 휴지통에만 있나 — 맡긴 작업 결과(JobWatcher)가 휴지통 방을 같은 id 의 활성 방으로 되살리지 않게 본다.
     * 활성·휴지통 어디에도 없으면(새 방이 아직 첫 턴 전이거나 영구 삭제) false — 그 id 로 붙여도 겹칠 방이 없다.
     */
    fun isTrashed(id: String): Boolean = synchronized(LOCK) { !activeFile(id).exists() && trashFile(id).exists() }

    override fun get(id: String): List<TurnCard>? {
        val j = synchronized(LOCK) {
            val f = activeFile(id).takeIf { it.exists() } ?: trashFile(id).takeIf { it.exists() }
            f?.let { runCatching { read(it) }.getOrNull() }
        } ?: return if (id == Prefs.sessionId(ctx)) emptyList() else null
        val sessionSource = j.optString("source").ifEmpty { inferSource(id, j) }
        val turns = j.optJSONArray("turns") ?: JSONArray()
        return (0 until turns.length()).mapNotNull { i ->
            val t = turns.optJSONObject(i) ?: return@mapNotNull null
            val st = t.optJSONArray("steps") ?: JSONArray()
            val run = t.optString("run")
            TurnCard(
                run = run, title = t.optString("title").ifEmpty { TitleMaker.rule(t.optString("request")) },
                request = t.optString("request"), final = t.optString("final"), status = t.optString("status"),
                t = t.optLong("t"), ms = t.optLong("ms"), source = t.optString("source").ifEmpty { sessionSource },
                rating = if (t.has("rating") && !t.isNull("rating")) t.optBoolean("rating") else null,
                steps = (0 until st.length()).mapNotNull { k -> st.optJSONObject(k)?.let { Step(it.optString("tool"), it.optString("summary")) } },
                // 턴에 저장된 것 우선(새 턴은 appendTurn 때 넣는다), 옛 턴은 runs jsonl 에서 지연 로드
                confirms = t.optJSONArray("confirms")?.let { parseConfirms(it) } ?: readConfirms(run),
                attachments = Attachments.parse(t.optJSONArray("attachments")),
                userFiles = userFilesOf(t),
            )
        }
    }

    private fun userFilesOf(t: JSONObject): List<UserFile> {
        val a = t.optJSONArray("userFiles") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            fun s(k: String) = o.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }
            UserFile(s("title") ?: return@mapNotNull null, s("mime"), if (o.has("size")) o.optLong("size", -1).takeIf { it >= 0 } else null, s("fileId"))
        }.take(5)
    }

    private fun runFile(runId: String): File? {
        if (runId.length < 8) return null
        return File(runsDir, "${runId.substring(0, 8)}/$runId.jsonl").takeIf { it.exists() }
    }

    override fun trace(runId: String): File? = runFile(runId)

    private fun parseConfirms(a: JSONArray) = (0 until a.length()).mapNotNull { i ->
        a.optJSONObject(i)?.let { ConfirmMark(it.optString("question"), it.optString("result"), it.optLong("t")) }
    }

    /**
     * runs jsonl 의 confirm 이벤트(Agent.gate: {label, what, ok}) → ConfirmMark. 질문 문장은 gate 와 같은 모양으로 되살린다.
     * 줄마다 화면 전문이 든 큰 msg 이벤트가 섞여 있어, 문자열로 먼저 거르고 confirm 줄만 파싱한다.
     */
    private fun readConfirms(runId: String): List<ConfirmMark> {
        val f = runFile(runId) ?: return emptyList()
        val out = ArrayList<ConfirmMark>()
        val tRe = Regex("^\\{\"t\":(\\d+)")
        var prevT = 0L
        runCatching {
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val t = tRe.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: prevT
                    if (line.contains("\"type\":\"confirm\"")) {
                        val d = runCatching { JSONObject(line).optJSONObject("data") }.getOrNull()
                        if (d != null) {
                            val ok = d.optBoolean("ok")
                            val result = when {
                                ok -> "ok"
                                prevT > 0 && t - prevT >= CONFIRM_TIMEOUT_GUESS_MS -> "timeout"
                                else -> "denied"
                            }
                            out += ConfirmMark("「${d.optString("label")}」 ${d.optString("what")} 할까요?", result, t)
                        }
                    }
                    prevT = t
                }
            }
        }
        return out
    }

    override fun historyMessages(id: String): List<JSONObject> {
        val j = synchronized(LOCK) { runCatching { read(activeFile(id)) }.getOrNull() } ?: return emptyList()
        return historyOf(j.optJSONArray("turns") ?: JSONArray())
    }

    /** 이번 요청 앞에 붙일 이전 대화(옛 Session.historyMessages 그대로). 도구 호출·응답 짝이 깨지지 않게 턴 단위로 넣거나 뺀다 */
    private fun historyOf(turns: JSONArray): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val n = turns.length()
        val cut = maxOf(0, n - FULL_TURNS)
        if (cut > 0) {
            val sb = StringBuilder("(이 대화의 앞부분 요약)\n")
            for (i in 0 until cut) {
                val t = turns.getJSONObject(i)
                sb.append("- 요청: ").append(t.optString("request").take(120))
                    .append(" → 결과(").append(t.optString("status")).append("): ").append(t.optString("final").take(200))
                Attachments.summary(Attachments.parse(t.optJSONArray("attachments"))).takeIf { it.isNotEmpty() }?.let { sb.append(" [첨부 ").append(it).append(']') }
                userFilesOf(t).takeIf { it.isNotEmpty() }?.let { fs -> sb.append(" [보낸 파일 ").append(fs.joinToString(", ") { "${it.title.take(40)}${it.fileId?.let { id -> " id $id" } ?: ""}" }).append(']') }
                sb.append('\n')
            }
            out += JSONObject().put("role", "user").put("content", sb.toString().trim())
            out += JSONObject().put("role", "assistant").put("content", "앞부분 대화를 기억하고 이어 갑니다.")
        }
        for (i in cut until n) {
            val t = turns.getJSONObject(i)
            val ms = t.optJSONArray("messages")
            if (ms != null) for (k in 0 until ms.length()) out += ms.getJSONObject(k)
            // 첨부는 요약 한 줄만(계약 cc-capabilities §1-3) — 턴 메시지는 도구 호출·응답 짝이 닫힌 채 끝나므로 뒤에 붙여도 짝이 안 깨진다
            val att = Attachments.summary(Attachments.parse(t.optJSONArray("attachments")))
            if (att.isNotEmpty()) out += JSONObject().put("role", "assistant").put("content", "(이 턴에 채팅에 붙은 첨부 — $att)")
        }
        return out
    }

    // ================================================================ 이어 갈 방

    override fun currentId(): String = Prefs.sessionId(ctx) ?: Session.newId().also { Prefs.setSessionId(ctx, it) }

    override fun select(id: String) {
        if (AgentService.instance?.busy == true) throw IllegalStateException("작업 중에는 대화를 바꿀 수 없어요")
        Prefs.setSessionId(ctx, id)
        fire(RecordEvent.Changed(id))
    }

    override fun startNew(): String {
        val id = Session.newId()
        Prefs.setSessionId(ctx, id)   // 파일은 첫 턴에 생긴다(빈 방은 목록에 안 나온다)
        fire(RecordEvent.Changed(id))
        return id
    }

    // ================================================================ 에이전트 쪽 쓰기

    /**
     * 턴 하나 덧붙이기(Agent.run 끝). turn = {run,request,final,status,steps,messages[,ms,t]} — messages 는 Session 이 이미 가볍게 만든 것.
     * 디스크에서 다시 읽고 붙이므로, 실행 도중 UI 에서 바꾼 제목·고정·보관이 살아남는다.
     */
    override fun appendTurn(id: String, turn: JSONObject, source: String) {
        var llm = false
        var n = 0
        synchronized(LOCK) {
            ensureIndex()
            val f = activeFile(id)
            // 깨진 파일은 read 가 .corrupt 로 치운다 — 옛 턴은 거기 남고, 이 방은 이번 턴부터 새로 쌓인다
            val j = (if (f.exists()) read(f) else null) ?: JSONObject().put("created", System.currentTimeMillis())
            if (j.optString("source").isEmpty() && source.isNotEmpty()) j.put("source", source)
            normalize(j, id)
            val t = JSONObject(turn.toString())
            val req = t.optString("request")
            if (t.optString("title").isEmpty()) t.put("title", TitleMaker.rule(req))
            if (t.optString("source").isEmpty()) t.put("source", source.ifEmpty { j.optString("source") })
            if (!t.has("t")) t.put("t", System.currentTimeMillis())
            if (!t.has("ms")) t.put("ms", 0L)
            t.put("confirms", JSONArray(readConfirms(t.optString("run")).map {
                JSONObject().put("question", it.question).put("result", it.result).put("t", it.t)
            }))
            val turns = j.getJSONArray("turns")
            turns.put(t)
            n = turns.length()
            if (j.optString("title").isEmpty() && j.optString("titleSource") != "user") {
                j.put("title", TitleMaker.rule(turns.optJSONObject(0)?.optString("request").orEmpty())).put("titleSource", "rule")
            }
            // LLM 이 쓴 설명은 다음 LLM 차례(3턴마다)까지 둔다. 그 밖엔 마지막 결과 첫 문장
            if (j.optString("summarySource") != "llm") j.put("summary", TitleMaker.summary(t.optString("final")))
            j.put("updated", System.currentTimeMillis())
            writeSession(f, j)
            llm = Prefs.llmTitles(ctx) && j.optString("source") != "adb" && t.optString("source") != "adb" &&
                (n == 1 || n % 3 == 0)
        }
        fire(RecordEvent.Changed(id))
        // 제목 다듬기는 별도 스레드 — Agent.run 은 이 뒤에 finish·speak 를 하므로 기다리게 하지 않는다.
        // 결과 반영은 applyGenerated(다시 읽고 메타만 바꿈)라 그 사이 붙은 턴·UI 변경을 덮지 않는다
        if (llm) Thread({ generate(id, turn.optString("run")) }, "records-title").start()
    }

    override fun rate(runId: String, good: Boolean) {
        var id: String? = null
        synchronized(LOCK) {
            ensureIndex()
            val key = idx.entries.firstOrNull { (_, e) ->
                val r = e.optJSONArray("runs") ?: return@firstOrNull false
                (0 until r.length()).any { r.optString(it) == runId }
            }?.key ?: return
            val f = if (key.startsWith(TRASH_KEY)) trashFile(key.removePrefix(TRASH_KEY)) else activeFile(key)
            val j = runCatching { read(f) }.getOrNull() ?: return
            val turns = j.optJSONArray("turns") ?: return
            val t = (0 until turns.length()).mapNotNull { turns.optJSONObject(it) }.lastOrNull { it.optString("run") == runId } ?: return
            t.put("rating", good)
            writeSession(f, j)
            id = f.name.removeSuffix(".json")
        }
        id?.let { fire(RecordEvent.Changed(it)) }
        // 대화방에서 평가한 경우엔 Agent.feedback 이 없다 — 실행 기록에 feedback 이 아직 없을 때만 같은 흔적(이벤트 + 👎면 recipes bad)을 남긴다.
        // 패널 경로는 agent.feedback 이 먼저 이벤트를 써서 여기선 건너뛴다(두 줄 찍히면 analyze 👎 통계가 틀어진다).
        runCatching {
            val f = runFile(runId) ?: return
            if (f.readLines().any { it.contains("\"type\":\"feedback\"") }) return
            f.appendText(JSONObject().put("t", System.currentTimeMillis()).put("run", runId).put("type", "feedback")
                .put("data", JSONObject().put("good", good).put("from", "app")).toString() + "\n")
            if (!good) Knowledge(ctx).markBad(runId)
        }
    }

    override fun applyGenerated(id: String, title: String?, summary: String?, turnRun: String?, turnTitle: String?) {
        synchronized(LOCK) {
            val f = activeFile(id).takeIf { it.exists() } ?: trashFile(id).takeIf { it.exists() } ?: return
            val j = runCatching { read(f) }.getOrNull() ?: return
            normalize(j, id)
            // 사용자가 고친 제목은 절대 덮지 않는다
            if (!title.isNullOrBlank() && j.optString("titleSource") != "user") j.put("title", title).put("titleSource", "llm")
            if (!summary.isNullOrBlank()) j.put("summary", summary).put("summarySource", "llm")
            if (!turnRun.isNullOrEmpty() && !turnTitle.isNullOrBlank()) {
                val turns = j.getJSONArray("turns")
                (0 until turns.length()).mapNotNull { turns.optJSONObject(it) }.lastOrNull { it.optString("run") == turnRun }?.put("title", turnTitle)
            }
            writeSession(f, j)   // updated 는 안 바꾼다 — 제목이 바뀌었다고 목록 순서가 뛰면 안 된다
        }
        fire(RecordEvent.Changed(id))
    }

    /**
     * LLM 으로 방 제목·설명·이번 턴 제목. 입력은 요청(각 120자)·결과(각 200자)만 — 화면 전문·메시지는 넣지 않는다.
     * 실패·시간 초과(8초)면 아무것도 안 한다 = 규칙값 유지.
     * LlmClient.chat 은 tools 필수·60초 대기·재시도라 제목 한 줄에 맞지 않아, 같은 엔드포인트에 짧은 요청을 직접 보낸다.
     */
    private fun generate(id: String, runId: String) {
        val t0 = System.currentTimeMillis()
        runCatching {
            val j = raw(id) ?: return
            val turns = j.optJSONArray("turns") ?: return
            val from = maxOf(0, turns.length() - 6)
            val sb = StringBuilder()
            for (i in from until turns.length()) {
                val t = turns.optJSONObject(i) ?: continue
                sb.append("${i + 1}. 요청: ").append(t.optString("request").take(120)).append('\n')
                    .append("   결과(").append(t.optString("status")).append("): ").append(t.optString("final").take(200)).append('\n')
            }
            val sys = "휴대폰 에이전트에게 시킨 작업 기록의 이름을 짓는다. JSON 한 줄만 답한다: " +
                "{\"title\":\"대화 전체 제목, 20자 이내 명사형\",\"summary\":\"무엇을 했고 결과가 어땠는지 한 문장, 60자 이내\",\"turnTitle\":\"마지막 요청의 제목, 20자 이내 명사형\"}. " +
                "음성 인식 오기는 문맥으로 바로잡는다. 따옴표·이모지·마침표 없이."
            val body = JSONObject().put("model", Prefs.model(ctx)).put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", sys))
                .put(JSONObject().put("role", "user").put("content", sb.toString().trim())))
            val conn = URL("${Prefs.base(ctx)}/chat/completions").openConnection() as HttpURLConnection
            val text = try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 3_000
                conn.readTimeout = LLM_TIMEOUT_MS
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer ${Prefs.apiKey(ctx).ifEmpty { "dummy" }}")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally { conn.disconnect() }
            if (System.currentTimeMillis() - t0 > LLM_TIMEOUT_MS + 3_000) return   // 늦게 온 답은 버린다
            val content = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
            val a = content.indexOf('{'); val b = content.lastIndexOf('}')
            if (a < 0 || b <= a) return
            val out = JSONObject(content.substring(a, b + 1))
            fun clean(k: String, max: Int) = out.optString(k).replace(Regex("[\"「」]"), "").trim().trimEnd('.').let {
                if (it.isEmpty()) null else TitleMaker.clip(it, max)
            }
            applyGenerated(id, clean("title", TitleMaker.TITLE_MAX), clean("summary", TitleMaker.SUMMARY_MAX), runId, clean("turnTitle", TitleMaker.TITLE_MAX))
            Log.i(TAG, "records: LLM 제목 $id ${System.currentTimeMillis() - t0}ms")
        }.onFailure { Log.i(TAG, "records: LLM 제목 실패(규칙 제목 유지) $id ${System.currentTimeMillis() - t0}ms $it") }
    }

    // ================================================================ 사용자 조작

    /** 실행 중인 방은 보관·휴지통 불가 — 끝에 appendTurn 이 활성 폴더에 다시 파일을 만들어 둘로 갈라진다 */
    private fun ensureNotRunning(id: String, what: String) {
        if (AgentService.instance?.runningSessionId == id) throw IllegalStateException("작업 중인 대화는 $what 수 없어요 — 끝난 뒤에")
    }

    /** 활성(없으면 휴지통) 파일을 다시 읽고 바꿔 쓴다 */
    private fun mutate(id: String, block: (JSONObject) -> Unit): Boolean {
        val ok = synchronized(LOCK) {
            ensureIndex()
            val f = activeFile(id).takeIf { it.exists() } ?: trashFile(id).takeIf { it.exists() } ?: return@synchronized false
            val j = read(f) ?: return@synchronized false
            normalize(j, id)
            block(j)
            writeSession(f, j)
            true
        }
        if (ok) fire(RecordEvent.Changed(id))
        return ok
    }

    override fun rename(id: String, title: String) {
        val t = title.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return
        mutate(id) { it.put("title", t.take(60)).put("titleSource", "user") }
    }

    override fun pin(id: String, on: Boolean) { mutate(id) { it.put("pinned", on) } }

    override fun archive(id: String, on: Boolean) {
        ensureNotRunning(id, "보관할")
        mutate(id) { it.put("archived", on) }
    }

    /** 휴지통으로. 이름이 겹치면(시험 세션 이름 재사용) 휴지통 쪽 id 를 <id>-d<n> 으로 — 휴지통 안에서는 id 가 파일 이름과 늘 같다 */
    override fun delete(id: String) {
        ensureNotRunning(id, "휴지통으로 옮길")
        val moved = synchronized(LOCK) {
            ensureIndex()
            val f = activeFile(id)
            val j = (if (f.exists()) read(f) else null) ?: return@synchronized false
            normalize(j, id)
            var tid = id; var k = 1
            while (trashFile(tid).exists()) tid = "$id-d${k++}"
            j.put("id", tid).put("trashedAt", System.currentTimeMillis())
            writeSession(trashFile(tid), j)
            f.delete()
            idx.remove(id)
            saveIndex()
            true
        }
        // 지금 이어 가는 방을 지우면 새 방으로(파일은 첫 턴에 생긴다). 복원해도 자동으로 되돌리지 않는다
        if (Prefs.sessionId(ctx) == id) Prefs.setSessionId(ctx, Session.newId())
        if (moved) fire(RecordEvent.Removed(id, true))
    }

    /** 휴지통에서 꺼낸다. 같은 id 의 활성 파일이 이미 있으면 <id>-r<n>. 실제 복원된 id 를 돌려준다 */
    override fun restore(id: String): String {
        val nid = synchronized(LOCK) {
            ensureIndex()
            val f = trashFile(id)
            val j = (if (f.exists()) read(f) else null) ?: return id
            var nid = id; var k = 1
            while (activeFile(nid).exists()) nid = "$id-r${k++}"
            j.put("id", nid).put("trashedAt", 0L)
            writeSession(activeFile(nid), j)
            f.delete()
            idx.remove(TRASH_KEY + id)
            saveIndex()
            nid
        }
        fire(RecordEvent.Restored(nid))
        return nid
    }

    /**
     * 영구 삭제 — 휴지통에 있는 것만. ids=null 이면 휴지통에 olderThanDays 일 넘게 있던 것 전부.
     * 방 파일 + 그 턴들의 runs/<날짜>/<runId>.jsonl + recipes.jsonl 의 그 run 줄(재작성) + purged.jsonl 원장.
     * learned.json(앱 별칭·좌표 탭 앱)은 run 과 연결이 없고 개인 내용이 아니라 건드리지 않는다.
     */
    override fun purge(ids: List<String>?, olderThanDays: Int): Int {
        val purgedIds = ArrayList<String>()
        var runCount = 0
        synchronized(LOCK) {
            ensureIndex(); reconcile()
            val now = System.currentTimeMillis()
            val targets = idx.entries.filter { it.key.startsWith(TRASH_KEY) }.map { it.key.removePrefix(TRASH_KEY) to it.value }
                .filter { (tid, e) ->
                    if (ids != null) tid in ids else Purge.expired(e.optLong("trashedAt"), now, olderThanDays)
                }.map { it.first }
            if (targets.isEmpty()) return 0
            val allRuns = HashSet<String>()
            val ledger = StringBuilder()
            for (tid in targets) {
                val f = trashFile(tid)
                val j = runCatching { read(f) }.getOrNull()
                if (j == null) { idx.remove(TRASH_KEY + tid); continue }
                val turns = j.optJSONArray("turns") ?: JSONArray()
                val runs = (0 until turns.length()).mapNotNull { turns.optJSONObject(it)?.optString("run")?.ifEmpty { null } }
                runCount += Purge.deleteRuns(runsDir, runs)
                ledger.append(Purge.ledgerLines(tid, runs, now))
                allRuns += runs
                if (f.delete()) purgedIds += tid
                idx.remove(TRASH_KEY + tid)
            }
            runCatching { recDir.mkdirs(); purgedFile.appendText(ledger.toString()) }
            val lines = runCatching { kr.joonlab.foldagent.Knowledge.removeRecipeRuns(ctx, allRuns) }.getOrDefault(0)
            Log.i(TAG, "records: 영구 삭제 방 ${purgedIds.size} · runs $runCount · recipes 줄 $lines")
            saveIndex()
        }
        if (purgedIds.isNotEmpty()) fire(RecordEvent.Purged(purgedIds, runCount))
        return purgedIds.size
    }

    override fun addListener(l: (RecordEvent) -> Unit) { listeners.addIfAbsent(l) }
    override fun removeListener(l: (RecordEvent) -> Unit) { listeners.remove(l) }
}
