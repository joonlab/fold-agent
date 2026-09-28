package kr.joonlab.foldagent

/** 모델 답의 마크다운 기호(**굵게**, # 제목, `코드`)를 걷어 낸다 — 말풍선·패널은 일반 텍스트고, 음성은 기호를 읽어 버린다. */
object Plain {
    private val bold = Regex("""\*\*(.+?)\*\*""")
    private val heading = Regex("""(?m)^#{1,6}\s*""")
    private val code = Regex("""`([^`]+)`""")
    fun of(s: String): String = s.replace(bold, "$1").replace(heading, "").replace(code, "$1")
}
