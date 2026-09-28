package kr.joonlab.foldagent

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.provider.Settings
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kr.joonlab.foldagent.ui.FaTokens

/**
 * 테두리 빛(DESIGN §1-6·§3-2) — 에이전트가 일하는 동안 화면 네 변이 숨 쉰다.
 *
 * - 패널(Overlay)과 **별도 창**이다. `FLAG_NOT_TOUCHABLE` 은 생성 때 박고 **절대 토글하지 않는다** —
 *   패널처럼 제스처마다 켜고 끄면 updateViewLayout 과 dispatchGesture 가 경주한다(AgentService.gesture 주석, run 172029-564).
 * - 그림은 크기가 바뀔 때만 셰이더를 다시 만들고, 숨쉬기는 `View.ALPHA` 애니메이션만 한다(RenderThread 합성만, 블러 없음).
 * - 확인 대기(WAIT)는 숨쉬기를 멈추고 호박색으로 고정한다(§9 7-6) — 「일하는 중」과 「당신 차례」가 다른 신호로 읽히게.
 * - 메인 스레드에서만 호출된다(AgentService 가 main.post 로 부른다).
 *
 * 창 두 겹: frame(창 루트) 알파 = 캡처 숨김 복귀 페이드·끝 페이드아웃, glowView 알파 = 숨쉬기. 두 값은 곱해진다 —
 * 한 속성에 두 애니메이션을 걸면 서로 덮어써서 페이드인 도중 숨쉬기가 튀었다.
 */
class EdgeGlow(private val svc: AgentService) : RunIndicator {

    private val wm = svc.getSystemService(WindowManager::class.java)
    private var frame: FrameLayout? = null
    private var glowView: GlowView? = null
    private var breathe: ObjectAnimator? = null      // glowView.alpha — 숨쉬기·확인 대기 펄스
    private var fade: ObjectAnimator? = null         // frame.alpha — 페이드인·페이드아웃
    private var phase = RunPhase.ACT
    private var captureHidden = false

    override fun start() {
        phase = RunPhase.ACT
        if (frame == null && !addWindow()) return
        // 끝 페이드아웃 도중 새 작업이 시작되면 창을 치우지 말고 그대로 되살린다
        fade?.cancel(); fade = null
        glowView?.setColorAndStrength(FaTokens.of(svc).glow, Prefs.glowStrength(svc))
        frame?.alpha = 1f
        frame?.visibility = if (captureHidden) View.INVISIBLE else View.VISIBLE
        applyPhase()
    }

    override fun phase(p: RunPhase) {
        if (p == phase) return
        phase = p
        // 확인만 띄우는 시험(작업 없이 confirm)에서는 빛 창이 없다 — 표시는 패널 몫으로 둔다
        if (frame == null) return
        applyPhase()
    }

    override fun stop() {
        val f = frame ?: return
        breathe?.cancel(); breathe = null
        fade?.cancel()
        if (reducedMotion()) { removeWindow(); return }
        fade = ObjectAnimator.ofFloat(f, View.ALPHA, f.alpha, 0f).apply {
            duration = 450
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) { canceled = true }
                // cancel() 도 onAnimationEnd 를 부른다 — start() 가 되살린 창을 치우지 않게 취소는 걸러 낸다
                override fun onAnimationEnd(animation: Animator) { if (!canceled && fade === animation) removeWindow() }
            })
            start()
        }
    }

    override fun setCaptureHidden(hidden: Boolean) {
        captureHidden = hidden
        val f = frame ?: return
        if (hidden) {
            // 페이드 없이 즉시 INVISIBLE. 여기서 할 수 있는 건 가시성까지다 — 실제 프레임 반영은
            // AgentService 가 숨긴 뒤 캡처 전에 두는 150ms 대기(screenshotJpegBase64·captureBitmap)와
            // systemScreenshot 의 대기에 기댄다. 모자라면(V3 에서 빛이 찍히면) 프레임 콜백 2회 뒤 캡처로 바꿀 것(§3-2-3).
            // 숨쉬기 애니메이션은 그대로 둔다(INVISIBLE 이면 그려지지 않는다) — 복귀 때 박자가 끊기지 않게.
            // 끝 페이드아웃 중이어도 그 페이드는 그대로 두고(창을 치우러 간다) 보이지만 않게 한다.
            f.visibility = View.INVISIBLE
        } else {
            if (f.visibility == View.VISIBLE) return
            f.visibility = View.VISIBLE
            // 끝 페이드아웃 중이었으면 그 페이드가 이어서 창을 치우게 둔다
            if (fade?.isRunning == true) return
            if (reducedMotion()) { f.alpha = 1f; return }
            fade = ObjectAnimator.ofFloat(f, View.ALPHA, 0f, 1f).apply { duration = 300; start() }
        }
    }

    override fun release() {
        breathe?.cancel(); breathe = null
        fade?.cancel(); fade = null
        removeWindow()
    }

    // ---- 내부

    private fun addWindow(): Boolean {
        val gv = GlowView()
        val f = object : FrameLayout(svc) { override fun hasOverlappingRendering() = false }
        f.addView(gv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // ⚠️ NOT_TOUCHABLE 은 여기서 한 번만. 이 창은 한 번도 터치를 받지 않는다(안전 불변식 3)
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)   // 상태 막대·내비 막대 자리까지 덮는다(가장자리가 빛나야 하므로)
            if (Build.VERSION.SDK_INT >= 33) setCanPlayMoveAnimation(false)   // 접고 펼 때 창이 미끄러져 들어오는 애니 끔
            title = "FoldAgentGlow"
        }
        // z순서: 같은 TYPE_ACCESSIBILITY_OVERLAY 끼리는 나중에 add 한 창이 위다. 작업 시작(start)이 첫 status() 보다 먼저
        // main.post 되므로 보통은 빛이 패널 아래에 깔린다. 이전 작업의 패널이 아직 떠 있으면(hideWhenIdle 10초 안) 빛이 위에 올 수
        // 있지만, 빛은 NOT_TOUCHABLE 이라 패널 버튼 터치는 그대로 통하고 두께(22~28dp)가 패널 y(36dp)보다 얇아 거의 겹치지 않는다.
        // 그래서 패널을 remove/add 로 다시 올리지 않는다(패널 재생성은 확인 대기 콜백을 잃을 위험). 실제 순서는 실기에서 판정(§8).
        try {
            wm.addView(f, lp)
        } catch (e: WindowManager.BadTokenException) {
            android.util.Log.w("FoldAgent", "glow skipped: ${e.message}")
            return false
        } catch (e: IllegalStateException) {
            android.util.Log.w("FoldAgent", "glow skipped: ${e.message}")
            return false
        }
        frame = f; glowView = gv
        return true
    }

    private fun removeWindow() {
        frame?.let { runCatching { wm.removeView(it) } }
        frame = null; glowView = null; fade = null
        breathe?.cancel(); breathe = null
    }

    /** 시스템 「애니메이션 제거」(애니메이터 배율 0) — 숨쉬기 없이 α0.6 고정 */
    private fun reducedMotion(): Boolean =
        !ValueAnimator.areAnimatorsEnabled() ||
            Settings.Global.getFloat(svc.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun applyPhase() {
        val gv = glowView ?: return
        breathe?.cancel(); breathe = null
        val pal = FaTokens.of(svc)
        val strength = Prefs.glowStrength(svc)
        val reduced = reducedMotion()
        when (phase) {
            RunPhase.WAIT -> {
                gv.setColorAndStrength(pal.glowWait, strength)
                if (reduced) { gv.alpha = WAIT_ALPHA; return }
                // 숨쉬기 멈춤 → 호박색 α0.55 고정. 들어갈 때만 느린 펄스 2회로 「바뀌었다」를 알린다(반복 없음, 끝나면 고정)
                breathe = ObjectAnimator.ofFloat(gv, View.ALPHA, gv.alpha, 0.85f, 0.40f, 0.85f, WAIT_ALPHA).apply {
                    duration = 2400
                    interpolator = EASE
                    start()
                }
            }
            RunPhase.ACT, RunPhase.THINK -> {
                gv.setColorAndStrength(pal.glow, strength)
                if (reduced) { gv.alpha = 0.6f; return }
                val (lo, hi) = if (phase == RunPhase.THINK) 0.20f to 0.45f else 0.25f to 0.90f
                val half = (Prefs.glowPeriodMs(svc).takeIf { it >= 600 } ?: FaTokens.GLOW_PERIOD_MS) / 2
                gv.alpha = lo
                breathe = ObjectAnimator.ofFloat(gv, View.ALPHA, lo, hi).apply {
                    duration = half
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = EASE
                    start()
                }
            }
        }
    }

    /**
     * 네 변 LinearGradient + 네 모서리 RadialGradient. 모서리는 화면 둥근 모서리 반경에 맞춰 호를 따라가므로
     * 변과 모서리의 이음새가 없다(모서리 호의 반지름 방향 기울기 = 변의 수직 기울기, 같은 멈춤점).
     */
    private inner class GlowView : View(svc) {
        private var color = Color.RED
        private var strength = 0.8f
        private val edge = Array(4) { Paint(Paint.ANTI_ALIAS_FLAG) }     // 위·아래·왼쪽·오른쪽
        private val corner = Array(4) { Paint(Paint.ANTI_ALIAS_FLAG) }   // 왼위·오위·왼아래·오아래
        private var t = 0f                                                // 두께(px)
        private val r = FloatArray(4)                                     // 모서리 반경(px), corner 순서

        init {
            if (Build.VERSION.SDK_INT >= 35) {
                // 알파만 바뀌는 느린 숨쉬기 — 120Hz 로 돌릴 이유가 없다(배터리). 반영 여부는 실기 판정(§8)
                runCatching { setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_LOW) }
            }
        }

        override fun hasOverlappingRendering() = false

        fun setColorAndStrength(c: Int, s: Float) {
            if (c == color && s == strength) return
            color = c; strength = s
            if (width > 0) { rebuild(width, height); invalidate() }
        }

        // 커버·펼침 밀도가 다를 수 있다 — 두께 dp 환산은 그릴 때마다 지금 값으로
        private val d get() = resources.displayMetrics.density
        private var cornerKey = ""                                        // 마지막으로 쓴 모서리 반경 4개 — 바뀌었을 때만 다시 만든다

        init {
            // 회전·접힘 전환: 크기가 바뀌면 onSizeChanged 가 잡는다. 그런데 180° 회전(크기 같음)이나, 크기 변경보다 인셋이
            // 늦게 오는 경우엔 모서리 반경(RoundedCorner 는 회전을 따라 위치가 바뀐다)이 옛값으로 남는다 → 인셋이 올 때도 비교해 다시 만든다
            setOnApplyWindowInsetsListener { v, ins ->
                if (width > 0 && readCornerKey(ins) != cornerKey) { rebuild(width, height); invalidate() }
                v.onApplyWindowInsets(ins)
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            rebuild(w, h)
            invalidate()
        }

        private fun readCornerKey(ins: android.view.WindowInsets?): String {
            if (Build.VERSION.SDK_INT < 31 || ins == null) return ""
            return intArrayOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
                RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT)
                .joinToString(",") { (ins.getRoundedCorner(it)?.radius ?: -1).toString() }
        }

        private fun rebuild(w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            // 커버(짧은 변 < 600dp) 22dp · 펼침 28dp. 폭이 아니라 짧은 변으로 본다 — 커버를 가로로 돌리면 폭이 600dp 를 넘는다
            t = (if (minOf(w, h) / d < 600f) 22f else 28f) * d
            val ins = rootWindowInsets
            cornerKey = readCornerKey(ins)
            val pos = intArrayOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
                RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT)
            for (i in 0..3) {
                val rc = if (Build.VERSION.SDK_INT >= 31) ins?.getRoundedCorner(pos[i]) else null
                val phys = (rc?.radius?.toFloat()?.takeIf { it > 0f }) ?: (24f * d)
                // 반경이 두께보다 작으면 빛 안쪽 끝이 중심을 넘는다 — 두께 이상으로 잡는다(바깥 여분은 CLAMP 로 가장자리 색)
                r[i] = maxOf(phys, t)
            }
            val a = (0.55f * strength).coerceIn(0f, 1f)
            val c0 = withAlpha(color, a)
            val c1 = withAlpha(color, a * 0.35f)
            val c2 = withAlpha(color, 0f)
            val lc = intArrayOf(c0, c1, c2)
            val ls = floatArrayOf(0f, 0.4f, 1f)
            val fw = w.toFloat(); val fh = h.toFloat()
            edge[0].shader = LinearGradient(0f, 0f, 0f, t, lc, ls, Shader.TileMode.CLAMP)
            edge[1].shader = LinearGradient(0f, fh, 0f, fh - t, lc, ls, Shader.TileMode.CLAMP)
            edge[2].shader = LinearGradient(0f, 0f, t, 0f, lc, ls, Shader.TileMode.CLAMP)
            edge[3].shader = LinearGradient(fw, 0f, fw - t, 0f, lc, ls, Shader.TileMode.CLAMP)
            val cx = floatArrayOf(r[0], fw - r[1], r[2], fw - r[3])
            val cy = floatArrayOf(r[0], r[1], fh - r[2], fh - r[3])
            for (i in 0..3) {
                // 반지름 방향: 중심 쪽(R−t)에서 0 → 호(R)에서 가장자리 색. 변 그라데이션을 거꾸로 놓은 같은 멈춤점
                val s = (r[i] - t) / r[i]
                corner[i].shader = RadialGradient(cx[i], cy[i], r[i], intArrayOf(c2, c1, c0),
                    floatArrayOf(s, s + (1f - s) * 0.6f, 1f), Shader.TileMode.CLAMP)
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (t <= 0f) return
            val fw = width.toFloat(); val fh = height.toFloat()
            canvas.drawRect(r[0], 0f, fw - r[1], t, edge[0])
            canvas.drawRect(r[2], fh - t, fw - r[3], fh, edge[1])
            canvas.drawRect(0f, r[0], t, fh - r[2], edge[2])
            canvas.drawRect(fw - t, r[1], fw, fh - r[3], edge[3])
            canvas.drawRect(0f, 0f, r[0], r[0], corner[0])
            canvas.drawRect(fw - r[1], 0f, fw, r[1], corner[1])
            canvas.drawRect(0f, fh - r[2], r[2], fh, corner[2])
            canvas.drawRect(fw - r[3], fh - r[3], fw, fh, corner[3])
        }

        private fun withAlpha(c: Int, a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
    }

    companion object {
        private const val WAIT_ALPHA = 0.55f
        private val EASE = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    }
}
