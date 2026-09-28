package kr.joonlab.foldagent.ui

import android.content.Context
import android.content.res.Configuration
import kr.joonlab.foldagent.Prefs

/**
 * 디자인 토큰 — View(오버레이·빛·팝업)와 Compose(앱 화면) 두 세계가 같은 값을 읽는다(DESIGN §3-4).
 * 전부 ARGB Int. Compose 쪽은 Color(int) 로 감싼다.
 * 값은 시안 1 「숨결(Ember)」(docs/design/mockups.html .d-ember) 다크 + 그 라이트 짝. 값 조정은 D 가 단독으로.
 */
object FaTokens {
    data class Pal(val bg: Int, val panel: Int, val panel2: Int, val raised: Int, val border: Int,
                   val text: Int, val dim: Int, val accent: Int, val accentTint: Int, val onAccent: Int,
                   val glow: Int, val glowWait: Int, val ok: Int, val warn: Int, val stop: Int,
                   val overlayBg: Int, val overlayLine: Int, val overlayText: Int)

    // 따뜻한 먹색 바탕 + 잉걸불 붉은색 하나. 실패는 붉은색이 아니라 호박색(붉은색 = 오류로 읽히는 것을 피한다, §5 위험)
    val dark = Pal(
        bg = 0xFF15120F.toInt(), panel = 0xFF1C1814.toInt(), panel2 = 0xFF262019.toInt(), raised = 0xFF221D19.toInt(),
        border = 0xFF3A322B.toInt(), text = 0xFFEDE6DF.toInt(), dim = 0xFFA89C91.toInt(),
        accent = 0xFFE5484D.toInt(), accentTint = 0x21E5484D, onAccent = 0xFFFFFFFF.toInt(),   // accentTint = α.13
        glow = 0xFFE5484D.toInt(), glowWait = 0xFFE0A23A.toInt(),                               // 확인 대기 = 호박색 고정(§9 7-6)
        ok = 0xFF8FC98A.toInt(), warn = 0xFFE0A23A.toInt(), stop = 0xFF8A8078.toInt(),
        overlayBg = 0xFC181411.toInt(), overlayLine = 0x59E5484D, overlayText = 0xFFF2EBE4.toInt(),   // α.99 — .94 는 뒤 앱 글자가 비쳐 겹쳐 읽혔다(2026-09-26 실기) · 선 α.35
    )

    // 라이트 짝: 종이빛 바탕. 강조색은 흰 바탕 대비를 위해 한 단계 진하게, 빛(glow)은 다른 앱 위에 뜨므로 다크와 같은 색.
    val light = Pal(
        bg = 0xFFFAF7F4.toInt(), panel = 0xFFF3EEE9.toInt(), panel2 = 0xFFEBE4DD.toInt(), raised = 0xFFFFFFFF.toInt(),
        border = 0xFFDDD3CA.toInt(), text = 0xFF221C18.toInt(), dim = 0xFF6E625A.toInt(),
        accent = 0xFFD93A40.toInt(), accentTint = 0x1AD93A40, onAccent = 0xFFFFFFFF.toInt(),   // accentTint = α.10
        glow = 0xFFE5484D.toInt(), glowWait = 0xFFE0A23A.toInt(),
        ok = 0xFF3A8A45.toInt(), warn = 0xFFB7791F.toInt(), stop = 0xFF8A8078.toInt(),        // 글자로 쓰는 warn 은 흰 바탕에서 읽히게 진한 호박
        overlayBg = 0xF5FFFCF9.toInt(), overlayLine = 0x40D93A40, overlayText = 0xFF221C18.toInt(),
    )

    /** Prefs.theme(system/light/dark). system 이면 지금 uiMode 의 야간 여부로 고른다. */
    fun of(ctx: Context): Pal = when (Prefs.theme(ctx)) {
        "light" -> light
        "dark" -> dark
        else -> if ((ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) dark else light
    }

    /** 반경(dp): 태그 · 작은 면 · 카드·말풍선·입력 · 패널 · 팝업 카드 위 */
    object R { const val tag = 5; const val small = 10; const val card = 14; const val panel = 20; const val sheet = 24 }

    /** 테두리 빛·패널 숨쉬는 점이 공유하는 숨쉬기 주기(§9 7-7, 반주기 1.8s) */
    const val GLOW_PERIOD_MS = 3600L
}
