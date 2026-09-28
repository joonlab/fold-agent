package kr.joonlab.foldagent

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 홈 비서 도구 — 폰 화면을 거치지 않고 홈맥(:3100 /api/phone)의 결정적 라우트를 부른다. 계약 docs/server-contract/CONTRACT.md §1·§3.
 *
 * 여기는 문자열·JSON 만 만든다(부수효과 없음): 도구 스키마 · op 조립과 인자 검증 · 확인 창 문구 · 거부 키 · 결과 요약 · 상태 문구.
 * 실행 흐름(게이트·confirmId·결과 규약)은 Agent.assistantTool() 이 가진다.
 * 서버 data 모양(계약 §2-4)이 조금 달라도 죽지 않게 전부 opt* 로 읽는다 — 못 읽으면 원문 JSON 을 줄여서라도 돌려준다.
 */
object AssistantTools {

    private val KST: ZoneId = ZoneId.of("Asia/Seoul")

    /** 모델이 읽는 결과 글 상한(계약 §3-1) */
    private const val MAX_OUT = 3000

    // Agent.kt 의 fn/p 는 private 이라 같은 모양 헬퍼를 둔다(OpenAI function 스키마)
    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().put("type", "function").put(
            "function", JSONObject().put("name", name).put("description", desc).put(
                "parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))
            )
        )

    private fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

    val TOOLS: List<JSONObject> = listOf(
        fn("agenda", "사용자의 일정(홈 서버에 연결된 캘린더)을 조회한다. 일정 질문은 반드시 이것으로 — 기억으로 지어내거나 캘린더 앱을 열지 않는다. " +
            "결과 줄마다 id=·cal= 이 있어 event_delete 에 그대로 쓴다. 「이번 주」처럼 여러 날이면 from·to 로 한 번에 조회한다", JSONObject()
            .put("from", p("string", "시작일 YYYY-MM-DD(한국 날짜, 생략하면 오늘)"))
            .put("to", p("string", "끝일 YYYY-MM-DD(그날 포함, 생략하면 from 과 같은 날 · 최대 62일)"))
            .put("query", p("string", "제목에 이 글이 든 일정만(선택)"))),
        fn("briefing", "홈 비서가 만든 최신 아침 브리핑(오늘 할 일·메일·일정 요약)을 가져온다. 「오늘 브리핑」 요청에 쓴다"),
        fn("mail_list", "사용자의 Gmail 메일 목록을 본다(Gmail 앱을 열지 않는다). 결과 줄의 id= 로 mail_read·mail_reply 를 부른다", JSONObject()
            .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("unreplied", "awaiting", "search")))
                .put("description", "unreplied = 답장이 필요한 메일 · awaiting = 내가 보내고 답을 기다리는 메일 · search = Gmail 검색식으로 찾기"))
            .put("query", p("string", "kind=search 일 때 Gmail 검색식. 예: from:홍길동 newer_than:7d · subject:견적"))
            .put("n", p("integer", "몇 개(기본 10, 최대 20)")), listOf("kind")),
        fn("mail_read", "메일 한 통의 본문을 읽는다(보낸 사람·받는 사람·제목·날짜·본문)", JSONObject()
            .put("id", p("string", "mail_list 결과의 메일 id")), listOf("id")),
        fn("memory_search", "사용자의 기억(홈 비서 기억 저장소)에서 찾는다 — 사람·고객사·프로젝트·예전 결정 같은 개인 맥락은 지어내지 말고 여기서 찾는다", JSONObject()
            .put("query", p("string", "찾을 말(공백으로 여러 낱말)")), listOf("query")),
        fn("memory_get", "기억 한 건을 전문으로 본다", JSONObject()
            .put("ref", p("string", "memory_search 결과의 slug 또는 제목")), listOf("ref")),
        fn("memory_add", "기억에 새 항목을 추가한다(같은 제목이 있으면 그 항목에 이어 붙는다). 사용자가 「기억해 둬」처럼 명시적으로 요청했을 때만 쓴다 — 대화에서 스스로 쌓지 않는다. " +
            "실행 전 사용자 확인 창이 뜬다", JSONObject()
            .put("title", p("string", "제목(짧게)"))
            .put("body", p("string", "본문"))
            .put("tags", p("string", "태그(콤마로 여러 개, 선택)")), listOf("title", "body")),
        fn("event_create", "사용자의 캘린더(홈 서버 연결)에 일정을 만든다. 실행 전 사용자 확인 창이 뜬다(캘린더 앱의 새 일정 화면을 열지 않는다)", JSONObject()
            .put("title", p("string", "일정 제목"))
            .put("start", p("string", "시작. ISO 8601 한국 시간 예 2026-09-28T15:00:00+09:00 · 종일이면 YYYY-MM-DD"))
            .put("end", p("string", "끝. 같은 형식(말이 없으면 시작 1시간 뒤 · 종일이면 끝나는 날, 하루짜리는 start 와 같은 날)"))
            .put("all_day", p("boolean", "종일 일정이면 true"))
            .put("note", p("string", "메모(선택)")), listOf("title", "start", "end")),
        fn("event_delete", "일정을 지운다. event_id·calendar_id 는 agenda 결과 줄의 id=·cal= 값 그대로(먼저 agenda 로 찾는다). 실행 전 사용자 확인 창이 뜬다", JSONObject()
            .put("event_id", p("string", "agenda 결과의 id"))
            .put("calendar_id", p("string", "agenda 결과의 cal")), listOf("event_id", "calendar_id")),
        fn("mail_send", "새 메일을 보낸다. 발신 주소는 홈 비서 서버에 설정된 주소이고 받는 사람의 답장은 사용자 메일함으로 온다. 본문은 네가 평문으로 직접 쓴다. " +
            "보내기 전 확인 창이 받는 사람·제목·본문을 사용자에게 보여 주므로 따로 초안 확인을 받지 않는다(받는 사람·내용이 애매할 때만 되묻는다)", JSONObject()
            .put("to", p("string", "받는 사람 주소 1~5개(콤마)"))
            .put("subject", p("string", "제목(200자 이내)"))
            .put("body", p("string", "본문(평문)"))
            .put("cc", p("string", "참조 주소(콤마, 선택 · to 와 합쳐 5개까지)")), listOf("to", "subject", "body")),
        fn("mail_reply", "받은 메일에 답장한다(원문 스레드로 이어진다 · 받는 사람과 제목은 원문에서 서버가 정한다 · 발신은 홈 비서 주소). 본문은 네가 평문으로 쓴다. " +
            "보내기 전 확인 창이 원문 보낸 사람·제목·답장 본문을 보여 준다", JSONObject()
            .put("id", p("string", "mail_list·mail_read 의 메일 id"))
            .put("body", p("string", "답장 본문(평문)")), listOf("id", "body")),
    )

    val NAMES: Set<String> = TOOLS.map { it.getJSONObject("function").getString("name") }.toSet()
    /** 조회만 하는 도구 — 게이트 없음 */
    val READ_ONLY: Set<String> = setOf("agenda", "briefing", "mail_list", "mail_read", "memory_search", "memory_get")
    /** 쓰기 도구 — 도구 종류만으로 무조건 확인 게이트(계약 §0-1). 모델이 건너뛸 인자는 없다 */
    val GATED: Set<String> = setOf("memory_add", "event_create", "event_delete", "mail_send", "mail_reply")

    /** BASE_PROMPT 뒤에 붙는 한 단락(Agent.systemPrompt) */
    val PROMPT: String = """
        ## 홈 비서 도구(홈맥 연결 — 폰 화면을 쓰지 않는다)
        - 사용자의 일정·메일·기억·브리핑 같은 개인 정보는 반드시 agenda · mail_list · mail_read · memory_search · memory_get · briefing 으로 조회한다. 기억이나 이전 대화로 지어내지 않고, 캘린더·Gmail 앱 화면을 열어 찾지 않는다(질문형이어도 도구로 조회한 뒤 finish 로 답한다).
        - 일정 만들기·지우기는 event_create · event_delete, 메일 보내기·답장은 mail_send · mail_reply 로 한다. 시각은 한국 시간(+09:00)으로 쓴다. 「내일」「이번 주」는 오늘 날짜 기준으로 계산한다. 지울 일정은 먼저 agenda 로 찾아 그 줄의 id=·cal= 을 쓴다.
        - 메일 본문은 네가 쓴다. 보내기 전 확인 창이 초안을 보여 주므로 finish 로 초안 확인을 따로 받지 않는다 — 받는 사람·내용이 애매할 때만 되묻는다. 발신은 홈 비서 서버에 설정된 주소이고, 답장은 mail_list·mail_read 의 id 로 원문 스레드에 이어진다.
        - memory_add 는 사용자가 「기억해 둬」처럼 명시적으로 요청했을 때만 쓴다. 대화에서 스스로 기억을 쌓지 않는다.
        - 쓰기 도구가 「사용자가 거부함」「시험 제한」을 돌려주면 같은 대상으로 다시 부르지 말고 finish 로 알린다. 결과 줄의 id= 같은 내부 값은 사용자에게 읽어 주지 않는다.
    """.trimIndent()

    // ─────────────────────────────── 공통 헬퍼 ───────────────────────────────

    private fun today(): LocalDate = LocalDate.now(KST)

    /** 시스템 프롬프트용 지금 시각 — 모델은 오늘 날짜를 모른다(「오늘·내일·다음 주」 해석과 일정 만들기에 필요) */
    fun nowLine(): String {
        val n = LocalDateTime.now(KST)
        return "지금: ${n.year}-%02d-%02d(${KDAYS[n.dayOfWeek]}) %02d:%02d (한국 시간)".format(n.monthValue, n.dayOfMonth, n.hour, n.minute)
    }

    /** Android org.json 의 optString 은 null 값에 "null" 을 돌려준다 → 빈 글로 읽는다 */
    private fun str(o: JSONObject?, vararg keys: String): String {
        if (o == null) return ""
        for (k in keys) {
            if (!o.has(k) || o.isNull(k)) continue
            val v = o.opt(k)
            val s = (if (v is JSONArray) (0 until v.length()).mapNotNull { v.opt(it)?.toString() }.joinToString(", ") else v.toString()).trim()
            if (s.isNotEmpty()) return s
        }
        return ""
    }

    /** 참/거짓 인자 — 모델이 "true" 글로 보내도 받는다 */
    private fun bool(o: JSONObject?, k: String): Boolean = when (val v = o?.opt(k)) {
        is Boolean -> v
        is String -> v.trim().equals("true", true) || v.trim() == "1"
        is Number -> v.toInt() != 0
        else -> false
    }

    private fun oneLine(s: String, max: Int): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.take(max) + "…"
    }

    private fun cut(s: String, max: Int): String = if (s.length <= max) s else s.take(max) + "…(이하 ${s.length - max}자 생략)"

    /** 줄 목록을 상한 안에서 잇고 넘치면 「…외 N건」(계약 §3-1) */
    private fun capLines(head: String, lines: List<String>, max: Int = MAX_OUT): String {
        val sb = StringBuilder(head)
        for ((i, line) in lines.withIndex()) {
            val reserve = if (i < lines.lastIndex) 16 else 0   // 뒤에 붙일 「…외 N건」 자리
            if (sb.length + 1 + line.length > max - reserve) return sb.append("\n…외 ${lines.size - i}건").toString()
            sb.append('\n').append(line)
        }
        return sb.toString()
    }

    /** data 안의 첫 배열 — 서버가 {entries|items|results|events|data:[…]} 중 어느 키로 감싸도 읽는다 */
    private fun firstArray(o: JSONObject, vararg prefer: String): JSONArray? {
        for (k in prefer) o.optJSONArray(k)?.let { return it }
        val it = o.keys()
        while (it.hasNext()) o.optJSONArray(it.next())?.let { a -> return a }
        return null
    }

    private fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    // ─────────────────────────────── 시각 ───────────────────────────────

    private val DATE_ONLY = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val KDAYS = mapOf(DayOfWeek.MONDAY to "월", DayOfWeek.TUESDAY to "화", DayOfWeek.WEDNESDAY to "수", DayOfWeek.THURSDAY to "목",
        DayOfWeek.FRIDAY to "금", DayOfWeek.SATURDAY to "토", DayOfWeek.SUNDAY to "일")
    private val ISO_KST_OUT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

    /** 시각 하나를 KST 로 읽는다 — ISO(오프셋·Z·없음=KST)·epoch ms/초. 날짜만이면 null(→ parseDate) */
    private fun parseTime(raw: String): ZonedDateTime? {
        val s = raw.trim()
        if (s.isEmpty() || DATE_ONLY.matches(s)) return null
        s.toLongOrNull()?.let { n -> return Instant.ofEpochMilli(if (n < 100_000_000_000L) n * 1000 else n).atZone(KST) }
        runCatching { return OffsetDateTime.parse(s).atZoneSameInstant(KST) }
        runCatching { return ZonedDateTime.parse(s).withZoneSameInstant(KST) }
        runCatching { return LocalDateTime.parse(s.replace(' ', 'T')).atZone(KST) }
        // 메일 날짜(RFC 2822): "Sat, 27 Sep 2026 10:00:00 +0900 (KST)"
        runCatching {
            val t = s.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "")
            return ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).withZoneSameInstant(KST)
        }
        return null
    }

    private fun parseDate(raw: String): LocalDate? = raw.trim().let { if (DATE_ONLY.matches(it)) runCatching { LocalDate.parse(it) }.getOrNull() else null }

    /** 9/28(월) — 올해가 아니면 2027/1/4(월) */
    private fun day(d: LocalDate): String = (if (d.year != today().year) "${d.year}/" else "") + "${d.monthValue}/${d.dayOfMonth}(${KDAYS[d.dayOfWeek]})"

    private fun hm24(t: ZonedDateTime) = "%02d:%02d".format(Locale.ROOT, t.hour, t.minute)

    /** 오후 3:00 — 확인 창용(사람이 읽는 형식) */
    private fun ampm(t: ZonedDateTime): String {
        val h = t.hour % 12
        return (if (t.hour < 12) "오전 " else "오후 ") + "%d:%02d".format(Locale.ROOT, if (h == 0) 12 else h, t.minute)
    }

    /**
     * 시작~끝 한 줄(KST). 종일이면 날짜만, 같은 날이면 끝은 시각만.
     * @param human true = 「9/28(월) 오후 3:00–4:00」(확인 창) · false = 「9/28(월) 15:00–16:00」(모델이 읽는 목록, 계약 §3-1)
     * 못 읽는 값은 원문 그대로 보여 준다(확인 창에서 사람이 이상한 값을 보게).
     */
    private fun span(startRaw: String, endRaw: String, allDay: Boolean, human: Boolean): String {
        val sd = parseDate(startRaw); val ed = parseDate(endRaw)
        val st = parseTime(startRaw); val et = parseTime(endRaw)
        if (allDay || (sd != null && (ed != null || endRaw.isBlank()))) {
            val a = sd ?: st?.toLocalDate() ?: return "$startRaw ~ $endRaw 종일"
            val b = ed ?: et?.toLocalDate() ?: a
            return if (!b.isAfter(a)) "${day(a)} 종일" else "${day(a)}–${day(b)} 종일"
        }
        if (st == null) return if (endRaw.isBlank()) startRaw else "$startRaw ~ $endRaw"
        val fmt: (ZonedDateTime) -> String = if (human) ::ampm else ::hm24
        val head = "${day(st.toLocalDate())} ${fmt(st)}"
        if (et == null) return if (endRaw.isBlank()) head else "$head ~ $endRaw"
        if (et.toLocalDate() == st.toLocalDate()) {
            // 같은 날 · 같은 오전/오후면 「오후 3:00–4:00」처럼 뒤쪽 오전/오후를 뺀다
            val tail = if (human && (st.hour < 12) == (et.hour < 12)) ampm(et).substringAfter(' ') else fmt(et)
            return "$head–$tail"
        }
        return "$head – ${day(et.toLocalDate())} ${fmt(et)}"
    }

    /** 모델이 준 시각 → 서버에 보낼 ISO KST(+09:00). 오프셋이 없으면 한국 시간으로 본다. 날짜만이면 그대로 */
    private fun normTime(raw: String, what: String): String {
        val s = raw.trim()
        if (parseDate(s) != null) return s
        val t = parseTime(s) ?: throw IllegalArgumentException("$what 시각을 읽지 못함: 「${s.take(40)}」 — 예 2026-09-28T15:00:00+09:00 또는 종일이면 2026-09-28")
        return t.format(ISO_KST_OUT)
    }

    /** event_create 의 종일 판정 — all_day 가 true 거나, 시작·끝이 둘 다 날짜만이면 종일 */
    private fun isAllDay(a: JSONObject): Boolean =
        bool(a, "all_day") || (parseDate(str(a, "start")) != null && (str(a, "end").isEmpty() || parseDate(str(a, "end")) != null))

    // ─────────────────────────────── 메일 주소 ───────────────────────────────

    private val EMAIL = Regex("^[^\\s@<>,;\"]+@[^\\s@<>,;\"]+\\.[^\\s@<>,;\"]+$")

    /** 「홍길동 <a@example.com>, c@example.com; …」 → 주소 목록(소문자 아님 — 보이는 그대로) */
    private fun addrs(raw: String): List<String> = raw.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        .map { part -> Regex("<([^>]+)>").find(part)?.groupValues?.get(1)?.trim() ?: part.trim('"', '\'', ' ') }

    /**
     * 헤더의 첫 주소 — 서버 phoneOps addrOf 와 같은 규칙(첫 「<…>」, 없으면 첫 콤마 앞)·소문자. 주소 모양이 아니면 빈 글.
     * 답장 확인 창의 「받는 사람」이 서버가 실제로 보낼 주소와 같아야 한다
     */
    private fun firstAddr(header: String): String {
        val a = (Regex("<([^>]+)>").find(header)?.groupValues?.get(1) ?: header.split(',')[0]).trim().lowercase()
        return if (SERVER_EMAIL.matches(a)) a else ""
    }
    private val SERVER_EMAIL = Regex("^[^\\s@<>(),;:\"]+@[^\\s@<>(),;:\"]+\\.[^\\s@<>(),;:\"]+$")

    /** 목록 줄용 보낸 사람 — 「홍길동 <a@example.com>」 → 「홍길동」, 이름이 없으면 주소 */
    private fun who(raw: String): String {
        val name = raw.substringBefore('<').trim().trim('"', '\'').trim()
        return if (raw.contains('<') && name.isNotEmpty()) name else raw.trim().removePrefix("<").removeSuffix(">")
    }

    // ─────────────────────────────── op 조립·인자 검증 ───────────────────────────────

    /**
     * 도구 인자 → 서버 op 와 args. 서버는 허용 목록 op 만 받고 argv 를 스스로 조립한다(계약 §0-4) — 여기서는 키만 고르고 모양을 다듬는다.
     * 필수 인자가 비거나 형식이 틀리면 IllegalArgumentException(메시지가 그대로 「실패: 인자 오류 — …」가 된다).
     * memory_add 의 runId 는 Agent 가 붙인다.
     */
    fun op(name: String, args: JSONObject): Pair<String, JSONObject> {
        fun s(k: String) = str(args, k)
        fun need(vararg ks: String) = ks.forEach { require(s(it).isNotEmpty()) { "$it 가 비었다" } }
        return when (name) {
            "agenda" -> {
                val fromS = s("from"); val toS = s("to")
                val from = if (fromS.isEmpty()) today() else requireNotNull(parseDate(fromS) ?: parseTime(fromS)?.toLocalDate()) { "from 은 YYYY-MM-DD: 「${fromS.take(30)}」" }
                val to = if (toS.isEmpty()) from else requireNotNull(parseDate(toS) ?: parseTime(toS)?.toLocalDate()) { "to 는 YYYY-MM-DD: 「${toS.take(30)}」" }
                require(!to.isBefore(from)) { "to($to) 가 from($from) 보다 앞이다" }
                require(!to.isAfter(from.plusDays(62))) { "한 번에 62일까지만 조회한다 — 기간을 나눠 부를 것" }
                "agenda" to JSONObject().put("from", from.toString()).put("to", to.toString()).also { if (s("query").isNotEmpty()) it.put("query", s("query")) }
            }
            "briefing" -> "brief_latest" to JSONObject()
            "mail_list" -> {
                val n = (args.opt("n") as? Number)?.toInt() ?: s("n").toIntOrNull() ?: 10
                val nn = n.coerceIn(1, 20)
                when (s("kind")) {
                    "unreplied" -> "mail_triage" to JSONObject().put("n", nn)
                    "awaiting" -> "mail_awaiting" to JSONObject().put("n", nn)
                    "search" -> { need("query"); "mail_search" to JSONObject().put("query", s("query")).put("n", nn) }
                    "" -> throw IllegalArgumentException("kind 가 비었다 — unreplied · awaiting · search 중 하나")
                    else -> throw IllegalArgumentException("kind 는 unreplied · awaiting · search 중 하나(받은 값: ${s("kind").take(20)})")
                }
            }
            "mail_read" -> { need("id"); "mail_read" to JSONObject().put("id", s("id")) }
            "memory_search" -> { need("query"); "memory_search" to JSONObject().put("query", s("query")) }
            "memory_get" -> { need("ref"); "memory_get" to JSONObject().put("ref", s("ref")) }
            "memory_add" -> {
                need("title", "body")
                require(s("title").length <= 120) { "title 은 120자 이내" }
                val tags = addrs(s("tags")).joinToString(",")   // 콤마·세미콜론 섞여도 콤마 목록으로
                "memory_add" to JSONObject().put("title", s("title")).put("body", s("body")).also { if (tags.isNotEmpty()) it.put("tags", tags) }
            }
            "event_create" -> {
                need("title", "start", "end")
                val allDay = isAllDay(args)
                val o = JSONObject().put("title", s("title")).put("all_day", allDay)
                if (allDay) {
                    val a = requireNotNull(parseDate(s("start")) ?: parseTime(s("start"))?.toLocalDate()) { "start 날짜를 읽지 못함: 「${s("start").take(40)}」" }
                    val b = requireNotNull(parseDate(s("end")) ?: parseTime(s("end"))?.toLocalDate()) { "end 날짜를 읽지 못함: 「${s("end").take(40)}」" }
                    require(!b.isBefore(a)) { "끝 날짜가 시작보다 앞이다" }
                    o.put("start", a.toString()).put("end", b.toString())
                } else {
                    val st = normTime(s("start"), "start"); val et = normTime(s("end"), "end")
                    require(parseDate(st) == null && parseDate(et) == null) { "시각 일정은 start·end 둘 다 시각이 있어야 한다(종일이면 all_day=true)" }
                    require(parseTime(et)!!.isAfter(parseTime(st)!!)) { "끝 시각이 시작보다 앞이거나 같다" }
                    o.put("start", st).put("end", et)
                }
                if (s("note").isNotEmpty()) o.put("note", s("note"))
                "event_create" to o
            }
            "event_delete" -> { need("event_id", "calendar_id"); "event_delete" to JSONObject().put("event_id", s("event_id")).put("calendar_id", s("calendar_id")) }
            "mail_send" -> {
                need("to", "subject", "body")
                val to = addrs(s("to")); val cc = addrs(s("cc"))
                require(to.isNotEmpty()) { "to 에 주소가 없다" }
                (to + cc).firstOrNull { !EMAIL.matches(it) }?.let { throw IllegalArgumentException("주소 형식이 아니다: 「${it.take(60)}」") }
                require(to.size + cc.size <= 5) { "받는 사람·참조 합쳐 5개까지(지금 ${to.size + cc.size}개)" }
                require(s("subject").length <= 200) { "제목은 200자 이내" }
                require(s("body").length <= 20_000) { "본문은 20,000자 이내" }
                "mail_send" to JSONObject().put("to", to.joinToString(",")).put("subject", s("subject")).put("body", s("body"))
                    .also { if (cc.isNotEmpty()) it.put("cc", cc.joinToString(",")) }
            }
            "mail_reply" -> {
                need("id", "body")
                require(s("body").length <= 20_000) { "본문은 20,000자 이내" }
                "mail_reply" to JSONObject().put("id", s("id")).put("body", s("body"))
            }
            else -> throw IllegalArgumentException("홈 비서 도구가 아님: $name")
        }
    }

    // ─────────────────────────────── 확인 창 ───────────────────────────────

    /** 본문 앞 n자 — 줄바꿈은 살리고 넘치면 「…(총 N자)」 */
    private fun preview(s: String, n: Int): String = if (s.length <= n) s else s.take(n).trimEnd() + "…(총 ${s.length}자)"

    /**
     * 확인 창 문구 — 사람이 판단할 모든 것을 넣는다(계약 §0-2). 시각은 KST 「9/28(월) 오후 3:00–4:00」.
     * @param ctx mail_reply 일 때 Agent 가 미리 읽은 원문(mail_read data: from·subject·replyTo …) · event_delete 일 때 이번 실행 agenda 로 본 일정. 그 밖엔 null
     */
    fun gateText(name: String, args: JSONObject, ctx: JSONObject? = null): String {
        fun s(k: String) = str(args, k)
        return when (name) {
            "mail_send" -> {
                val to = addrs(s("to")); val cc = addrs(s("cc"))
                buildString {
                    append("✉️ 메일을 보낼까요?\n\n")
                    append("받는 사람(${to.size}): ${to.joinToString(", ")}\n")
                    if (cc.isNotEmpty()) append("참조(${cc.size}): ${cc.joinToString(", ")}\n")
                    append("제목: ${s("subject")}\n")
                    append("보내는 주소: 홈 비서(${BuildConfig.MAIL_FROM_LABEL.ifBlank { "서버 설정 주소" }})\n\n")
                    append(preview(s("body"), 300))
                }
            }
            "mail_reply" -> {
                val from = str(ctx, "from")
                val replyTo = str(ctx, "replyTo", "reply_to")
                val subj = str(ctx, "subject")
                val reSubj = if (subj.isEmpty()) "" else if (subj.startsWith("re:", true)) subj else "Re: $subj"
                // 실제 받는 사람 = 서버와 같은 규칙(Reply-To 첫 주소 ?? From 첫 주소) — 원문 보낸 사람만 보여 주면
                // Reply-To 가 다른 메일에서 사람이 모르는 주소로 나갔다(리뷰 safety#2·runtime#3)
                val fromAddr = firstAddr(from)
                val to = firstAddr(replyTo).ifEmpty { fromAddr }
                buildString {
                    append("↩️ 답장을 보낼까요?\n\n")
                    append("받는 사람: ${to.ifEmpty { "?(원문을 못 읽음)" }}")
                    if (to.isNotEmpty() && to != fromAddr) append(" ⚠️ 원문 보낸 사람과 다름(원문의 Reply-To)")
                    append("\n")
                    // 보낼 본문을 원문 정보보다 먼저 — 뒤에 두면 확인 창 아래로 밀려 안 보였다(2026-09-27 실기 T9)
                    if (reSubj.isNotEmpty()) append("답장 제목: $reSubj\n")
                    append("보내는 주소: 홈 비서(${BuildConfig.MAIL_FROM_LABEL.ifBlank { "서버 설정 주소" }})\n\n")
                    append(preview(s("body"), 300)).append("\n\n")
                    append("원문: ${from.ifEmpty { "?(원문을 못 읽음)" }}")
                    str(ctx, "date").takeIf { it.isNotEmpty() }?.let { d -> append(" · ${parseTime(d)?.let { "${day(it.toLocalDate())} ${ampm(it)}" } ?: d}") }
                    append("\n원문 제목: ${subj.ifEmpty { "?" }}")
                }
            }
            "event_create" -> {
                val allDay = isAllDay(args)
                buildString {
                    append("📅 일정을 만들까요?\n\n")
                    append("제목: ${s("title")}\n")
                    append("시각: ${span(s("start"), s("end"), allDay, human = true)}\n")
                    if (allDay) append("종일: 예\n")
                    if (s("note").isNotEmpty()) append("메모: ${preview(s("note"), 200)}\n")
                }.trimEnd()
            }
            "event_delete" -> buildString {
                append("🗑️ 일정을 지울까요?\n\n")
                val title = str(ctx, "summary", "title")
                if (ctx != null && title.isNotEmpty()) {
                    append("제목: $title\n")
                    append("시각: ${span(str(ctx, "start"), str(ctx, "end"), bool(ctx, "allDay") || bool(ctx, "all_day"), human = true)}\n")
                    str(ctx, "calendar").takeIf { it.isNotEmpty() }?.let { append("캘린더: $it\n") }
                } else {
                    append("⚠️ 이번 대화에서 조회하지 않은 일정이라 제목·시각을 모릅니다.\n")
                }
                append("id: ${s("event_id")}")
            }
            "memory_add" -> buildString {
                append("🧠 기억에 추가할까요?\n\n")
                append("제목: ${s("title")}\n")
                val tags = addrs(s("tags"))
                if (tags.isNotEmpty()) append("태그: ${tags.joinToString(", ")}\n")
                append("\n")
                append(preview(s("body"), 200))
            }
            else -> "「$name」 실행할까요?"
        }
    }

    /**
     * 거부 기억 키 = 도구명:대상(계약 §0-3). 메일은 받는 사람+제목, 일정 생성은 제목+시작, 삭제는 event_id, 기억은 제목.
     * 같은 대상을 모양만 바꿔 다시 부르면 같은 키가 나오게 다듬는다(주소 순서·대소문자, 시각 표기).
     */
    fun gateKey(name: String, args: JSONObject): String {
        fun s(k: String) = str(args, k)
        fun norm(t: String) = t.replace(Regex("\\s+"), " ").trim().lowercase()
        val target = when (name) {
            "mail_send" -> addrs(s("to")).map { it.lowercase() }.sorted().joinToString(",") + "|" + norm(s("subject"))
            "mail_reply" -> s("id")
            "event_create" -> {
                val st = s("start")
                val key = parseDate(st)?.toString() ?: parseTime(st)?.format(ISO_KST_OUT) ?: st
                norm(s("title")) + "|" + key
            }
            "event_delete" -> s("event_id")
            "memory_add" -> norm(s("title"))
            else -> args.toString()
        }
        return "$name:$target"
    }

    // ─────────────────────────────── 결과 요약 ───────────────────────────────

    /**
     * 성공 결과 → 모델이 읽을 글(계약 §3-1). 쓰기 도구는 Agent 가 data 에 "request"(모델 인자)를 넣어 준다.
     * 일정 줄엔 id·cal 을(event_delete 용), 메일 줄엔 id 를(mail_read·mail_reply 용) 남긴다. 3,000자 상한 「…외 N건」.
     */
    fun format(name: String, data: JSONObject): String {
        val req = data.optJSONObject("request") ?: JSONObject()
        val out = when (name) {
            "agenda" -> fmtAgenda(data)
            "briefing" -> fmtBrief(data)
            "mail_list" -> fmtMailList(data)
            "mail_read" -> fmtMailRead(data)
            "memory_search" -> fmtMemSearch(data)
            "memory_get" -> fmtMemGet(data)
            "mail_send" -> {
                val to = addrs(str(req, "to")); val cc = addrs(str(req, "cc"))
                "✅ 메일 보냄 — to ${to.joinToString(", ")}" + (if (cc.isNotEmpty()) " · 참조 ${cc.joinToString(", ")}" else "") +
                    " · 제목 「${str(req, "subject")}」" + str(data, "id").let { if (it.isEmpty()) "" else " (id=$it)" }
            }
            "mail_reply" -> "✅ 답장 보냄 — to ${str(data, "to").ifEmpty { "?" }} · 제목 「${str(data, "subject").ifEmpty { "?" }}」" +
                str(data, "id").let { if (it.isEmpty()) "" else " (id=$it)" } +
                // 원문에 Message-ID 가 없으면 서버가 스레드 헤더 없이 보낸다 — 사용자에게 알릴 것
                if (data.has("threaded") && !bool(data, "threaded")) " ⚠️ 원문 Message-ID 가 없어 스레드로 이어지지 않은 새 메일로 갔다" else ""
            "event_create" -> "✅ 일정 만듦 — 「${str(req, "title")}」 ${span(str(req, "start"), str(req, "end"), isAllDay(req), human = false)}" +
                " (id=${str(data, "id").ifEmpty { "?" }} cal=${str(data, "calendarId", "calendar_id").ifEmpty { "?" }})"
            "event_delete" ->
                if (data.has("deleted") && !bool(data, "deleted")) "실패: 서버가 삭제되지 않았다고 답함 (id=${str(req, "event_id")})"
                else "✅ 일정 삭제 — id=${str(req, "event_id")}"
            "memory_add" -> {
                if (data.has("ok") && !bool(data, "ok")) "실패: 기억 추가 안 됨 — ${str(data, "error", "message").ifEmpty { "사유 없음" }}"
                else "✅ 기억에 추가 — 「${str(req, "title")}」" +
                    (if (str(data, "action") == "updated") " (같은 제목 항목에 이어 붙임)" else "") +
                    str(data, "slug").let { if (it.isEmpty()) "" else " slug=$it" }
            }
            else -> data.toString()
        }
        return if (out.length <= MAX_OUT) out else out.take(MAX_OUT) + "\n…(3,000자에서 자름)"
    }

    private fun fmtAgenda(data: JSONObject): String {
        val ev = objects(firstArray(data, "events", "items"))
        // 조회 기간을 결과 머리에 밝힌다 — 기간 없이 「일정 1건」만 주자 모델이 오늘(9/27)을 9/28 로 잘못 알고 다시 조회해 「오늘 9/28」로 답했다(2026-09-27 실기 T1)
        val range = data.optJSONObject("request")?.let { r ->
            val f = parseDate(str(r, "from")); val t = parseDate(str(r, "to")) ?: f
            if (f == null) "" else if (t == null || t == f) day(f) else "${day(f)}~${day(t)}"
        }.orEmpty()
        val head = if (range.isEmpty()) "" else "조회 기간 $range · "
        if (ev.isEmpty()) return "${head}해당 기간 일정 없음"
        val lines = ev.map { e ->
            val allDay = bool(e, "allDay") || bool(e, "all_day")
            val cal = str(e, "calendar").ifEmpty { str(e, "calendarId", "calendar_id") }
            buildString {
                append(span(str(e, "start"), str(e, "end"), allDay, human = false))
                append(" ").append(str(e, "summary", "title").ifEmpty { "(제목 없음)" })
                if (cal.isNotEmpty()) append(" [$cal]")
                str(e, "labelName").takeIf { it.isNotEmpty() }?.let { append(" #$it") }
                append(" (id=${str(e, "id")} cal=${str(e, "calendarId", "calendar_id")})")
                str(e, "note").takeIf { it.isNotEmpty() }?.let { append(" — 메모: ${oneLine(it, 80)}") }
            }
        }
        return capLines("${head}일정 ${ev.size}건:", lines)
    }

    private fun fmtBrief(data: JSONObject): String {
        // {brief:null} · {ok:false,error:no_briefing} · {ok:true,markdown} · {brief:{date,markdown}} 모두 받는다
        val b = data.optJSONObject("brief") ?: data
        val md = str(b, "markdown", "body", "content", "text")
        if (md.isEmpty() || (data.has("brief") && data.isNull("brief"))) return "아직 만들어진 브리핑이 없음"
        val date = str(b, "date")
        // 결번 날엔 latest 가 며칠 전 것이다 — 오늘 것처럼 읽히지 않게 밝힌다(리뷰 server#6)
        val stale = parseDate(date)?.let { it != today() } == true
        val head = when {
            date.isEmpty() -> ""
            stale -> "브리핑($date) ⚠️ 오늘(${today()}) 것이 아님 — 가장 최근 브리핑\n"
            else -> "브리핑($date)\n"
        }
        return cut(head + md.trim(), MAX_OUT - 40)
    }

    private fun mailWhen(raw: String): String = parseTime(raw)?.let { "${day(it.toLocalDate())} ${hm24(it)}" } ?: raw.take(25)

    private fun fmtMailList(data: JSONObject): String {
        val items = objects(firstArray(data, "items", "messages", "threads", "results"))
        if (items.isEmpty()) return "해당 메일 없음"
        val lines = items.map { m ->
            val id = str(m, "id", "messageId", "threadId")
            buildString {
                append("• ").append(mailWhen(str(m, "date")))
                append(" ").append(who(str(m, "from", "to")).ifEmpty { "?" })
                append(" — 「").append(oneLine(str(m, "subject"), 80).ifEmpty { "(제목 없음)" }).append("」")
                str(m, "kind").takeIf { it.isNotEmpty() }?.let { append(" [$it]") }
                // awaiting 의 id 는 스레드 첫 메일(내가 보낸 것)이다 — 여기에 mail_reply 하면 나에게 간다(서버가 막는다, 리뷰 runtime#10)
                if (str(m, "kind") == "awaiting") append(" (내가 보낸 메일 — mail_reply 대상 아님)")
                str(m, "snippet").takeIf { it.isNotEmpty() }?.let { append("\n  ").append(oneLine(it, 100)) }
                append(" (id=$id)")
            }
        }
        // 서버가 앞에서 잘랐으면 전체 건수를 함께 보인다(triage total)
        val total = data.optInt("total", items.size)
        return capLines(if (total > items.size) "메일 ${total}건 중 최근 ${items.size}건:" else "메일 ${items.size}건:", lines)
    }

    private fun fmtMailRead(data: JSONObject): String {
        val m = data.optJSONObject("message") ?: data
        val head = buildString {
            append("보낸 사람: ${str(m, "from").ifEmpty { "?" }}\n")
            str(m, "to").takeIf { it.isNotEmpty() }?.let { append("받는 사람: $it\n") }
            str(m, "cc").takeIf { it.isNotEmpty() }?.let { append("참조: $it\n") }
            append("제목: ${str(m, "subject").ifEmpty { "(제목 없음)" }}\n")
            str(m, "date").takeIf { it.isNotEmpty() }?.let { append("날짜: ${mailWhen(it)}\n") }
            append("(id=${str(m, "id")}" + str(m, "threadId").let { if (it.isEmpty()) "" else " thread=$it" } + ")\n\n")
        }
        val body = str(m, "body", "text", "snippet").ifEmpty { "(본문 없음)" }
        return head + cut(body.trim(), (MAX_OUT - head.length - 40).coerceAtLeast(200))
    }

    private fun fmtMemSearch(data: JSONObject): String {
        val ent = objects(firstArray(data, "entries", "results", "items"))
        if (ent.isEmpty()) return "기억에서 찾지 못함"
        val lines = ent.map { e ->
            buildString {
                append("• ").append(str(e, "title").ifEmpty { "(제목 없음)" })
                str(e, "slug").takeIf { it.isNotEmpty() }?.let { append(" (slug=$it)") }
                val tags = str(e, "tags")
                if (tags.isNotEmpty()) append(" #").append(tags.replace(", ", " #"))
                str(e, "updated").takeIf { it.isNotEmpty() }?.let { u -> append(" · 갱신 ${parseTime(u)?.let { day(it.toLocalDate()) } ?: u.take(10)}") }
                str(e, "summary", "snippet").takeIf { it.isNotEmpty() }?.let { append("\n  ").append(oneLine(it, 140)) }
            }
        }
        return capLines("기억 ${ent.size}건:", lines)
    }

    private fun fmtMemGet(data: JSONObject): String {
        // 스크립트 {ok, meta, body} · 웹 {entry:{meta, body}} · 못 찾음 {ok:false, error:not_found}
        val e = data.optJSONObject("entry") ?: data
        if (data.has("ok") && !bool(data, "ok") && !e.has("body")) return "기억 없음 — ${str(data, "ref").ifEmpty { str(data, "error") }}"
        val meta = e.optJSONObject("meta") ?: e
        val head = buildString {
            append("# ${str(meta, "title").ifEmpty { "(제목 없음)" }}")
            str(meta, "slug").takeIf { it.isNotEmpty() }?.let { append(" (slug=$it)") }
            append("\n")
            str(meta, "tags").takeIf { it.isNotEmpty() }?.let { append("태그: $it\n") }
            str(meta, "updated").takeIf { it.isNotEmpty() }?.let { append("갱신: ${it.take(10)}\n") }
            append("\n")
        }
        return head + cut(str(e, "body").ifEmpty { "(본문 없음)" }.trim(), (MAX_OUT - head.length - 40).coerceAtLeast(200))
    }

    // ─────────────────────────────── 한 줄 요약·상태 ───────────────────────────────

    /** steps.summary 용 한 줄(예 `agenda 9/28~9/28`, `mail_send → minji@… 「제목」`) */
    fun summarize(name: String, args: JSONObject, outcome: String): String {
        fun s(k: String) = str(args, k)
        fun d(raw: String) = (parseDate(raw) ?: parseTime(raw)?.toLocalDate())?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: raw.take(16)
        return when (name) {
            "agenda" -> {
                val from = if (s("from").isEmpty()) d(today().toString()) else d(s("from"))
                "agenda $from~${if (s("to").isEmpty()) from else d(s("to"))}" + (if (s("query").isNotEmpty()) " 「${s("query").take(20)}」" else "")
            }
            "briefing" -> "briefing"
            "mail_list" -> "mail_list ${s("kind")}" + (if (s("query").isNotEmpty()) " 「${s("query").take(30)}」" else "")
            "mail_read" -> "mail_read ${s("id").take(24)}"
            "memory_search" -> "memory_search 「${s("query").take(30)}」"
            "memory_get" -> "memory_get ${s("ref").take(40)}"
            "memory_add" -> "memory_add 「${s("title").take(30)}」"
            "event_create" -> "event_create 「${s("title").take(30)}」 ${span(s("start"), s("end"), isAllDay(args), human = false).take(40)}"
            "event_delete" -> "event_delete ${s("event_id").take(24)}"
            "mail_send" -> {
                val to = addrs(s("to"))
                val first = to.firstOrNull()?.let { a -> if ('@' in a) a.substringBefore('@') + "@…" else a.take(20) } ?: "?"
                "mail_send → $first" + (if (to.size > 1) " 외 ${to.size - 1}" else "") + " 「${s("subject").take(30)}」"
            }
            "mail_reply" -> "mail_reply ${s("id").take(24)}"
            else -> name
        } + when {
            outcome == "사용자가 멈춤" -> " ⏹"
            outcome.startsWith("실패") -> " ✗"
            else -> ""
        }
    }

    /** 원격 호출 중 상태 문구(svc.status) */
    fun status(name: String): String = when (name) {
        "agenda" -> "📅 일정 확인 중…"
        "briefing" -> "📰 브리핑 가져오는 중…"
        "mail_list" -> "📬 메일 확인 중…"
        "mail_read" -> "📬 메일 읽는 중…"
        "memory_search", "memory_get" -> "🧠 기억 찾는 중…"
        "memory_add" -> "🧠 기억에 추가하는 중…"
        "event_create" -> "📅 일정 만드는 중…"
        "event_delete" -> "📅 일정 지우는 중…"
        "mail_send" -> "✉️ 메일 보내는 중…"
        "mail_reply" -> "✉️ 답장 보내는 중…"
        else -> "⏳ 홈 비서 호출 중…"
    }
}
