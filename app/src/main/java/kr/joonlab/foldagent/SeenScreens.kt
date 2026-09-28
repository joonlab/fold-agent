package kr.joonlab.foldagent

import java.security.MessageDigest

/**
 * 본 화면 기억 — 이번 실행에서 거쳐 간 화면을 「같은 화면은 한 줄」로 쌓는다(설계 memory.md 추천안 A, 2026-09-27).
 * 왜: 이전 화면 전문은 요청에서 잘리므로 모델이 방금 본 화면을 다시 보러 갔다(run 232447-007 — 개발자 옵션 두 쪽을 16번 왕복).
 *
 * - 서명 = 앱|제목|앞 40개 라벨(숫자→#)의 해시. 시계·메모리 사용량처럼 숫자만 바뀌는 화면도 같은 화면으로 잡는다.
 * - block() 은 요청마다 새로 만들어 마지막 화면 메시지에만 붙인다(messages 에 저장하지 않음 → 세션·다음 턴에 안 남는다).
 * - 민감 앱(금융·메신저·노트·갤러리·메일·연락처)은 앱 이름과 「항목 N개」만 — 제목·라벨을 요약에 싣지 않는다.
 */
class SeenScreens(private val ownPkg: String, private val appName: (String) -> String) {

    companion object {
        private const val MAX_LINES = 15
        private const val SIG_LABELS = 40
        private val SENSITIVE_PKGS = setOf(
            "viva.republica.toss", "com.kakao.talk", "com.kakaopay.app", "com.kakaobank.channel",
            "com.samsung.android.messaging", "com.google.android.apps.messaging",
            "com.samsung.android.app.notes", "com.sec.android.gallery3d", "com.google.android.apps.photos",
            "com.samsung.android.email.provider", "com.google.android.gm",
            "com.samsung.android.app.contacts", "com.google.android.contacts", "com.samsung.android.dialer",
            "com.samsung.android.spay", "com.kbstar.kbbank", "com.shinhan.sbanking", "com.wooribank.smart.npib",
            "com.hanabank.ebk.channel.android.hananbank", "nh.smart.banking", "com.ibk.android.ionebank",
        )
        /** 금융앱은 이름이 제각각이라 목록 밖이어도 이름 조각으로 민감 처리한다(Agent.SENSITIVE_SCHEMES 와 같은 취지) */
        private val SENSITIVE_PARTS = listOf("bank", "toss", "pay", "card", "stock", "securities")

        fun isSensitive(pkg: String) = pkg in SENSITIVE_PKGS || SENSITIVE_PARTS.any { pkg.lowercase().contains(it) }

        private val DIGITS = Regex("\\d+")

        fun sigOf(s: Snapshot): String {
            val labels = s.nodes.take(SIG_LABELS).joinToString("\u0001") { runCatching { ScreenReader.labelOf(it) }.getOrDefault("") }
            val raw = "${s.pkg}|${s.title}|$labels".replace(DIGITS, "#")
            return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
        }

        /** 전문(숫자→#) 해시 — 재계획의 「새 화면」·「같은 화면」 판정용(cases.md C′ 가 이 기준으로 오탐 0). 서명은 켜짐/꺼짐·선택 수를 못 봐
         *  같은 설정 화면에서 스위치를 차례로 켜는 정상 진척도 「같은 화면」이 됐다(리뷰 2026-09-27) */
        fun textKeyOf(s: Snapshot): String = s.text.replace(DIGITS, "#").hashCode().toString(16)

        /** 1·3·5 — 다섯 개가 넘으면 1·3·5…15 (8회) */
        fun stepsText(steps: List<Int>): String =
            if (steps.size <= 5) steps.joinToString("·") else steps.take(3).joinToString("·") + "…${steps.last()} (${steps.size}회)"
    }

    /**
     * 한 번 기록한 결과. revisit = 화면이 바뀌었는데 이미 본 서명(직전과 같은 서명에서 글자만 바뀐 입력·토글은 제외).
     * fresh = 전문(숫자→#)이 이번 실행에서 처음 — 재계획 무진척은 이것으로 센다. textKey = 그 전문 해시
     */
    class Visit(val sig: String, val pkg: String, val isNew: Boolean, val revisit: Boolean, val visits: Int, val prevSteps: List<Int>,
                val fresh: Boolean, val textKey: String)

    private class Entry(val sig: String, val pkg: String, val title: String, val scrolled: Boolean, val span: Pair<String, String>?,
                        val more: ScrollMore?, val count: Int) {
        val steps = ArrayList<Int>()
    }

    private val entries = LinkedHashMap<String, Entry>()
    private var current: Entry? = null
    private val textSteps = HashMap<String, ArrayList<Int>>()   // 전문 해시 → 나온 단계

    /** 블록을 붙일 만한가 — 화면이 둘 이상일 때만(하나면 [현재 화면]과 같은 말) */
    val nonEmpty get() = entries.size >= 2

    /**
     * 단계의 결과 화면을 기록한다. changed=false 면 방문을 늘리지 않는다(「화면 변화 없음」 경고가 따로 맡는다).
     * 우리 앱(채팅 화면)은 조작 대상이 아니라 기록하지 않는다.
     */
    fun record(step: Int, tool: String, snap: Snapshot, changed: Boolean): Visit? {
        if (snap.pkg == ownPkg) return null
        val sig = sigOf(snap)
        val e = entries[sig]
        if (!changed && e != null) { current = e; return null }
        val tk = textKeyOf(snap)
        val ts = textSteps.getOrPut(tk) { ArrayList() }
        val fresh = ts.isEmpty()
        ts += step
        // 직전과 같은 서명인데 글자만 바뀜 = 같은 화면 안의 입력·토글·선택 — 다시 본 게 아니다(⟲·재방문 집계 모두 제외)
        if (e != null && e === current) return Visit(sig, snap.pkg, isNew = false, revisit = false, visits = e.steps.size, prevSteps = e.steps.toList(), fresh = fresh, textKey = tk)
        if (e != null) {
            val prev = e.steps.toList()
            e.steps += step; current = e
            return Visit(sig, snap.pkg, isNew = false, revisit = true, visits = e.steps.size, prevSteps = prev, fresh = fresh, textKey = tk)
        }
        // 같은 앱에서 scroll 직후라 제목이 사라졌으면 앞 제목을 물려받는다(설계 규칙 3)
        val cur = current
        val inherit = snap.title.isEmpty() && tool == "scroll" && cur != null && cur.pkg == snap.pkg
        val title = if (inherit) cur!!.title else snap.title
        val scrolled = inherit || snap.scrollMore?.back == true
        val n = Entry(sig, snap.pkg, title, scrolled, snap.listSpan, snap.scrollMore, snap.nodes.size).also { it.steps += step }
        entries[sig] = n; current = n
        return Visit(sig, snap.pkg, isNew = true, revisit = false, visits = 1, prevSteps = emptyList(), fresh = fresh, textKey = tk)
    }

    /** 전문 해시 textKey 가 from 단계 이후(포함) 나온 횟수 — 재계획 트리거 「같은 화면이 최근 10단계 중 4회」용(설계대로 전문 기준) */
    fun textVisitsSince(textKey: String, from: Int): Int = textSteps[textKey]?.count { it >= from } ?: 0

    /** 화면 한 줄 설명(단계 표기 없이) — 재계획 입력의 행동 기록에도 쓴다 */
    fun describe(sig: String): String = entries[sig]?.let { describe(it) } ?: "?"

    private fun describe(e: Entry): String {
        val app = runCatching { appName(e.pkg) }.getOrDefault(e.pkg)
        if (e.pkg == "?") return "(읽을 수 없는 화면)"
        if (isSensitive(e.pkg)) return "$app · 항목 ${e.count}개"
        return buildString {
            append(app).append(" › ").append(e.title.ifEmpty { "?" })
            if (e.scrolled) append("(스크롤)")
            e.span?.let { (a, b) -> append(" · 「").append(a).append('」'); if (b != a) append("…「").append(b).append('」') }
            append(" · 항목 ").append(e.count).append('개')
            e.more?.let { append(if (it.forward) " [↓더 있음]" else " [↓끝]") }
        }
    }

    /** 요청마다 새로 만드는 블록. 줄 수 상한을 넘으면 마지막 방문이 오래된 화면부터 접는다. */
    fun block(): String {
        val all = entries.values.toList()
        val keep = if (all.size <= MAX_LINES) all else {
            val recent = all.sortedByDescending { it.steps.last() }.take(MAX_LINES - 1).toMutableSet()
            current?.let { recent += it }
            all.filter { it in recent }
        }
        val folded = all.size - keep.size
        return buildString {
            append("[지금까지 본 화면 — 같은 화면은 한 줄, 숫자는 그 화면이 나온 단계. 이미 본 곳을 다시 보러 가지 말 것]\n")
            if (folded > 0) append("· (그 밖 ").append(folded).append("개 화면)\n")
            keep.forEach { e ->
                append("· ").append(stepsText(e.steps)).append("단계: ").append(describe(e))
                if (e === current) append(" ← 지금")
                append('\n')
            }
        }.trimEnd()
    }
}
