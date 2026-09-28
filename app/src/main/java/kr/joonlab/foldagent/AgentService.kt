package kr.joonlab.foldagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kr.joonlab.foldagent.records.Records
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 폰의 눈과 손. 시스템이 바인딩해 두므로 앱이 백그라운드여도 살아 있고,
 * 접근성 서비스라서 백그라운드에서 다른 앱을 여는 것도 허용된다.
 * 에이전트 루프는 별도 스레드에서 돌고, UI(오버레이·TTS)만 메인 스레드로 넘긴다.
 */
class AgentService : AccessibilityService() {

    companion object {
        /** choose() 가 돌려주는 「그만」 번호 */
        const val CHOSE_STOP = -1
        /** choose() 가 돌려주는 「직접 답함」 번호 — 글은 [Choice.reply] */
        const val CHOSE_REPLY = -2
        /** 「직접 답하기」 창을 연 뒤 답을 기다리는 시한 — 선택 카드 시한과 따로(말하거나 쓰는 데 10초는 짧다) */
        const val REPLY_WAIT_MS = 60_000L
        private const val TAG = "FoldAgent"
        @Volatile var instance: AgentService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastEventAt = 0L
    @Volatile private var running: Agent? = null
    private var overlay: Overlay? = null
    private var glow: RunIndicator? = null      // 테두리 빛(작업 B). 메인 스레드에서만 만진다
    private var tts: TextToSpeech? = null
    val knowledge: Knowledge by lazy { Knowledge(this) }
    /** 실행 중 화면 켜짐 유지 — startTask 가 켜고 실행 스레드의 finally 가 끈다(취소·예외 포함) */
    private val keepAwake = KeepAwake(this)

    override fun onServiceConnected() {
        instance = this
        overlay?.hide()          // 재연결 — 이전 연결의 패널은 토큰이 죽었으니 치운다
        overlay = Overlay(this)
        glow?.release()          // 재연결 — 이전 연결의 빛 창도 치운다
        glow = EdgeGlow(this)
        Records.init(this)
        tts = TextToSpeech(this) { st -> if (st == TextToSpeech.SUCCESS) tts?.language = Locale.KOREAN }
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) = setSpeaking(true)
            override fun onDone(id: String?) = setSpeaking(false)
            @Deprecated("") override fun onError(id: String?) = setSpeaking(false)
            override fun onStop(id: String?, interrupted: Boolean) = setSpeaking(false)
        })
        kr.joonlab.foldagent.jobs.JobWatcher.start(this)   // 맡긴 작업 감시(계약 cc-capabilities §2) — 작업이 있을 때만 long-poll
        // 화면이 꺼졌다 켜지면 확인 카드가 안 보였다(2026-09-27 실기 cc-phone-4: 코드는 카드가 떠 있다고 알고 10분 시한까지 기다림).
        // 화면이 켜지거나 잠금이 풀릴 때 카드가 떠 있어야 하면 창을 다시 붙인다
        runCatching { unregisterReceiver(screenRx) }
        registerReceiver(screenRx, android.content.IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) })
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAt = SystemClock.uptimeMillis()
        // 사람 개입 중에만 사용자의 조작을 모은다 — 평소엔 시각만(가볍게). 우리 앱(패널·답하기 창)·입력기(키 누름) 이벤트는 뺀다
        val h = hitl
        if (h != null && h.acked && !h.resumed && event != null) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName || pkg == hitlImePkg) return
            // 떠 있는 오버레이 앱(한 손 조작·다이내믹 스팟 등 — 접근성 오버레이 창만 가진 앱)의 창 이벤트는 사람 조작이 아니다(실기 2026-09-28 a).
            // 앱·시스템(알림창) 창을 가진 패키지만 받는다
            if (!hitlRealWindow(pkg)) return
            runCatching { h.record(event, pkg) }.onFailure { Log.w(TAG, "hitl record: ${it.javaClass.simpleName}") }
        }
    }

    override fun onInterrupt() {}

    private val screenRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            val what = i?.action?.substringAfterLast('.') ?: return
            main.post { overlay?.reattachIfAsking(what) }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        runCatching { unregisterReceiver(screenRx) }
        kr.joonlab.foldagent.jobs.JobWatcher.stop()
        running?.cancel()
        keepAwake.off()
        main.post { overlay?.hide(); glow?.release() }
        tts?.shutdown()
        return super.onUnbind(intent)
    }

    val busy get() = running != null

    // ---- 화면(기록 목록·상태 카드·팝업)이 읽는 진행 상태. 에이전트 스레드가 쓰고 메인이 읽는다
    /** 지금 실행 중인 세션 id — 「실행 중인 방」은 보관·휴지통 불가 판정용 */
    @Volatile var runningSessionId: String? = null
        private set
    /** 마지막 상태 문장(status()) — 「작업 중 — …」 문구용 */
    @Volatile var lastStatus: String = ""
        private set
    /** 이번 작업의 단계 수(emit("step") 횟수) */
    @Volatile var stepCount: Int = 0
        private set
    /** 이번 작업 시작 시각(System.currentTimeMillis), 유휴면 0 */
    @Volatile var startedAt: Long = 0L
        private set

    /** meta: 요청 출처·음성 인식 후보 등 — 전부 기록에 남는다. */
    fun startTask(task: String, meta: JSONObject = JSONObject()): Boolean {
        if (running != null) { speak("이미 작업 중이에요"); return false }
        // 지금 이어 가는 대화에 턴을 더한다. 단 맥에서 adb 로 보낸 시험 요청은 별도 「시험」 세션으로 —
        // 사용자 대화에 시험 턴이 섞여 다음 요청이 엉뚱한 맥락을 물려받았다(2026-09-26)
        // 맡긴 일 이어가기(source=job)는 맡긴 방에 붙인다
        val session = when {
            meta.optString("source") == "adb" -> Session.load(this, meta.optString("session").ifBlank { "adb-test" })
            meta.optString("source") == "job" && meta.optString("session").isNotBlank() -> Session.load(this, meta.optString("session"))
            else -> Session.current(this)
        }
        val agent = Agent(this, task, meta, session)
        // 빠른 입력·대시보드가 고른 방을 보낸 직후 패널에 짧게(「↳ 알람 10시에 이어서」 / 「＋ 새 대화」) — 2026-09-27 사용자 요청
        meta.optString("roomLabel").takeIf { it.isNotBlank() }?.let { lb -> main.postDelayed({ overlay?.note(lb) }, 300) }
        running = agent
        lastStatus = ""   // 확인 창 맥락이 지난 실행의 문장을 쓰지 않게
        runningSessionId = session.id
        // 새 작업은 패널 기본 자리에서(사용자 2026-09-28: 옮긴 자리는 그 작업 동안만) · 패널을 누르면 이 방으로
        main.post { overlay?.newTask(session.id) }
        stepCount = 0; startedAt = System.currentTimeMillis()
        // 테두리 빛 = 「폰을 조작 중」. 채팅 화면에서 시킨 일은 화면을 물릴 때(yieldApp)부터 켠다 — 대화형 답엔 켜지 않는다
        glowOn = false
        main.post { if (MainActivity.current == null && running != null) { glowOn = true; glow?.start() } }
        Thread({
            var status = "error"
            keepAwake.on()
            try { status = agent.run() } finally {
                keepAwake.off()
                hitl?.let { hitlEnd(it) }   // 멈춘 채 예외로 끝나도 기록 이벤트·패널을 되돌린다
                running = null
                runningSessionId = null
                kr.joonlab.foldagent.jobs.JobWatcher.kick()   // 이 실행이 끝나길 기다리던 맡긴 일 결과를 바로 붙이게
                startedAt = 0L
                main.post {
                    glow?.stop()
                    overlay?.refreshButton()
                    // 끝난 뒤 👍/👎 — 👎 받은 경로는 다음부터 참고하지 않는다
                    if (status != "cancel" && MainActivity.current == null) overlay?.rate { good -> Thread { agent.feedback(good); Records.store.rate(agent.runId, good) }.start() }
                }
                hideWhenIdle(if (status != "cancel") Prefs.panelLingerSec(this) * 1000L else 5000)
            }
        }, "agent").start()
        return true
    }

    /**
     * 정지. 확인 창이 답을 기다리는 중이면 그 확인을 **거부**로 즉시 푼다 — 앱 화면·빠른 입력의 정지는 오버레이를 거치지 않아
     * 40초 시한까지 작업 스레드가 latch 에 묶여 있었다. 게이트 약화가 아니라 거부 쪽이다(계약 §3 W-C).
     */
    fun cancelTask() {
        // 작업의 cancelled 표지를 먼저 세운다 — latch 를 먼저 풀면 깨어난 작업 스레드가 아직 cancelled=false 를 보고
        // 「사용자가 거부함」으로 기록·failStreak·거부 키 등록까지 했다(리뷰 runtime#5)
        running?.cancel()
        pendingConfirm?.let { (answer, latch) ->
            if (answer.compareAndSet(null, false)) Log.i(TAG, "confirm 대기 중 정지 → 거부")
            latch.countDown()
        }
        pendingChoose?.let { p -> p.answer.compareAndSet(null, Choice(CHOSE_STOP, "cancel")); p.latch.countDown() }
        hitl?.latch?.countDown()   // 사람 차례로 멈춰 있으면 기다림을 푼다(작업은 cancelled 를 보고 끝낸다)
    }

    // ---- 사람 개입(HITL, 사용자 2026-09-28) — 본체는 Hitl.kt. 에이전트 스레드는 Agent.humanTurn 에서 기다린다

    /** 지금 멈춤(요청됨 또는 사람 차례). null = 에이전트가 하는 중 */
    @Volatile var hitl: Hitl? = null
        private set
    /** 에이전트가 모델 답을 기다리는 중 — 이때 멈추면 곧바로 사람 차례(그 답은 버린다). Agent 가 쓴다 */
    @Volatile var agentThinking = false
    private fun hitlRealWindow(pkg: String): Boolean = runCatching {
        windows.any { w -> (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ||
            w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) && w.root?.packageName?.toString() == pkg }
    }.getOrDefault(true)

    /** 사람 차례 동안 뺄 입력기 패키지(키 누름이 「누름」으로 잡히지 않게) */
    @Volatile private var hitlImePkg: String? = null

    /**
     * 「✋ 내가 할게」 — 확인·선택 카드가 떠 있으면 받지 않는다(그 카드의 답이 먼저). 모델 대기 중이면 곧바로 사람 차례,
     * 도구가 도는 중이면 그 한 동작을 마치고(에이전트 스레드가 hitlAck). 메인 스레드에서 부른다.
     */
    fun pauseTask(): Boolean {
        if (running == null) return false
        val h: Hitl
        synchronized(askLock) {
            if (confirming || choosing || hitl != null) return false
            h = Hitl("hitl${SystemClock.uptimeMillis()}")
            hitl = h
        }
        Log.i(TAG, "hitl 멈춤 요청 thinking=$agentThinking")
        stopSpeaking()
        // hitl 을 먼저 세우고 thinking 을 읽는다 — 에이전트는 thinking 을 내린 뒤 hitl 을 본다(둘 중 한쪽은 반드시 상대를 본다)
        if (agentThinking) hitlAck(h) else overlay?.hitlPending()
        return true
    }

    /** 멈춤 확정 → 사람 차례. 조작 기록을 켜고, 화면 켜짐 유지·테두리 빛을 끈다. 어느 스레드에서 불러도 된다(한 번만 친다) */
    fun hitlAck(h: Hitl) {
        synchronized(h) { if (h.acked) return; h.acked = true; h.ackAt = SystemClock.uptimeMillis() }
        hitlImePkg = runCatching { android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/') }.getOrNull()
        runCatching { serviceInfo = serviceInfo.apply { eventTypes = eventTypes or Hitl.EXTRA_TYPES } }
        keepAwake.off()
        Log.i(TAG, "hitl 사람 차례 (${SystemClock.uptimeMillis() - h.requestedAt}ms 만에)")
        main.post { glow?.stop(); overlay?.hitlPaused() }
        speak("넘겨받았어요. 끝나면 이어서를 눌러 주세요")
    }

    /** 「▶ 이어서」 — 사람 차례일 때만 */
    fun resumeTask() {
        val h = hitl ?: return
        if (!h.acked || h.resumed) return
        // 말하기 창에 보내지 않은 말이 있으면 거둔다(닫으면 버려졌다). 메인 스레드에서 불리므로 곧바로 읽는다
        val draft = if (Looper.myLooper() == Looper.getMainLooper()) QuickAskActivity.takeDraft(h.id) else null
        draft?.let { h.addSaid(it); Log.i(TAG, "hitl 말하기 창의 보내지 않은 말 ${it.length}자 거둠") }
        h.resumed = true
        Log.i(TAG, "hitl 이어서 — 조작 ${h.eventCount()}개 · 말 ${h.saidCount()}번")
        // 말하기 창이 떠 있으면 먼저 닫는다 — 에이전트가 이어서 읽는 「지금 화면」이 그 창이 되지 않게
        main.post { QuickAskActivity.endReply(h.id) }
        h.latch.countDown()
    }

    /**
     * 이어서·정지·시한 — 기록을 끄고 패널을 되돌린다. 에이전트 스레드가 부른다.
     * @param resume 이어서면 화면 켜짐 유지·테두리 빛을 다시 켠다(정지·시한이면 실행 끝이 끈다)
     */
    fun hitlEnd(h: Hitl, resume: Boolean = false) {
        synchronized(askLock) { if (hitl === h) hitl = null else return }
        // 평소 셋(agent_service.xml)엔 멈춤 때 더한 종류가 없다 — 그것만 뺀다
        if (h.acked) runCatching { serviceInfo = serviceInfo.apply { eventTypes = eventTypes and Hitl.EXTRA_TYPES.inv() } }
        if (resume && running != null) keepAwake.on()
        main.post {
            QuickAskActivity.endReply(h.id)
            if (resume && running != null && glowOn) glow?.start()
            overlay?.hitlEnd()
        }
    }

    /** 「💬 말하기」 — 빠른 입력을 답하기 모드로(말·키보드). 여러 번 할 수 있고, 이어서 때 모아 전한다 */
    fun hitlSay() {
        val h = hitl ?: return
        if (!h.acked || h.resumed) return
        runCatching {
            startActivity(Intent(this, QuickAskActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(QuickAskActivity.EXTRA_CHOOSE_REPLY, h.id)
                .putExtra(QuickAskActivity.EXTRA_CHOOSE_QUESTION, "에이전트에게 알려 줄 말"))
        }.onFailure { Log.w(TAG, "hitl 말하기 창: ${it.javaClass.simpleName}") }
    }

    /** 패널을 눌렀다 — 그 작업의 대화방으로. id 가 없으면 지금 이어 가는 방 */
    fun openRoom(id: String?) {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .apply { if (id.isNullOrBlank()) putExtra("showChat", true) else putExtra(MainActivity.EXTRA_OPEN_ROOM, id) })
        }.onFailure { Log.w(TAG, "방 열기: ${it.javaClass.simpleName}") }
        Log.i(TAG, "패널 탭 → 방 ${id ?: "(지금 방)"}")
    }

    // ---- 채팅 화면 연결: 진행 단계·끝을 화면이 떠 있으면 바로 보여 준다
    val listeners = java.util.concurrent.CopyOnWriteArrayList<(String, String) -> Unit>()
    fun emit(kind: String, text: String) {
        if (kind == "step") stepCount++
        main.post { listeners.forEach { it(kind, text) } }
    }

    /** 채팅창을 앞으로 — 화면 조작 없이 끝난 대화형 답을 보여 줄 때. 접근성 서비스라 백그라운드 시작이 허용된다. */
    fun showChat() {
        main.post {
            runCatching {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra("showChat", true))
            }
        }
    }

    /** 돌고 있는 작업이 없으면 delay 뒤 패널을 숨긴다(그 사이 새 작업이 시작되면 그대로 둔다). */
    private fun hideWhenIdle(delayMs: Long) {
        // 답을 읽는 동안엔 패널(「■ 읽기 멈춤」)을 남긴다 — 다 읽을 때까지 1초마다 다시 본다
        // 원격 확인이 떠 있으면 남긴다 — 닫으면 답 없이 사라진다
        main.postDelayed({ if (running == null && remoteAsk == null) { if (speaking) hideWhenIdle(1000) else overlay?.hide() } }, delayMs)
    }

    fun status(s: String) {
        Log.i(TAG, s)
        lastStatus = s
        // 채팅 화면이 앞에 있으면 패널을 띄우지 않는다 — 앱이 상태 카드·진행 단계를 이미 보여 주고, 패널이 대화방 머리를 가렸다(2026-09-27).
        // 확인 창(ask)은 이 길을 거치지 않으므로 어디서든 뜬다
        main.post { if (MainActivity.current == null) overlay?.show(Plain.of(s)) }
    }

    /** 답을 소리 내어 읽는 중 — 패널·앱 화면이 「■ 읽기 멈춤」을 보인다(2026-09-27 사용자: 언제든 멈출 수 있게) */
    @Volatile var speaking = false
        private set

    private fun setSpeaking(on: Boolean) {
        if (speaking == on) return
        speaking = on
        main.post { overlay?.refreshButton() }
        emit("tts", if (on) "1" else "0")
    }

    fun speak(s: String) {
        if (!Prefs.speakAnswers(this)) return
        var text = Plain.of(s)
        // 설정 「음성 답변 길이 = 첫 문장만」 — 긴 답은 화면으로 읽는다
        if (Prefs.ttsLength(this) == "first") text = text.split(Regex("(?<=[.!?。])\\s+")).first().trim().ifEmpty { text }
        tts?.setSpeechRate(Prefs.ttsRate(this))
        tts?.setPitch(Prefs.ttsPitch(this))
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "agent")
    }

    /** 패널의 닫기·정지·읽기 멈춤, 앱의 「■ 읽기 멈춤」 — 읽던 음성을 바로 멈춘다 */
    fun stopSpeaking() {
        val was = tts?.isSpeaking == true
        tts?.stop()
        setSpeaking(false)
        Log.i(TAG, "stopSpeaking: was=$was now=${tts?.isSpeaking == true}")
    }

    /** 오버레이에 실행/취소를 띄우고 사용자의 답을 기다린다(40초 무응답 = 취소). */
    /** 확인 창이 떠서 사용자 답을 기다리는 중 — 앱 화면이 상태 카드를 호박색으로 그린다(표시용, 판정과 무관) */
    @Volatile var confirming = false
        private set

    /** 지금 답을 기다리는 확인 — (첫 답, latch). 정지(cancelTask)가 거부로 풀 수 있게 잡아 둔다 */
    @Volatile private var pendingConfirm: Pair<java.util.concurrent.atomic.AtomicReference<Boolean?>, CountDownLatch>? = null

    /** 지금 패널에 떠 있는 확인 카드의 주인 — 자기 카드일 때만 치운다(원격 확인을 에이전트 확인이 밀어냈을 때 남의 카드를 닫지 않게). 메인 스레드에서만 */
    private var askOwner: Any? = null
    private val askLock = Any()

    /** @param timeoutMs 답을 기다리는 시한(기본 40초). 무응답·답 없이 닫힘 = 거부 */
    fun confirm(question: String, timeoutMs: Long = Overlay.CONFIRM_MS): Boolean {
        val latch = CountDownLatch(1)
        // 먼저 온 답 하나만 친다 — 정지로 거부된 뒤 늦게 누른 「실행」이 뒤집지 못하게
        val answer = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val mine = answer to latch
        synchronized(askLock) {
            pendingConfirm = mine
            confirming = true
            // 원격 확인(맡긴 일)이 떠 있으면 밀어낸다 — 에이전트 확인이 먼저. 원격은 대기 상한이 길어 JobWatcher 가 다시 띄운다
            remoteAsk?.let { r -> if (r.answer.compareAndSet(null, RemoteAnswer.WITHDRAWN)) r.latch.countDown() }
        }
        // 맥에서 확인 창이 떴는지 보는 표지 — uiautomator dump 는 실행 중 접근성 서비스를 끊어 작업이 취소된다(2026-09-26).
        // 첫 줄·길이만 찍는다 — 비서 확인 창엔 받는 사람·제목·본문 앞부분이 들어 logcat 에 남았다(리뷰 safety#5)
        val head = question.lineSequence().first()
        Log.i(TAG, "confirm_ask「${head.take(60)}」(${question.length}자)")
        // 소리로도 첫 줄만 — 본문·주소까지 읽으면 길고 공공장소에서 새어 나간다(리뷰 runtime#8). 한 줄짜리 확인(설정·화면 게이트)은 전과 같다
        speak("확인이 필요해요. $head")
        main.post { askOwner = mine; glow?.phase(RunPhase.WAIT); overlay?.ask(question, timeoutMs, Plain.of(lastStatus.ifBlank { "작업 중" })) { a -> answer.compareAndSet(null, a == true); latch.countDown() } }
        val answered = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        synchronized(askLock) {
            if (pendingConfirm === mine) pendingConfirm = null
            confirming = false
        }
        val ok = answer.get() == true
        main.post { if (askOwner === mine) { askOwner = null; overlay?.clearAsk() }; glow?.phase(RunPhase.ACT) }
        hideWhenIdle(3000)  // 작업 없이 확인만 띄운 경우(시험 등) 패널이 남지 않게
        Log.i(TAG, "confirm「$question」 → ${if (!answered) "timeout" else ok}")
        return answered && ok
    }

    /** 선택 카드가 떠 있는 중 — 원격 확인이 겹쳐 뜨지 않게(BUSY). 「직접 답하기」 창이 떠 있는 동안도 이 상태 그대로다 */
    @Volatile private var choosing = false

    /** choose() 의 답 — picked: 0.. 고른 것 · [CHOSE_STOP] 그만 · [CHOSE_REPLY] 직접 답(글은 reply) */
    data class Choice(val picked: Int, val how: String, val reply: String? = null)

    /** 지금 답을 기다리는 선택 카드. id = 「직접 답하기」 창과 맞춰 보는 요청 번호(되살린 옛 인텐트·다른 요청을 가린다) */
    private class ChooseSlot(val id: String, val question: String, val recommended: Int) {
        val answer = java.util.concurrent.atomic.AtomicReference<Choice?>(null)
        val latch = CountDownLatch(1)
        /** 「직접 답하기」 창을 연 때부터의 시한(uptime). 0 = 아직 안 엶 — 선택 카드 시한을 쓴다 */
        @Volatile var replyUntil = 0L
    }
    @Volatile private var pendingChoose: ChooseSlot? = null

    /**
     * 방법 고르기(choose_way) — 선택 카드를 띄우고 답을 기다린다. 작업 스레드에서 부를 것.
     * 확인 창(에이전트·원격)이 떠 있으면 기다리지 않고 곧바로 추천안(카드 충돌 방지), 창을 못 띄워도 추천안.
     * 카드 맨 아래 「✎ 직접 답하기」 → 빠른 입력(QuickAskActivity)을 답하기 모드로 열고 시한을 [REPLY_WAIT_MS] 로 바꾼다(사용자 요청 2026-09-28).
     * @return how = tap | stop | timeout | closed | busy | no_window | cancel | reply | reply_timeout | reply_closed
     */
    fun choose(question: String, options: List<Pair<String, String>>, recommended: Int, timeoutMs: Long): Choice {
        val mine = ChooseSlot("ch${SystemClock.uptimeMillis()}", question, recommended)
        val answer = mine.answer
        val latch = mine.latch
        synchronized(askLock) {
            if (confirming || remoteAsk != null || choosing) return Choice(recommended, "busy")
            choosing = true
            pendingChoose = mine
        }
        Log.i(TAG, "choose_ask「${question.take(60)}」 ${options.size}개 추천=$recommended")
        speak("어떻게 할까요")
        main.post {
            if (answer.get() != null) return@post
            askOwner = mine
            glow?.phase(RunPhase.WAIT)
            val shown = overlay?.choose(question, options, recommended, timeoutMs, Plain.of(lastStatus.ifBlank { "작업 중" }),
                onReply = { openChooseReply(mine) }) { i ->
                answer.compareAndSet(null, when (i) { null -> Choice(recommended, "closed"); -1 -> Choice(CHOSE_STOP, "stop"); else -> Choice(i, "tap") })
                latch.countDown()
            } ?: false
            if (!shown) { answer.compareAndSet(null, Choice(recommended, "no_window")); latch.countDown() }
        }
        // 시한: 「직접 답하기」를 열면 그때부터 REPLY_WAIT_MS 로 바뀐다 — 조금씩 끊어 기다리며 다시 본다
        val cardUntil = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val until = mine.replyUntil.takeIf { it > 0 } ?: cardUntil
            val left = until - SystemClock.uptimeMillis()
            if (left <= 0 || latch.await(minOf(left, 500L), TimeUnit.MILLISECONDS)) break
        }
        answer.compareAndSet(null, Choice(recommended, if (mine.replyUntil > 0) "reply_timeout" else "timeout"))
        synchronized(askLock) {
            if (pendingChoose === mine) pendingChoose = null
            choosing = false
        }
        main.post {
            if (askOwner === mine) { askOwner = null; overlay?.clearAsk() }
            glow?.phase(RunPhase.ACT)
            QuickAskActivity.endReply(mine.id)   // 답하기 창이 남아 있으면 닫는다(옵션을 눌렀거나 시한·정지)
        }
        hideWhenIdle(3000)   // 시험(choosetest)처럼 작업 없이 띄운 경우 패널이 남지 않게
        val res = answer.get()!!
        Log.i(TAG, "choose → ${res.picked}(${res.how})${res.reply?.let { " ${it.length}자" } ?: ""}")
        return res
    }

    /** 선택 카드의 「✎ 직접 답하기」 — 시한을 바꾸고 빠른 입력을 답하기 모드로 연다. 메인 스레드 */
    private fun openChooseReply(slot: ChooseSlot) {
        if (slot.answer.get() != null || pendingChoose !== slot) return
        slot.replyUntil = SystemClock.uptimeMillis() + REPLY_WAIT_MS
        Log.i(TAG, "choose 직접 답하기 열기 ${slot.id}")
        val ok = runCatching {
            startActivity(Intent(this, QuickAskActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(QuickAskActivity.EXTRA_CHOOSE_REPLY, slot.id)
                .putExtra(QuickAskActivity.EXTRA_CHOOSE_QUESTION, slot.question))
        }.isSuccess
        // 창을 못 열었으면 기다릴 것 없이 추천안(카드는 이미 시한을 멈췄다)
        if (!ok && slot.answer.compareAndSet(null, Choice(slot.recommended, "reply_closed"))) slot.latch.countDown()
    }

    /** 답하기 창이 받아도 되는 요청인가 — choose 가 지금 이 id 로 답을 기다리고, 「직접 답하기」를 누른 뒤일 때만 */
    fun chooseReplyPending(id: String?): Boolean {
        // 사람 개입의 「💬 말하기」도 같은 답하기 창을 쓴다 — id 로 가린다
        hitl?.let { h -> if (id != null && id == h.id) return h.acked && !h.resumed }
        val p = pendingChoose ?: return false
        return id != null && p.id == id && p.replyUntil > 0 && p.answer.get() == null
    }

    /**
     * 답하기 창에서 보냄(text) 또는 답 없이 닫힘(text=null → 추천안, reply_closed). id 가 다르거나 이미 답이 났으면 무시.
     * @return 이 답이 받아들여졌나
     */
    fun chooseReply(id: String, text: String?): Boolean {
        hitl?.let { h ->
            if (id != h.id) return@let
            // 말하기는 답 없이 닫혀도 아무 일 없다(기다리는 게 없다). 글이 있으면 모아 두었다 이어서 때 전한다
            val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return true
            if (!h.acked || h.resumed) return false
            h.addSaid(t)
            Log.i(TAG, "hitl 말 ${t.length}자 (${h.saidCount()}번째)")
            main.post { overlay?.hitlPaused() }
            return true
        }
        val p = pendingChoose ?: return false
        if (p.id != id) return false
        val t = text?.trim()?.takeIf { it.isNotEmpty() }
        val ok = p.answer.compareAndSet(null, if (t != null) Choice(CHOSE_REPLY, "reply", t) else Choice(p.recommended, "reply_closed"))
        if (ok) p.latch.countDown()
        return ok
    }

    /** 원격 확인의 결과. NONE = 답 없이 닫힘·시한 만료(서버가 시한에 자동 거부) · BUSY = 다른 확인이 떠 있어 못 띄움 · WITHDRAWN = 에이전트 확인에 밀림·치움 */
    enum class RemoteAnswer { ALLOW, DENY, NONE, BUSY, WITHDRAWN }

    private class RemoteAskSlot(val key: String, val answer: java.util.concurrent.atomic.AtomicReference<RemoteAnswer?>, val latch: CountDownLatch)
    @Volatile private var remoteAsk: RemoteAskSlot? = null

    /**
     * 홈맥에 맡긴 일(Claude Code)이 폰 승인을 기다린다 — 에이전트 확인과 같은 패널 카드에 전문을 띄우고 답을 기다린다(계약 DELEGATION §2-2·§3).
     * 에이전트 확인과 달리 답 없이 닫히거나 시한이 지나면 「답하지 않음」(NONE) — 거부를 보내지 않고 서버 시한(10분)에 맡긴다.
     * 에이전트 확인이 떠 있으면 띄우지 않고(BUSY), 떠 있는 중에 에이전트 확인이 오면 밀려난다(WITHDRAWN). 작업 스레드에서 부를 것.
     * @param key 같은 요청 판별(jobId:reqId) — [withdrawRemote] 가 쓴다
     */
    fun confirmRemote(key: String, question: String, timeoutMs: Long, context: String = "🤖 맡긴 일"): RemoteAnswer {
        val slot = RemoteAskSlot(key, java.util.concurrent.atomic.AtomicReference(null), CountDownLatch(1))
        synchronized(askLock) {
            if (confirming || remoteAsk != null || choosing) return RemoteAnswer.BUSY
            remoteAsk = slot
        }
        val head = question.lineSequence().first()
        Log.i(TAG, "confirm_ask_remote「${head.take(60)}」(${question.length}자) ${timeoutMs / 1000}초")
        speak("확인이 필요해요. $head")
        main.post {
            if (slot.answer.get() != null) return@post   // 띄우기 전에 이미 밀렸다
            askOwner = slot
            overlay?.ask(question, timeoutMs, context) { a ->
                slot.answer.compareAndSet(null, when (a) { true -> RemoteAnswer.ALLOW; false -> RemoteAnswer.DENY; null -> RemoteAnswer.NONE })
                slot.latch.countDown()
            }
        }
        val answered = slot.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        synchronized(askLock) { if (remoteAsk === slot) remoteAsk = null }
        slot.answer.compareAndSet(null, RemoteAnswer.NONE)   // 시한 만료
        val res = slot.answer.get() ?: RemoteAnswer.NONE
        main.post {
            if (askOwner === slot) { askOwner = null; overlay?.clearAsk() }
            // 답한 뒤 윗줄: 에이전트가 돌고 있으면 그 상태로, 아니면 이 맡긴 일의 결과 한 줄(곧 닫힌다)
            if (running != null) overlay?.show(Plain.of(lastStatus.ifBlank { "작업 중" }))
            else when (res) {
                RemoteAnswer.ALLOW -> overlay?.show("$context — 실행함, 홈맥에서 계속 진행 중")
                RemoteAnswer.DENY -> overlay?.show("$context — 거부함")
                else -> {}
            }
        }
        hideWhenIdle(3000)
        Log.i(TAG, "confirm_remote $key → $res${if (!answered) "(시한)" else ""}")
        return res
    }

    /** 원격 확인이 더는 필요 없다(서버에서 풀림·다른 상태로) — 떠 있는 그 카드를 치운다 */
    fun withdrawRemote(key: String) {
        val r = remoteAsk ?: return
        if (r.key == key && r.answer.compareAndSet(null, RemoteAnswer.WITHDRAWN)) r.latch.countDown()
    }

    /**
     * 채팅 화면(MainActivity)이 앞에 있으면 뒤로 물린다 — 앱 안에서 시킨 일이 화면 조작을 처음 필요로 할 때만(Agent.execute).
     * 물러난 뒤엔 그 전에 보던 앱이 앞에 온다. 우리 앱 창이 창 목록에서 사라질 때까지 기다린다(최대 1.5초).
     * @return 물렸으면 true
     */
    enum class Yield { NOT_NEEDED, YIELDED, FAILED }

    fun yieldApp(): Yield {
        if (!ourAppShown()) { glowOnForTask(); return Yield.NOT_NEEDED }
        // 채팅 화면이 떠 있는데 액티비티 참조가 없으면(분할 화면 등) HOME 으로라도 치운다
        main.post { MainActivity.current?.moveTaskToBack(true) ?: performGlobalAction(GLOBAL_ACTION_HOME) }
        var n = 0
        while (n < 15 && ourAppShown()) { Thread.sleep(100); n++ }
        // 못 치웠으면 조작하지 않는다 — 우리 채팅 화면을 「조작할 화면」으로 넘겨 우리 입력줄·버튼을 누르게 된다(2026-09-27 리뷰 M1)
        if (ourAppShown()) { Log.w(TAG, "채팅 화면을 치우지 못함"); return Yield.FAILED }
        glowOnForTask()
        waitIdle()
        Log.i(TAG, "채팅 화면 물림 ${n * 100}ms")
        return Yield.YIELDED
    }

    /** 채팅 화면에서 시킨 일이 폰을 조작하기 시작했다 — 테두리 빛을 켠다(이미 켜졌으면 그대로) */
    private fun glowOnForTask() {
        main.post { if (running != null && !glowOn) { glowOn = true; glow?.start() } }
    }
    @Volatile private var glowOn = false

    /** 작업 중에 사용자가 채팅 화면을 떠났다 — 곧바로 패널(■ 정지)과 빛을 보인다(다음 status 까지 기다리지 않게, 리뷰 M2) */
    fun onChatLeft() {
        if (running == null) return
        main.post { if (running != null) { overlay?.show(Plain.of(lastStatus.ifBlank { "작업 중" })); overlay?.refreshButton() } }
        glowOnForTask()
    }

    private fun ourAppShown(): Boolean = runCatching {
        windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName == packageName }
    }.getOrDefault(false)

    /** 동작 뒤 화면이 잠잠해질 때까지(이벤트가 400ms 끊길 때까지, 최대 2.5초) 기다린다. */
    fun waitIdle() {
        Thread.sleep(350)
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < 2500) {
            if (SystemClock.uptimeMillis() - lastEventAt > 400) break
            Thread.sleep(100)
        }
    }

    /**
     * 지금 화면을 JPEG(base64)로 — look 도구용. 긴 변 1024px 로 줄인다(프록시 부하·지연↓, 개구리 퀘스트 실측).
     * 우리 상태 패널이 사진을 가리지 않게 잠깐 숨긴다. 보안 화면(FLAG_SECURE)은 검게 나오거나 실패한다.
     */
    fun screenshotJpegBase64(): String? {
        val latch = CountDownLatch(1)
        var out: String? = null
        main.post { overlay?.setVisible(false); glow?.setCaptureHidden(true) }
        Thread.sleep(150)
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, java.util.concurrent.Executors.newSingleThreadExecutor(),
            object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    runCatching {
                        val hw = android.graphics.Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                        val bmp = hw!!.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        r.hardwareBuffer.close()
                        val scale = 1024f / maxOf(bmp.width, bmp.height)
                        val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
                        val bos = java.io.ByteArrayOutputStream()
                        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, bos)
                        out = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                    }.onFailure { Log.w(TAG, "screenshot encode: $it") }
                    latch.countDown()
                }
                override fun onFailure(code: Int) { Log.w(TAG, "screenshot failed $code"); latch.countDown() }
            })
        latch.await(5, TimeUnit.SECONDS)
        main.post { overlay?.setVisible(true); glow?.setCaptureHidden(false) }
        return out
    }

    /** 지금 화면을 비트맵으로(우리 상태 패널은 숨기고). 보안 화면은 실패(null) */
    fun captureBitmap(): android.graphics.Bitmap? {
        val latch = CountDownLatch(1)
        var out: android.graphics.Bitmap? = null
        main.post { overlay?.setVisible(false); glow?.setCaptureHidden(true) }
        Thread.sleep(150)
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, java.util.concurrent.Executors.newSingleThreadExecutor(),
            object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    runCatching {
                        val hw = android.graphics.Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                        out = hw!!.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        r.hardwareBuffer.close()
                    }.onFailure { Log.w(TAG, "capture: $it") }
                    latch.countDown()
                }
                override fun onFailure(code: Int) { Log.w(TAG, "capture failed $code"); latch.countDown() }
            })
        latch.await(5, TimeUnit.SECONDS)
        main.post { overlay?.setVisible(true); glow?.setCaptureHidden(false) }
        return out
    }

    /**
     * 화면의 한 영역만 잘라 갤러리 「스크린샷」(DCIM/Screenshots)에 저장한다. 삼성 캡처 툴바의 편집·자르기는 몇 초 뒤 사라지고
     * 좌표로 핸들을 끌어야 해서 못 했다(2026-09-26 run 170942-494) — 우리가 찍어서 우리가 자른다. 자기 앱이 만든 파일이라 권한이 필요 없다.
     * @return 저장한 상대 경로, 실패면 null
     */
    fun saveCapture(bmp: android.graphics.Bitmap, crop: android.graphics.Rect?, tag: String): String? {
        val r = crop?.let { android.graphics.Rect(it).apply { if (!intersect(0, 0, bmp.width, bmp.height)) return null } }
        val img = if (r == null) bmp else android.graphics.Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
        val name = "FoldAgent_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(java.util.Date()) +
            (if (tag.isNotBlank()) "_" + tag.replace(Regex("[^0-9A-Za-z가-힣]+"), "_").take(20) else "") + ".jpg"
        val cv = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Screenshots")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        contentResolver.openOutputStream(uri)?.use { img.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) } ?: return null
        contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return "DCIM/Screenshots/$name"
    }

    /**
     * 받은 이미지 바이트를 **다시 인코딩하지 않고 그대로** 갤러리(기본 Pictures/FoldAgent)에 저장한다 — image_gen 결과 PNG 용
     * (계약 cc-capabilities §1-1). 이름은 부르는 쪽이 FoldAgent_ 로 시작하게 준다 — 그래야 share_images·trash_captures 가 우리 파일로 찾는다.
     * 자기 앱이 만든 파일이라 권한이 필요 없다. 작업 스레드에서 부를 것(디스크 쓰기).
     * @return 저장한 content uri, 실패면 null(쓰다 실패하면 만든 빈 항목은 지운다 — 우리 소유 파일이라 권한 없이 된다)
     */
    fun saveImageBytes(bytes: ByteArray, name: String, mime: String = "image/png", relPath: String = Gallery.REL_PATH): android.net.Uri? {
        // 저장 창구는 Gallery.save 하나(CapTools·JobFiles 와 같은 길) — 여기선 바이트를 그대로 쓰고 실패를 null 로 바꾼다
        val uri = runCatching { Gallery.save(this, name, mime, relPath) { it.write(bytes); bytes.size.toLong() }.first }
            .getOrElse { Log.w(TAG, "saveImageBytes: ${it.javaClass.simpleName}"); return null }
        Log.i(TAG, "saveImageBytes $relPath/$name ${bytes.size}B")
        return uri
    }

    /**
     * 사용자가 찍은 것과 같은 시스템 스크린샷(갤러리 「스크린샷」에 저장). 우리 상태 패널이 찍히지 않게 잠깐 숨긴다.
     * 찍고 나면 삼성 캡처 툴바가 잠깐 뜬다 — 그게 사라질 때까지 기다리지 않고 패널만 되돌린다.
     */
    fun systemScreenshot(): Boolean {
        main.post { overlay?.setVisible(false); glow?.setCaptureHidden(true) }
        Thread.sleep(250)
        val ok = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        Thread.sleep(1200)   // 캡처 애니메이션 동안 패널을 숨긴 채로
        main.post { overlay?.setVisible(true); glow?.setCaptureHidden(false) }
        return ok
    }

    /**
     * 키보드처럼 글자를 넣는다(Android 13+ 접근성 입력기, flagInputMethodEditor). 서식 편집기(삼성 노트)는 SET_TEXT 를 거부하고,
     * 뒤에 있는 앱의 클립보드 쓰기는 불안정해 붙여넣기가 실패했다(2026-09-26) — 이 경로는 둘 다 필요 없다.
     * 입력칸에 포커스가 있어야 연결이 생긴다. @return 넣었으면 true
     */
    fun commitTextViaIme(text: String): Boolean {
        val ic = inputMethod?.currentInputConnection ?: return false
        return runCatching { ic.commitText(text, 1, null); true }.getOrElse { Log.w(TAG, "commitText: $it"); false }
    }

    /**
     * 커서 앞 글자를 입력기 연결에서 직접 읽는다(Android 13+). 접근성 트리는 서식 편집기(삼성 노트)에서 2초 넘게 늦게 갱신돼
     * 「들어갔나」 판정이 양쪽으로 틀렸다(2026-09-26) — 편집기 내부 값이라 지연이 없다. 읽을 수 없으면 null
     */
    fun textBeforeCursor(n: Int): String? =
        runCatching { inputMethod?.currentInputConnection?.getSurroundingText(n, 0, 0)?.text?.toString() }.getOrNull()

    /** 커서 뒤 글자 — 이어쓰기 전에 커서가 정말 본문 끝인지(뒤에 글이 없는지) 본다. 읽을 수 없으면 null */
    fun textAfterCursor(n: Int): String? =
        runCatching { inputMethod?.currentInputConnection?.getSurroundingText(0, n, 0)?.text?.toString() }.getOrNull()   // 접근성 입력기엔 getTextAfterCursor 가 없다

    /** 키보드가 떠 있나 — 떠 있을 때 스와이프하면 키보드 위를 지나가 글자가 찍힌다(2026-09-26 사용자 노트에 「ㅛ」 14자) */
    fun keyboardShown(): Boolean = windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    /** 입력기가 지금 붙어 있는 칸의 정보 — 어느 칸에 쓰게 될지 쓰기 전에 확인한다(접근성 포커스는 삼성 노트 본문에서 안 잡혔다) */
    fun imeEditorInfo(): android.view.inputmethod.EditorInfo? = runCatching { inputMethod?.currentInputEditorInfo }.getOrNull()
    fun imeConnected(): Boolean = runCatching { inputMethod?.currentInputConnection != null }.getOrDefault(false)

    /**
     * 편집기의 글 전체(커서 앞뒤)와 그 시작 위치 — 긴 노트는 접근성 트리에 본문이 안 나와(2026-09-26 QA15, 「3페이지 중 2페이지」만 보임)
     * 입력기 연결로 읽는다. @return (글, 편집기 안에서의 시작 위치) · 연결 없으면 null
     */
    fun imeFullText(): Pair<String, Int>? = runCatching {
        val st = inputMethod?.currentInputConnection?.getSurroundingText(20000, 20000, 0) ?: return null
        st.text.toString() to st.offset
    }.getOrNull()

    fun imeSetCursor(pos: Int): Boolean = runCatching { inputMethod?.currentInputConnection?.setSelection(pos, pos); true }.getOrDefault(false)

    /** 입력기로 Ctrl+End — 문서 끝으로 커서. 누르면 커서가 누른 자리로 가서 쓴다 */
    fun imeCtrlEnd(): Boolean = runCatching {
        val ic = inputMethod?.currentInputConnection ?: return false
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MOVE_END, 0, android.view.KeyEvent.META_CTRL_ON))
        ic.sendKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MOVE_END, 0, android.view.KeyEvent.META_CTRL_ON))
        true
    }.getOrDefault(false)

    /** 화면 밖 좌표는 누르지 않는다 — 스크롤된 본문의 위쪽(음수)을 누르려다 제스처 생성 예외로 앱이 죽었다(2026-09-26 QA20) */
    fun tapAt(x: Float, y: Float, durationMs: Long = 60): Boolean {
        val dm = resources.displayMetrics
        if (x < 0 || y < 0 || x >= dm.widthPixels || y >= dm.heightPixels) { Log.w(TAG, "tapAt 화면 밖 ($x,$y) — 안 누름"); return false }
        return gesture(Path().apply { moveTo(x, y) }, durationMs)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, durationMs)

    /** 제스처는 오버레이를 뚫고 가야 하므로 그동안 오버레이를 터치 불가로 둔다. */
    private fun gesture(path: Path, durationMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        // 패널을 터치 통과로 바꾸는 것(updateViewLayout)은 비동기라, 같은 순간 제스처를 보내면 반영 전에 탭이 패널에 떨어진다 —
        // 맨 위 툴바(삼성 노트 「삽입」)를 좌표로 누르다 패널의 정지 버튼을 눌러 작업이 스스로 취소됐다(2026-09-26 run 172029-564).
        // 먼저 바꾸고 반영될 시간을 준 뒤 보낸다.
        main.post { overlay?.setTouchable(false) }
        Thread.sleep(250)
        main.post {
            // 여기서 예외가 나면 메인 스레드라 앱 전체가 죽는다 — 실패로만 돌린다
            val g = runCatching { GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build() }
                .getOrElse { Log.w(TAG, "gesture: $it"); latch.countDown(); return@post }
            val sent = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(d: GestureDescription?) { latch.countDown() }
            }, null)
            if (!sent) latch.countDown()
        }
        latch.await(durationMs + 3000, TimeUnit.MILLISECONDS)
        main.post { overlay?.setTouchable(true) }
        return ok
    }
}
