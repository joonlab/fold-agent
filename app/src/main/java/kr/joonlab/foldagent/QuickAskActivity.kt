package kr.joonlab.foldagent

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.window.OnBackInvokedDispatcher
import kr.joonlab.foldagent.records.Records
import kr.joonlab.foldagent.records.RoomChoice
import org.json.JSONObject

/**
 * 「에이전트 빠른 입력」 — 두 번째 LAUNCHER 항목(DESIGN §9 7-3·7-4, §3-1 (c)).
 * 입구: Good Lock Home Up 멀티핑거 제스처(핀치 인 — 두 번 탭은 뒤 앱이 눌려 바꿈) → 「앱 실행」 → 이 항목. 기본 어시스턴트·측면 버튼은 안 바꾼다.
 * 투명 창 위에 아래서 올라오는 카드(QuickAskView) 하나 — 스크림 없이 뒤 앱이 그대로 보인다.
 *
 * - singleInstance + 별도 taskAffinity: 채팅 화면(MainActivity 태스크)을 끌어올리지 않고, 떠 있는 동안 같은 제스처가 다시 오면
 *   새 화면이 아니라 onNewIntent 로 받는다(= 「다 말했다」). noHistory 는 쓰지 않는다 — 쓰면 onNewIntent 가 안 온다.
 * - 재전달 가드: 시스템은 재생성·최근 앱에서 마지막 시작 인텐트를 다시 준다(MainActivity 시험 명령 되살아남 사고, 2026-09-26)
 *   → FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY·savedInstanceState≠null 이면 자동 듣기·글 채우기를 하지 않는다. 제출은 언제나 사람이 누른 것만.
 * - 자세(DESIGN §2, 2026-09-27 확정): 매니페스트 configChanges 로 재생성 없이 크기만 바뀐다 → 입력 글·듣기·고른 방이 그대로 남고,
 *   배치는 root 크기(onMeasure·onSizeChanged)와 인셋으로 다시 잰다(displayMetrics 는 늦게 갱신될 수 있어 폭 판정에 안 쓴다).
 *   커버 세로(폭<600dp·세로) = 폭 94% / 그 밖(커버 가로·펼침·펼침 돌림) = 560dp 가운데 / 키보드로 남은 높이가 작으면 칩 줄 접기 /
 *   반접기 테이블톱 = 카드를 접힌 선 아래 절반 안에.
 * - 답하기 모드(2026-09-28 사용자): 방법 고르기 카드의 「✎ 직접 답하기」가 [EXTRA_CHOOSE_REPLY]=요청 id 로 연다. 새 실행을 만들지 않고
 *   AgentService.chooseReply 로 글을 돌려준다. 답 없이 닫히면(뒤로·바깥·다른 앱) 추천안. id 가 지금 기다리는 선택과 다르거나 되살린 인텐트면 받지 않는다.
 */
class QuickAskActivity : Activity(), QuickAskView.Host {

    companion object {
        private const val TAG = "FoldAgent"
        private const val HALF_MIN = 30f            // Posture.kt 와 같은 값 — 닫힘·완전히 펼침 사이
        private const val HALF_MAX = 150f
        private const val HALF_SETTLE_MS = 500L
        private const val CHIPS_MIN_LEFT_DP = 360   // 키보드 뒤 남은 높이가 이보다 작으면 칩 줄 접기(커버 가로 ≈ 475-키보드 → 접힘, 커버 세로는 남긴다)
        /** 답하기 모드: 선택 요청 id(AgentService.choose) · 머리에 보일 질문 한 줄 */
        const val EXTRA_CHOOSE_REPLY = "chooseReply"
        const val EXTRA_CHOOSE_QUESTION = "chooseQuestion"

        /** 떠 있는 창 — 선택이 다른 길(선택지 탭·시한·정지)로 끝나면 AgentService 가 [endReply] 로 답하기 창을 닫는다. 메인 스레드 */
        private var live: java.lang.ref.WeakReference<QuickAskActivity>? = null

        /**
         * 사람 개입 ▶ 이어서 — 이 id 의 말하기 창에 보내지 않은 글(듣던 말 포함)이 있으면 돌려준다. 닫기 전에 불러야 한다(닫으면 버려진다,
         * 실기 2026-09-28 사용자 지적). 메인 스레드
         */
        fun takeDraft(id: String): String? {
            val a = live?.get() ?: return null
            if (a.replyId != id) return null
            return a.card.draftText().takeIf { it.isNotEmpty() }
        }

        /** 이 선택 요청의 답하기 창이 떠 있으면 닫는다(답은 이미 났으니 추천안을 다시 보내지 않는다). 메인 스레드 */
        fun endReply(id: String) {
            val a = live?.get() ?: return
            if (a.replyId != id) return
            a.replyId = null
            Log.i(TAG, "빠른 입력: 선택이 끝나 답하기 창 닫음 $id")
            a.cancelAndClose()
        }
    }

    /** 답하기 모드의 선택 요청 id — null 이면 보통 빠른 입력. 답을 보냈거나 닫으며 돌려줬으면 null 로 비운다 */
    private var replyId: String? = null

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var card: QuickAskView
    private var closing = false
    private var awaitingPermission = false     // 권한 창이 떠 있는 동안 onStop 이 와도 닫지 않는다
    private var submitting = false             // 방 바꾸기(IO) 도는 동안 두 번 보내기 막기

    // 마지막 인셋(px) — 회전·접힘 뒤 onSizeChanged 에서 다시 쓴다
    private var imeBottom = 0
    private var navBottom = 0
    private var topInset = 0

    /*
     * 반접기 — 폴드8(One UI)은 반쯤 접어도 FoldingFeature 를 FLAT 으로만 준다(기록 앱 Posture.kt 실측 2026-09-25) →
     * 접힌 정도는 공개 센서 TYPE_HINGE_ANGLE 로 읽는다. 접거나 펼 때 90° 를 스쳐 지나가니(0.1~0.5초) 0.5초 머물러야 인정, 벗어나는 건 즉시.
     * 이 앱은 androidx.window 를 안 쓰므로 힌지 방향은 창 모양으로 가른다: 안쪽 화면(짧은 변 ≥600dp)을 세로로 세우면(704×933) 힌지가 가로 = 테이블톱.
     * (펼침 기본 933×704 에선 힌지가 세로 = 책 — 팝업은 가운데 560dp 라 힌지에 걸치지만 아래 붙은 카드라 v1 은 그대로 둔다)
     */
    private var hingeAngle: Float? = null
    private var halfOpen = false
    private val settleHalf = Runnable { if (!halfOpen) { halfOpen = true; Log.i(TAG, "빠른 입력: 반접기 인정 angle=$hingeAngle"); relayout() } }
    private val hingeListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values[0]; hingeAngle = v
            val raw = v in HALF_MIN..HALF_MAX
            if (raw) { if (!halfOpen) { ui.removeCallbacks(settleHalf); ui.postDelayed(settleHalf, HALF_SETTLE_MS) } }
            else { ui.removeCallbacks(settleHalf); if (halfOpen) { halfOpen = false; relayout() } }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)   // 카드가 스스로 올라온다 — 창 전환 애니메이션은 끈다
        window.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // 가장자리까지 그리고 아래 여백은 내비·키보드 인셋으로 직접 맞춘다(targetSdk 36 은 어차피 edge-to-edge)
            setDecorFitsSystemWindows(false)
            @Suppress("DEPRECATION") run { statusBarColor = Color.TRANSPARENT; navigationBarColor = Color.TRANSPARENT }
            isNavigationBarContrastEnforced = false
        }
        Records.init(this)

        card = QuickAskView(this, this)
        card.onRequestingPermission = { awaitingPermission = true }
        // 카드 폭·최대 높이는 지금 창 크기로 매번 — 접기·펴기·돌리기에도 재생성 없이 이 onMeasure 가 다시 돈다
        root = object : FrameLayout(this) {
            override fun onMeasure(w: Int, h: Int) {
                placeCard(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
                super.onMeasure(w, h)
            }
            override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
                super.onSizeChanged(w, h, ow, oh)
                if (ow != 0 && (w != ow || h != oh)) Log.i(TAG, "빠른 입력: 창 크기 ${ow}x$oh → ${w}x$h")
                ui.post { refreshChipsRoom(); card.onPostureChanged() }
            }
        }
        root.addView(card, FrameLayout.LayoutParams(dp(360), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.setOnClickListener { cancelAndClose() }        // 카드 밖 탭 = 닫기(카드는 isClickable 이라 안쪽 탭은 여기 안 온다)
        root.setOnApplyWindowInsetsListener { _, ins ->
            // 아래 여백 = 내비 인셋 + 12dp. 키보드가 뜨면 키보드 위로
            navBottom = ins.getInsets(WindowInsets.Type.navigationBars()).bottom
            imeBottom = ins.getInsets(WindowInsets.Type.ime()).bottom
            topInset = ins.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
            val lp = card.layoutParams as FrameLayout.LayoutParams
            val bottom = maxOf(navBottom, imeBottom) + dp(12)
            if (lp.bottomMargin != bottom) { lp.bottomMargin = bottom; card.layoutParams = lp }
            refreshChipsRoom()
            WindowInsets.CONSUMED
        }
        setContentView(root)
        live = java.lang.ref.WeakReference(this)

        // 뒤로 = 닫기(듣는 중이면 취소). targetSdk 36 은 예측 뒤로가 기본이라 onBackPressed 가 안 불린다 → 콜백 등록
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { cancelAndClose() }

        getSystemService(SensorManager::class.java)?.let { sm ->
            val hs = sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
            if (hs != null) sm.registerListener(hingeListener, hs, SensorManager.SENSOR_DELAY_NORMAL)
        }

        card.refreshState()
        card.loadChips()
        val replay = savedInstanceState != null || fromHistory(intent)
        when {
            // 답하기 인텐트는 지금 기다리는 선택과 id 가 같을 때만 — 되살린 옛 인텐트·다른 id 는 받지 않고 닫는다
            intent.hasExtra(EXTRA_CHOOSE_REPLY) -> if (replay || !takeReply(intent)) {
                Log.w(TAG, "빠른 입력: 답하기 인텐트 무시(replay=$replay) — 닫음")
                closeNow()
            }
            replay -> Log.w(TAG, "빠른 입력: 재전달된 시작 — 자동 듣기 안 함")
            else -> openFresh(intent)
        }

        // 짧은 슬라이드업 + 페이드(180ms)
        card.alpha = 0f; card.translationY = dp(28).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
    }

    // ---------------------------------------------------------------- 자세

    /** 폭: 커버 세로(폭<600dp 이고 세로) = 94% · 그 밖 = 560dp(좁으면 94%) 가운데. 반접기 테이블톱이면 최대 높이 = 접힌 선 아래 */
    private fun placeCard(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val d = resources.displayMetrics.density
        val portrait = h > w
        val want = if (w / d < 600f && portrait) (w * 0.94f).toInt() else minOf(dp(560), (w * 0.94f).toInt())
        val lp = card.layoutParams as FrameLayout.LayoutParams
        if (lp.width != want) lp.width = want
        // 키보드가 떠 있으면 아래 절반은 키보드 차지 — 그때는 절반 제한을 풀어 카드가 키보드 위(위 절반)로 올라가게 둔다
        card.maxHeightPx = if (tabletop(w, h) && imeBottom == 0) maxOf(dp(160), h / 2 - lp.bottomMargin - dp(12)) else 0
    }

    private fun tabletop(w: Int, h: Int): Boolean {
        if (!halfOpen) return false
        val d = resources.displayMetrics.density
        return minOf(w, h) / d >= 600f && h > w
    }

    /** 키보드 뒤로 남은 높이가 작으면(커버 가로 등) 칩 줄을 접는다 — 입력줄·보내기가 먼저다 */
    private fun refreshChipsRoom() {
        if (!::card.isInitialized) return
        val h = root.height
        val left = (h - imeBottom - topInset) / resources.displayMetrics.density
        card.setChipsSuppressed(imeBottom > 0 && h > 0 && left < CHIPS_MIN_LEFT_DP)
    }

    /** 반접기 판정이 바뀜 — 크기는 그대로라 onMeasure 가 저절로 안 돈다 */
    private fun relayout() {
        root.requestLayout()
        card.onPostureChanged()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 재생성 없이 여기로 온다(configChanges) — 입력 글·듣기·고른 방은 뷰에 그대로. 인셋·배치만 다시
        Log.i(TAG, "빠른 입력: 자세 바뀜 ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp")
        root.requestApplyInsets()
        relayout()
    }

    private fun fromHistory(i: Intent?) = i != null && (i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0

    /** 새로 열림: (디버그) --es text 로 입력칸 채우기 · 공유 시트(파일 칩·글 채우기) · 그 밖엔 설정에 따라 바로 듣기 또는 키보드 */
    private fun openFresh(i: Intent) {
        if (debugText(i)) return
        // 공유로 열리면 듣지 않고 키보드 — 파일 요청은 보통 글로 쓴다(사용자 2026-09-28: 열자마자 듣기 끄기)
        if (Uploads.isShare(i)) { if (!takeShare(i) && AgentService.instance?.busy != true) card.focusInput(showIme = true); return }
        val svc = AgentService.instance
        if (Prefs.popupAutoListen(this) && svc != null && !svc.busy) card.startListening()
        else if (svc?.busy != true) card.focusInput(showIme = true)
    }

    /** 답하기 인텐트 → 카드를 답하기 모드로. 서비스가 이 id 로 답을 기다리는 중이 아니면 false */
    private fun takeReply(i: Intent): Boolean {
        val id = i.getStringExtra(EXTRA_CHOOSE_REPLY)
        val q = i.getStringExtra(EXTRA_CHOOSE_QUESTION).orEmpty()
        i.removeExtra(EXTRA_CHOOSE_REPLY); i.removeExtra(EXTRA_CHOOSE_QUESTION)
        if (id == null || AgentService.instance?.chooseReplyPending(id) != true) return false
        replyId = id
        Log.i(TAG, "빠른 입력: 답하기 모드 $id")
        card.enterReply(id, q)
        return true
    }

    /** 답하기 창이 답 없이 닫힘 — 기다리는 선택을 추천안(reply_closed)으로 푼다. 이미 답이 났으면 서비스가 무시한다 */
    private fun releaseReply() {
        val id = replyId ?: return
        replyId = null
        val took = AgentService.instance?.chooseReply(id, null) == true
        Log.i(TAG, "빠른 입력: 답하기 닫힘 → ${if (took) "추천안" else "이미 끝남"} $id")
    }

    /** 스크린샷 시험용(디버그 빌드만): am start … --es text "<글>" → 입력칸만 채운다. 제출은 절대 안 한다 */
    private fun debugText(i: Intent): Boolean {
        if (!BuildConfig.DEBUG) return false
        val t = i.getStringExtra("text") ?: return false
        i.removeExtra("text")
        card.setText(t)
        return true
    }

    /**
     * 공유 시트(ACTION_SEND·SEND_MULTIPLE, 계약 INPUTS §1) — 파일은 칩으로(보내기 때 올린다), EXTRA_TEXT 는 입력칸에.
     * 재전달 가드는 부르는 쪽(openFresh·onNewIntent)이 이미 했다 — 되살린 인텐트의 uri 는 권한도 없다. 제출은 여기서 하지 않는다.
     * @return 입력칸에 글을 채웠으면 true(자동 듣기 대신 키보드)
     */
    private fun takeShare(i: Intent): Boolean {
        val uris = Uploads.streamsOf(i)
        Log.i(TAG, "빠른 입력: 공유 받음 파일 ${uris.size}개 type=${i.type}")
        card.addShared(uris, i.type)
        val t = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return false
        card.setText(t)
        card.focusInput(showIme = true)
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (closing || fromHistory(intent)) return
        // 떠 있는 빠른 입력에 답하기가 옴 → 이 창을 답하기 모드로. 다른 id(지난 선택)면 지금 창은 그대로 둔다
        if (intent.hasExtra(EXTRA_CHOOSE_REPLY)) {
            if (!takeReply(intent)) Log.w(TAG, "빠른 입력: 답하기 인텐트 무시(기다리는 선택과 id 다름)")
            return
        }
        if (debugText(intent)) return
        // 카드가 떠 있는 동안 또 공유 — 칩을 더한다(제스처로 치지 않는다)
        if (Uploads.isShare(intent)) { if (AgentService.instance?.busy != true) takeShare(intent); return }
        // 떠 있는 동안 같은 제스처가 또 옴 = 「다 말했다」(듣는 중이 아니면 듣기 시작)
        card.onGesture()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code != QuickAskView.REQ_MIC) return
        awaitingPermission = false
        card.onMicPermission(results.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    @Deprecated("API 33 미만의 뒤로 — 33 이상은 onBackInvokedDispatcher 콜백")
    override fun onBackPressed() = cancelAndClose()

    override fun onResume() {
        super.onResume()
        card.refreshState()
    }

    // 다른 앱으로 넘어가거나 화면이 꺼지면 닫는다 — 안 보이는 태스크에 남아 있으면 다음 제스처가 옛 카드에 onNewIntent 로 붙는다
    override fun onStop() {
        super.onStop()
        if (!isFinishing && !awaitingPermission && !isChangingConfigurations) { card.cancelVoice(); closeNow() }
    }

    override fun onDestroy() {
        releaseReply()
        if (live?.get() === this) live = null
        runCatching { getSystemService(SensorManager::class.java)?.unregisterListener(hingeListener) }
        ui.removeCallbacks(settleHalf)
        card.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Host

    override fun dismiss() = cancelAndClose()

    private fun cancelAndClose() {
        if (closing) return
        closing = true
        releaseReply()
        card.cancelVoice()
        card.hideIme()
        card.animate().alpha(0f).translationY(dp(20).toFloat()).setDuration(120).setInterpolator(AccelerateInterpolator())
            .withEndAction { closeNow() }.start()
    }

    private fun closeNow() {
        closing = true
        releaseReply()
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    /**
     * 제출 — 순서가 중요하다(DESIGN §3-1 함정 1): 우리 창이 화면에 남아 있는 채로 시작하면 에이전트의 첫 화면 읽기가
     * 뒤 앱이 아니라 이 카드를 본다. 그래서 키보드 내림(View 가 이미) → 고른 대화방으로 바꾸기(IO) → 애니메이션 없이 finish →
     * 창 목록에서 우리 앱 창이 사라질 때까지 100ms×최대 10회 폴링 → startTask.
     * 방 바꾸기를 닫기 «앞»에 두는 이유: select 는 작업 중이면 예외 — 닫은 뒤에 실패하면 이유를 보여 줄 곳이 없다.
     * 방 바꾸기는 화면을 건드리지 않으니 「창이 사라진 뒤 startTask」라는 핵심 순서는 그대로다.
     */
    override fun submit(text: String, meta: JSONObject) {
        replyId?.let { submitReply(it, text, meta); return }
        val svc = AgentService.instance
        if (svc == null || svc.busy) { card.refreshState(); return }     // 카드 안에 이유가 보인다(닫지 않음)
        if (submitting || closing) return
        submitting = true
        val picked = card.selectedRoom
        meta.put("source", "popup")
        val pkg = packageName
        Thread({
            // 칩이 아직 안 읽혔으면(아주 빠른 제출) 같은 규칙의 기본값 — 1분 안이면 이어서, 아니면 새 대화
            val opt = picked ?: runCatching { RoomChoice.build(Records.store, Prefs.continueWindowSec(this) * 1000L).let { it.options[it.defaultIndex] } }
                .getOrElse { RoomChoice.Option(null, "＋ 새 대화", "새 대화") }
            // 칩을 읽는 사이 다른 입구에서 작업이 시작됐으면 방을 바꾸지 않는다(startNew 는 busy 를 안 본다 — 작업 중 현재 방이 바뀐다)
            val prevRoom = runCatching { Records.store.currentId() }.getOrNull()
            val applied = if (AgentService.instance?.busy == true) Result.failure(IllegalStateException("작업 중에는 대화를 바꿀 수 없어요"))
                          else runCatching { RoomChoice.apply(Records.store, opt) }
            if (applied.isFailure) {
                val e = applied.exceptionOrNull()
                Log.w(TAG, "빠른 입력: 대화방 바꾸기 실패 $e")
                ui.post {
                    submitting = false
                    card.showSubmitError(if (e is IllegalStateException) (e.message ?: "작업 중이라 대화방을 바꿀 수 없어요")
                                         else "대화방을 못 열었어요 — 다시 보내 주세요")
                }
                return@Thread
            }
            meta.put("roomLabel", RoomChoice.sentLabel(opt))
            ui.post {
                if (closing) {   // 그 사이 카드 밖 탭 등으로 닫힘 — 보내지 않고, 바꿔 둔 방도 되돌린다(안 그러면 다음 요청이 엉뚱한 방·빈 새 방을 물려받는다)
                    submitting = false
                    if (prevRoom != null && prevRoom != opt.id) Thread { runCatching { Records.store.select(prevRoom) } }.start()
                    return@post
                }
                closeNow()
                Thread({
                    var waited = 0
                    while (waited < 10 && ourWindowShown(pkg)) { Thread.sleep(100); waited++ }
                    Log.i(TAG, "빠른 입력 제출: 창 사라짐 대기 ${waited * 100}ms room=${opt.id ?: "새 대화"}")
                    ui.post {
                        val s = AgentService.instance
                        if (s == null) Log.w(TAG, "빠른 입력: 제출 직전 서비스가 끊겼다 — 버림: $text")
                        else s.startTask(text, meta)
                    }
                }, "quickask-submit").start()
            }
        }, "quickask-room").start()
    }

    /**
     * 답하기 보내기 — 새 실행을 만들지 않는다. 제출과 같은 이유로 창이 사라진 뒤에 글을 돌려준다
     * (답을 받은 실행이 곧바로 화면을 읽으면 이 카드를 뒤 앱으로 본다).
     */
    private fun submitReply(id: String, text: String, meta: JSONObject) {
        if (submitting || closing) return
        submitting = true
        replyId = null            // 닫으며 추천안으로 풀지 않게
        val pkg = packageName
        closeNow()
        Thread({
            var waited = 0
            while (waited < 10 && ourWindowShown(pkg)) { Thread.sleep(100); waited++ }
            val took = AgentService.instance?.chooseReply(id, text) == true
            Log.i(TAG, "빠른 입력: 직접 답 ${text.length}자 voice=${meta.has("stt")} 창 대기 ${waited * 100}ms → ${if (took) "전달" else "이미 끝난 선택 — 버림"}")
        }, "quickask-reply").start()
    }

    /** 우리 패키지의 앱 창(TYPE_APPLICATION)이 아직 창 목록에 있나. 상태 패널(접근성 오버레이)은 우리 것이어도 뺀다 */
    private fun ourWindowShown(pkg: String): Boolean {
        val svc = AgentService.instance ?: return false
        return runCatching {
            svc.windows.any { w -> w.type == AccessibilityWindowInfo.TYPE_APPLICATION && w.root?.packageName?.toString() == pkg }
        }.getOrDefault(false)
    }
}
