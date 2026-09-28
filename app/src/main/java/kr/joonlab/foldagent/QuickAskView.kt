package kr.joonlab.foldagent

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kr.joonlab.foldagent.records.Records
import kr.joonlab.foldagent.records.RoomChoice
import kr.joonlab.foldagent.ui.FaTokens
import org.json.JSONObject

/**
 * 빠른 입력 카드(DESIGN §1-4, 계약 §4-4) — 다른 앱 위에 아래서 올라오는 입력 카드 하나.
 * View 로 만든다: 어디에 꽂히든(지금은 QuickAskActivity 하나, 나중엔 어시스턴트 세션 창도) 같은 모양으로 뜨게(§3-1).
 * 카드 자체만 그린다 — 화면 위치(아래 여백·폭)·열고 닫는 움직임은 Host(액티비티) 몫.
 *
 * 구성: [접근성 꺼짐 안내] · [답하기 질문] · 대화방 칩(RoomChoice) · [공유받은 파일 칩] · 입력줄(표식·입력칸/듣는 중 파형·마이크·보내기) · [작업 중 줄] · 안내 한 줄.
 * 답하기 모드(choose_way 「✎ 직접 답하기」, [enterReply]) — 새 작업이 아니라 떠 있는 선택 카드에 답한다: 질문 한 줄을 머리에,
 * 대화방·파일 칩과 「기록 보기」는 숨기고, 에이전트가 돌고 있어도(그 실행이 답을 기다린다) 작업 중 줄 대신 입력줄을 보인다.
 * 모든 호출은 메인 스레드.
 */
class QuickAskView(ctx: Context, private val host: Host) : FrameLayout(ctx) {

    interface Host {
        /** 닫기(카드 밖 탭·뒤로·기록 보기) — 듣는 중이면 이미 취소된 뒤에 불린다 */
        fun dismiss()
        /** 보내기 — 키보드는 이미 내렸다. meta 에 source 는 Host 가 넣는다 */
        fun submit(text: String, meta: JSONObject)
    }

    private val pal = FaTokens.of(ctx)
    private val isDark = pal === FaTokens.dark
    private val ui = Handler(Looper.getMainLooper())
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dp(v: Int) = dp(v.toFloat()).toInt()

    // 대화방 칩(2026-09-27 사용자 결정 A안) — 하나만 고른다. 아직 안 읽혔으면 null(Host 가 제출 때 같은 규칙으로 기본값을 다시 잰다)
    private var rooms: RoomChoice.Choice? = null
    private var roomSel = 0
    /** 지금 고른 대화방 칩 — 칩을 아직 못 읽었으면 null */
    val selectedRoom: RoomChoice.Option? get() = rooms?.options?.getOrNull(roomSel)
    // 키보드가 떠서 남은 높이가 작을 때 Host 가 칩 줄을 접는다(커버 가로 등) — busy 와 따로 기억해야 서로 덮어쓰지 않는다
    private var chipsSuppressed = false
    /** 반접기 테이블톱에서 Host 가 주는 카드 최대 높이(px). 0 = 제한 없음 */
    var maxHeightPx = 0
        set(v) { if (field != v) { field = v; requestLayout() } }

    /** 답하기 모드의 선택 요청 id(AgentService.choose) — null 이면 보통 빠른 입력. 보내기는 Host 가 이 값으로 가른다 */
    var replyTo: String? = null
        private set

    // 음성으로 들어온 글인가 — 보낼 때 stt 메타(후보·신뢰도)를 같이 싣는다. 글자를 고쳐도 원 전사 기록은 남긴다
    private var usedVoice = false
    // 「다 말했다」(마이크·보내기·두 번째 제스처)를 받았다 — 최종 결과가 오면 곧바로 보낸다
    private var submitOnFinal = false

    /** 마이크 권한 창을 띄우기 직전 — Host 가 그동안 onStop 으로 닫히지 않게 표시해 둔다 */
    var onRequestingPermission: (() -> Unit)? = null

    // ⚠️ init 블록(refreshMic)이 voice 를 처음 만든다 — 콜백은 그보다 먼저 선언돼 있어야 null 이 안 넘어간다
    private val voiceCb = object : VoiceInput.Callback {
        override fun onState(listening: Boolean, ready: Boolean) {
            refreshMic()
            setWave(listening && ready && !voice.finishing)
            if (listening) showHeard()
        }
        override fun onPartial(text: String) = showHeard()
        override fun onFinal(text: String, alts: List<String>, conf: FloatArray?) {
            refreshMic(); setWave(false)
            if (text.isBlank()) {
                submitOnFinal = false
                showHint("못 알아들었어요 — 다시 말하거나 글로 써 주세요", true)
                return
            }
            usedVoice = true
            edit.setText(text); edit.setSelection(edit.text.length)
            if (submitOnFinal) { submitOnFinal = false; onSend() }
        }
        override fun onError(msg: String) {
            submitOnFinal = false
            refreshMic(); setWave(false)
            showHint(msg, true)
        }
    }

    private val voice: VoiceInput by lazy { VoiceInput(context, voiceCb) }

    // ---------------------------------------------------------------- 뷰

    private val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private val noticeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = GONE }
    private val replyHead = TextView(ctx)
    private val chipScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; visibility = GONE }
    private val chipRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    // 공유받은 파일 칩(계약 INPUTS §1) — 보내기 때 먼저 올리고, 다 올라가야 실행한다
    private val files = ArrayList<Uploads.Pending>()
    private val fileScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; visibility = GONE }
    private val fileRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private var uploading = false
    @Volatile private var uploadCancelled = false

    private val inputRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val edit = EditText(ctx)
    private val listenBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = GONE }
    private val waveRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val waveBars = ArrayList<View>()
    private val waveAnims = ArrayList<ObjectAnimator>()
    private val heard = TextView(ctx)
    private val mic = ImageView(ctx)
    private val send = ImageView(ctx)

    private val busyRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = GONE }
    private val busyText = TextView(ctx)
    private val stopBtn = TextView(ctx)

    private val hintLeft = TextView(ctx)
    private val hintRight = TextView(ctx)

    private val HINT = "한 번 더 핀치 인 = 말하기 끝"

    init {
        background = GradientDrawable().apply {
            cornerRadius = dp(26f); setColor(pal.raised)
            // 시안 .pop 의 가는 테두리(--popLine) — 다른 앱 화면 위에서 카드 가장자리가 묻히지 않게
            setStroke(dp(1), if (isDark) 0x10FFFFFF else 0x14000000)
        }
        elevation = dp(12f)
        clipToOutline = true
        isClickable = true            // 카드 안 탭이 뒤(=카드 밖 탭 = 닫기)로 새지 않게
        setPadding(dp(12), dp(12), dp(12), dp(10))
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        buildNotice()
        replyHead.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(pal.text); maxLines = 3; visibility = GONE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        col.addView(replyHead, rowLp())
        chipScroll.addView(chipRow)
        col.addView(chipScroll, rowLp())
        fileScroll.addView(fileRow)
        col.addView(fileScroll, rowLp(dp(6)))
        buildInputRow()
        buildBusyRow()
        buildHint()
    }

    private fun rowLp(top: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = top }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun pill(bg: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(999f); setColor(bg); if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun buildNotice() {
        val t = text(13f, pal.warn).apply { text = "접근성 서비스를 먼저 켜 주세요" }
        noticeRow.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val b = text(13f, pal.text, bold = true).apply {
            text = "설정 열기"; gravity = Gravity.CENTER
            background = pill(pal.panel2); setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener {
                runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        noticeRow.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)))
        col.addView(noticeRow, rowLp())
    }

    private fun buildInputRow() {
        inputRow.addView(MarkView(context), LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(9) })

        val mid = FrameLayout(context)
        edit.apply {
            background = null
            setPadding(0, dp(8), 0, dp(8))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(pal.text); setHintTextColor(pal.dim)
            hint = "무엇을 해 드릴까요?"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1; maxLines = 4
            isVerticalScrollBarEnabled = true
            textCursorDrawable = GradientDrawable().apply { setColor(pal.accent); setSize(dp(2), dp(20)) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { if (s.isNullOrBlank()) usedVoice = false; refreshSend() }
            })
        }
        mid.addView(edit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))

        // 듣는 중: 입력칸 자리에 파형 막대 + 부분 결과. 전사를 누르면 듣기를 멈추고 그 글을 고칠 수 있게 입력칸으로
        repeat(7) { k ->
            val b = View(context).apply { background = pill(pal.accent); pivotY = dp(9f); scaleY = 0.3f }
            waveRow.addView(b, LinearLayout.LayoutParams(dp(3), dp(18)).apply { if (k > 0) leftMargin = dp(3) })
            waveBars += b
        }
        listenBox.addView(waveRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(18)).apply { bottomMargin = dp(4) })
        heard.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(pal.text); maxLines = 4; ellipsize = TextUtils.TruncateAt.START }
        listenBox.addView(heard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        listenBox.setPadding(0, dp(4), 0, dp(4))
        listenBox.setOnClickListener { editTranscript() }
        mid.addView(listenBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        inputRow.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        mic.apply {
            setImageResource(R.drawable.qa_mic); scaleType = ImageView.ScaleType.CENTER
            contentDescription = "말하기"
            setOnClickListener { if (uploading) return@setOnClickListener; if (voice.active) doneSpeaking() else startListening() }
        }
        inputRow.addView(mic, LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(6) })
        send.apply {
            setImageResource(R.drawable.qa_send); scaleType = ImageView.ScaleType.CENTER
            contentDescription = "보내기"
            setOnClickListener { onSend() }
        }
        inputRow.addView(send, LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(8) })
        col.addView(inputRow, rowLp())
        refreshMic(); refreshSend()
    }

    private fun buildBusyRow() {
        val dot = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.glow) } }
        busyRow.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { leftMargin = dp(8); rightMargin = dp(12) })
        busyText.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(pal.text); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        busyRow.addView(busyText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        stopBtn.apply {
            text = "정지"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(pal.onAccent)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER; background = pill(pal.accent); setPadding(dp(12), 0, dp(16), 0)
            val ic = context.getDrawable(R.drawable.qa_stop)!!.mutate().apply { setTint(pal.onAccent); setBounds(0, 0, dp(18), dp(18)) }
            setCompoundDrawables(ic, null, null, null); compoundDrawablePadding = dp(4)
            contentDescription = "작업 정지"
            // 상태 패널의 정지와 같은 cancelTask() — 확인 게이트가 대기 중이면 AgentService.cancelTask() 가 거부로 푼다
            // 누른 뒤엔 잠깐(멈추는 중… 보여 줄 만큼)만 두고 닫는다 — 아래 busyCloser 설명
            setOnClickListener { AgentService.instance?.cancelTask(); busyText.text = "멈추는 중…"; isEnabled = false; alpha = 0.5f; armBusyClose(STOPPED_LINGER_MS) }
        }
        busyRow.addView(stopBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        busyRow.minimumHeight = dp(48)
        col.addView(busyRow, rowLp())
    }

    private fun buildHint() {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, 0, 0) }
        hintLeft.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(pal.dim); text = HINT; maxLines = 2 }
        row.addView(hintLeft, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        hintRight.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(pal.dim); text = "기록 보기 ›"
            setPadding(dp(10), dp(6), dp(4), dp(6))   // 글자는 작아도 누르는 자리는 넉넉히
            setOnClickListener { openRecords() }
        }
        row.addView(hintRight)
        col.addView(row, rowLp(dp(8)))
    }

    // ---------------------------------------------------------------- 상태 갱신

    private var busyMode = false
    /*
     * 작업 중 카드는 오래 두지 않는다(리뷰 지적, 2026-09-26). 이 카드는 포커스를 가진 불투명하지 않은 액티비티라
     * 떠 있는 동안 ①ScreenReader.read 의 rootInActiveWindow 가 뒤 앱이 아니라 이 카드(kr.joonlab.foldagent)를 읽고
     * ②에이전트의 탭(좌표 제스처)이 카드나 전면 root(탭 = 닫기)에 떨어져 삼켜진다 → 한 단계를 잘못 판단하거나 헛친다.
     * FLAG_NOT_TOUCHABLE·NOT_FOCUSABLE 로 비키면 정지 버튼도 못 누른다. 그래서 작업 중 화면은 「보고 필요하면 정지」만 하는
     * 짧은 창으로: 손을 안 대면 BUSY_LINGER_MS 뒤, 정지를 누르면 STOPPED_LINGER_MS 뒤 스스로 닫는다. 카드를 만지면 다시 잰다.
     * 정지는 상태 패널에도 항상 있으니(DESIGN 안전 불변식 2) 닫혀도 멈출 길은 남는다. 이 짧은 창 동안의 간섭은 남는다 — 실기 확인 항목.
     */
    private val busyCloser = Runnable { if (busyMode) host.dismiss() }
    private fun armBusyClose(ms: Long) { ui.removeCallbacks(busyCloser); ui.postDelayed(busyCloser, ms) }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 작업 중 카드를 만지는 동안(정지 누르려는 참)은 닫지 않는다 — 정지를 이미 눌렀으면 그 짧은 시간을 그대로 둔다
        if (busyMode && stopBtn.isEnabled && ev.actionMasked == MotionEvent.ACTION_DOWN) armBusyClose(BUSY_LINGER_MS)
        return super.dispatchTouchEvent(ev)
    }
    // 서비스 연결·작업 중 여부는 뜨는 동안 바뀔 수 있다(설정에서 켜고 돌아옴·작업이 끝남) — 0.5초마다 본다
    private val ticker = object : Runnable {
        override fun run() { refreshState(); ui.postDelayed(this, 500) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ui.post(ticker)
        if (hasMic()) voice.prewarm()
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(busyCloser)
        super.onDetachedFromWindow()
    }

    /** 서비스 없음 → 안내 줄 · 작업 중 → 입력 대신 「작업 중 — …」+ 정지 · 그 밖 → 입력 */
    fun refreshState() {
        val svc = AgentService.instance
        noticeRow.visibility = if (svc == null) VISIBLE else GONE
        // 답하기 모드는 작업 중이어도 입력줄 — 도는 실행이 바로 이 답을 기다린다
        val busy = svc?.busy == true && replyTo == null
        if (busy != busyMode) {
            busyMode = busy
            if (busy) { if (voice.active) voice.cancel(quiet = true); submitOnFinal = false; hideIme(); armBusyClose(BUSY_LINGER_MS) }
            else ui.removeCallbacks(busyCloser)
            inputRow.visibility = if (busy) GONE else VISIBLE
            fileScroll.visibility = if (!busy && replyTo == null && files.isNotEmpty()) VISIBLE else GONE
            refreshChipsVisible()
            busyRow.visibility = if (busy) VISIBLE else GONE
            stopBtn.isEnabled = true; stopBtn.alpha = 1f
            hintLeft.text = if (busy) "작업은 뒤에서 계속돼요" else if (replyTo != null) REPLY_HINT else HINT
            hintLeft.setTextColor(pal.dim)
        }
        if (busy && stopBtn.isEnabled) busyText.text = "작업 중 — " + Plain.of(svc!!.lastStatus).lineSequence().firstOrNull().orEmpty().ifBlank { "…" }
    }

    /**
     * 대화방 칩: [이어서·현재 방](1분 안일 때만) [＋ 새 대화] [최근 방 3개] — 규칙은 RoomChoice 한 곳(대시보드 입력줄과 같음).
     * 목록 읽기는 IO 라 뒤에서. 말하는 중에 칩을 바꿔도 듣기는 그대로 — 칩은 포커스·음성에 손대지 않는다.
     */
    fun loadChips() {
        Records.init(context)
        Thread({
            val c = runCatching { RoomChoice.build(Records.store, Prefs.continueWindowSec(context) * 1000L) }.getOrNull() ?: return@Thread
            ui.post {
                rooms = c; roomSel = c.defaultIndex
                chipRow.removeAllViews()
                c.options.forEachIndexed { i, o -> chipRow.addView(chip(o.label).apply { setOnClickListener { pickRoom(i) } }) }
                styleRooms()
                refreshChipsVisible()
                scrollToRoom()
            }
        }, "quickask-chips").start()
    }

    private fun chip(label: String) = text(13f, pal.text).apply {
        text = label; gravity = Gravity.CENTER; maxLines = 1
        background = pill(pal.panel2); setPadding(dp(12), 0, dp(12), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply { rightMargin = dp(6) }
    }

    private fun pickRoom(i: Int) {
        if (i == roomSel) return
        roomSel = i; styleRooms(); scrollToRoom()
    }

    /** 고른 칩 = accent 로 채움 + ✓ + 굵게, 나머지 = panel2. 글자 길이가 바뀌니(✓) 스크롤은 배치 뒤에 맞춘다 */
    private fun styleRooms() {
        val opts = rooms?.options ?: return
        for (k in 0 until chipRow.childCount) {
            val v = chipRow.getChildAt(k) as TextView
            val o = opts.getOrNull(k) ?: continue
            val on = k == roomSel
            v.text = if (on) "✓ ${o.label}" else o.label
            v.setTextColor(if (on) pal.onAccent else pal.text)
            v.typeface = Typeface.create(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
            v.background = pill(if (on) pal.accent else pal.panel2)
            v.contentDescription = (if (on) "선택됨: " else "") + "대화방 " + o.label
        }
    }

    /** 고른 칩이 가로 스크롤 밖이면 보이게(최근 방을 고르거나, 회전으로 폭이 줄었을 때) */
    private fun scrollToRoom() {
        chipScroll.post {
            val v = chipRow.getChildAt(roomSel) ?: return@post
            val w = chipScroll.width
            if (w == 0) return@post
            val x = chipScroll.scrollX
            val pad = dp(12)
            when {
                v.left - pad < x -> chipScroll.smoothScrollTo(maxOf(0, v.left - pad), 0)
                v.right + pad > x + w -> chipScroll.smoothScrollTo(v.right + pad - w, 0)
            }
        }
    }

    private fun refreshChipsVisible() {
        val show = !busyMode && replyTo == null && !chipsSuppressed && chipRow.childCount > 0
        chipScroll.visibility = if (show) VISIBLE else GONE
    }

    /** Host: 키보드 때문에 남은 높이가 작으면 칩 줄을 접는다(고른 방은 그대로 기억) */
    fun setChipsSuppressed(on: Boolean) {
        if (chipsSuppressed == on) return
        chipsSuppressed = on; refreshChipsVisible()
        if (!on) scrollToRoom()
    }

    /** Host: 자세가 바뀜(회전·접힘) — 칩 폭이 달라졌으니 고른 칩이 보이게 다시 맞춘다 */
    fun onPostureChanged() = scrollToRoom()

    /** Host: 보내기 전 방 바꾸기가 실패(작업 중 등) — 카드를 닫지 않고 이유를 보인다 */
    fun showSubmitError(msg: String) {
        refreshState()
        showHint(msg, true)
    }

    override fun onMeasure(w: Int, h: Int) {
        // 반접기: 카드를 접힌 선 아래 절반 안에 — 넘치면 아래(입력줄·안내)가 잘리지 않게 입력칸 줄 수부터 줄인다
        val cap = maxHeightPx
        val lines = if (cap in 1 until dp(260)) 2 else 4
        if (edit.maxLines != lines) edit.maxLines = lines     // 같을 때 다시 넣으면 requestLayout 이 되풀이된다
        val hs = if (cap > 0) MeasureSpec.makeMeasureSpec(minOf(cap, MeasureSpec.getSize(h).takeIf { it > 0 } ?: cap), MeasureSpec.AT_MOST) else h
        super.onMeasure(w, hs)
    }

    private fun refreshSend() {
        val on = !uploading && (voice.active || !edit.text.isNullOrBlank())
        send.isEnabled = on
        send.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) pal.accent else pal.panel2) }
        send.imageTintList = ColorStateList.valueOf(if (on) pal.onAccent else pal.dim)
    }

    private fun refreshMic() {
        val live = voice.active
        mic.background = if (live) {
            // 시안 .mic.live — 강조색 원 + 옅은 후광
            LayerDrawable(arrayOf(
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.accentTint) },
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.accent) },
            )).apply { setLayerInset(1, dp(4), dp(4), dp(4), dp(4)) }
        } else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.panel2) }
        mic.imageTintList = ColorStateList.valueOf(if (live) pal.onAccent else pal.dim)
        edit.visibility = if (live) INVISIBLE else VISIBLE     // INVISIBLE: 입력줄 높이가 흔들리지 않게
        listenBox.visibility = if (live) VISIBLE else GONE
        refreshSend()
    }

    private fun setWave(on: Boolean) {
        if (on == waveAnims.isNotEmpty()) return
        if (on) waveBars.forEachIndexed { k, b ->
            waveAnims += ObjectAnimator.ofFloat(b, "scaleY", 0.25f, 1f).apply {
                duration = 420L + (k * 53 % 5) * 60; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                startDelay = (k * 137 % 7) * 60L; start()
            }
        } else { waveAnims.forEach { it.cancel() }; waveAnims.clear(); waveBars.forEach { it.scaleY = 0.3f } }
    }

    private fun showHint(msg: String, warn: Boolean) {
        hintLeft.text = msg; hintLeft.setTextColor(if (warn) pal.warn else pal.dim)
    }

    // ---------------------------------------------------------------- 음성

    private fun hasMic() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 듣기 시작. 마이크 권한이 없으면 요청한다(결과는 액티비티가 onMicPermission 으로 넘긴다) */
    fun startListening() {
        if (busyMode || voice.active) return
        if (!hasMic()) {
            onRequestingPermission?.invoke()
            (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        hideIme()
        submitOnFinal = false
        heard.text = ""
        showHint(if (replyTo != null) REPLY_HINT else HINT, false)
        voice.start()
    }

    fun onMicPermission(granted: Boolean) {
        if (granted) startListening() else showHint("마이크 권한이 없어 들을 수 없어요 — 글로 써 주세요", true)
    }

    /** 「다 말했다」 — 최종 결과가 오면 바로 보낸다 */
    private fun doneSpeaking() {
        if (!voice.active) return
        submitOnFinal = true
        voice.finish()
    }

    /** 두 번째 제스처(onNewIntent). 듣는 중 = 말하기 끝. 안 듣고 입력칸이 비었으면 듣기 시작. 글이 있으면 건드리지 않는다 */
    fun onGesture() {
        when {
            busyMode -> {}
            voice.active -> doneSpeaking()
            edit.text.isNullOrBlank() -> startListening()
        }
    }

    /** 듣는 중 전사를 누름 — 듣기를 멈추고 들은 글을 입력칸에서 고치게 */
    private fun editTranscript() {
        if (!voice.active || voice.finishing) return
        val t = voice.transcript()
        voice.cancel(quiet = true)
        submitOnFinal = false
        if (t.isNotEmpty()) { usedVoice = true; edit.setText(t); edit.setSelection(edit.text.length) }
        focusInput(showIme = true)
    }

    fun cancelVoice() { if (voice.active) voice.cancel(quiet = true); submitOnFinal = false }

    /** 보내지 않은 지금 글 — 듣는 중이면 들은 데까지, 아니면 입력칸. 사람 개입 ▶ 이어서가 말하기 창을 닫기 전에 거둔다 */
    fun draftText(): String = (if (voice.active) voice.transcript() else edit.text?.toString().orEmpty()).trim()

    private fun showHeard() {
        val t = voice.transcript()
        heard.setTextColor(if (voice.ready || t.isNotEmpty()) pal.text else pal.dim)
        heard.text = when {
            voice.finishing -> t.ifEmpty { "…" }
            !voice.ready && t.isEmpty() -> "준비 중… 진동이 오면 말하세요"
            t.isEmpty() -> "듣고 있어요"
            else -> t
        }
    }

    // ---------------------------------------------------------------- 보내기 · 닫기

    private fun onSend() {
        if (uploading) return
        if (voice.active) { doneSpeaking(); return }
        val t = edit.text.toString().trim()
        if (t.isEmpty()) { if (files.isNotEmpty()) showHint(Uploads.HINT_NO_TEXT, true); return }
        val svc = AgentService.instance
        if (svc == null) { refreshState(); showHint("접근성 서비스가 꺼져 있어 보낼 수 없어요", true); return }
        // 답하기: 새 실행이 아니다 — 작업 중 검사·파일 올리기 없이 Host 가 선택 카드에 글을 돌려준다
        if (replyTo != null) { hideIme(); host.submit(t, JSONObject().apply { if (usedVoice) put("stt", voice.meta()) }); return }
        if (svc.busy) { refreshState(); return }
        hideIme()
        val meta = JSONObject()
        if (usedVoice) meta.put("stt", voice.meta())
        if (files.isEmpty()) { host.submit(t, meta); return }
        // 파일이 있으면 먼저 올린다 — 하나라도 못 올리면 실행하지 않는다(계약 INPUTS §1)
        if (files.any { it.state == "too_big" }) { showHint(Uploads.HINT_TOO_BIG, true); return }
        uploadThenSubmit(t, meta)
    }

    // ---------------------------------------------------------------- 공유받은 파일

    /** Host: 공유 시트로 받은 uri 들 — 이름·크기는 뒤에서 읽어 칩으로. 5개 넘는 건 뺀다 */
    fun addShared(uris: List<Uri>, typeHint: String?) {
        if (uris.isEmpty()) return
        val known = files.map { it.uri }.toSet()
        val room = Uploads.MAX_FILES - files.size
        val take = uris.filter { it !in known }
        val add = take.take(maxOf(0, room))
        val dropped = take.size - add.size
        if (add.isEmpty()) { if (dropped > 0) showHint("파일은 ${Uploads.MAX_FILES}개까지예요 — ${dropped}개는 뺐어요", true); return }
        Thread({
            val got = add.map { Uploads.resolve(context, it, typeHint) }
            ui.post {
                if (uploading) return@post
                got.forEach { g -> if (files.size < Uploads.MAX_FILES && files.none { it.uri == g.uri }) files += g }
                renderFiles()
                when {
                    dropped > 0 -> showHint("파일은 ${Uploads.MAX_FILES}개까지예요 — ${dropped}개는 뺐어요", true)
                    files.any { it.state == "too_big" } -> showHint(Uploads.HINT_TOO_BIG, true)
                    else -> showHint("이 파일로 무엇을 할지 말하거나 적어 주세요", false)
                }
            }
        }, "quickask-files").start()
    }

    private fun renderFiles() {
        fileRow.removeAllViews()
        files.forEach { f ->
            // 글자·상태 꼬리는 앱 입력줄 칩(ui/Parts FileChips)과 같은 규칙 — Uploads.chipLabel
            val v = chip(Uploads.chipLabel(f)).apply {
                setTextColor(if (Uploads.isBad(f)) pal.warn else pal.text)
                contentDescription = "첨부 ${f.name}${Uploads.chipTail(f)} — 누르면 빼기"
                setOnClickListener { if (!uploading) { files.remove(f); renderFiles(); if (files.none { it.state == "too_big" || it.state == "error" }) showHint(HINT, false) } }
            }
            fileRow.addView(v)
        }
        fileScroll.visibility = if (files.isNotEmpty() && !busyMode && replyTo == null) VISIBLE else GONE
    }

    /** 칩 순서대로 아직 안 올린 것만 올린다(다시 보내면 실패한 것만 다시). 다 올라가면 meta.uploads 를 싣고 host.submit */
    private fun uploadThenSubmit(text: String, meta: JSONObject) {
        val token = Prefs.assistantToken(context)
        if (token.isBlank() || Prefs.assistantBase(context).isBlank()) {
            files.filter { it.state != "done" }.forEach { it.state = "error"; it.error = "연결 설정 없음" }
            renderFiles(); showHint("홈 비서 연결이 설정되지 않아 파일을 못 올려요(설정 › 홈 비서)", true); return
        }
        val base = Prefs.assistantBase(context)
        uploading = true; uploadCancelled = false
        refreshSend()
        showHint("파일 올리는 중…", false)
        val todo = files.filter { it.state != "done" }
        todo.forEach { it.state = "uploading"; it.sent = 0; it.error = null }
        renderFiles()
        val ctx = context
        Thread({
            Uploads.uploadAll(ctx, base, token, todo, cancelled = { uploadCancelled }, changed = { ui.post { renderFiles() } })
            ui.post {
                uploading = false
                refreshSend()
                if (uploadCancelled) return@post
                val failed = files.filter { it.state != "done" }
                if (failed.isNotEmpty()) {
                    renderFiles()
                    val why = failed.first().error?.let { " ($it)" } ?: ""
                    showHint("파일 ${failed.size}개를 못 올려 실행하지 않았어요$why — 다시 보내면 그것만 다시 올려요", true)
                    return@post
                }
                meta.put("uploads", Uploads.metaOf(files))
                host.submit(text, meta)
            }
        }, "quickask-upload").start()
    }

    private fun openRecords() {
        cancelVoice()
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        host.dismiss()
    }

    /**
     * Host: 답하기 모드로(선택 카드 「✎ 직접 답하기」). 질문을 머리에 보이고 새 작업용 칩·기록 보기를 숨긴 뒤 바로 듣는다 —
     * 자동 듣기 설정(popupAutoListen)과 무관. 키보드로 써도 된다(파형을 누르면 입력칸). 떠 있던 보통 빠른 입력에서 바뀔 때는 쓰던 글을 비운다.
     */
    fun enterReply(id: String, question: String) {
        cancelVoice()
        replyTo = id
        replyHead.text = "✎ " + question.ifBlank { "어떻게 할까요?" }
        replyHead.visibility = VISIBLE
        edit.hint = "답을 말하거나 써 주세요"
        usedVoice = false
        edit.setText("")
        hintRight.visibility = GONE
        refreshChipsVisible()
        fileScroll.visibility = GONE
        refreshState()
        showHint(REPLY_HINT, false)
        refreshMic()
        startListening()
    }

    fun setText(t: String) {
        cancelVoice(); usedVoice = false
        edit.setText(t); edit.setSelection(edit.text.length)
        refreshMic()
    }

    fun focusInput(showIme: Boolean) {
        edit.requestFocus()
        if (showIme) edit.post {
            edit.windowInsetsController?.show(WindowInsets.Type.ime())
                ?: context.getSystemService(InputMethodManager::class.java).showSoftInput(edit, 0)
        }
    }

    fun hideIme() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(edit.windowToken, 0)
        edit.windowInsetsController?.hide(WindowInsets.Type.ime())
        edit.clearFocus()
    }

    /** Host 가 사라질 때(onDestroy) — 인식기를 놓는다 */
    fun release() {
        uploadCancelled = true
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(busyCloser)
        setWave(false)
        voice.destroy()
    }

    companion object {
        const val REQ_MIC = 7
        const val REPLY_HINT = "말하거나 써서 답해요 · 창을 닫으면 추천안"
        const val BUSY_LINGER_MS = 4000L      // 작업 중 카드: 손 안 대면 이만큼 뒤 닫기(문구 읽고 정지 판단할 시간)
        const val STOPPED_LINGER_MS = 700L    // 정지 누른 뒤: 「멈추는 중…」 보여 주고 닫기
    }

    /** 작은 원형 표식(시안 .mark) — 옅은 강조 면 + 빛 색 고리 + 가운데 점 */
    private inner class MarkView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = minOf(width, height) / 2f; val cx = width / 2f; val cy = height / 2f
            p.style = Paint.Style.FILL; p.color = pal.accentTint; c.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.5f); p.color = pal.glow; c.drawCircle(cx, cy, r - dp(0.75f), p)
            p.style = Paint.Style.FILL; c.drawCircle(cx, cy, r * 0.32f, p)
        }
    }
}
