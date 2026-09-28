package kr.joonlab.foldagent

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch

/**
 * 사람 개입(HITL, 2026-09-28 사용자) — 에이전트가 헤맬 때 사용자가 「✋ 내가 할게」로 잠깐 넘겨받아 화면을 조작하거나 말로 알려 주고,
 * 「▶ 이어서」로 돌려준다. 에이전트는 멈춘 뒤 사람이 누른 것·말한 것·바뀐 화면을 요약으로 받아 **그 지점부터** 원래 요청을 잇는다.
 *
 * 한 번의 멈춤 = 이 객체 하나. AgentService 가 만들고(pauseTask) 치운다(hitlEnd). 사람 조작 기록은 접근성 이벤트에서 —
 * 멈춤이 확정된(acked) 뒤부터만 모은다(그 전엔 에이전트가 하던 동작이 끝나는 중이라 섞인다).
 * 🔒 비밀번호 칸은 글을 받지 않는다. 입력 글은 이번 실행의 모델에게만 — trace·세션에는 「n자」만(summary(typed=false)).
 */
class Hitl(val id: String) {
    /** 에이전트가 멈춤을 받아들였다(도구 사이에 섰거나, 모델 대기 중이라 그 답을 버리기로 했다). 이때부터 사람 차례 */
    @Volatile var acked = false
    @Volatile var ackAt = 0L            // uptime
    @Volatile var resumed = false
    val requestedAt = SystemClock.uptimeMillis()
    val latch = CountDownLatch(1)
    private val events = ArrayList<Ev>()
    private var dropped = 0
    private val said = ArrayList<String>()

    /** kind: tap · long · screen · type · secret · scroll */
    data class Ev(val kind: String, val pkg: String, val label: String, val text: String = "", val field: String = "")

    fun saidCount() = synchronized(this) { said.size }
    fun eventCount() = synchronized(this) { events.size + dropped }

    fun addSaid(t: String) = synchronized(this) { said += t }

    /**
     * 접근성 이벤트 하나를 사람 조작으로 기록한다. 부르는 쪽(AgentService)이 우리 앱·입력기 패키지는 이미 뺐다.
     */
    fun record(e: AccessibilityEvent, pkg: String) {
        val label = labelOf(e)
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> add(Ev("tap", pkg, label))
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> add(Ev("long", pkg, label))
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> add(Ev("screen", pkg, label))
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> add(Ev("scroll", pkg, ""))
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val field = fieldOf(e)
                // 비밀번호 칸은 글자를 받지 않는다 — 들어갔다는 사실만
                if (e.isPassword) add(Ev("secret", pkg, "", field = field))
                else add(Ev("type", pkg, "", text = e.text.joinToString("").take(200), field = field))
            }
        }
    }

    private fun add(ev: Ev): Unit = synchronized(this) {
        val last = events.lastOrNull()
        // 같은 칸에 이어 친 글은 마지막 글 하나로 · 이어진 스크롤·같은 화면 전환은 한 줄로
        if (last != null && last.pkg == ev.pkg) {
            if (ev.kind in setOf("type", "secret") && last.kind == ev.kind && last.field == ev.field) { events[events.size - 1] = ev; return }
            if (ev.kind == "scroll" && last.kind == "scroll") return
            if (ev.kind == "screen" && last.kind == "screen" && last.label == ev.label) return
        }
        // 빈 이름 화면 전환(팝업·토스트)은 앞 줄이 같은 앱이면 버린다 — 요약이 「→ 설정」 반복으로 차지 않게
        if (ev.kind == "screen" && ev.label.isBlank() && last?.pkg == ev.pkg) return
        events += ev
        if (events.size > MAX_EVENTS) { events.removeAt(0); dropped++ }
    }

    /**
     * 이어서 할 때 모델에게 주는 요약(끝에 현재 화면은 부르는 쪽이 붙인다).
     * @param typed false 면 입력 글을 「n자」로 — trace·세션 저장용
     */
    fun summary(task: String, before: Snapshot?, beforeApp: String, nowApp: String, appLabel: (String) -> String, typed: Boolean): String {
        val (evs, drop, words) = synchronized(this) { Triple(events.toList(), dropped, said.toList()) }
        val sec = ((SystemClock.uptimeMillis() - (if (ackAt > 0) ackAt else requestedAt)) / 1000).coerceAtLeast(0)
        return buildString {
            append("[사람 개입 — 사용자가 ${if (sec >= 60) "${sec / 60}분 ${sec % 60}초" else "${sec}초"} 동안 넘겨받아 도왔다]\n")
            append("멈춘 화면: ").append(beforeApp).append(before?.let { " (항목 ${it.nodes.size}개)" } ?: "").append('\n')
            append("사용자가 한 일:")
            if (evs.isEmpty()) append(" (화면 조작 없음)\n") else {
                append('\n')
                if (drop > 0) append("(앞의 ${drop}개 생략)\n")
                evs.forEachIndexed { i, ev -> append("${i + 1}. ").append(line(ev, appLabel, typed)).append('\n') }
            }
            append("사용자가 한 말:")
            if (words.isEmpty()) append(" (없음)\n") else { append('\n'); words.forEach { append("- 「").append(it).append("」\n") } }
            append("지금 화면: ").append(nowApp).append('\n')
            append("→ 원래 요청(「").append(task).append("」)을 처음부터 다시 하지 말고, 사용자가 옮겨 둔 지금 화면에서 이어서 끝낼 것. ")
            append("사용자가 한 말은 지시로 따른다. 사용자가 직접 누른 것은 이미 일어난 일이다(되돌리거나 다시 누르지 말 것). ")
            append("요청을 이미 다 이뤘으면 확인하고 finish 한다.")
        }
    }

    /** 성공 경로·trace 에 남길 한 줄 — 입력 글 없이 */
    fun shortLine(appLabel: (String) -> String): String {
        val (evs, words) = synchronized(this) { events.toList() to said.size }
        val taps = evs.filter { it.kind in setOf("tap", "long") }.take(4).joinToString(" → ") { "「${it.label.take(20)}」" }
        return buildString {
            append("(사람이 도움)")
            if (taps.isNotEmpty()) append(' ').append(taps)
            if (words > 0) append(" · 말 ${words}번")
        }
    }

    private fun line(ev: Ev, appLabel: (String) -> String, typed: Boolean): String {
        val app = appLabel(ev.pkg)
        return when (ev.kind) {
            "tap" -> "「${ev.label.ifBlank { "(이름 없는 항목)" }}」 누름 ($app)"
            "long" -> "「${ev.label.ifBlank { "(이름 없는 항목)" }}」 길게 누름 ($app)"
            "screen" -> "화면 전환 → $app" + if (ev.label.isNotBlank()) " › ${ev.label}" else ""
            "scroll" -> "스크롤 ($app)"
            "secret" -> "비밀번호 칸에 입력 (${ev.field.ifBlank { app }} — 내용 기록 안 함)"
            else -> (if (typed) "「${ev.text}」 입력" else "글 입력(${ev.text.length}자)") + " (${ev.field.ifBlank { "입력칸" }} · $app)"
        }
    }

    companion object {
        /** 이어서가 없으면 이 시간 뒤 작업을 멈춘다(사용자 결정 2026-09-28: 10분 · 멈춤 동안 화면 켜짐 유지는 끔) */
        const val MAX_MS = 10 * 60_000L
        private const val MAX_EVENTS = 40

        /** 멈춤 동안만 더 받는 이벤트 — 평소엔 agent_service.xml 의 가벼운 셋만 */
        const val EXTRA_TYPES = AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or AccessibilityEvent.TYPE_VIEW_SCROLLED

        private fun clean(s: CharSequence?) = s?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

        private fun labelOf(e: AccessibilityEvent): String {
            val t = e.text.joinToString(" ") { clean(it) }.trim()
            if (t.isNotEmpty()) return t.take(40)
            val cd = clean(e.contentDescription)
            if (cd.isNotEmpty()) return cd.take(40)
            val src = runCatching { e.source }.getOrNull()
            val st = clean(src?.text).ifEmpty { clean(src?.contentDescription) }
            if (st.isNotEmpty()) return st.take(40)
            return if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) "" else e.className?.toString()?.substringAfterLast('.').orEmpty()
        }

        /** 입력칸 이름 — 힌트 · 설명 · id 끝 */
        private fun fieldOf(e: AccessibilityEvent): String {
            val src = runCatching { e.source }.getOrNull() ?: return ""
            return clean(src.hintText).ifEmpty { clean(src.contentDescription) }
                .ifEmpty { src.viewIdResourceName?.substringAfterLast('/').orEmpty() }.take(30)
        }
    }
}
