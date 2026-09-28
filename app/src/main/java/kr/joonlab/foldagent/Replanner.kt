package kr.joonlab.foldagent

import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 막히면 재계획 — 매 턴 도구 하나씩 누르다 헛돌 때, 행동 대신 한 번 물러나 「본 것 요약 → 가설 → 다음 전략」을 받는다.
 * 메인 대화와 따로, 도구 없이 JSON 만 받는다(설계 replan.md 변형 A, 2026-09-27). 결과는 user 메시지로 넣어 메인 모델이 따른다 —
 * finish 는 메인 모델이 하게 두어 조건 체크·확인 게이트를 그대로 거친다.
 *
 * 왜: run 232447-007 이 같은 두 화면을 16번 오갔다. 막힌 4개 실행을 재생하니 모델은 다 「다른 경로」를 냈다 — 모자랐던 건
 * 모델이 아니라 한 발 물러서는 시점이었다.
 *
 * 트리거(cases.md 조합 C′ — 헤맴 19/25 탐지, 깨끗한 ok 188건 오탐 0): 반복 감시 경고 · 무진척 ≥4 · 화면 변화 없음 ≥3 연속 ·
 * 도구 실패 ≥2 연속 · 같은 화면이 최근 10단계 중 4회 · 15단계째까지 재계획이 없었으면 점검 1회. 쿨다운 4단계, 한 실행 최대 2회.
 */
class Replanner(private val svc: AgentService, private val trace: TraceLog, private val seen: SeenScreens) {

    companion object {
        private const val TAG = "FoldAgent"
        const val MAX = 2
        private const val COOLDOWN = 4
        private const val NO_PROGRESS = 4
        private const val UNCHANGED = 3
        private const val FAILS = 2
        private const val SAME_SCREEN = 4
        private const val SAME_SCREEN_WINDOW = 10
        private const val CHECKPOINT_STEP = 15
        private const val TIMEOUT_MS = 30_000
        private const val SCREEN_MARK = "[현재 화면]"

        /** 무진척에서 빼는 도구 — 화면을 조작하지 않는다(보기·메모·기기 제어). 홈 비서 도구도 화면을 안 읽어 늘 「새 화면 없음」으로
         *  세졌다 — mail_list → mail_read×3 만으로 4단계째에 재계획이 걸렸다(리뷰 runtime#1) */
        private val NEUTRAL = setOf("note", "look", "capture", "list_apps", "clipboard", "wait", "geocode", "trash_captures",
            "media", "volume", "flashlight", "brightness", "sound_mode", "quick_toggle", "set_alarm", "set_timer", "finish", "choose_way", "save_to_downloads") + Agent.PRIVATE_READ_TOOLS + AssistantTools.NAMES +
            OaiTools.NAMES + CapTools.NAMES   // 웹·그림·스킬·실행·MCP 도 화면을 안 읽는다(계약 cc-capabilities §0-6)
        /** 성공하면 진척으로 치는 도구(무진척·같은 화면 창을 비운다) */
        private val PROGRESS_TOOLS = setOf("note", "capture", "write_note")

        const val DEFAULT_ADVICE = "같은 방법을 멈추고 다른 경로·전용 도구를 쓰거나, 없으면 finish(success=false)로 무엇을 확인했고 왜 못 하는지 정직하게 말할 것."

        /** replan.md 의 시스템 프롬프트 전문 + 실험 뒤 권고 두 줄(이미 실패한 동작·이전 재계획) */
        private val SYSTEM = """
            너는 안드로이드 폰 조작 에이전트의 「재계획자」다. 실행자는 매 턴 도구 하나씩 누르다가 막혔다. 너는 도구를 누르지 않는다 — 한 발 물러나 지금까지의 기록을 읽고, 무엇을 알게 됐는지 정리하고, 가설을 세워, 실행자가 따를 다음 전략 하나를 정한다.
            개발자가 로그를 보고 추론하듯 한다:
            1. 관찰: 어느 화면에서 무엇을 확인했고, 무엇이 「없었는지」를 사실로 적는다. 이미 본 화면·이미 해 본 동작을 빠짐없이 센다.
            2. 막힌 이유: 같은 동작·같은 화면을 반복하는 이유를 한 줄로.
            3. 가설 2~3개: 서로 다른 설명(예: 항목이 아직 안 본 곳에 있다 / 이 기기엔 그 항목이 없다 / 동작은 됐는데 화면 목록이 늦게 갱신된다 / 누른 요소가 틀렸다). 각 가설마다 그것을 「한두 동작으로」 확인하거나 반증할 방법(도구 이름과 인자)을 적는다. 이미 해 본 확인은 다시 제안하지 않는다.
            4. 다음 전략 하나: 같은 경로를 되풀이하지 말고 다른 경로·다른 도구(전용 도구·설정 검색·딥링크·look 으로 눈으로 확인 등)를 쓴다. 전용 도구가 있으면 화면 조작보다 우선한다. 첫 행동은 도구 목록에 있는 것만.
            5. 포기 판단: 남은 방법이 없거나, 확인 결과 기기에 그 기능이 없거나, 되돌릴 수 없는 동작·비밀번호가 필요하면 give_up=true 로 하고 사용자에게 할 말(무엇을 확인했고 왜 못 하는지, 대안)을 적는다. 거짓 성공은 금지다.
            규칙: 되돌릴 수 없는 동작(발송·결제·삭제)을 새로 제안하지 않는다(요청에 이미 있던 것은 예외이되 실행자가 확인 게이트를 거친다). 요청 범위를 넓히지 않는다.
            입력의 「이미 실패한 동작」은 첫 행동으로도, 가설 확인 방법으로도 다시 제안하지 않는다.
            입력에 「이전 재계획」이 있으면 그와 같은 전략을 다시 내지 않는다 — 그 뒤 결과를 보고 다른 길을 고르거나, 남은 길이 없으면 give_up.
            반드시 JSON 객체 하나로만 답한다(설명 문장·코드펜스 금지):
            {"observed":"2~4문장","stuck_reason":"한 줄","hypotheses":[{"claim":"…","check":"확인 방법(도구+인자)","likelihood":"high|mid|low"}],"strategy":{"summary":"한 줄","first_action":{"tool":"도구 이름","args":{}},"avoid":["다시 하지 말 것"]},"give_up":false,"user_message":""}
        """.trimIndent()

        /** 도구 목록을 TOOLS 에서 그대로 — 이름·설명·인자 설명 전부(자르면 인자를 지어냈다: 설계 실험 1차 vs 2차). 나중에 늘어난 도구도 자동으로 들어간다 */
        fun describeTools(tools: JSONArray): String = (0 until tools.length()).joinToString("\n") { i ->
            val f = tools.getJSONObject(i).getJSONObject("function")
            val params = f.optJSONObject("parameters")
            val req = params?.optJSONArray("required")?.let { r -> (0 until r.length()).map { r.optString(it) }.toSet() } ?: emptySet()
            val props = params?.optJSONObject("properties")
            val args = props?.keys()?.asSequence()?.map { k -> describeParam(k, props.getJSONObject(k), k in req) }?.toList().orEmpty()
            "- ${f.getString("name")}: ${f.optString("description")}" + if (args.isEmpty()) "" else " [인자 ${args.joinToString("; ")}]"
        }

        private fun describeParam(name: String, p: JSONObject, required: Boolean): String {
            val sb = StringBuilder(name).append('(').append(p.optString("type"))
            if (required) sb.append(", 필수")
            sb.append(')')
            p.optJSONArray("enum")?.let { e -> sb.append(" = ").append((0 until e.length()).joinToString("|") { e.optString(it) }) }
            p.optString("description").takeIf { it.isNotBlank() }?.let { sb.append(": ").append(it) }
            p.optJSONObject("items")?.optJSONObject("properties")?.let { ip ->
                sb.append(" {항목: ").append(ip.keys().asSequence().joinToString(", ") { k -> "$k: ${ip.getJSONObject(k).optString("description")}" }).append('}')
            }
            return sb.toString()
        }

        /** 동작 비교용 키 — 인자 순서·숫자/문자 표기 차이를 없애고 irreversible 은 뺀다(표시용이면 keep=true 로 남긴다) */
        fun keyOf(tool: String, args: JSONObject?, keepIrreversible: Boolean = false): String {
            val a = args ?: JSONObject()
            val parts = a.keys().asSequence().filter { keepIrreversible || it != "irreversible" }.sorted().map { "$it=${a.opt(it)}" }.toList()
            return "$tool(${parts.joinToString(",")})"
        }

        /** 재계획 응답으로 쓸 만한가 — 바깥 객체가 잘리면 firstJsonObject 가 안쪽 가설·first_action 객체를 돌려준다(리뷰) */
        private fun usable(j: JSONObject?) = j != null && (j.has("strategy") || j.has("give_up"))

        private fun clean(s: String) = s.replace(SCREEN_MARK, "현재 화면").replace("[앱 요령", "(앱 요령").trim()
    }

    /** 한 단계의 행동 기록(재계획 입력용) */
    private class Act(val step: Int, val tool: String, val key: String, val fromSig: String?, val argsText: String, val first: String,
                      val changed: Boolean?, val unchangedWarn: Boolean, val screen: String, val failed: Boolean, val revisit: Boolean)

    /** 이미 실패한 동작 — 같은 동작이라도 「어느 화면에서」가 다르면 다른 동작이다(첫 쪽에서의 scroll down 과 둘째 쪽에서의 scroll down) */
    private class Failed(val key: String, val fromSig: String?, val why: String)

    private class Past(val step: Int, val reason: String, val summary: String, val firstAction: String)

    private val acts = ArrayList<Act>()
    private val past = ArrayList<Past>()
    var used = 0; private set
    private var lastStep = -100
    private var noProgress = 0
    private var unchangedStreak = 0
    private var windowFrom = 1          // 「같은 화면 10단계 중 4회」를 세기 시작하는 단계(진척·재계획 때 옮긴다)
    private var checked = false
    private var lastSig: String? = null
    private var lastTextKey: String? = null   // 마지막 결과 화면의 전문 해시 — 「같은 화면」 창은 서명이 아니라 이것으로 센다
    /** 재계획이 give_up 으로 낸 사용자 안내 — 결국 멈추게 되면 이 말로 끝낸다 */
    var giveUpMessage = ""; private set

    val canReplan get() = used < MAX

    /**
     * 도구 하나를 실행한 뒤 부른다.
     * @param changed 화면을 다시 읽은 도구만 true/false(읽지 않았으면 null)
     * @param unchangedWarn 「⚠️ 화면 변화 없음」을 붙였나(입력 확인됨·capture 등 정상 무변화는 false)
     */
    fun observe(step: Int, tool: String, args: JSONObject, outcome: String, changed: Boolean?, visit: SeenScreens.Visit?,
                unchangedWarn: Boolean, failed: Boolean) {
        val fromSig = lastSig
        if (visit != null) { lastSig = visit.sig; lastTextKey = visit.textKey }
        val screen = when {
            changed == null -> "(화면 안 읽음)"
            visit == null -> "(같은 화면)"
            visit.revisit -> "⟲ ${SeenScreens.stepsText(visit.prevSteps)}단계와 같은 화면(다시 봄)"
            else -> seen.describe(visit.sig)
        }
        val a = JSONObject(args.toString()).apply { if (!optBoolean("irreversible", false)) remove("irreversible") }
        acts += Act(step, tool, keyOf(tool, args), fromSig, a.toString().take(140), clean(outcome.lineSequence().firstOrNull().orEmpty()).take(120),
            changed, unchangedWarn, screen, failed, visit?.revisit == true)

        // 새 화면 = 전문(숫자→#)이 처음(cases.md C′). 서명으로 세면 스위치 여러 개 켜기·사진 여러 장 고르기가 무진척이 됐다
        if (tool !in NEUTRAL) noProgress = if (visit?.fresh == true) 0 else noProgress + 1
        if (tool in PROGRESS_TOOLS && !failed && !(tool == "write_note" && outcome.startsWith("노트 쓰기 중단"))) {
            noProgress = 0; windowFrom = step + 1
        }
        if (changed != null) unchangedStreak = if (unchangedWarn) unchangedStreak + 1 else if (changed) 0 else unchangedStreak
    }

    /**
     * 이번 단계 뒤에 재계획할 이유(쿨다운 적용). 없으면 null. guard = 반복 감시 경고 문구.
     * 재계획을 다 쓴 뒤에도 이유를 돌려준다 — 부르는 쪽이 canReplan 을 보고 세 번째 트리거면 stuck 으로 멈춘다(설계 replan.md 횟수 상한).
     * 무진척·같은 화면은 옛 감시에 없는 신호라, 이걸 안 하면 재계획 2회 뒤 HARD_CAP 까지 돌았다(리뷰)
     */
    fun trigger(step: Int, guard: String?, failStreak: Int): String? {
        if (step - lastStep < COOLDOWN) return null
        val same = lastTextKey?.let { seen.textVisitsSince(it, maxOf(windowFrom, step - SAME_SCREEN_WINDOW + 1)) } ?: 0
        return when {
            guard != null -> "반복 감시: $guard"
            noProgress >= NO_PROGRESS -> "무진척: 화면을 조작한 도구가 ${noProgress}번 연속 새 화면을 못 만듦"
            unchangedStreak >= UNCHANGED -> "화면 변화 없음 ${unchangedStreak}번 연속"
            failStreak >= FAILS -> "도구 실패 ${failStreak}번 연속"
            same >= SAME_SCREEN -> "같은 화면을 최근 ${SAME_SCREEN_WINDOW}단계 중 ${same}번 봄(목록↔같은 상세 왕복 등)"
            step >= CHECKPOINT_STEP && used == 0 && !checked -> { checked = true; "점검: ${step}단계째 — 진척을 한 번 점검" }
            else -> null
        }
    }

    /**
     * 코드가 뽑은 「이미 실패한 동작」 — 같은 화면에서 한 같은 동작이 변화 없음 2회 이상 · 이미 본 화면으로 되돌아간 게 2회 이상 · 실패 2회 이상.
     * 시작 화면을 키에 넣는다: 007 에서 첫 쪽의 scroll down 은 헛돌았지만, 둘째 쪽에서 더 내려가는 scroll down 은 아직 안 해 본 정답 후보였다.
     */
    private fun failedActions(): List<Failed> = acts.groupBy { it.key to it.fromSig }.mapNotNull { (k, list) ->
        // 경고가 붙은 무변화만 — capture·wait·quick_toggle·입력 확인된 type_text 는 화면이 안 바뀌는 게 정상이다(리뷰 2026-09-27)
        val nc = list.count { it.unchangedWarn }
        val rv = list.count { it.revisit }
        val fl = list.count { it.failed }
        val why = listOfNotNull(if (nc >= 2) "변화 없음 ${nc}회" else null, if (rv >= 2) "이미 본 화면으로 ${rv}회" else null,
            if (fl >= 2) "실패 ${fl}회" else null)
        if (why.isEmpty()) null else Failed(k.first, k.second, why.joinToString(" · "))
    }

    private fun failedText(f: Failed) = "${f.key} — " + (f.fromSig?.let { "[${seen.describe(it)}] 에서 " } ?: "") + f.why

    private fun buildInput(reason: String, task: String, turns: JSONArray, memo: List<String>, pkg: String, screen: String): String {
        val prev = (maxOf(0, turns.length() - 3) until turns.length()).mapNotNull { turns.optJSONObject(it) }
            .joinToString("\n") { "- 「${it.optString("request").take(120)}」 → ${it.optString("status")}: ${it.optString("final").take(200)}" }
        val log = acts.joinToString("\n") { a ->
            "${a.step}. ${a.tool}(${a.argsText}) → ${a.first}" +
                (when (a.changed) { true -> " [변화]"; false -> " [변화 없음]"; null -> "" }) + " · 결과: ${a.screen}"
        }
        val failed = failedActions()
        val pastText = past.joinToString("\n") { p ->
            val after = acts.filter { it.step > p.step }
            val fresh = after.count { it.changed == true && !it.revisit && !it.screen.startsWith("(") }
            "- ${p.step}단계 (${p.reason}): ${p.summary} / 첫 행동 ${p.firstAction} → 그 뒤 ${after.size}단계, 새 화면 ${fresh}개" +
                (after.lastOrNull()?.let { ", 마지막: ${it.tool} → ${it.first.take(60)}" } ?: "")
        }
        val tips = svc.knowledge.appNotes(pkg)
        return buildString {
            append("요청: ").append(task).append('\n')
            if (prev.isNotEmpty()) append("이전 대화(같은 세션, 최근 것):\n").append(prev).append('\n')
            append("\n재계획을 부른 이유: ").append(reason).append('\n')
            append("\n실행자가 쓸 수 있는 도구:\n").append(describeTools(Agent.TOOLS)).append('\n')
            append("\n지금까지 한 일(단계. 도구(인자) → 결과 첫 줄 [화면 변화] · 결과 화면 한 줄 요약, ⟲ = 이미 본 화면):\n")
            append("0. (시작)\n").append(log).append('\n')
            append("\n이미 실패한 동작(다시 제안 금지):\n")
            append(if (failed.isEmpty()) "(없음)" else failed.joinToString("\n") { "- ${failedText(it)}" }).append('\n')
            if (pastText.isNotEmpty()) append("\n이전 재계획(같은 전략 금지):\n").append(pastText).append('\n')
            if (seen.nonEmpty) append('\n').append(seen.block()).append('\n')
            append("\n[메모]\n").append(if (memo.isEmpty()) "(없음)" else memo.joinToString("\n")).append('\n')
            if (!tips.isNullOrBlank()) append("\n[앱 요령: ").append(pkg).append("]\n").append(tips).append('\n')
            append('\n').append(SCREEN_MARK).append('\n').append(screen.trim())
        }
    }

    /**
     * 재계획 한 번 — 메인 모델에게 넣을 user 메시지를 돌려준다(SCREEN_MARK 없음 → 이전 화면 자르기에서 살아남아 기억이 된다).
     * 모델 = Prefs.replanModel, 30초 안에 안 오거나 JSON 이 아니면 Prefs.replanFallback(gpt-6-sol) 로 한 번 더, 그것도 안 되면 코드가 만든 기본 안내.
     */
    fun replan(step: Int, reason: String, task: String, turns: JSONArray, memo: List<String>, pkg: String, screen: String,
               isCancelled: () -> Boolean = { false }): String {
        used++; lastStep = step
        svc.status("🧭 다시 생각하는 중…")
        val input = buildInput(reason, task, turns, memo, pkg, screen)
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM))
            .put(JSONObject().put("role", "user").put("content", input))
        val llm = LlmClient(Prefs.base(svc), Prefs.model(svc), Prefs.apiKey(svc))
        val t0 = SystemClock.uptimeMillis()
        var reply: LlmClient.JsonReply? = null
        var model = ""
        var fallback: String? = null
        val errors = JSONArray()
        for ((k, m) in listOf(Prefs.replanModel(svc), Prefs.replanFallback(svc)).distinct().withIndex()) {
            // 멈춤을 눌렀으면 둘째 모델을 부르지 않는다 — 한 모델이 최대 45초라 두 번이면 1분 반 뒤에야 멈췄다(리뷰)
            if (k > 0 && isCancelled()) { errors.put("사용자 멈춤 — 대체 모델 생략"); break }
            if (k > 0) fallback = "model"
            val r = try { llm.chatJson(msgs, m, TIMEOUT_MS) } catch (e: Exception) { errors.put("$m: ${e.javaClass.simpleName} ${e.message?.take(120)}"); null }
            if (usable(r?.parsed)) { reply = r; model = m; break }
            if (r != null) errors.put("$m: 재계획 JSON 아님 ${r.raw.take(120)}")
        }
        val ms = SystemClock.uptimeMillis() - t0
        val j = reply?.parsed
        if (j == null) fallback = "default"

        // 첫 행동 검증: 도구 목록에 없거나 이미 실패한 동작이면 버린다(실험: gpt-5.6-sol 이 실패한 type_text 를 다시 냈다)
        val strategy = j?.optJSONObject("strategy")
        val fa = strategy?.optJSONObject("first_action")
        val faTool = fa?.optString("tool").orEmpty()
        val faArgs = fa?.optJSONObject("args") ?: JSONObject()
        val toolNames = (0 until Agent.TOOLS.length()).map { Agent.TOOLS.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
        // 지금 화면(lastSig)에서 이미 실패한 동작만 막는다
        val failedHere = failedActions().filter { it.fromSig == lastSig }.map { it.key }.toSet()
        val rejected = when {
            fa == null || faTool.isBlank() -> null
            faTool !in toolNames -> "없는 도구 $faTool"
            keyOf(faTool, faArgs) in failedHere -> "이미 실패한 동작 ${keyOf(faTool, faArgs)}"
            else -> null
        }
        if (rejected != null) trace.event("first_action_rejected", JSONObject().put("step", step).put("why", rejected).put("first_action", fa))
        // 실행자에게 보이는 문구는 irreversible 을 남긴다 — 빼면 라벨 휴리스틱에 안 걸리는 확정 버튼이 확인 없이 눌릴 수 있다(리뷰)
        val faText = if (fa != null && faTool.isNotBlank() && rejected == null) keyOf(faTool, faArgs, keepIrreversible = true) else ""

        trace.event("replan", JSONObject().put("step", step).put("k", used).put("reason", reason).put("model", model.ifEmpty { JSONObject.NULL })
            .put("ms", ms).put("usage", reply?.usage ?: JSONObject.NULL).put("parsed", j != null).put("rejected", rejected ?: JSONObject.NULL)
            .put("fallback", fallback ?: JSONObject.NULL).put("errors", errors).put("input_chars", input.length)
            .put("answer", j ?: JSONObject.NULL))
        Log.i(TAG, "replan $used/$MAX step $step model=$model ${ms}ms parsed=${j != null} rejected=$rejected")

        val head = "[재계획 $used/$MAX — 이유: $reason]"
        if (j == null) {
            val f = failedActions()
            past += Past(step, reason, "(재계획 모델 응답 없음 — 기본 안내)", "-")
            return "$head\n$DEFAULT_ADVICE" + if (f.isEmpty()) "" else "\n하지 말 것: " + f.joinToString(" · ") { failedText(it) }
        }
        val observed = clean(j.optString("observed"))
        val summary = clean(strategy?.optString("summary").orEmpty())
        past += Past(step, reason, summary.ifEmpty { "(전략 없음)" }.take(160), faText.ifEmpty { "-" })
        if (j.optBoolean("give_up", false)) {
            val um = clean(j.optString("user_message"))
            giveUpMessage = um
            return "$head 남은 방법이 없다 — finish(success=false)로 이 취지로 말할 것: ${um.ifEmpty { "무엇을 확인했고 왜 못 하는지, 대안" }}" +
                if (observed.isEmpty()) "" else "\n지금까지: $observed"
        }
        return buildString {
            append(head)
            if (observed.isNotEmpty()) append("\n지금까지: ").append(observed)
            j.optString("stuck_reason").takeIf { it.isNotBlank() }?.let { append("\n막힌 이유: ").append(clean(it)) }
            val hy = j.optJSONArray("hypotheses")
            if (hy != null && hy.length() > 0) {
                append("\n가설:")
                for (i in 0 until minOf(3, hy.length())) {
                    val h = hy.optJSONObject(i) ?: continue
                    append(' ').append("①②③"[i]).append(' ').append(clean(h.optString("claim")))
                    h.optString("check").takeIf { it.isNotBlank() }?.let { append(" → 확인: ").append(clean(it)) }
                }
            }
            append("\n다음 전략: ").append(summary.ifEmpty { DEFAULT_ADVICE })
            if (faText.isNotEmpty()) append("\n첫 행동 제안(참고 — 인자는 지금 화면과 도구 설명대로): ").append(faText)
            val avoid = strategy?.optJSONArray("avoid")?.let { a -> (0 until a.length()).map { clean(a.optString(it)) }.filter { it.isNotBlank() } }.orEmpty()
            if (avoid.isNotEmpty()) append("\n하지 말 것: ").append(avoid.take(4).joinToString(" · "))
        }
    }

    /** 재계획 직후 — 감시가 곧바로 다시 걸리지 않게 이 쪽 카운터를 비운다(Agent 는 actionHistory·repeat·failStreak 를 비운다) */
    fun resetAfter(step: Int) {
        noProgress = 0; unchangedStreak = 0; windowFrom = step + 1
    }
}
