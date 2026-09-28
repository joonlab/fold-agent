package kr.joonlab.foldagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 가벼운 마크다운 — 결과 카드용. 모델 답에 흔한 것만: 제목(#)·목록(- * 1.)·인용(>)·굵게(**)·`코드`·코드 블록(```)·링크([글](주소)·맨 주소).
 * 표·이미지·기울임(*)은 다루지 않는다(별표 하나는 곱셈·각주와 헷갈린다). 링크를 누르면 브라우저(LocalUriHandler).
 * 파싱은 글이 바뀔 때만(remember) — 긴 결과(6,000자)도 다시 그릴 때마다 돌지 않게.
 */
object Md {
    sealed interface Block {
        data class Code(val text: String) : Block
        data class Para(val text: AnnotatedString) : Block
    }

    private val heading = Regex("""^\s{0,3}#{1,6}\s+(.*)$""")
    private val bullet = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val ordered = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
    private val quote = Regex("""^\s{0,3}>\s?(.*)$""")
    private val rule = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
    // 굵게 · 인라인 코드 · [글](주소) · 맨 주소(끝 문장부호는 뺀다)
    private val inline = Regex(
        """\*\*(.+?)\*\*|`([^`\n]+)`|\[([^\]\n]+)]\((https?://[^\s)]+)\)|(https?://[^\s<>()\[\]「」]*[^\s<>()\[\]「」.,;:!?'"])"""
    )

    fun blocks(src: String, link: Color, dim: Color, codeBg: Color, open: (String) -> Unit): List<Block> {
        val out = ArrayList<Block>()
        val para = ArrayList<String>()
        fun flush() {
            if (para.isEmpty()) return
            // 앞뒤 빈 줄은 걷고, 문단 사이 빈 줄은 한 줄로
            while (para.isNotEmpty() && para.first().isBlank()) para.removeAt(0)
            while (para.isNotEmpty() && para.last().isBlank()) para.removeAt(para.size - 1)
            if (para.isNotEmpty()) out += Block.Para(paragraph(para, link, dim, codeBg, open))
            para.clear()
        }
        var code: StringBuilder? = null
        for (line in src.replace("\r\n", "\n").split('\n')) {
            if (line.trimStart().startsWith("```")) {
                if (code == null) { flush(); code = StringBuilder() }
                else { out += Block.Code(code.toString().trimEnd('\n')); code = null }
                continue
            }
            if (code != null) code.append(line).append('\n') else para += line
        }
        code?.let { out += Block.Code(it.toString().trimEnd('\n')) }   // 닫히지 않은 코드 블록도 코드로
        flush()
        return out
    }

    private fun paragraph(lines: List<String>, link: Color, dim: Color, codeBg: Color, open: (String) -> Unit): AnnotatedString = buildAnnotatedString {
        var prevBlank = false
        lines.forEach { raw ->
            // 빈 줄은 문단 사이 한 줄로(연달아 있어도 한 줄)
            if (raw.isBlank()) { if (!prevBlank && length > 0) append('\n'); prevBlank = true; return@forEach }
            if (length > 0) append('\n')
            prevBlank = false
            heading.matchEntire(raw)?.let { m -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(m.groupValues[1], link, codeBg, open) }; return@forEach }
            if (rule.matches(raw)) { withStyle(SpanStyle(color = dim)) { append("────────") }; return@forEach }
            bullet.matchEntire(raw)?.let { m ->
                append("  ".repeat((m.groupValues[1].length / 2).coerceAtMost(4)) + "•  ")
                inline(m.groupValues[2], link, codeBg, open); return@forEach
            }
            ordered.matchEntire(raw)?.let { m ->
                append("  ".repeat((m.groupValues[1].length / 2).coerceAtMost(4)) + "${m.groupValues[2]}. ")
                inline(m.groupValues[3], link, codeBg, open); return@forEach
            }
            quote.matchEntire(raw)?.let { m ->
                withStyle(SpanStyle(color = dim)) { append("▏ "); inline(m.groupValues[1], link, codeBg, open) }; return@forEach
            }
            inline(raw, link, codeBg, open)
        }
    }

    private fun AnnotatedString.Builder.inline(s: String, link: Color, codeBg: Color, open: (String) -> Unit) {
        var at = 0
        for (m in inline.findAll(s)) {
            if (m.range.first > at) append(s.substring(at, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(g[1], link, codeBg, open) }
                g[2].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(g[2]) }
                g[3].isNotEmpty() -> linkOf(g[4], g[3], link, open)
                g[5].isNotEmpty() -> linkOf(g[5], g[5], link, open)
            }
            at = m.range.last + 1
        }
        if (at < s.length) append(s.substring(at))
    }

    private fun AnnotatedString.Builder.linkOf(url: String, text: String, color: Color, open: (String) -> Unit) {
        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))) { open(url) }) {
            append(text)
        }
    }
}

/** 마크다운 글 — 문단은 Text, 코드 블록은 가로 스크롤 고정폭 상자. 전체를 선택(복사)할 수 있다 */
@Composable
fun MdText(text: String, color: Color, fontSize: TextUnit = 15.sp, lineHeight: TextUnit = 22.sp, modifier: Modifier = Modifier,
           onOpenFail: (String) -> Unit = {}) {
    val uri: UriHandler = LocalUriHandler.current
    val link = C.accentText; val dim = C.dim; val codeBg = C.panel2
    val blocks = remember(text, link, dim, codeBg) {
        Md.blocks(text, link, dim, codeBg) { u -> runCatching { uri.openUri(u) }.onFailure { onOpenFail(u) } }
    }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { b ->
                when (b) {
                    is Md.Block.Para -> Text(b.text, color = color, fontSize = fontSize, lineHeight = lineHeight)
                    is Md.Block.Code -> Text(b.text, color = color, fontSize = (fontSize.value - 2f).coerceAtLeast(11f).sp,
                        lineHeight = (lineHeight.value - 3f).coerceAtLeast(14f).sp, fontFamily = FontFamily.Monospace, softWrap = false,
                        modifier = Modifier.fillMaxWidth().background(codeBg, RoundedCornerShape(8.dp))
                            .horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
        }
    }
}
