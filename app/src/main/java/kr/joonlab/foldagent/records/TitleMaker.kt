package kr.joonlab.foldagent.records

/**
 * 규칙 제목·설명 — 턴을 저장하는 순간 동기로 채운다(지연 0). LLM 제목은 이 위에 비동기로 덮인다(FileRecordStore.generate).
 * 순수 함수만 둔다(안드로이드·org.json 의존 없음) — 나중에 JVM 단위 테스트를 붙일 수 있게.
 *
 * 실측(2026-09-26 수거본 196턴): 요청 중앙값 31자·p90 106자, 40자 초과가 36% — 옛 제목 take(40) 은 셋 중 하나가 어절 중간에서 잘렸다.
 * 목록 한 줄에 들어가는 24자로 줄이고, 어절 경계에서 자른다.
 */
object TitleMaker {
    const val TITLE_MAX = 24
    const val SUMMARY_MAX = 60

    // 말로 시킨 요청의 앞 군말(음성 인식이 그대로 적는다)
    private val LEAD = Regex("^((음|어|아|저기|그|자)+[\\s,.…]+)+")
    // 「설정해 줘」 → 「설정」: 하다 동사는 어간만 남기면 명사가 된다
    private val HAE_TAIL = Regex("\\s*해\\s*(줘요?|줄래요?|주세요|주실래요\\??|줄\\s*수\\s*있어요?|주라|봐요?|보세요|주면\\s*좋겠어요?)$")
    // 「보내 줘」 → 「보내」 → (아래 표) 「보내기」
    private val JWO_TAIL = Regex("\\s*(줘요?|줄래요?|주세요|주실래요\\??|줄\\s*수\\s*있어요?|주라|봐요?|보세요)$")
    private val POLITE_TAIL = Regex("\\s*(부탁해요?|부탁합니다|부탁드려요|좀)$")
    private val PUNCT_TAIL = Regex("[\\s.?!~。？！…,]+$")
    private val JOM = Regex("(^|\\s)좀(?=\\s|$)")
    // 흔한 연결형 → 명사형. 표에 없으면 그대로 둔다(억지 변환보다 낫다). "" = 낱말을 뺀다(「날씨 알려 줘」 → 「날씨」)
    private val NOUN_FORM = mapOf(
        "열어" to "열기", "보내" to "보내기", "켜" to "켜기", "꺼" to "끄기", "틀어" to "틀기", "찾아" to "찾기",
        "알려" to "", "보여" to "", "만들어" to "만들기", "써" to "쓰기", "적어" to "적기", "찍어" to "찍기", "맞춰" to "맞추기",
        "띄워" to "띄우기", "올려" to "올리기", "내려" to "내리기", "줄여" to "줄이기", "늘려" to "늘리기", "지워" to "지우기",
        "골라" to "고르기", "읽어" to "읽기", "들어" to "듣기", "봐" to "보기", "넣어" to "넣기", "붙여" to "붙이기",
        "옮겨" to "옮기기", "바꿔" to "바꾸기", "걸어" to "걸기", "눌러" to "누르기", "닫아" to "닫기", "멈춰" to "멈추기",
        "재생해" to "재생", "캡처해" to "캡처", "공유해" to "공유",
    )

    /** 턴 제목(= 첫 턴이면 방 제목). 요청이 비면 "" */
    fun rule(request: String): String {
        var s = request.replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return ""
        s = s.replace(LEAD, "").replace(PUNCT_TAIL, "")
        val orig = s
        s = s.replace(POLITE_TAIL, "").replace(PUNCT_TAIL, "")
        s = if (HAE_TAIL.containsMatchIn(s)) s.replace(HAE_TAIL, "")
        else if (JWO_TAIL.containsMatchIn(s)) s.replace(JWO_TAIL, "").let { base ->
            val words = base.split(' ')
            val last = words.last()
            NOUN_FORM[last]?.let { (words.dropLast(1) + it).filter { w -> w.isNotEmpty() }.joinToString(" ") } ?: base
        } else s
        s = s.replace(JOM, " ").replace(Regex("\\s+"), " ").replace(PUNCT_TAIL, "").trim()
        if (s.length < 2) s = orig   // 「해 줘」만 남는 식으로 다 지워지면 원문
        return clip(s, TITLE_MAX)
    }

    /** 방 설명 = 마지막 결과의 첫 문장(60자). 결과가 비면 "" */
    fun summary(finalMsg: String): String {
        val s = finalMsg.replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return ""
        val first = s.split(Regex("(?<=[.!?。])\\s")).first().trim()
        return clip(first.ifEmpty { s }, SUMMARY_MAX)
    }

    /**
     * max 자 안으로 — 첫 문장이 들어가면 첫 문장, 아니면 어절 경계에서 자르고 「…」.
     * 첫 어절부터 넘치면(띄어쓰기 없는 긴 말) 글자로 자른다.
     */
    fun clip(text: String, max: Int): String {
        val s = text.trim()
        if (s.length <= max) return s
        val sentence = s.split(Regex("(?<=[.!?。])\\s")).first().trim().trimEnd('.', '。')
        if (sentence.length in 6..max) return sentence
        val sb = StringBuilder()
        for (w in s.split(' ')) {
            val add = if (sb.isEmpty()) w.length else w.length + 1
            if (sb.length + add > max - 1) break
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(w)
        }
        val cut = if (sb.length >= max / 3) sb.toString() else s.take(max - 1)
        return cut.trimEnd(',', ' ', '·') + "…"
    }
}
