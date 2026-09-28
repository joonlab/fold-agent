package kr.joonlab.foldagent

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kr.joonlab.foldagent.ui.FaTokens

/**
 * 화면 위쪽의 작은 상태 패널 — 지금 무엇을 하는지 · 정지 · 확인(실행/취소).
 * TYPE_ACCESSIBILITY_OVERLAY 라 「다른 앱 위에 그리기」 권한이 따로 필요 없다.
 * 메인 스레드에서만 호출할 것.
 *
 * 모양(DESIGN §1-5, Ember): 먹색 면 + 1dp 붉은 테두리, 반경 20dp, 알약 버튼. 왼쪽 숨쉬는 점은 테두리 빛과 같은 주기.
 * 확인 대기면 패널이 세로로 커져 확인 카드가 된다 — 머리가 호박 점으로 바뀌고, 취소가 넓고 실행은 테두리만(거부 쪽이 기본값처럼 보이게).
 * 판정(40초 무응답 = 거부)은 AgentService.confirm 이 한다. 여기 카운트다운은 표시일 뿐이다.
 *
 * 사람 개입(2026-09-28): 작업 중 「✋ 내가 할게」 → 멈춤 줄(💬 말하기 · ▶ 이어서). 상태는 AgentService.hitl 이 정본.
 * 끌어서 옮길 수 있다(버튼 위에서 시작해도 슬롭을 넘으면 끌기) — 옮긴 자리는 그 작업 동안만, 새 작업·접힘/펼침·회전이면 기본 자리.
 * 버튼이 아닌 곳을 탭하면 그 작업의 대화방(확인·선택 카드 중엔 안 연다 — 카드의 답이 먼저).
 */
class Overlay(private val svc: AgentService) {

    private val wm = svc.getSystemService(WindowManager::class.java)
    // 커버·펼침 화면의 밀도가 다를 수 있다 — 한 번 잡아 두지 말고 그때그때 읽는다
    private val d get() = svc.resources.displayMetrics.density
    // 오버레이는 남의 앱 위에 뜬다 — 앱 테마(라이트/다크)와 무관하게 다크 토큰 고정(시안 .d-ember 의 --ov*). 빛과도 같은 색 세계.
    private val pal = FaTokens.dark
    private val dimText = withAlpha(pal.overlayText, 0.68f)
    private val btnFace = 0x1AFFFFFF              // 중립 면(흰색 α.10, 시안 --ovBtn)
    private val accentOnOv = 0xFFFF7A7E.toInt()   // 먹색 위 강조 글자·테두리(시안 --accentOnOv) — #E5484D 는 먹색 위에서 대비가 모자라다
    private val noteColor = 0xFFFF8A8D.toInt()    // 보낸 방 한 줄 — 앱 다크 accentText(Theme.kt)와 같은 값

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private lateinit var dot: View
    private lateinit var label: TextView
    private lateinit var meta: TextView
    private lateinit var askBox: LinearLayout
    // 방법 고르기 카드(choose_way) — 확인 카드와 같은 자리·같은 머리, 버튼만 선택지. 시한이 지나면 추천안(판정은 AgentService.choose)
    private lateinit var chooseBox: LinearLayout
    private lateinit var chooseText: TextView
    private lateinit var chooseOpts: LinearLayout
    private lateinit var chooseTimerBar: TimerBar
    private lateinit var chooseTimerText: TextView
    private var choosing = false
    /** 「✎ 직접 답하기」를 누른 뒤 — 시한 막대를 멈추고 선택지를 접는다(아래서 올라오는 답하기 창을 가리지 않게) */
    private var chooseReplying = false
    private var onChooseReply: (() -> Unit)? = null
    /** 선택 카드의 답 — 0.. 고른 선택지 · -1 그만 · null 답 없이 닫힘(패널 닫힘·치움) */
    private var onChoose: ((Int?) -> Unit)? = null
    private lateinit var askText: TextView
    private lateinit var timerBar: TimerBar
    private lateinit var timerText: TextView
    private lateinit var stop: TextView
    private lateinit var expand: TextView
    private lateinit var noteView: TextView
    // 보낸 방 한 줄(note). 패널이 아직 없으면 보류했다가 처음 뜰 때 보인다(5초 지나면 버림)
    private var noteText = ""
    private var noteUntil = 0L          // uptime — 이 시각까지 보인다
    private var pendingNote: Pair<String, Long>? = null
    private var pendingAt = 0L
    private var fitTypesDefault = 0     // 세로 배치로 돌아갈 때 되돌릴 창 기본 fitInsetsTypes
    private var cutoutDefault = 0       // 끌기에서 바꾼 컷아웃 모드를 기본 자리로 돌아갈 때 되돌린다
    private var placedLand: Boolean? = null
    private val noteOff = Runnable { noteText = ""; if (::noteView.isInitialized) noteView.visibility = View.GONE }
    private var expanded = false
    private lateinit var rateRow: LinearLayout
    private var onRate: ((Boolean) -> Unit)? = null
    /** 확인 카드의 답 — true 실행 · false 거부(취소·■ 거부) · null 답 없이 닫힘(패널 닫힘·치움). 에이전트 확인은 null 을 거부로 본다 */
    private var onAnswer: ((Boolean?) -> Unit)? = null
    private var asking = false
    private var lastText = ""           // 확인 카드가 떠 있는 동안 들어온 상태 문장 — 카드가 닫히면 되돌린다
    private var askStartedAt = 0L
    private var askMs = CONFIRM_MS      // 이번 카드의 표시용 시한(원격 확인은 서버 대기 상한까지 남은 시간)
    private var dotAnim: ObjectAnimator? = null
    // ---- 사람 개입 — NONE · PENDING(지금 동작을 마치는 중) · PAUSED(사용자 차례)
    private enum class HitlUi { NONE, PENDING, PAUSED }
    private var hitlUi = HitlUi.NONE
    private lateinit var hand: TextView
    private lateinit var hitlBox: LinearLayout
    private lateinit var sayBtn: TextView
    private lateinit var resumeBtn: TextView
    // ---- 끌기·탭 — 옮긴 자리는 이번 작업 동안만(newTask·자세 바뀜이면 기본 자리)
    private var panelRoom: String? = null
    private var userPlaced = false
    private var dragging = false
    private var noDrag = false
    private var downX = 0f; private var downY = 0f; private var downAt = 0L
    private var dragFromX = 0; private var dragFromY = 0
    private val ticker = object : Runnable {
        override fun run() {
            if (root == null) return
            updateMeta()
            if (asking) updateTimer()
            if (asking || svc.busy) root?.postDelayed(this, if (asking) 250 else 1000)
        }
    }

    private fun dp(v: Int) = (v * d).toInt()

    private fun build(): LinearLayout {
        dot = View(svc).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.glow) } }
        label = TextView(svc).apply {
            setTextColor(pal.overlayText); textSize = 14f; maxLines = 3
            setLineSpacing(0f, 1.15f)
            // ⚠️ maxLines 와 maxHeight 는 서로를 덮어쓴다(마지막에 부른 쪽만 남음) — 펼칠 때만 maxHeight 로 바꾼다
            // 접힌 동안엔 터치를 받지 않는다 — 패널 탭(대화방)·끌기가 문장 위에서도 되게. 펼치면 스크롤(setExpanded)
        }
        meta = TextView(svc).apply {
            setTextColor(dimText); textSize = 11f; maxLines = 1
            fontFeatureSettings = "tnum"      // 경과 초가 바뀔 때 글자 폭이 흔들리지 않게
            visibility = View.GONE
        }
        noteView = TextView(svc).apply {
            setTextColor(noteColor); textSize = 11.5f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(18), 0, dp(6), dp(1))   // 18dp = 점(8)+간격(10) — 상태 문장 글머리에 맞춘다
            visibility = View.GONE
        }
        val textCol = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            addView(label); addView(meta)
        }
        expand = pill("펼치기", stroke = 0, fill = btnFace, text = pal.overlayText).apply {
            visibility = View.GONE; setOnClickListener { toggleExpand() }
        }
        // 정지는 어떤 상태에서든 반응해야 한다: 확인 대기면 거부 → 작업 중이면 멈춤 → 아무것도 없으면 닫기
        stop = pill("■ 정지", stroke = pal.glow, fill = Color.TRANSPARENT, text = pal.overlayText).apply {
            setOnClickListener {
                // 작업이 끝나고 답만 읽는 중이면 「■ 읽기 멈춤」 — 읽기만 멈추고 결과 패널은 남긴다
                if (!svc.busy && onAnswer == null && svc.speaking) { svc.stopSpeaking(); refreshButton(); return@setOnClickListener }
                svc.stopSpeaking()
                // 선택 카드 중엔 「■ 그만」 = 카드의 「그만」과 같다 — 작업은 모델이 finish 로 닫는다
                if (onChoose != null) { pick(-1); return@setOnClickListener }
                if (onAnswer != null) answer(false)
                if (svc.busy) svc.cancelTask() else hide()
            }
        }
        // 사람 개입 입구 — 작업 중이고 카드가 없을 때만 보인다(refreshButton)
        hand = pill("✋ 내가 할게", stroke = 0, fill = btnFace, text = pal.overlayText).apply {
            visibility = View.GONE
            setOnClickListener { if (!svc.pauseTask()) android.util.Log.i("FoldAgent", "hitl 멈춤 거절(카드 중이거나 작업 없음)") }
        }
        val top = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) })
            addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(expand, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
            addView(hand, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
            addView(stop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        }

        // ---- 확인 카드(ask) — 머리는 윗줄이 맡는다(호박 점 + 「확인이 필요해요」), 여기는 질문·남은 시간·버튼
        askText = TextView(svc).apply {
            // 14줄 — 8줄이면 답장 확인 창의 본문이 잘려 스크롤해야 보였다(2026-09-27 실기 T9: 218자 중 본문이 창 밖). 커버 화면에서도 카드가 화면 안에 든다
            setTextColor(pal.overlayText); textSize = 14f; maxLines = 14
            setLineSpacing(0f, 1.2f)
            movementMethod = android.text.method.ScrollingMovementMethod()   // 긴 질문(보낼 내용)은 카드 안에서 스크롤
        }
        timerBar = TimerBar()
        timerText = TextView(svc).apply { setTextColor(dimText); textSize = 11f; fontFeatureSettings = "tnum" }
        // 취소가 넓고(1.5) 중립 면, 실행은 강조색 테두리만 — 음성·빠른 탭 오조작이 잦아 거부 쪽을 기본값처럼 보이게(§1-5)
        val no = pill("취소", stroke = 0, fill = btnFace, text = pal.overlayText, radius = 12, bold = true).apply {
            setOnClickListener { answer(false) }
        }
        val yes = pill("실행", stroke = accentOnOv, fill = Color.TRANSPARENT, text = accentOnOv, radius = 12, bold = true).apply {
            setOnClickListener { answer(true) }
            // 실행은 기본 포커스를 받지 않는다(키·접근성 포커스가 먼저 가지 않게). 창 자체도 NOT_FOCUSABLE.
            isFocusable = false
        }
        val btns = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(no, LinearLayout.LayoutParams(0, dp(48), 1.5f))
            addView(yes, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        }
        askBox = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(10), dp(4), 0)
            addView(askText)
            addView(timerBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(12) })
            addView(timerText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(btns, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            visibility = View.GONE
        }

        // ---- 선택 카드(choose) — 질문 · 선택지 버튼(라벨 굵게 + 설명 작게, 추천안 강조) · 그만 · 남은 시간
        chooseText = TextView(svc).apply { setTextColor(pal.overlayText); textSize = 14f; maxLines = 3; setLineSpacing(0f, 1.2f) }
        chooseOpts = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        chooseTimerBar = TimerBar()
        chooseTimerText = TextView(svc).apply { setTextColor(dimText); textSize = 11f; fontFeatureSettings = "tnum" }
        val quit = pill("그만", stroke = 0, fill = btnFace, text = pal.overlayText, radius = 12, bold = true).apply { setOnClickListener { pick(-1) } }
        chooseBox = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(10), dp(4), 0)
            addView(chooseText)
            addView(chooseOpts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(chooseTimerBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(10) })
            addView(chooseTimerText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(quit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })
            visibility = View.GONE
        }

        // ---- 멈춤 줄(사용자 차례) — 말하기(중립) · 이어서(강조). 정지는 윗줄 ■ 그대로
        sayBtn = pill("💬 말하기", stroke = 0, fill = btnFace, text = pal.overlayText, radius = 12, bold = true).apply { setOnClickListener { svc.hitlSay() } }
        resumeBtn = pill("▶ 이어서", stroke = accentOnOv, fill = Color.TRANSPARENT, text = accentOnOv, radius = 12, bold = true).apply { setOnClickListener { svc.resumeTask() } }
        hitlBox = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(8), dp(4), 0)
            addView(sayBtn, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(resumeBtn, LinearLayout.LayoutParams(0, dp(48), 1.3f).apply { marginStart = dp(8) })
            visibility = View.GONE
        }

        val good = pill("잘했어 👍", stroke = 0, fill = btnFace, text = pal.overlayText).apply { setOnClickListener { rated(true) } }
        val bad = pill("틀렸어 👎", stroke = 0, fill = btnFace, text = pal.overlayText).apply { setOnClickListener { rated(false) } }
        rateRow = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(bad)
            addView(good, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }
        // 서비스엔 액티비티 콜백이 없다 — 접기·펴기·돌리기는 패널 창 자신의 설정 변경으로 받아 다시 배치한다
        return object : LinearLayout(svc) {
            override fun onConfigurationChanged(newConfig: Configuration) {
                super.onConfigurationChanged(newConfig)
                post { reposition() }
            }
            // 끌기: 버튼 위에서 시작해도 슬롭을 넘으면 가로챈다(버튼은 CANCEL 을 받아 안 눌린다). 스크롤하는 글(펼친 문장·긴 확인 글) 위에선 안 끈다
            override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> touchDown(e, this)
                    MotionEvent.ACTION_MOVE -> if (!dragging && !noDrag && moved(e)) beginDrag()
                }
                return dragging
            }
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> touchDown(e, this)
                    MotionEvent.ACTION_MOVE -> { if (!dragging && !noDrag && moved(e)) beginDrag(); if (dragging) dragTo(e) }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) { dragging = false; android.util.Log.i("FoldAgent", "panel moved x=${params?.x} y=${params?.y}") }
                        else if (!moved(e) && e.eventTime - downAt < ViewConfiguration.getLongPressTimeout()) panelTapped()
                    }
                    MotionEvent.ACTION_CANCEL -> dragging = false
                }
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = FaTokens.R.panel * d
                setColor(pal.overlayBg)
                setStroke(maxOf(1, dp(1)), pal.overlayLine)
            }
            addView(noteView); addView(top); addView(hitlBox); addView(askBox); addView(chooseBox); addView(rateRow)
            // 옮긴 자리에서 카드로 커지면(확인·선택·멈춤 줄) 화면 밖으로 넘치지 않게 안으로 당긴다
            addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (userPlaced && (b - t) != (ob - ot)) clampToScreen() }
        }
    }

    /**
     * 알약 버튼. 보이는 높이는 36dp 지만 위아래 4dp 를 터치 영역으로 더해 최소 44dp 를 지킨다(InsetDrawable).
     * 기본 Button 은 One UI 테마 그림자·대문자·최소폭이 붙어 패널이 두꺼워졌다.
     */
    private fun pill(txt: String, stroke: Int, fill: Int, text: Int, radius: Int = 999, bold: Boolean = false): TextView {
        val face = GradientDrawable().apply {
            cornerRadius = if (radius >= 999) 999f * d else radius * d
            setColor(fill)
            if (stroke != 0) setStroke(maxOf(1, dp(1)), stroke)
        }
        val mask = GradientDrawable().apply { cornerRadius = if (radius >= 999) 999f * d else radius * d; setColor(Color.WHITE) }
        val bg = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), face, mask)
        return TextView(svc).apply {
            this.text = txt
            setTextColor(text); textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) 700 else 600, false)   // 시안 알약 600 · 확인 버튼 700
            gravity = Gravity.CENTER
            minHeight = dp(44); minWidth = dp(44)
            setPadding(dp(14), 0, dp(14), 0)
            background = InsetDrawable(bg, 0, dp(4), 0, dp(4))
            isClickable = true
        }
    }

    private fun ensure(): Boolean {
        if (root != null) return true
        val v = build()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        fitTypesDefault = lp.fitInsetsTypes
        cutoutDefault = lp.layoutInDisplayCutoutMode
        placedLand = null
        place(lp)
        // 접근성 연결이 끊겼다 다시 붙는 사이에는 창 토큰이 없다(BadTokenException) — 앱을 죽이지 말고 패널만 건너뛴다
        try {
            wm.addView(v, lp)
        } catch (e: WindowManager.BadTokenException) {
            android.util.Log.w("FoldAgent", "overlay skipped: ${e.message}")
            return false
        } catch (e: IllegalStateException) {
            android.util.Log.w("FoldAgent", "overlay skipped: ${e.message}")
            return false
        }
        root = v; params = lp
        // 패널이 없을 때 들어온 보낸 방 한 줄 — 5초 안이면 지금 보인다
        pendingNote?.let { (t, ms) ->
            pendingNote = null
            if (SystemClock.uptimeMillis() - pendingAt <= 5000) note(t, ms)
        }
        return true
    }

    /**
     * 자세별 자리. 가로(폭>높이 — 커버 가로·펼침)면 오른쪽 위 모서리 폭 min(420dp, 0.5w), 세로면 가운데 위(현행).
     * 가로에선 fitInsetsTypes 를 0 으로 두고 상태 막대·컷아웃·오른쪽 막대 높이를 직접 더한다 — 창 기본 맞춤에 기대면
     * 세로의 y=36dp(화면 맨 위 기준으로 맞춘 값)와 기준이 달라질 수 있어 가로만 명시적으로 잡는다.
     * flags(NOT_TOUCHABLE 토글)는 건드리지 않는다 — 같은 params 객체의 자리 필드만 바꾼다.
     */
    private fun place(lp: WindowManager.LayoutParams): Boolean {
        val cfg = svc.resources.configuration
        val den = cfg.densityDpi / 160f
        val wPx = (cfg.screenWidthDp * den).toInt()
        val land = cfg.screenWidthDp > cfg.screenHeightDp
        if (placedLand == land && lp.width == widthFor(land, wPx)) return false
        placedLand = land
        userPlaced = false   // 자세가 바뀌면 옮긴 자리를 버리고 기본 자리(사용자 결정 2026-09-28)
        lp.width = widthFor(land, wPx)
        lp.layoutInDisplayCutoutMode = cutoutDefault
        if (land) {
            val ins = barInsets()
            lp.gravity = Gravity.TOP or Gravity.END
            lp.fitInsetsTypes = 0
            lp.x = dp(12) + ins.second
            lp.y = ins.first + dp(8)
        } else {
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.fitInsetsTypes = fitTypesDefault
            lp.x = 0
            lp.y = dp(36)
        }
        return true
    }

    private fun widthFor(land: Boolean, wPx: Int) =
        if (land) minOf(dp(420), (wPx * 0.5).toInt()) else minOf((wPx * 0.92).toInt(), dp(560))

    /** (위 여백, 오른쪽 여백) px — 상태 막대·컷아웃·오른쪽에 붙은 내비 막대. 보임 여부와 무관하게(전체 화면 앱에서도 당김 영역 비킴) */
    private fun barInsets(): Pair<Int, Int> = runCatching {
        val i = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.navigationBars())
        // 아래쪽 내비 막대는 위 여백과 무관 — top 은 상태 막대·컷아웃에서만 나온다
        i.top to i.right
    }.getOrElse { dp(28) to 0 }

    /** 회전·접힘 전환 뒤 다시 배치. 확인 카드·정지·touchable 상태는 그대로(같은 창·같은 params) */
    private fun reposition() {
        val v = root ?: return
        val lp = params ?: return
        if (!place(lp)) return
        if (expanded) setExpanded(true)   // 펼침 최대 높이(화면 절반)를 새 높이로
        runCatching { wm.updateViewLayout(v, lp) }
    }

    fun show(text: String) {
        if (!ensure()) return
        reposition()   // 설정 변경 콜백을 못 받은 경우의 보험 — 자세가 같으면 아무것도 안 한다
        lastText = text
        refreshButton()
        refreshDot()
        kick()
        // 확인 카드가 떠 있는 동안에는 윗줄이 카드 머리다 — 문장은 기억만 해 두고 카드가 닫히면 보인다
        // 사람 개입 중에도 윗줄은 「내 차례」 — 멈춤이 끝나면 마지막 문장으로 돌아간다
        if (asking || hitlUi != HitlUi.NONE) return
        label.text = text
        updateMeta()
        // 3줄을 넘길 만큼 길면 「펼치기」를 보인다(짧아지면 접힌 상태로 돌아간다)
        label.post {
            if (asking) return@post
            val long = label.layout != null && (label.layout.lineCount > 3 || text.length > 90)
            expand.visibility = if (long || expanded) View.VISIBLE else View.GONE
            if (!long && expanded) setExpanded(false)
        }
    }

    private fun toggleExpand() { if (!asking) setExpanded(!expanded) }

    private fun setExpanded(on: Boolean) {
        expanded = on
        // 접힘: 3줄 · 펼침: 화면 절반 높이까지(넘으면 패널 안에서 스크롤)
        if (on) label.maxHeight = (svc.resources.displayMetrics.heightPixels * 0.5).toInt() else label.maxLines = 3
        // 펼친 동안만 문장이 터치(스크롤)를 받는다 — 접힌 문장 위 탭은 패널 탭(대화방)·끌기
        label.movementMethod = if (on) android.text.method.ScrollingMovementMethod() else null
        if (!on) { label.isClickable = false; label.isLongClickable = false; label.isFocusable = false }
        label.scrollTo(0, 0)
        expand.text = if (on) "접기" else "펼치기"
    }

    fun refreshButton() {
        if (root == null) return
        stop.text = when {
            asking && choosing -> "■ 그만"
            asking -> "■ 거부"
            svc.busy -> "■ 정지"
            svc.speaking -> "■ 읽기 멈춤"
            else -> "닫기"
        }
        hand.visibility = if (svc.busy && !asking && hitlUi == HitlUi.NONE) View.VISIBLE else View.GONE
        refreshDot()
        kick()
    }

    fun hide() {
        // noteOff 도 치운다 — 남겨 두면 다음에 새로 만든 패널의 한 줄을 엉뚱하게 지운다
        root?.let { v -> v.removeCallbacks(ticker); v.removeCallbacks(noteOff); runCatching { wm.removeView(v) } }
        dotAnim?.cancel(); dotAnim = null
        root = null; params = null; onRate = null; expanded = false; asking = false; choosing = false; chooseReplying = false; onChooseReply = null
        hitlUi = HitlUi.NONE; panelRoom = null; userPlaced = false; dragging = false
        lastText = ""   // 다음에 뜬 패널이 지난 작업의 문장을 보이지 않게
        noteText = ""; noteUntil = 0L; placedLand = null
        // 확인 대기 중에 닫히면 거부로 풀어 준다(에이전트 스레드가 40초를 헛되이 기다리지 않게)
        onAnswer?.let { cb -> onAnswer = null; cb(null) }
        onChoose?.let { cb -> onChoose = null; cb(null) }   // 선택 카드는 null = 추천안(AgentService.choose)
    }

    /** 작업이 끝난 뒤 평가를 받는다. 누르지 않으면 그냥 사라진다(평가 없음). */
    fun rate(cb: (Boolean) -> Unit) {
        if (root == null) return
        rateRow.visibility = View.VISIBLE
        onRate = cb
    }

    private fun rated(good: Boolean) {
        val cb = onRate
        onRate = null
        rateRow.visibility = View.GONE
        cb?.invoke(good)
        label.text = if (good) "고마워요 — 이 경로를 기억할게요" else "알겠어요 — 이 경로는 참고하지 않을게요"
    }

    /**
     * @param context 카드가 닫힌 뒤 윗줄에 보일 지금 작업의 맥락. 주지 않으면 마지막 상태 문장 —
     *   채팅에서 시킨 실행은 패널 문장을 안 바꾸므로 지난 실행의 답이 남아 떴다(2026-09-28: 원격 확인마다 앞 PDF 답이 보임)
     */
    fun ask(question: String, timeoutMs: Long = CONFIRM_MS, context: String? = null, cb: (Boolean?) -> Unit) {
        if (root != null) rateRow.visibility = View.GONE
        if (context != null) lastText = context
        show(lastText)
        onAnswer = cb
        if (root == null) return   // 창을 못 띄웠다 — 답은 AgentService 의 40초 시한이 거부로 푼다(이전과 같음)
        askText.text = question
        askText.scrollTo(0, 0)
        askStartedAt = SystemClock.uptimeMillis()
        askMs = timeoutMs.coerceAtLeast(1_000L)
        choosing = false
        setAskMode(true)
    }

    /**
     * 화면이 켜졌다·잠금이 풀렸다 — 확인 카드가 떠 있어야 하는데 안 보일 수 있다(화면이 꺼지며 창이 떨어지거나 잠금 화면 아래로 감).
     * 붙어 있든 떨어졌든 떼었다 다시 붙여 맨 위로. 로그의 attached 로 어느 쪽이었는지 가린다
     */
    fun reattachIfAsking(why: String) {
        val v = root ?: return
        val lp = params ?: return
        if (!asking) return
        val was = v.isAttachedToWindow
        if (was) runCatching { wm.removeViewImmediate(v) }
        val ok = runCatching { wm.addView(v, lp) }.isSuccess
        android.util.Log.i("FoldAgent", "overlay reattach($why) attached=$was → ${if (ok) "ok" else "실패"}")
        if (ok) kick()
    }

    fun clearAsk() {
        if (root != null) setAskMode(false)
        onAnswer?.invoke(null)
        onAnswer = null
        onChoose?.invoke(null)
        onChoose = null
        onChooseReply = null
    }

    /**
     * 방법 고르기 카드. 확인 카드와 같은 자리에 뜨고 같은 규칙(채팅 화면이 앞이어도 뜬다)을 따른다.
     * @param options (라벨, 설명) 2~3개 · @param recommended 강조할 선택지
     * 모델이 준 선택지 뒤에 늘 「✎ 직접 답하기」를 붙인다 — 누르면 onReply(답하기 창 열기)만 부르고 카드는 그대로 답을 기다린다.
     * @param cb 0.. 고른 것 · -1 그만 · null 답 없이 닫힘. 시한 판정은 AgentService.choose — 여기 카운트다운은 표시일 뿐
     * @return 창을 띄웠나(못 띄웠으면 cb 는 부르지 않는다 — 부르는 쪽이 곧바로 추천안으로)
     */
    fun choose(question: String, options: List<Pair<String, String>>, recommended: Int, timeoutMs: Long, context: String?,
               onReply: () -> Unit, cb: (Int?) -> Unit): Boolean {
        if (root != null) rateRow.visibility = View.GONE
        if (context != null) lastText = context
        show(lastText)
        if (root == null) return false
        onChoose = cb
        chooseText.text = question
        chooseOpts.removeAllViews()
        options.forEachIndexed { i, (label, detail) ->
            chooseOpts.addView(optionButton(label, detail, i == recommended) { pick(i) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = dp(6) })
        }
        chooseOpts.addView(optionButton("✎ 직접 답하기", "말하거나 써서 답해요", false) { startReply() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        onChooseReply = onReply
        chooseReplying = false
        chooseOpts.visibility = View.VISIBLE
        askStartedAt = SystemClock.uptimeMillis()
        askMs = timeoutMs.coerceAtLeast(1_000L)
        choosing = true
        setAskMode(true)
        return true
    }

    /** 「✎ 직접 답하기」 — 시한 막대를 멈추고 선택지를 접은 뒤 답하기 창을 연다(답·시한 판정은 AgentService.choose) */
    private fun startReply() {
        if (onChoose == null || chooseReplying) return
        chooseReplying = true
        chooseOpts.visibility = View.GONE
        chooseTimerText.text = "✎ 직접 답을 기다려요 · 창을 닫으면 추천안으로"
        onChooseReply?.invoke()
    }

    private fun pick(i: Int) {
        val cb = onChoose ?: return
        onChoose = null
        onChooseReply = null
        if (root != null) setAskMode(false)
        cb(i)
    }

    /** 선택지 버튼 — 라벨 굵게 + 설명 작게. 추천안은 강조색 테두리·글자 + 「★추천」 */
    private fun optionButton(label: String, detail: String, rec: Boolean, onClick: () -> Unit): View {
        val face = GradientDrawable().apply {
            cornerRadius = 12 * d
            setColor(btnFace)
            if (rec) setStroke(maxOf(1, dp(1)), accentOnOv)
        }
        val mask = GradientDrawable().apply { cornerRadius = 12 * d; setColor(Color.WHITE) }
        return LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), face, mask)
            minimumHeight = dp(48)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            addView(TextView(svc).apply {
                text = if (rec) "$label  ★추천" else label
                setTextColor(if (rec) accentOnOv else pal.overlayText); textSize = 14f; maxLines = 2
                typeface = Typeface.create(Typeface.DEFAULT, 700, false)
            })
            if (detail.isNotBlank()) addView(TextView(svc).apply {
                text = detail
                setTextColor(dimText); textSize = 12f; maxLines = 2
            })
            setOnClickListener { onClick() }
        }
    }

    fun setVisible(visible: Boolean) {
        root?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    fun setTouchable(touchable: Boolean) {
        val v = root ?: return
        val lp = params ?: return
        lp.flags = if (touchable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun answer(ok: Boolean) {
        val cb = onAnswer
        onAnswer = null
        if (root != null) setAskMode(false)
        cb?.invoke(ok)
    }

    // ---- 확인 카드 · 숨쉬는 점 · 단계/경과 표시

    /** 확인 카드 켜고 끄기. 켜면 윗줄이 카드 머리(호박 점 + 「확인이 필요해요」 15 Bold)가 된다. */
    private fun setAskMode(on: Boolean) {
        asking = on
        if (on) {
            if (expanded) setExpanded(false)
            label.text = if (choosing) "어떻게 할까요" else "확인이 필요해요"
            label.textSize = 15f
            label.setTypeface(label.typeface, Typeface.BOLD)
            meta.visibility = View.GONE
            expand.visibility = View.GONE
            noteView.visibility = View.GONE   // 확인 카드 중엔 질문에만 눈이 가게
            hitlBox.visibility = View.GONE
            askBox.visibility = if (choosing) View.GONE else View.VISIBLE
            chooseBox.visibility = if (choosing) View.VISIBLE else View.GONE
            updateTimer()
        } else {
            choosing = false
            chooseReplying = false
            askBox.visibility = View.GONE
            chooseBox.visibility = View.GONE
            label.textSize = 14f
            label.typeface = Typeface.DEFAULT
            label.text = when (hitlUi) { HitlUi.PAUSED -> PAUSED_TEXT; HitlUi.PENDING -> PENDING_TEXT; else -> lastText }
            if (hitlUi == HitlUi.PAUSED) hitlBox.visibility = View.VISIBLE
            updateMeta()
            // 카드가 닫혔을 때 보낸 방 한 줄 시간이 남아 있으면 되살린다
            if (noteText.isNotEmpty() && SystemClock.uptimeMillis() < noteUntil) noteView.visibility = View.VISIBLE
        }
        refreshButton()
    }

    private fun updateTimer() {
        val left = (askMs - (SystemClock.uptimeMillis() - askStartedAt)).coerceAtLeast(0)
        val sec = ((left + 999) / 1000).toInt()
        if (choosing) {
            if (chooseReplying) return   // 답하기 창이 떠 있는 동안 막대는 멈춘 그대로(시한은 AgentService 가 60초로 바꿨다)
            chooseTimerBar.fraction = left.toFloat() / askMs
            val t = if (sec > 0) "${sec}초 뒤 추천안으로 진행해요" else "곧 추천안으로"
            if (chooseTimerText.text.toString() != t) chooseTimerText.text = t
            return
        }
        timerBar.fraction = left.toFloat() / askMs
        val t = if (sec >= 120) "${sec / 60}분 뒤 자동으로 취소해요" else if (sec > 0) "${sec}초 뒤 자동으로 취소해요" else "곧 자동 취소"
        if (timerText.text.toString() != t) timerText.text = t
    }

    private fun updateMeta() {
        if (root == null || asking) return
        val started = svc.startedAt
        val h = svc.hitl
        val t = if (hitlUi == HitlUi.PAUSED && h != null && h.ackAt > 0) {
            val sec = ((SystemClock.uptimeMillis() - h.ackAt) / 1000).coerceAtLeast(0)
            val el = if (sec >= 60) "${sec / 60}분 ${sec % 60}초" else "${sec}초"
            "⏸ ${el}째 · 조작 ${h.eventCount()} · 말 ${h.saidCount()} · 10분이면 멈춤"
        } else if (hitlUi == HitlUi.PENDING) "" else if (svc.busy && started > 0L) {
            val sec = ((System.currentTimeMillis() - started) / 1000).coerceAtLeast(0)
            val el = if (sec >= 60) "${sec / 60}분 ${sec % 60}초" else "${sec}초"
            if (svc.stepCount > 0) "${svc.stepCount}단계 · $el" else el
        } else ""
        meta.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
        if (meta.text.toString() != t) meta.text = t
    }

    /** 1초(확인 중엔 0.25초)마다 경과·남은 시간을 고친다. 작업도 확인도 없으면 스스로 멈춘다. */
    private fun kick() {
        val v = root ?: return
        v.removeCallbacks(ticker)
        if (asking || svc.busy) v.postDelayed(ticker, if (asking) 250 else 1000)
    }

    /** 점: 작업 중 = 붉은 숨쉬기(빛과 같은 주기) · 확인 대기 = 호박 고정 · 유휴 = 회색 고정 */
    private fun refreshDot() {
        if (root == null) return
        val (color, breathe) = when {
            asking -> pal.glowWait to false
            hitlUi != HitlUi.NONE -> pal.stop to false   // 에이전트는 쉬는 중 — 사용자 차례
            svc.busy -> pal.glow to true
            else -> pal.stop to false
        }
        (dot.background as? GradientDrawable)?.setColor(color)
        val reduced = !ValueAnimator.areAnimatorsEnabled() ||
            Settings.Global.getFloat(svc.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        if (breathe && !reduced) {
            if (dotAnim?.isRunning == true) return
            val half = (Prefs.glowPeriodMs(svc).takeIf { it >= 600 } ?: FaTokens.GLOW_PERIOD_MS) / 2
            dotAnim = ObjectAnimator.ofFloat(dot, View.ALPHA, 0.35f, 1f).apply {
                duration = half
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
                start()
            }
        } else {
            dotAnim?.cancel(); dotAnim = null
            dot.alpha = 1f
        }
    }

    /** 남은 시간 막대 — 호박색이 오른쪽에서 줄어든다 */
    private inner class TimerBar : View(svc) {
        var fraction = 1f
            set(v) { if (field != v) { field = v; invalidate() } }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4D7F7F7F }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.glowWait }
        private val rect = RectF()
        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat(); val rad = h / 2
            rect.set(0f, 0f, width.toFloat(), h); canvas.drawRoundRect(rect, rad, rad, track)
            rect.set(0f, 0f, width * fraction.coerceIn(0f, 1f), h); canvas.drawRoundRect(rect, rad, rad, fill)
        }
    }

    private fun withAlpha(c: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    companion object {
        /** 표시용 카운트다운 기본값. 판정 시한은 AgentService.confirm 의 latch.await 가 정본 — 둘이 어긋나도 판정은 그쪽 */
        const val CONFIRM_MS = 40_000L
        private const val PENDING_TEXT = "✋ 지금 동작만 마치고 넘겨드릴게요…"
        private const val PAUSED_TEXT = "⏸ 내 차례 — 화면을 직접 조작하거나 💬 말해 주세요. 끝나면 ▶ 이어서"
    }

    // ---- 사람 개입 표시(상태 정본은 AgentService.hitl)

    /** 멈춤 요청 — 도구가 도는 중이라 그 한 동작을 마치는 동안 */
    fun hitlPending() {
        if (!ensure()) return
        hitlUi = HitlUi.PENDING
        if (!asking) { if (expanded) setExpanded(false); expand.visibility = View.GONE; label.text = PENDING_TEXT; updateMeta() }
        refreshButton()
    }

    /** 사용자 차례 — 멈춤 줄(💬 말하기 · ▶ 이어서)과 경과를 보인다. 말을 받을 때마다 다시 불러 수를 고친다 */
    fun hitlPaused() {
        if (!ensure()) return
        hitlUi = HitlUi.PAUSED
        if (!asking) {
            if (expanded) setExpanded(false)
            expand.visibility = View.GONE
            label.text = PAUSED_TEXT
            hitlBox.visibility = View.VISIBLE
            updateMeta()
        }
        refreshButton()
    }

    /** 이어서·정지·시한 — 원래 상태 문장으로 */
    fun hitlEnd() {
        if (hitlUi == HitlUi.NONE) return
        hitlUi = HitlUi.NONE
        if (root == null) return
        hitlBox.visibility = View.GONE
        if (!asking) { label.text = lastText; updateMeta() }
        refreshButton()
    }

    /** 새 작업 — 옮긴 자리를 버리고 기본 자리로, 패널 탭은 이 방으로 */
    fun newTask(room: String?) {
        panelRoom = room
        hitlUi = HitlUi.NONE
        if (root != null) hitlBox.visibility = View.GONE
        if (!userPlaced) return
        val v = root ?: return
        val lp = params ?: return
        placedLand = null
        place(lp)
        runCatching { wm.updateViewLayout(v, lp) }
    }

    // ---- 끌기·탭

    private fun touchDown(e: MotionEvent, v: View) {
        downX = e.rawX; downY = e.rawY; downAt = e.eventTime
        dragging = false
        // 스크롤하는 글 위에서 시작한 움직임은 그 글의 스크롤이다
        noDrag = (expanded && hit(label, e)) ||
            (asking && !choosing && hit(askText, e) && (askText.canScrollVertically(1) || askText.canScrollVertically(-1)))
    }

    private fun hit(v: View, e: MotionEvent): Boolean {
        if (v.visibility != View.VISIBLE || !v.isShown) return false
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        return e.rawX >= loc[0] && e.rawX < loc[0] + v.width && e.rawY >= loc[1] && e.rawY < loc[1] + v.height
    }

    private fun moved(e: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(svc).scaledTouchSlop
        val dx = e.rawX - downX; val dy = e.rawY - downY
        return dx * dx + dy * dy > slop * slop
    }

    /** 끌기 시작 — 지금 화면 위치를 절대 좌표(TOP|START, 인셋 맞춤 없음)로 바꿔 둔다. 기본 자리의 gravity 기준이 자세마다 달라서 */
    private fun beginDrag() {
        val v = root ?: return
        val lp = params ?: return
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.fitInsetsTypes = 0
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.x = loc[0]; lp.y = loc[1]
        dragFromX = lp.x; dragFromY = lp.y
        dragging = true; userPlaced = true
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun dragTo(e: MotionEvent) {
        val lp = params ?: return
        lp.x = dragFromX + (e.rawX - downX).toInt()
        lp.y = dragFromY + (e.rawY - downY).toInt()
        clampToScreen(force = true)
    }

    /** 화면 밖으로 못 나가게 — 창 크기가 바뀌었을 때(카드로 커짐)도 부른다 */
    private fun clampToScreen(force: Boolean = false) {
        val v = root ?: return
        val lp = params ?: return
        val b = runCatching { wm.currentWindowMetrics.bounds }.getOrNull() ?: return
        val nx = lp.x.coerceIn(0, maxOf(0, b.width() - v.width))
        val ny = lp.y.coerceIn(0, maxOf(0, b.height() - v.height))
        if (!force && nx == lp.x && ny == lp.y) return
        lp.x = nx; lp.y = ny
        runCatching { wm.updateViewLayout(v, lp) }
    }

    /** 버튼이 아닌 곳을 짧게 탭 — 그 작업의 대화방. 확인·선택 카드 중엔 열지 않는다(카드의 답이 먼저) */
    private fun panelTapped() {
        if (asking) return
        svc.openRoom(panelRoom)
    }

    /**
     * 보낸 직후 「어느 방에 들어갔나」 한 줄(RoomChoice.sentLabel) — 상태 문장 위에 작게, ms 동안 보였다 사라진다.
     * 패널이 아직 없으면 보류해 두었다가 처음 뜰 때 보인다(5초 넘으면 버림). 확인 카드 중엔 숨긴다(끝나고 시간이 남았으면 다시).
     * 글자는 받은 그대로(↳ 포함) — 방 이름 모양은 RoomChoice 가 정본.
     */
    fun note(text: String, ms: Long = 2500) {
        if (text.isBlank()) return
        val v = root
        if (v == null) { pendingNote = text to ms; pendingAt = SystemClock.uptimeMillis(); return }
        noteText = text
        noteUntil = SystemClock.uptimeMillis() + ms
        noteView.text = text
        noteView.visibility = if (asking) View.GONE else View.VISIBLE
        v.removeCallbacks(noteOff)
        v.postDelayed(noteOff, ms)
    }
}
