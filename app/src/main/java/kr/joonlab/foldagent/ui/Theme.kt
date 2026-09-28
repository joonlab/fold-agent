package kr.joonlab.foldagent.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import kr.joonlab.foldagent.Prefs
import kr.joonlab.foldagent.R

/**
 * 앱 화면(Compose)의 색 — FaTokens(ARGB Int, View 와 공용)를 Color 로 감싼 것. 새 색을 여기서 만들지 않는다(두 세계가 어긋난다).
 * accentText 만 파생값: 다크에선 먹색 위 붉은 글자가 어두워 읽히지 않아 시안(.d-ember --accentText #FF8A8D)처럼 한 단계 밝힌다.
 */
class FaColors(p: FaTokens.Pal, dark: Boolean) {
    val bg = Color(p.bg); val panel = Color(p.panel); val panel2 = Color(p.panel2); val raised = Color(p.raised)
    val border = Color(p.border); val text = Color(p.text); val dim = Color(p.dim)
    val accent = Color(p.accent); val accentTint = Color(p.accentTint); val onAccent = Color(p.onAccent)
    val ok = Color(p.ok); val warn = Color(p.warn); val stop = Color(p.stop)
    val glowWait = Color(p.glowWait)
    val accentText = if (dark) Color(0xFFFF8A8D) else accent
    /** 확인 대기 물듦(호박 α.16) · 실패 알약 바탕 */
    val waitTint = Color(p.glowWait).copy(alpha = .16f)
    val warnTint = Color(p.warn).copy(alpha = .15f)
    val dark = dark
}

val LocalFa = staticCompositionLocalOf<FaColors> { error("FaTheme 로 감싸지 않았다") }

/** 지금 색 — 부품에서 `C.text` 처럼 쓴다 */
val C: FaColors @Composable get() = LocalFa.current

/** Pretendard 2굵기(§9 7-13). Bold 요청은 SemiBold 로 가깝게 그린다 — 3.2MB 로 묶으려고 굵기를 더 넣지 않는다 */
val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
)

/** 테마 3상태(system·light·dark) — Prefs.theme 가 정본, 바꾸면 화면이 곧바로 다시 그려지게 상태로 들고 있는다 */
object ThemeState {
    var mode by mutableStateOf("system")
}

@Composable
fun FaTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val sysDark = isSystemInDarkTheme()
    val dark = when (ThemeState.mode) { "light" -> false; "dark" -> true; else -> sysDark }
    val colors = if (dark) FaColors(FaTokens.dark, true) else FaColors(FaTokens.light, false)
    val view = LocalView.current
    LaunchedEffect(Unit) { ThemeState.mode = Prefs.theme(ctx) }
    LaunchedEffect(dark) {
        (ctx as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalFa provides colors,
        LocalTextStyle provides TextStyle(fontFamily = Pretendard, color = colors.text)) {
        content()
    }
}
