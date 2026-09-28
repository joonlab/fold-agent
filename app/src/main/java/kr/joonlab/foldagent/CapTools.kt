package kr.joonlab.foldagent

import kr.joonlab.foldagent.jobs.JobSummary
import org.json.JSONArray
import org.json.JSONObject

/**
 * 스킬·실행·MCP 도구 — 홈맥 `/api/phone/exec` 의 새 op 를 부른다(AssistantClient·토큰·주소는 홈 비서와 같다).
 * 계약 docs/server-contract/CONTRACT.md §1-2 · §1-3.
 *
 * P0 결선(스키마·PROMPT·gateTarget) + W-P2 본 구현(op 검증·정규화 · confirmText 머리 · format 결과 글 · attachments 서버 파일 받기 · summarize·status).
 * 서버 data 모양은 홈 서버 구현 기준 — 계약 요약과 키 이름이 조금 다른 곳(exit↔exitCode·name↔server·policy↔rules)은 둘 다 읽는다.
 * 실행 흐름(need_confirm 2단·거부·결과 불명·맡김·job 등록·첨부 모으기)은 Agent.capTool() 이 가진다 — 여기는 부수효과 없는 글·JSON 만.
 * (예외: attachments 는 서버 파일을 받아 저장하므로 부수효과가 있다 — Agent 가 성공 결과에서만 부른다)
 */
object CapTools {

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().put("type", "function").put(
            "function", JSONObject().put("name", name).put("description", desc).put(
                "parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))
            )
        )

    private fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

    /** 폰에서 올린 파일(계약 INPUTS §3) — 서버가 작업 폴더 in/ 에 복사한다. 모델은 [받은 파일] 블록의 id 만 넘긴다(폰 경로를 지어내지 않게) */
    private val INPUTS_SCHEMA: JSONObject = JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
        .put("description", "[받은 파일] 의 파일 id(최대 5, 선택) — 작업 폴더의 in/ 에 들어간다")

    val TOOLS: List<JSONObject> = listOf(
        fn("skill_search", "사용자 홈맥에 있는 스킬(유튜브 분석·법령 검색·문서 변환·데이터 조회 등 준비된 작업 도구)을 찾는다. 전용 도구로 안 되는 일이면 먼저 이것으로 찾고, " +
            "고른 스킬은 skill_view 로 문서를 읽은 뒤 run 으로 실행한다", JSONObject()
            .put("query", p("string", "무엇을 하려는지(예: 유튜브 요약, 법령 조문, pdf 변환)"))
            .put("n", p("integer", "몇 개(기본 8)")), listOf("query")),
        fn("skill_view", "스킬 문서(SKILL.md 또는 스킬 안 참고 파일)를 읽는다. 끝에 이 스킬에서 자동 실행·승인·차단되는 명령 규칙이 붙는다. " +
            "run 에 넣을 명령은 반드시 여기서 읽은 문서에 적힌 것만 쓴다", JSONObject()
            .put("name", p("string", "skill_search 결과의 스킬 이름"))
            .put("path", p("string", "스킬 안 참고 파일 경로(선택, 기본 SKILL.md)"))
            .put("offset", p("integer", "이어 읽을 위치(문서가 길면 결과 끝에 알려 준다)")), listOf("name")),
        fn("run", "스킬 문서에 적힌 명령 하나를 홈맥에서 실행한다. 셸을 쓰지 않으므로 argv 배열로 준다(파이프·&&·;·리다이렉트 불가 — 나눠서 부른다). " +
            "읽기 명령은 바로 돌고, 파일 생성·외부 호출은 사용자 확인 창이, 유료 API 는 비용 확인 창이 뜬다. 발송·게시·삭제는 막혀 있다. " +
            "오래 걸리는 작업은 홈맥이 뒤에서 돌리고(「맡김」) 끝나면 폰 알림이 온다 — 그때는 기다리지 말고 finish 로 맡겼다고 알린다", JSONObject()
            .put("skill", p("string", "스킬 이름"))
            .put("argv", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                .put("description", "명령과 인자를 하나씩(예: [\"python3\", \"scripts/search.py\", \"연차\"]). 스크립트 경로는 스킬 폴더 기준"))
            .put("background", p("boolean", "오래 걸릴 게 분명하면 true — 뒤에서 돌리고 끝나면 알림"))
            .put("stdin", p("string", "표준 입력으로 넘길 글(선택, 20KB 이하)"))
            .put("then", p("string", "뒤에서 돌 때: 끝난 뒤 원래 요청을 마치려고 이어서 할 일(예: 「받은 영상에서 00:01·04:46 프레임을 뽑아 노트 「AI서대표 요약」에 추가」). " +
                "적어 두면 결과가 오는 대로 폰이 자동으로 이어서 한다 — 남은 단계가 있으면 반드시 적는다"))
            .put("inputs", INPUTS_SCHEMA)
            .put("from_job", p("string", "앞선 run·code_run·claude_task 의 작업 id — 새 폴더 대신 그 작업 폴더에서 이어 실행한다(새로 생기거나 바뀐 파일만 결과에 붙는다)")),
            listOf("skill", "argv")),
        fn("code_run", "파이썬 코드를 홈맥 격리 상자에서 실행한다(인터넷 없음·20초·메모리 256MB, numpy·matplotlib·ffmpeg·python-docx·openpyxl·pypdf·reportlab 있음 · 한글 글꼴 /usr/share/fonts/truetype/nanum/NanumGothic.ttf). 계산·표 정리·그래프(matplotlib 로 png 저장)·워드·엑셀·PDF 만들기(docx·xlsx·pdf 스킬 문서의 방법대로)·" +
            "영상 프레임 뽑기(subprocess 로 ffmpeg -ss 시각 -i /in/파일 -frames:v 1 -threads 1 out.jpg — 상자 안에선 -threads 1 이 없으면 PNG·화질 옵션 인코딩이 실패한다)에 쓴다. 작업 폴더(현재 폴더)에 저장한 파일은 채팅에 붙는다", JSONObject()
            .put("code", p("string", "실행할 python 코드(결과는 print 로)"))
            .put("from_job", p("string", "앞선 run·code_run 의 작업 id — 그 작업 폴더를 /in 에 읽기 전용으로 붙인다(받은 영상·파일을 이어서 가공할 때. 채팅에 못 붙은 큰 파일도 여기 있다)"))
            .put("files", p("object", "작업 폴더에 미리 둘 작은 텍스트 파일 {이름: 내용}(선택)"))
            .put("inputs", INPUTS_SCHEMA), listOf("code")),
        fn("mcp_tools", "홈맥에 연결된 MCP 서버와 그 도구 목록(이름·설명·인자)을 본다. mcp_call 전에 인자 모양을 여기서 확인한다", JSONObject()
            .put("server", p("string", "서버 이름(선택, 없으면 전체)"))),
        fn("mcp_call", "홈맥 MCP 서버의 도구 하나를 부른다. 읽기가 아닌 동작은 확인 창이 뜨고, 발송·게시·삭제는 막혀 있다", JSONObject()
            .put("server", p("string", "mcp_tools 의 서버 이름"))
            .put("tool", p("string", "도구 이름"))
            .put("args", p("object", "도구 인자(mcp_tools 에 나온 모양 그대로)")), listOf("server", "tool", "args")),
        fn("claude_task", "홈맥의 Claude Code 에게 일을 통째로 맡긴다(스킬·코드 수정·파일 정리·긴 조사). 늘 뒤에서 돌고 끝나면 알림 — 결과·만든 파일은 이 방에 붙는다. " +
            "읽기는 알아서 하고, 파일 쓰기·명령 실행은 그때마다 사용자 폰에 확인을 묻는다. 발송·게시·삭제는 막혀 있다(초안까지)", JSONObject()
            .put("task", p("string", "맡길 일 — 사용자 요청을 한국어 그대로, 필요한 맥락(대상·조건·원하는 산출물)을 붙여서"))
            .put("title", p("string", "알림에 보일 짧은 이름(선택)"))
            .put("then", p("string", "끝난 뒤 원래 요청을 마치려고 폰이 이어서 할 일(선택, 예: 「만든 초안을 mail_send 로 사용자에게」). run 의 then 과 같다"))
            .put("inputs", INPUTS_SCHEMA), listOf("task")),
        fn("job_status", "홈맥에 맡긴 작업의 상태·결과를 본다(사용자가 물을 때만 — 끝나면 알림이 오므로 반복해서 확인하지 않는다)", JSONObject()
            .put("jobId", p("string", "작업 id(없으면 최근 5개)"))),
        fn("job_cancel", "홈맥에 맡긴 작업을 취소한다", JSONObject()
            .put("jobId", p("string", "작업 id")), listOf("jobId")),
    )

    val NAMES: Set<String> = TOOLS.map { it.getJSONObject("function").getString("name") }.toSet()

    /** 조회만 — 같은 조회 반복 감시(Agent)에 쓴다. 서버 판정과 무관하다(승인 판정은 늘 서버) */
    val READ_ONLY: Set<String> = setOf("skill_search", "skill_view", "mcp_tools", "job_status")

    /** 홈맥에서 무언가를 실행하는 도구 — 요청이 닿은 뒤 끊기면 「결과 불명」(다시 보내지 말 것). AssistantClient.EXEC_OPS 와 같다 */
    val EXEC: Set<String> = setOf("run", "code_run", "mcp_call", "job_cancel", "claude_task")

    /** BASE_PROMPT 뒤에 붙는 한 단락(Agent.systemPrompt) */
    val PROMPT: String = """
        ## 끝까지 스스로(2026-09-27 사용자: 「알아서 쭉 할 수 있는 걸 중간에 멈추고 묻는다」)
        - 사용자에게 되묻는 건 요청이 정말 모호해 어느 쪽으로 해도 틀릴 때만. 스킬 문서가 모델·옵션·저장 위치를 「사용자에게 물어라」라고 해도 문서의 기본·권장값으로 진행하고, 답에 무엇으로 했는지 한 줄 밝힌다.
        - 승인이 필요한 명령도 「진행할까요?」로 먼저 묻지 않는다 — 바로 부르면 확인 창이 뜬다(그게 질문이다).
        - 「할 수 없다」로 끝내기 전에 다른 길(code_run·from_job·다른 스킬·web_search)을 한 번 더 찾는다. 막힌 이유만 말하고 멈추지 않는다.
        - 여러 단계 요청은 한 번에 끝까지 한다. 중간 보고로 finish 하지 않는다.

        ## 방법 고르기(choose_way — 도구 목록에 있을 때만, 2026-09-28 사용자)
        - 요청을 처리할 길이 실질적으로 다른 2~3개(폰 앱 화면에서 직접 / 웹 검색 / 홈맥 Claude Code 위임 / 스킬 실행 등)이고 사용자가 방법을 정하지 않았으면 **첫 단계에서** choose_way 를 한 번 부른다 — 선택지마다 detail 에 걸리는 시간·폰 화면 사용·비용 한 줄, 가장 나은 것을 recommended.
        - 앱 화면에서 바로 되는 일은 그 길을 선택지에 넣는다. 예: 유튜브 채널 영상 찾기 → 「유튜브 앱에서 직접」.
        - 길이 사실상 하나거나 단순한 요청(앱 열기·기기 설정·짧은 질문·메모 등)이면 쓰지 않는다. 고른 뒤엔 되묻지 않고 그 방법으로 끝까지 한다.
        - 카드엔 늘 「직접 답하기」가 붙는다 — 사용자가 직접 답하면 그 글이 방법·조건이다(선택지에 없어도 그대로 따른다).

        ## 홈맥 스킬·코드 실행·MCP(폰 화면을 쓰지 않는다)
        - 전용 도구(홈 비서·기기 제어·web_search·image_gen 등)로 되는 일이면 그걸 먼저 쓴다.
        - 스킬이 필요해 보이면 skill_search → skill_view 로 문서를 읽고, 거기 적힌 명령을 argv 로 run 한다. 명령·옵션을 지어내지 않는다 — 문서에 없으면 --help 로 먼저 확인한다.
        - run 은 셸이 아니다. 파이프·&&·;·리다이렉트는 못 쓴다 — 나눠서 부르고, 파일은 작업 폴더(현재 폴더)에 쓰게 한다.
        - 발송·게시·삭제는 스킬로 하지 않는다(홈맥이 막는다). 메일은 mail_send·mail_reply 로만.
        - 계산·표·그래프·영상 프레임(ffmpeg)은 code_run(파이썬, 인터넷 없음). 앞 작업이 만든 파일(받은 영상 등)은 from_job 으로 /in 에서 읽는다 — 다시 받지 않는다.
        - 사용자의 모임·강의·세션 기록·업무 데이터처럼 웹에 없을 법한 것은 web_search 전에 skill_search 로 먼저 찾는다 — 결과에 MCP 서버(「mcp:」)도 나온다.
        - 확인 창에서 거부되면 같은 명령을 다시 부르지 말고 finish 로 알린다. 「차단된 동작」이면 다시 시도하지 않는다.
        - 「맡김」이 오면 홈맥이 뒤에서 돌리는 중이다 — 기다리거나 job_status 를 되풀이하지 말고 finish 로 「맡겼고 끝나면 알림이 온다」고 알린다.
          원래 요청에 남은 단계가 있으면 run 의 then 에 적어 둔다 — 결과가 오면 폰이 자동으로 이어서 한다(사용자가 다시 시킬 필요 없게).
        - 사용자가 폰에서 보낸 파일은 [받은 파일] 의 파일 id 로만 쓴다 — code_run·claude_task·run 의 inputs 에 id 를 넣으면 작업 폴더의 in/ 에 들어간다
          (code_run 은 in/<이름>, run 은 argv 에 in/<이름>, claude_task 는 task 에 「in/ 의 파일」이라고 적는다). 폰 경로를 지어내지 않는다.
        - 앞선 작업 폴더에서 이어 실행하려면 run 에 from_job(작업 id) — 그 폴더가 현재 폴더가 된다.
        - 만든 파일·그림은 채팅에 자동으로 붙는다. 결과 글에 없는 파일 이름을 지어내지 않는다.
        - 여러 스킬을 엮어야 하거나, 코드를 고치거나, 30초 넘게 걸릴 조사·정리는 claude_task 로 통째로 맡긴다. 전용 도구·run·code_run 한두 번으로 되면 그걸 먼저.
          발송은 맡기지 않는다 — 초안까지 맡기고 보내기는 mail_send(then 에 적어 둔다). 맡긴 뒤엔 finish 로 「맡겼고 끝나면 알림, 중간 확인은 폰에 뜬다」고 알린다.
    """.trimIndent()

    // ── op 조립·검증 ────────────────────────────────────────────────────

    /** 셸이 해석하던 연산자 — argv 원소가 통째로 이것이면 모델이 셸 명령을 쪼개 넣은 것이다(서버는 execFile 이라 글자 그대로 넘어간다) */
    private val SHELL_OPS = setOf("|", "||", "&&", "&", ";", ";;", ">", ">>", "<", "<<", "<<<", "2>", "2>>", "2>&1", "&>", ">&", "|&")

    /** 문자열 안에 셸 연산자가 섞였나(argv 를 문자열 하나로 줬을 때 거절 안내용) */
    private val SHELL_IN_STRING = Regex("""\s(\|\|?|&&|;|>>?|<|2>&1)\s|\$\(|`""")

    private const val MAX_ARGV = 64
    private const val MAX_ARGV_CHARS = 16_000
    private const val MAX_STDIN_BYTES = 20 * 1024
    private const val MAX_CODE_CHARS = 40_000
    private const val MAX_FILE_BYTES = 20 * 1024
    private const val MAX_FILES = 10
    private const val MAX_TASK_CHARS = 8_000     // 계약 DELEGATION §3-1
    private const val MAX_TITLE_CHARS = 60
    private val JOB_ID_RE = Regex("^[A-Za-z0-9_-]{6,64}$")
    private val FILE_ID_RE = Regex("^[A-Za-z0-9_-]{8,80}$")
    private const val MAX_INPUTS = 5              // 계약 INPUTS §3

    private fun str(a: JSONObject, k: String): String =
        a.opt(k).let { if (it == null || it == JSONObject.NULL) "" else it.toString().trim() }

    /** 정수 인자 — 숫자·숫자 글자 둘 다 받는다. 없으면 def, 형식이 틀리면 거절 */
    private fun int(a: JSONObject, k: String, def: Int, min: Int, max: Int): Int {
        val v = a.opt(k)
        if (v == null || v == JSONObject.NULL || (v is String && v.isBlank())) return def
        val n = (if (v is Number) v.toDouble() else if (v is String) v.trim().toDoubleOrNull() else null) ?: throw IllegalArgumentException("$k 는 정수여야 한다(받은 값: ${v.toString().take(20)})")
        require(n == Math.floor(n)) { "$k 는 정수여야 한다" }
        return n.toInt().coerceIn(min, max)
    }

    /** 참/거짓 인자 — true/false 와 "true"/"false" 만 */
    private fun bool(a: JSONObject, k: String): Boolean {
        val v: Any? = a.opt(k)
        if (v == null || v == JSONObject.NULL) return false
        if (v is Boolean) return v
        return when (if (v is String) v.trim().lowercase() else "?") {
            "", "false" -> false
            "true" -> true
            else -> throw IllegalArgumentException("$k 는 true 또는 false")
        }
    }

    /** 객체 인자 — 모델이 JSON 을 글자로 주는 일이 잦아 글자면 한 번 풀어 본다 */
    private fun obj(a: JSONObject, k: String): JSONObject? {
        val v: Any? = a.opt(k)
        if (v == null || v == JSONObject.NULL) return null
        if (v is JSONObject) return v
        if (v is String && v.isBlank()) return null
        return (if (v is String) runCatching { JSONObject(v) }.getOrNull() else null)
            ?: throw IllegalArgumentException("$k 는 객체({…})여야 한다")
    }

    private fun need(a: JSONObject, k: String): String =
        str(a, k).also { require(it.isNotEmpty()) { "$k 가 비었다" } }

    private fun fromJob(a: JSONObject, o: JSONObject) {
        str(a, "from_job").takeIf { it.isNotEmpty() }?.let {
            require(JOB_ID_RE.matches(it)) { "from_job 형식이 틀렸다 — 앞선 결과의 「작업 id」를 그대로" }
            o.put("from_job", it)
        }
    }

    /**
     * inputs — 폰에서 올린 파일 id 배열(계약 INPUTS §3). 모델이 글자 하나·JSON 글자로 주는 일도 받는다. 같은 id 는 한 번만.
     * 비면 키를 넣지 않는다(inputs 없는 요청의 confirmToken 이 예전과 같게). 있는 id 인지는 서버가 본다(없으면 bad_args).
     */
    private fun inputs(a: JSONObject, o: JSONObject) {
        val raw = a.opt("inputs")
        val arr: JSONArray = when {
            raw == null || raw == JSONObject.NULL -> return
            raw is JSONArray -> raw
            raw is String && raw.isBlank() -> return
            raw is String && raw.trim().startsWith("[") -> runCatching { JSONArray(raw.trim()) }.getOrNull()
                ?: throw IllegalArgumentException("inputs 는 파일 id 배열이어야 한다(예: [\"up_abc123def456\"])")
            raw is String -> JSONArray().put(raw.trim())
            else -> throw IllegalArgumentException("inputs 는 파일 id 배열이어야 한다(예: [\"up_abc123def456\"])")
        }
        val ids = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            if (v !is String) throw IllegalArgumentException("inputs[$i] 가 문자열이 아니다 — [받은 파일] 의 파일 id 를 그대로")
            val id = v.trim()
            require(FILE_ID_RE.matches(id)) { "inputs[$i] 「${id.take(40)}」 는 파일 id 가 아니다 — 폰 경로·파일 이름이 아니라 [받은 파일] 의 「파일 id」(up_…)를 그대로" }
            ids += id
        }
        if (ids.isEmpty()) return
        require(ids.size <= MAX_INPUTS) { "inputs 는 ${MAX_INPUTS}개까지" }
        o.put("inputs", JSONArray(ids.toList()))
    }

    /** run 의 argv — 문자열 배열 필수. 셸 명령 한 줄·셸 연산자는 거절하고 고칠 방법을 알려 준다 */
    private fun argv(a: JSONObject): JSONArray {
        val raw = a.opt("argv")
        if (raw is String) {
            val s = raw.trim()
            // 모델이 배열을 JSON 글자로 준 경우만 풀어 준다. 명령 한 줄은 공백으로 쪼개 주지 않는다(따옴표 해석 = 셸 흉내 = 보여 준 것과 달라질 수 있다)
            val parsed = if (s.startsWith("[")) runCatching { JSONArray(s) }.getOrNull() else null
            if (parsed != null) return argvArray(parsed)
            if (SHELL_IN_STRING.containsMatchIn(" $s ")) throw IllegalArgumentException(
                "argv 는 문자열 배열이어야 하고 셸이 없다 — 파이프·&&·;·리다이렉트(>)는 못 쓴다. 명령을 하나씩 나눠 run 을 여러 번 부르고, " +
                    "파일은 명령 자체의 출력 옵션으로 작업 폴더에 쓰게 할 것")
            throw IllegalArgumentException(
                "argv 는 문자열 배열이어야 한다 — 한 줄 명령이 아니라 [\"python3\", \"scripts/x.py\", \"인자\"] 처럼 인자마다 하나씩(따옴표로 묶던 인자는 원소 하나로)")
        }
        if (raw !is JSONArray) throw IllegalArgumentException("argv 는 문자열 배열이어야 한다(예: [\"python3\", \"scripts/search.py\", \"연차\"])")
        return argvArray(raw)
    }

    private fun argvArray(arr: JSONArray): JSONArray {
        require(arr.length() > 0) { "argv 가 비었다 — 첫 원소는 실행할 인터프리터·명령(예: python3)" }
        require(arr.length() <= MAX_ARGV) { "argv 원소가 너무 많다(${arr.length()}개, 최대 $MAX_ARGV)" }
        val out = JSONArray()
        var total = 0
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            if (v !is String) throw IllegalArgumentException(
                "argv[$i] 가 문자열이 아니다(${if (v == null || v == JSONObject.NULL) "null" else v.javaClass.simpleName}) — 숫자도 \"3\" 처럼 글자로")
            if (v.trim() in SHELL_OPS) throw IllegalArgumentException(
                "argv[$i] 「${v.trim()}」 는 셸 연산자다 — run 은 셸이 아니라 파이프·&&·;·리다이렉트를 못 쓴다. 명령을 나눠 run 을 따로 부르고, " +
                    "파일은 명령의 출력 옵션으로 작업 폴더에 쓰게 할 것")
            if ('\u0000' in v) throw IllegalArgumentException("argv[$i] 에 NUL 글자가 있다")
            total += v.length
            out.put(v)
        }
        require(total <= MAX_ARGV_CHARS) { "argv 가 너무 길다(${total}자, 최대 $MAX_ARGV_CHARS) — 긴 글은 stdin 으로" }
        val a0 = out.getString(0)
        if (a0.isBlank() || a0.any { it.isWhitespace() }) throw IllegalArgumentException(
            "argv[0] 「${a0.take(40)}」 는 명령 이름 하나여야 한다 — 공백으로 이어 쓴 명령은 원소마다 나눌 것(예: [\"python3\", \"scripts/x.py\"])")
        return out
    }

    /**
     * 도구 인자 → (op, 서버로 보낼 args). 인자가 틀리면 IllegalArgumentException(메시지 = 모델이 고칠 수 있게).
     * 계약 §1-2 인자만 골라 담는다(모르는 키는 버린다) — 이 결과가 「같은 요청」의 기준이다(need_confirm 뒤 재전송은 이 args 를 그대로 다시 보낸다).
     * 같은 인자면 늘 같은 JSON 이 나오게 기본값을 채워 넣는다.
     */
    fun op(name: String, args: JSONObject): Pair<String, JSONObject> {
        require(name in NAMES) { "모르는 도구 $name" }
        val o = JSONObject()
        when (name) {
            "skill_search" -> o.put("query", need(args, "query").take(200)).put("n", int(args, "n", 8, 1, 20))
            "skill_view" -> {
                o.put("name", need(args, "name"))
                val path = str(args, "path").ifEmpty { "SKILL.md" }
                require(!path.startsWith("/") && !path.startsWith("~") && path.split('/', '\\').none { it == ".." }) {
                    "path 는 스킬 폴더 안 상대 경로만(예: references/usage.md) — /·~·.. 는 못 쓴다"
                }
                o.put("path", path).put("offset", int(args, "offset", 0, 0, Int.MAX_VALUE))
            }
            "run" -> {
                o.put("skill", need(args, "skill")).put("argv", argv(args)).put("background", bool(args, "background"))
                val stdin = args.opt("stdin").let { if (it == null || it == JSONObject.NULL) "" else it.toString() }
                if (stdin.isNotEmpty()) {
                    val bytes = stdin.toByteArray(Charsets.UTF_8).size
                    require(bytes <= MAX_STDIN_BYTES) { "stdin 이 너무 길다(${bytes / 1024}KB, 최대 20KB)" }
                    o.put("stdin", stdin)
                }
                fromJob(args, o)
                inputs(args, o)
            }
            "code_run" -> {
                val code = args.opt("code").let { if (it == null || it == JSONObject.NULL) "" else it.toString() }
                require(code.isNotBlank()) { "code 가 비었다" }
                require(code.length <= MAX_CODE_CHARS) { "code 가 너무 길다(${code.length}자, 최대 $MAX_CODE_CHARS)" }
                o.put("code", code)
                val files = obj(args, "files") ?: JSONObject()
                require(files.length() <= MAX_FILES) { "files 는 ${MAX_FILES}개까지" }
                val clean = JSONObject()
                var total = 0
                files.keys().asSequence().sorted().forEach { fname ->
                    require(fname.isNotBlank() && '/' !in fname && '\\' !in fname && fname != "." && fname != ".." && !fname.startsWith(".")) {
                        "files 의 이름 「${fname.take(40)}」 은 폴더 없는 파일 이름만(예: data.csv)"
                    }
                    val v = files.opt(fname)
                    if (v !is String) throw IllegalArgumentException("files[$fname] 는 글(문자열)이어야 한다 — 표는 csv 글로")
                    val b = v.toByteArray(Charsets.UTF_8).size
                    require(b <= MAX_FILE_BYTES) { "files[$fname] 가 너무 크다(${b / 1024}KB, 파일 하나 최대 20KB)" }
                    total += b
                    clean.put(fname, v)
                }
                require(total <= 2 * MAX_FILE_BYTES) { "files 합계가 너무 크다(${total / 1024}KB, 최대 40KB)" }
                o.put("files", clean)
                fromJob(args, o)
                inputs(args, o)
            }
            "mcp_tools" -> str(args, "server").takeIf { it.isNotEmpty() }?.let { o.put("server", it) }
            "mcp_call" -> o.put("server", need(args, "server")).put("tool", need(args, "tool"))
                .put("args", obj(args, "args") ?: JSONObject())
            // then 은 폰만 쓴다(서버로 안 보낸다 — Agent 가 모델 원 인자에서 읽어 JobStore 에 기억)
            "claude_task" -> {
                val task = args.opt("task").let { if (it == null || it == JSONObject.NULL) "" else it.toString().trim() }
                require(task.isNotEmpty()) { "task 가 비었다 — 맡길 일을 적을 것" }
                require(task.length <= MAX_TASK_CHARS) { "task 가 너무 길다(${task.length}자, 최대 $MAX_TASK_CHARS)" }
                o.put("task", task)
                str(args, "title").replace(Regex("\\s+"), " ").take(MAX_TITLE_CHARS).takeIf { it.isNotEmpty() }?.let { o.put("title", it) }
                inputs(args, o)
            }
            "job_status" -> str(args, "jobId").takeIf { it.isNotEmpty() }?.let {
                require(JOB_ID_RE.matches(it)) { "jobId 형식이 틀렸다 — 「맡김 — 작업 <id>」의 id 를 그대로" }
                o.put("jobId", it)
            }
            "job_cancel" -> need(args, "jobId").let {
                require(JOB_ID_RE.matches(it)) { "jobId 형식이 틀렸다 — 「맡김 — 작업 <id>」의 id 를 그대로" }
                o.put("jobId", it)
            }
        }
        return name to o
    }

    /** 거부 키의 대상 — run = 스킬, mcp_call = 서버/도구. Agent 가 「도구명:대상:confirmToken 앞 16자」로 쓴다(계약 §1-2) */
    fun gateTarget(name: String, args: JSONObject): String = when (name) {
        "run" -> args.optString("skill")
        "mcp_call" -> args.optString("server") + "/" + args.optString("tool")
        else -> name
    }

    /**
     * need_confirm 의 data.confirm `{text, class, cost?}` → 확인 창 글. 서버 문구(text)를 **그대로** 싣는다(보여 준 것 = 실행하는 것).
     * 폰은 머리 한 줄(무엇을 묻는지)·유료 비용 줄만 앞에 붙이고 서버 문구를 줄이거나 바꾸지 않는다.
     * text 가 비면 빈 문자열 — Agent 는 그때 묻지도 실행하지도 않는다.
     */
    fun confirmText(name: String, args: JSONObject, confirm: JSONObject): String {
        val text = confirm.optString("text").trim()
        if (text.isEmpty() || text == "null") return ""
        val paid = confirm.optString("class") == "paid"
        val head = when (name) {
            "run" -> "🖥 홈맥에서 실행할까요? · 스킬 ${gateTarget(name, args)}" + if (args.optBoolean("background")) " (뒤에서)" else ""
            "mcp_call" -> "🔌 홈맥 MCP 도구를 부를까요? · ${gateTarget(name, args)}"
            "code_run" -> "🧮 홈맥 격리 상자에서 코드를 실행할까요?"
            "job_cancel" -> "⏹ 홈맥 작업을 취소할까요? · ${args.optString("jobId")}"
            "claude_task" -> "🤖 홈맥 Claude Code 에 맡길까요?"
            else -> "🖥 홈맥에서 실행할까요? · $name"
        }
        return buildString {
            append(head).append('\n')
            // 서버 문구에 비용 줄이 이미 있으면 머리에 다시 쓰지 않는다 — 두 번 떠서 확인 창이 길어졌다(2026-09-27 실기 T7)
            if (paid && !text.contains("유료 API")) append("💳 유료 API · 예상 비용: ")
                .append(confirm.optString("cost").trim().takeIf { it.isNotEmpty() && it != "null" } ?: "(서버가 알리지 않음)").append('\n')
            append(text)
        }
    }

    // ── 결과 글(계약 §1-2 결과 열) ──────────────────────────────────────

    private const val MAX_OUT = 14_000          // skill_view 본문 12,000자 + 규칙 요약이 들어가게
    private const val MAX_STDOUT = 8_000
    private const val MAX_STDERR = 2_000

    /** 서버 키 이름이 계약 요약과 types.ts 사이에 조금 다르다(exit↔exitCode, name↔server, policy↔rules) — 둘 다 읽는다 */
    private fun s(o: JSONObject?, vararg keys: String): String {
        if (o == null) return ""
        for (k in keys) {
            val v = o.opt(k)
            if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray) v.toString().trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    private fun arr(o: JSONObject?, vararg keys: String): List<JSONObject> {
        if (o == null) return emptyList()
        for (k in keys) o.optJSONArray(k)?.let { a -> return (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
        return emptyList()
    }

    private fun exitOf(d: JSONObject): Int? = listOf("exit", "exitCode", "code").firstNotNullOfOrNull { k ->
        d.opt(k).let { v -> if (v is Number) v.toInt() else (v as? String)?.trim()?.toIntOrNull() }
    }

    /** stdout·stderr 한 칸 — 비면 생략. 서버가 이미 잘랐으면(truncated) 표시 */
    private fun StringBuilder.stream(label: String, text: String, max: Int, cut: Boolean) {
        val t = text.trimEnd()
        if (t.isEmpty()) return
        append('\n').append(label).append(":\n")
        if (t.length > max) append(t.take(max)).append("\n…(${max}자에서 자름)")
        else { append(t); if (cut) append("\n…(홈맥이 잘랐다)") }
    }

    /** 서버 첨부 이름 목록 한 줄 — 이름은 서버가 준 것만(지어내지 않는다) */
    private fun StringBuilder.files(d: JSONObject) {
        val a = arr(d, "attachments")
        if (a.isEmpty()) return
        append("\n만든 파일 ${a.size}개: ").append(a.take(10).joinToString(", ") { s(it, "name", "fileId").take(60) })
        if (a.size > 10) append(" 외 ${a.size - 10}개")
    }

    private fun cut(d: JSONObject, k: String) = d.optJSONObject("truncated")?.optBoolean(k, false) == true

    private fun jobState(st: String) = when (st) {
        "queued" -> "대기"; "running" -> "실행 중"; "waiting" -> "확인 대기"; "done" -> "끝남"; "failed" -> "실패"; "cancelled" -> "취소됨"
        else -> st.ifEmpty { "?" }
    }

    private fun hhmm(ms: Long): String =
        if (ms <= 0) "" else java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.KOREA)
            .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul") }.format(java.util.Date(ms))

    /** skill_view 끝에 붙는 실행 규칙 — 서버가 글(rules)로 주면 그대로, 객체(policy)로 주면 줄로 편다 */
    private fun policyText(d: JSONObject): String {
        s(d, "rules").takeIf { it.isNotEmpty() }?.let { return it }
        val p = d.optJSONObject("policy") ?: d.optJSONObject("rules") ?: return ""
        return buildString {
            val keys = p.keys().asSequence().toList()
            for (k in keys) {
                val label = when (k) {
                    "interpreters" -> "인터프리터"; "bins" -> "실행 파일"; "auto" -> "자동 실행"; "confirm" -> "확인 후 실행"
                    "paid" -> "유료(비용 확인)"; "deny" -> "차단"; "default" -> "그 밖의 명령"; "long" -> "오래 걸림(뒤에서)"
                    "runtime" -> "실행 위치"; "env" -> continue   // 키 이름도 모델에게 줄 필요 없다
                    else -> k
                }
                val v: Any? = p.opt(k)
                val text = if (v is JSONArray) (0 until v.length()).joinToString(" · ") { i ->
                    v.opt(i).let { e -> if (e is JSONObject) listOf(s(e, "class"), s(e, "match"), s(e, "cost")).filter { it.isNotEmpty() }.joinToString(" ") else e.toString() }
                } else if (v == null || v == JSONObject.NULL) "" else v.toString()
                if (text.isNotBlank()) append("- ").append(label).append(": ").append(text.take(600)).append('\n')
            }
        }.trimEnd()
    }

    /** 성공 data → 모델이 읽을 결과 글(계약 §1-2 결과 열). 실패로 봐야 하는 결과(종료코드≠0·MCP 오류·시간초과)는 「실패: 」로 시작한다 */
    fun format(name: String, data: JSONObject): String {
        val out = when (name) {
            "skill_search" -> {
                val hits = arr(data, "skills", "items")
                if (hits.isEmpty()) "맞는 스킬 없음 — 다른 말로 한 번 더 찾거나, 전용 도구로 하거나, 할 수 없다고 finish 로 알릴 것"
                else buildString {
                    append("스킬 ${hits.size}개:")
                    hits.forEach { h ->
                        append("\n- ").append(s(h, "name")).append(" — ").append(s(h, "summary", "description").lineSequence().firstOrNull().orEmpty().take(160))
                        val tags = listOfNotNull("[유료]".takeIf { h.optBoolean("paid") }, "[오래 걸림]".takeIf { h.optBoolean("long") })
                        if (tags.isNotEmpty()) append(' ').append(tags.joinToString("·"))
                    }
                    val total = data.optInt("total", -1)
                    if (total > hits.size) append("\n(허용된 스킬 전체 ${total}개 중)")
                    append("\n고른 스킬은 skill_view 로 문서를 먼저 읽을 것")
                }
            }
            "skill_view" -> buildString {
                val path = s(data, "path").ifEmpty { "SKILL.md" }
                append("📖 ").append(s(data, "name")).append('/').append(path)
                val off = data.optInt("offset", 0)
                val len = data.optInt("length", -1)
                if (off > 0 || len > 0) append(" (").append(off).append("자부터").append(if (len > 0) " / 전체 ${len}자" else "").append(')')
                append("\n\n").append(s(data, "text").ifEmpty { "(빈 문서)" }.take(12_000))
                val next = data.opt("nextOffset").let { if (it is Number) it.toInt() else (it as? String)?.toIntOrNull() }
                if (next != null && next > off) append("\n…(offset=$next 으로 이어 읽기)")
                val pol = policyText(data)
                append("\n\n── 이 스킬의 실행 규칙(홈맥 정책) ──\n")
                append(pol.ifEmpty { "(규칙 요약 없음 — 명령마다 홈맥이 판정한다)" })
                append("\nrun 의 argv 는 위 문서에 적힌 명령만. 스크립트 경로는 스킬 폴더 기준(예: scripts/x.py)")
            }
            "run", "code_run" -> {
                val jid = s(data, "jobId")
                if (name == "run" && jid.isNotEmpty() && (s(data, "mode") == "job" || !data.has("stdout")))
                    "맡김 — 작업 $jid, 끝나면 알림" + (if (data.optBoolean("promoted")) " (90초를 넘겨 홈맥이 뒤로 돌렸다)" else "") +
                        "\n기다리거나 job_status 를 되풀이하지 말고 finish 로 사용자에게 「홈맥에 맡겼고 끝나면 알림이 온다」고 알릴 것"
                else buildString {
                    val exit = exitOf(data)
                    val timedOut = data.optBoolean("timedOut")
                    when {
                        timedOut -> append("실패: 시간 제한(${if (name == "code_run") "20초" else "홈맥 한도"})에 걸려 멈춤 — 더 가벼운 계산으로 나누거나 finish")
                        exit == null -> append("종료코드 ?(홈맥이 알리지 않음)")
                        exit != 0 -> append("실패: 종료코드 $exit — 명령이 오류로 끝났다. 아래 stderr 를 보고 인자를 고치거나(문서·--help 확인) finish")
                        else -> append("종료코드 0")
                    }
                    stream("stdout", JobSummary.tidy(s(data, "stdout")), MAX_STDOUT, cut(data, "stdout"))
                    stream("stderr", JobSummary.tidy(s(data, "stderr")), MAX_STDERR, cut(data, "stderr"))
                    if (s(data, "stdout").isEmpty() && s(data, "stderr").isEmpty()) append("\n(출력 없음)")
                    files(data)
                    s(data, "workId").takeIf { it.isNotEmpty() }?.let { append("\n작업 id ").append(it).append(" — 이 작업 폴더의 파일은 다음 code_run 에 from_job 으로 넘겨 /in 에서 읽을 수 있다") }
                }
            }
            "mcp_tools" -> {
                val servers = arr(data, "servers")
                if (servers.isEmpty()) "쓸 수 있는 MCP 서버 없음(허용 목록이 비었거나 그 이름의 서버가 없다)"
                else buildString {
                    servers.forEach { sv ->
                        if (isNotEmpty()) append("\n\n")
                        append("■ ").append(s(sv, "server", "name"))
                        s(sv, "summary", "description").takeIf { it.isNotEmpty() }?.let { append(" — ").append(it.take(120)) }
                        val tools = arr(sv, "tools")
                        if (tools.isEmpty()) append("\n  (도구 없음)")
                        tools.forEach { t ->
                            append("\n- ").append(s(t, "name"))
                            val sig = s(t, "args").ifEmpty { t.optJSONObject("inputSchema")?.optJSONObject("properties")?.keys()?.asSequence()?.joinToString(" · ").orEmpty() }
                            append('(').append(sig.take(200)).append(')')
                            when (s(t, "class")) { "confirm" -> " [확인]"; "paid" -> " [유료]"; "deny" -> " [차단]"; else -> "" }.let { append(it) }
                            s(t, "description").lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it.take(160)) }
                        }
                    }
                    append("\n\n[차단] 도구는 부르지 말 것. [확인]·[유료]는 사용자 확인 창이 뜬다")
                }
            }
            "mcp_call" -> buildString {
                val text = s(data, "text").ifEmpty { "(결과 글 없음)" }
                if (data.optBoolean("isError")) append("실패: MCP 도구가 오류를 돌려줌 — ")
                if (text.length > MAX_STDOUT) append(text.take(MAX_STDOUT)).append("\n…(${MAX_STDOUT}자에서 자름)") else append(text)
                files(data)
            }
            "job_status" -> {
                val jobs = arr(data, "jobs")
                if (jobs.isEmpty()) "맡긴 작업 없음"
                else buildString {
                    append("작업 ${jobs.size}개:")
                    jobs.forEach { j ->
                        append("\n- ").append(s(j, "jobId")).append(" [").append(jobState(s(j, "state"))).append("] ")
                        append(s(j, "title").take(60).ifEmpty { "(제목 없음)" })
                        hhmm(j.optLong("updatedAt", 0)).takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
                        s(j, "progress").takeIf { it.isNotEmpty() }?.let { append("\n  진행: ").append(it.take(200)) }
                        j.optJSONObject("ask")?.let { q -> append("\n  폰 확인을 기다림: ").append(s(q, "tool")).append(" — ").append(s(q, "text").replace(Regex("\\s+"), " ").take(80)) }
                        s(j, "result").takeIf { it.isNotEmpty() }?.let { append("\n  결과: ").append(it.take(1_500)) }
                        arr(j, "attachments").takeIf { it.isNotEmpty() }?.let { a ->
                            append("\n  파일 ${a.size}개: ").append(a.take(5).joinToString(", ") { s(it, "name").take(40) })
                        }
                    }
                    if (jobs.any { s(it, "state") in setOf("queued", "running", "waiting") })
                        append("\n(아직 도는 작업은 끝나면 알림이 온다 — 되풀이해서 확인하지 말 것)")
                }
            }
            "claude_task" -> s(data, "jobId").let { jid ->
                if (jid.isEmpty()) "결과 불명 — 홈맥이 작업 id 를 알리지 않았다. 다시 맡기지 말고 job_status 로 확인할 것"
                else "맡김 — 작업 $jid, 끝나면 알림\n홈맥 Claude Code 가 뒤에서 한다. 기다리거나 job_status 를 되풀이하지 말고 finish 로 「맡겼고 끝나면 알림이 온다(중간 확인은 폰에 뜬다)」고 알릴 것"
            }
            "job_cancel" -> {
                val jid = s(data, "jobId").ifEmpty { "?" }
                val st = s(data, "state")
                when {
                    data.optBoolean("cancelled") -> "✅ 작업 $jid 취소함"
                    st.isNotEmpty() -> "작업 $jid 은 이미 「${jobState(st)}」 — 취소할 것이 없었다"
                    else -> "작업 $jid 취소 결과 불명(홈맥이 상태를 알리지 않음) — job_status 로 확인할 수 있다"
                }
            }
            else -> data.toString().take(3_000)
        }
        return if (out.length <= MAX_OUT) out else out.take(MAX_OUT) + "\n…(${MAX_OUT}자에서 자름)"
    }

    // ── 첨부 받기(계약 §1-3) ─────────────────────────────────────────────

    private const val MAX_FILE_DOWNLOAD = 20L * 1024 * 1024     // 계약 §2: 첨부 파일 하나 20MB
    private const val MAX_TOTAL_DOWNLOAD = 60L * 1024 * 1024
    private const val MAX_ATTACH_PER_CALL = 10
    private const val DL_CONNECT_MS = 10_000
    private const val DL_READ_MS = 60_000

    /** 사람이 볼 이름에서 경로·위험 글자를 뺀다(폴더 탈출·숨김 파일 방지) */
    private fun safeName(n: String): String =
        n.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^0-9A-Za-z가-힣._ -]+"), "_")
            .trim().trimStart('.').take(80).ifEmpty { "file" }

    /**
     * 성공 data 의 서버 파일(`attachments[{fileId,name,mime,size}]`)을 `GET <base>/files/<fileId>`(Bearer)로 받아 저장하고
     * Agent.addAttachment 모양 `{kind, title, uri, mime, size}` 목록을 돌려준다(계약 §1-3).
     *   - 이미지(png·jpg·webp·gif) → 갤러리 Pictures/FoldAgent 에 `FoldAgent_cap_<시각>_<이름>`(Gallery.save) — share_images 가 이름으로 찾는다. uri = content uri, title = 그 갤러리 이름
     *   - 그 밖 → 앱 전용 첨부 폴더 Attachments.dir — uri = 그 폴더 안 파일 이름 `<시각>_<fileId8>__<이름>`(records/AttachProvider 가 열기·공유)
     * 한 호출 10개·파일 하나 20MB·합계 60MB. 하나가 실패해도 나머지는 받는다(실패는 로그에 fileId·예외 이름만).
     * 정지면 받던 것을 끊고 지금까지 받은 것만. 토큰은 헤더에만 싣고 로그·예외 메시지에 넣지 않는다.
     */
    fun attachments(svc: AgentService, base: String, token: String, name: String, data: JSONObject, cancelled: () -> Boolean): List<JSONObject> {
        if (name !in setOf("run", "code_run", "mcp_call")) return emptyList()
        val list = arr(data, "attachments")
        if (list.isEmpty()) return emptyList()
        val root = base.trim().trimEnd('/')
        val out = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        var total = 0L
        for (a in list) {
            if (out.size >= MAX_ATTACH_PER_CALL || cancelled()) break
            val fileId = s(a, "fileId")
            if (!FILE_ID_RE.matches(fileId) || !seen.add(fileId)) continue
            val declared = a.optLong("size", -1)
            if (declared > MAX_FILE_DOWNLOAD || (declared > 0 && total + declared > MAX_TOTAL_DOWNLOAD)) {
                android.util.Log.w(TAG, "cap attach: 너무 큼 $fileId ${declared}B"); continue
            }
            val title = safeName(s(a, "name").ifEmpty { fileId })
            val t0 = System.currentTimeMillis()
            val got = runCatching { download(svc, "$root/files/$fileId", token, title, s(a, "mime").lowercase(), fileId, MAX_TOTAL_DOWNLOAD - total, cancelled) }
                .onFailure { android.util.Log.w(TAG, "cap attach: $fileId 실패 ${it.javaClass.simpleName}") }
                .getOrNull() ?: continue
            android.util.Log.i(TAG, "cap attach: $fileId ${got.optLong("size")}B ${System.currentTimeMillis() - t0}ms")
            total += got.optLong("size", 0)
            out += got
        }
        return out
    }

    /** 파일 하나 받기 → 첨부 JSON. 실패면 예외(부른 쪽이 건너뛴다). 반쯤 받은 파일은 남기지 않는다 */
    private fun download(svc: AgentService, url: String, token: String, title: String, declaredMime: String, fileId: String,
                         budget: Long, cancelled: () -> Boolean): JSONObject {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        // 막힌 읽기는 cancelled() 를 못 보므로 감시 스레드가 끊는다(AssistantClient 와 같은 방식)
        val watcher = Thread {
            while (!done.get()) {
                if (runCatching { cancelled() }.getOrDefault(false)) { runCatching { conn.disconnect() }; break }
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; this.name = "cap-attach-watch" }
        watcher.start()
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = DL_CONNECT_MS
            conn.readTimeout = DL_READ_MS
            conn.useCaches = false
            conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            val mime = (conn.contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
                ?: declaredMime).ifEmpty { "application/octet-stream" }
            val limit = minOf(MAX_FILE_DOWNLOAD, budget)
            if (conn.contentLengthLong > limit) throw java.io.IOException("too large")
            val ext = Gallery.extOf(mime)
            val stamp = Gallery.stamp()
            return conn.inputStream.use { input ->
                // 이미지 title = 갤러리 이름 — 결과 글·다음 턴 맥락(「이미지 1장: 이름」)에 이 이름이 나가야 모델이 share_images 로 넘길 수 있다(image_gen 과 같은 규칙)
                if (ext != null) {
                    val display = "FoldAgent_cap_${stamp}_${title.substringBeforeLast('.').replace(' ', '_').take(30)}.$ext"
                    Gallery.save(svc, display, mime) { copyLimited(input, it, limit, cancelled) }
                        .let { (uri, size) -> JSONObject().put("kind", "image").put("title", display).put("uri", uri.toString()).put("mime", mime).put("size", size) }
                } else {
                    // 표시·열기·공유는 records/AttachProvider 가 Attachments.dir 에서 찾는다. 「__」 뒤가 공유 때 보일 이름
                    val dir = kr.joonlab.foldagent.records.Attachments.dir(svc).apply { mkdirs() }
                    val f = java.io.File(dir, "${stamp}_${fileId.take(8)}__$title")
                    val tmp = java.io.File(dir, ".${f.name}.part")
                    val size = try {
                        tmp.outputStream().use { copyLimited(input, it, limit, cancelled) }
                    } catch (e: Exception) { tmp.delete(); throw e }
                    if (!tmp.renameTo(f)) { tmp.delete(); throw java.io.IOException("rename") }
                    JSONObject().put("kind", "file").put("title", title).put("uri", f.name).put("mime", mime).put("size", size)
                }
            }
        } finally {
            done.set(true)
            watcher.interrupt()
            runCatching { conn.disconnect() }
        }
    }

    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, limit: Long, cancelled: () -> Boolean): Long {
        val buf = ByteArray(64 * 1024)
        var n = 0L
        while (true) {
            if (cancelled()) throw java.io.InterruptedIOException("cancelled")
            val r = input.read(buf)
            if (r < 0) break
            n += r
            if (n > limit) throw java.io.IOException("too large")
            output.write(buf, 0, r)
        }
        if (n == 0L) throw java.io.IOException("empty")
        return n
    }

    private const val TAG = "FoldAgent"

    // ── 기록·상태 ──────────────────────────────────────────────────────

    /** 레시피·기록용 한 줄 */
    fun summarize(name: String, args: JSONObject, outcome: String): String {
        val argv = args.optJSONArray("argv")?.let { a -> (0 until a.length()).joinToString(" ") { a.optString(it) } }
            ?: args.optString("argv")
        val head = when (name) {
            "skill_search" -> "「${args.optString("query").take(40)}」"
            "skill_view" -> args.optString("name") + args.optString("path").let { if (it.isBlank() || it == "SKILL.md") "" else "/$it" }
            "run" -> args.optString("skill") + " $ " + argv.take(50) + if (args.optBoolean("background")) " (뒤에서)" else ""
            "code_run" -> "python ${args.optString("code").lines().size}줄"
            "mcp_tools" -> args.optString("server").ifBlank { "전체" }
            "mcp_call" -> args.optString("server") + "/" + args.optString("tool")
            "job_status" -> args.optString("jobId").ifBlank { "최근" }
            "job_cancel" -> args.optString("jobId")
            "claude_task" -> "🤖 " + args.optString("title").ifBlank { args.optString("task") }.replace(Regex("\\s+"), " ").take(50)
            else -> args.toString().take(40)
        }
        val tail = when {
            outcome.startsWith("맡김") -> " → 맡김"
            outcome.startsWith("사용자가 멈춤") -> " ⏹"
            outcome.startsWith("실패") && "거부" in outcome.lineSequence().first() -> " ✗거부"
            outcome.startsWith("실패") && "차단" in outcome.lineSequence().first() -> " ✗차단"
            outcome.startsWith("실패") -> " ✗"
            else -> ""
        }
        return head + tail
    }

    /** 실행 중 상태 문구 */
    fun status(name: String): String = when (name) {
        "skill_search" -> "🧰 스킬 찾는 중…"
        "skill_view" -> "📖 스킬 문서 읽는 중…"
        "run" -> "🖥 홈맥에서 실행 중…"
        "code_run" -> "🧮 코드 실행 중…(20초 이내)"
        "mcp_tools" -> "🔌 MCP 도구 확인 중…"
        "mcp_call" -> "🔌 MCP 호출 중…"
        "job_status" -> "📋 맡긴 작업 확인 중…"
        "job_cancel" -> "⏹ 작업 취소 중…"
        "claude_task" -> "🤖 Claude Code 에 맡기는 중…"
        else -> "⏳ $name…"
    }
}
