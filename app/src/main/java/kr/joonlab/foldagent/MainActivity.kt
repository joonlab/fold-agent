package kr.joonlab.foldagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.json.JSONArray
import org.json.JSONObject
import kr.joonlab.foldagent.records.Records
import kr.joonlab.foldagent.ui.AppActions
import kr.joonlab.foldagent.ui.AppState
import kr.joonlab.foldagent.ui.FoldAgentApp
import kr.joonlab.foldagent.ui.HingeSensor
import kr.joonlab.foldagent.ui.HomeActions
import kr.joonlab.foldagent.ui.Posture
import kr.joonlab.foldagent.ui.RoomActions

/**
 * 앱 화면 — 홈(상태 카드 + 작업 기록) · 대화방 · 설정. 화면은 Compose(ui 패키지), 이 파일은 입구·음성·실행만 맡는다.
 * 측면 버튼(두 번 누르기 → 앱 열기)에 물려 두면 누르자마자 듣는다.
 * 인식된 문장을 AgentService 에 넘기고 뒤로 물러나 원래 화면을 에이전트에게 내준다.
 *
 * 테스트용(음성 없이): adb shell am start -n kr.joonlab.foldagent/.MainActivity --es cmd "알람 7시"
 * 화면 시험용: … --es screen "home|room:<id>|trash|settings" (디버그 빌드만)
 * 자세 시험용: … --es posture "cover|coverland|open|openrot|table|auto" (디버그 빌드만 — 접지 않고 자세별 배치 캡처, auto = 해제)
 * 홈 비서: … --es assistantToken <값> --es assistantBase <주소> --ez assistantHealth true (디버그 빌드만 — 결과는 logcat FoldAgent)
 * 맡긴 작업 알림을 누르면 --es openRoom <세션 id> 로 온다(jobs/JobNotify) — 그 방을 연다(이어 갈 방은 바꾸지 않는다)
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "FoldAgent"
        private const val REQ_MIC = 1
        private const val REQ_NOTIF = 2
        /** 진행 패널을 탭 → 그 작업의 대화방(AgentService.openRoom). 값 = 세션 id */
        const val EXTRA_OPEN_ROOM = "openRoom"
        /** 앞에 떠 있는 채팅 화면 — 에이전트가 화면 조작 직전에 뒤로 물린다(AgentService.yieldApp) */
        @Volatile var current: MainActivity? = null
            private set
    }

    private var listenOnResume = false

    // 누르면 시작, 한 번 더 누르면 끝. 인식 로직(재사용 인식기·온디바이스 폴백·재청취)은 VoiceInput 에 있다.
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val pauseCancel = Runnable { cancelSession(quiet = true) }
    private val voice: VoiceInput by lazy { VoiceInput(this, voiceCb) }

    /** 화면 상태 한 벌 — configChanges 로 액티비티가 다시 만들어지지 않으니 접고 펴도 그대로다 */
    private val st: AppState by lazy { AppState(this) }
    private val listener: (String, String) -> Unit = { kind, text ->
        st.onAgentEvent(kind, text)
        // 이번 실행이 홈맥에 일을 맡겼으면(원격 작업 생김) 끝날 때 알림 권한을 묻는다 — 이벤트는 메인 스레드로 온다
        if (kind == "finish") ui.post { maybeAskNotifications() }
    }
    /** 알림 권한은 이 프로세스에서 한 번만 묻는다(두 번 거부하면 시스템이 더 안 띄운다) */
    private var askedNotif = false
    /** 반접힘 판정(테이블톱) — onResume 에 켜고 onPause 에 끈다(뒤에 있을 때 센서를 잡고 있지 않게) */
    private val hinge by lazy { HingeSensor(this) { half -> st.halfOpen = half } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Records.init(this)
        enableEdgeToEdge()
        Records.store.addListener(st.recordListener)
        val stop = { AgentService.instance?.cancelTask(); Unit }
        val actions = AppActions(
            home = HomeActions(stop = stop, mic = { toggleMic("choice") },
                openA11y = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                // 입력줄에 첨부 칩이 있으면 먼저 올린다(AppState.sendWithFiles — 하나라도 실패하면 실행 안 함)
                send = { text -> st.sendWithFiles("choice", text, JSONObject().put("source", "text")) { m -> sendViaChoice(text, m) } }),
            room = RoomActions(stop = stop, send = { text -> st.sendWithFiles("room", text, JSONObject().put("source", "text")) { m -> run(text, m) } },
                mic = { toggleMic("room") }, micCancel = { cancelSession() }),
        )
        setContent { FoldAgentApp(st, actions) }
        st.reload()
        handle(intent, fresh = savedInstanceState == null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent, fresh = true)
    }

    private fun handle(i: Intent, fresh: Boolean) {
        // ⚠️ 시스템은 화면을 다시 만들 때(최근 앱에서 열기·프로세스 재시작) 마지막 시작 인텐트를 그대로 다시 준다.
        // removeExtra 는 메모리 사본만 지우므로, 옛 명령이 되살아나 재실행됐다(2026-09-26 실사고). 새로 받은 인텐트만 명령으로 친다.
        val fromHistory = i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val replay = !fresh || fromHistory
        // 시험용: 진행 중인 작업 취소(사용자가 정지를 누른 것과 같음) — 「멈춘 요청 이어 하기」 QA 용
        if (BuildConfig.DEBUG && !replay && i.getBooleanExtra("cancel", false)) { i.removeExtra("cancel"); AgentService.instance?.cancelTask(); moveTaskToBack(true); return }
        // 디버그 전용: 홈 비서 연결 설정·확인(계약 §3 W-C). 값은 로그에 찍지 않는다 — 토큰은 끝 4자만
        // adb … --es assistantToken <값> · --es assistantBase <주소> · --ez assistantHealth true (함께 주면 저장 뒤 확인)
        if (i.hasExtra("assistantToken") || i.hasExtra("assistantBase") || i.hasExtra("assistantHealth")) {
            val tok = i.getStringExtra("assistantToken"); val ab = i.getStringExtra("assistantBase")
            val check = i.getBooleanExtra("assistantHealth", false)
            i.removeExtra("assistantToken"); i.removeExtra("assistantBase"); i.removeExtra("assistantHealth")
            if (!BuildConfig.DEBUG || replay) Log.w(TAG, "재전달된 비서 설정 명령 무시")
            else {
                // 주소는 https + 믿는 호스트(BuildConfig.TRUSTED_HOST_SUFFIX)만 받는다 — exported 액티비티라 폰의 다른 앱이 주소를 바꿔
                // 다음 비서 호출의 Bearer 토큰을 제 서버로 받아 갈 수 있었다(리뷰 safety#4, 디버그 빌드)
                if (ab != null) {
                    if (Prefs.assistantBaseOk(ab, tailnetOnly = true)) { Prefs.setAssistantBase(this, ab); Log.i(TAG, "비서 주소 저장됨: ${Prefs.assistantBase(this)}") }
                    else Log.w(TAG, "비서 주소 거부: https + 믿는 호스트만 받는다(TRUSTED_HOST_SUFFIX)")
                }
                if (tok != null) { Prefs.setAssistantToken(this, tok); Log.i(TAG, "토큰 저장됨(${kr.joonlab.foldagent.ui.tokenTail(Prefs.assistantToken(this))})") }
                if (check) {
                    val b = Prefs.assistantBase(this); val t = Prefs.assistantToken(this)
                    if (t.isBlank()) Log.i(TAG, "assistantHealth 실패: 토큰 없음")
                    else Thread({
                        val r = runCatching { AssistantClient(b, t).health() }
                            .getOrElse { AssistantClient.Reply(false, null, "exec_failed", it.javaClass.simpleName) }
                        Log.i(TAG, "assistantHealth ${kr.joonlab.foldagent.ui.healthLine(r)}")
                    }, "assistant-health").start()
                }
            }
            if (!i.hasExtra("cmd")) return
        }
        val cmd = i.getStringExtra("cmd")
        if (!cmd.isNullOrBlank()) {
            i.removeExtra("cmd")
            // --es session <이름>: 시험마다 새 대화로(기본은 adb-test 하나에 계속 쌓인다)
            val meta = JSONObject().put("source", "adb")
            i.getStringExtra("session")?.let { meta.put("session", it); i.removeExtra("session") }
            if (replay) Log.w(TAG, "재전달된 옛 명령 무시: $cmd") else run(cmd, meta)
            return
        }
        if (replay && (i.hasExtra("exportapps") || i.hasExtra("confirmtest") || i.hasExtra("remoteasktest") || i.hasExtra("choosetest"))) { i.removeExtra("exportapps"); i.removeExtra("confirmtest"); i.removeExtra("remoteasktest"); i.removeExtra("choosetest"); return }
        // 디버그 전용: 런처 앱 목록(라벨·패키지·버전)을 files/apps.json 으로 — 맥에서 지식 구축용. adb … --ez exportapps true
        if (BuildConfig.DEBUG && i.getBooleanExtra("exportapps", false)) {
            i.removeExtra("exportapps")
            val pm = packageManager
            val arr = JSONArray()
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).forEach { ri ->
                val pkg = ri.activityInfo.packageName
                val pi = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
                arr.put(JSONObject().put("label", ri.loadLabel(pm).toString()).put("pkg", pkg).put("activity", ri.activityInfo.name)
                    .put("version", pi?.versionName ?: "").put("system", (ri.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                    .put("category", ri.activityInfo.applicationInfo.category))
            }
            java.io.File(getExternalFilesDir(null), "apps.json").writeText(arr.toString(1))
            Log.i(TAG, "exportapps ${arr.length()}")
            moveTaskToBack(true)
            return
        }
        // 디버그 전용: 지금(뒤에 있는) 화면에서 누를 수 있는 항목의 이름을 로그로 — 확인 게이트가 무엇을 보고 판정하는지 점검. --ez dumplabels true
        if (BuildConfig.DEBUG && !replay && i.getBooleanExtra("dumplabels", false)) {
            i.removeExtra("dumplabels")
            AgentService.instance?.let { svc ->
                moveTaskToBack(true)
                Thread {
                    Thread.sleep(1500)
                    val snap = ScreenReader.read(svc)
                    snap.nodes.forEachIndexed { k, n -> if (n.isClickable) ScreenReader.labelOf(n).let { lb -> Log.i(TAG, "label[$k] ${if (Agent.isRiskyAction(lb)) "●" else "·"} ${lb.take(90)}") } }
                }.start()
            }
            return
        }
        // 디버그 전용: 뒤 화면의 마지막 입력칸에 글을 넣어 보고 무엇이 되는지 로그로 — 입력 실패 원인 가르기. --es pastetest "글"
        val pt = i.getStringExtra("pastetest")
        if (BuildConfig.DEBUG && !replay && !pt.isNullOrBlank()) {
            i.removeExtra("pastetest")
            AgentService.instance?.let { svc ->
                moveTaskToBack(true)
                Thread {
                    Thread.sleep(1500)
                    val snap = ScreenReader.read(svc)
                    val eds = snap.nodes.filter { it.isEditable }
                    eds.forEachIndexed { k, n ->
                        Log.i(TAG, "pastetest ed[$k] cls=${n.className} focused=${n.isFocused} text=${n.text?.length} actions=${n.actionList.map { it.id }}")
                    }
                    val n = eds.lastOrNull() ?: return@Thread
                    val args = Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pt) }
                    Log.i(TAG, "pastetest SET_TEXT=${n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)}")
                    n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK); Thread.sleep(600)
                    val f = svc.rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                    Log.i(TAG, "pastetest focus=${f?.className} same=${f == n} factions=${f?.actionList?.map { it.id }}")
                    val cm = svc.getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("t", pt)); Thread.sleep(200)
                    Log.i(TAG, "pastetest clip=${cm.primaryClip?.getItemAt(0)?.text?.length} PASTE(node)=${n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)} PASTE(focus)=${f?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)}")
                }.start()
            }
            return
        }
        // 디버그 전용: 확인 게이트만 띄워 본다(실제 동작 없음). adb … --es confirmtest "문구"
        val ct = i.getStringExtra("confirmtest")
        if (BuildConfig.DEBUG && !ct.isNullOrBlank()) {
            i.removeExtra("confirmtest")
            AgentService.instance?.let { svc ->
                moveTaskToBack(true)
                Thread { Log.i(TAG, "confirmtest result=${svc.confirm(ct)}") }.start()
            }
            return
        }
        // 디버그 전용: 방법 고르기 카드만 띄워 본다(가짜 선택지 3개, 실제 동작 없음). adb … --es choosetest "질문"
        val cht = i.getStringExtra("choosetest")
        if (BuildConfig.DEBUG && !cht.isNullOrBlank()) {
            i.removeExtra("choosetest")
            AgentService.instance?.let { svc ->
                moveTaskToBack(true)
                val opts = listOf("유튜브 앱에서 직접" to "1~2분 · 화면 씀 · 무료", "웹 검색" to "30초 · 화면 안 씀 · 무료",
                    "홈맥 Claude Code 위임" to "3~5분 · 화면 안 씀 · 토큰 듦")
                Thread {
                    val ch = svc.choose(cht, opts, 0, Prefs.chooseWaitSec(svc) * 1000L)
                    val got = when (ch.picked) { AgentService.CHOSE_STOP -> "그만"; AgentService.CHOSE_REPLY -> "reply「${ch.reply.orEmpty().take(200)}」"; else -> opts[ch.picked].first }
                    Log.i(TAG, "choosetest result=$got(${ch.how})")
                }.start()
            }
            return
        }
        // 디버그 전용: 원격 확인 창만 서버 없이 띄워 본다(답은 보내지 않고 로그만). adb … --es remoteasktest "문구" [--ei sec 60]
        val rt = i.getStringExtra("remoteasktest")
        if (BuildConfig.DEBUG && !rt.isNullOrBlank()) {
            i.removeExtra("remoteasktest")
            kr.joonlab.foldagent.jobs.RemoteAsk.debugShow(rt, i.getIntExtra("sec", 60))
            return
        }
        // 원격 확인 알림을 눌렀다 — 그 방을 열고 전문 확인 창을 띄운다(DELEGATION §3). 되살린 옛 인텐트면 창을 띄우지 않는다
        val askJob = i.getStringExtra(kr.joonlab.foldagent.jobs.RemoteAsk.EXTRA_ASK_JOB)
        if (!askJob.isNullOrBlank()) {
            i.removeExtra(kr.joonlab.foldagent.jobs.RemoteAsk.EXTRA_ASK_JOB)
            val room = i.getStringExtra(kr.joonlab.foldagent.jobs.JobNotify.EXTRA_ROOM)
            i.removeExtra(kr.joonlab.foldagent.jobs.JobNotify.EXTRA_ROOM)
            if (replay) { Log.w(TAG, "재전달된 원격 확인 열기 무시"); return }
            if (!room.isNullOrBlank()) st.openRoom(room)
            kr.joonlab.foldagent.jobs.RemoteAsk.open(this, askJob)
            return
        }
        // 맡긴 작업 알림을 눌렀다 — 그 방을 연다. 최근 앱에서 되살린 옛 인텐트면 무시(방 열기뿐이라 해는 없지만 엉뚱한 방이 뜬다)
        val jobRoom = i.getStringExtra(kr.joonlab.foldagent.jobs.JobNotify.EXTRA_ROOM)
        if (!jobRoom.isNullOrBlank()) {
            i.removeExtra(kr.joonlab.foldagent.jobs.JobNotify.EXTRA_ROOM)
            // 「이어서 하기」 알림 — 그 방을 열고 남은 단계를 화면을 쓰는 실행으로 시작한다. 되살린 옛 인텐트면 실행하지 않는다(시험 명령과 같은 가드)
            val cont = i.getStringExtra(kr.joonlab.foldagent.jobs.JobNotify.EXTRA_CONTINUE)
            i.removeExtra(kr.joonlab.foldagent.jobs.JobNotify.EXTRA_CONTINUE)
            if (replay) { Log.w(TAG, "재전달된 방 열기 무시"); return }
            st.openRoom(jobRoom)
            if (!cont.isNullOrBlank()) run(cont, JSONObject().put("source", "job").put("session", jobRoom).put("then", cont))
            return
        }
        // 진행 패널을 눌렀다 — 그 작업의 방(이어 갈 방은 바꾸지 않는다). 되살린 옛 인텐트면 무시(엉뚱한 방이 뜬다)
        val panelRoom = i.getStringExtra(EXTRA_OPEN_ROOM)
        if (!panelRoom.isNullOrBlank()) {
            i.removeExtra(EXTRA_OPEN_ROOM)
            if (replay) { Log.w(TAG, "재전달된 패널 방 열기 무시"); return }
            st.openRoom(panelRoom)
            return
        }
        // 화면 조작 없이 끝난 대화형 답 — 서비스가 불러 올린다. 지금 이어 가는 방을 연다(예전 채팅 화면 자리)
        if (i.getBooleanExtra("showChat", false)) { i.removeExtra("showChat"); st.openCurrentRoom(); return }
        // 디버그 전용: 자세 강제 — 메인 세션이 폰을 접지 않고 자세별 배치를 캡처. screen 과 함께 줄 수 있다(자세 먼저)
        // adb … --es posture "cover|coverland|open|openrot|table|auto"
        val pz = i.getStringExtra("posture")
        if (!pz.isNullOrBlank()) {
            i.removeExtra("posture")
            if (BuildConfig.DEBUG && !replay) {
                st.forcedPosture = pz.takeIf { it in Posture.ALL }
                Log.i(TAG, "posture 강제: ${st.forcedPosture ?: "해제(실제 자세)"}")
            } else Log.w(TAG, "재전달된 자세 명령 무시: $pz")
            if (!i.hasExtra("screen")) return
        }
        // 디버그 전용: 스크린샷 시험용으로 화면 바로 열기. adb … --es screen "home|room:<id>|trash|settings"
        val scr = i.getStringExtra("screen")
        if (!scr.isNullOrBlank()) {
            i.removeExtra("screen")
            if (BuildConfig.DEBUG && !replay) openScreen(scr) else Log.w(TAG, "재전달된 화면 명령 무시: $scr")
            return
        }
        // 듣는 중에 다시 불리면(측면 버튼 한 번 더) = 「다 말했다」
        if (voice.active && fresh) { ui.removeCallbacks(pauseCancel); finishSession(); return }
        // 최근 앱 목록에서 돌아온 경우엔 듣지 않는다 — 새로 불렀을 때만
        listenOnResume = fresh && !fromHistory && Prefs.autoListen(this)
        // 새로 불렸으면 지난번에 고른 대화방 칩을 끌고 가지 않는다 — 기본값(1분 안이면 이어서, 아니면 새 대화)부터
        if (fresh && !fromHistory) st.resetPick()
    }

    override fun onResume() {
        super.onResume()
        current = this
        kr.joonlab.foldagent.jobs.RemoteAsk.resumePending(this)
        st.poll()
        AgentService.instance?.listeners?.addIfAbsent(listener)
        st.reload(); st.roomId?.let { st.loadRoom(it) }
        // 재설치 직후엔 접근성 서비스가 조금 늦게 붙는다 — 잠시 뒤 상태·이벤트 연결을 다시 확인
        ui.postDelayed({ st.poll(); AgentService.instance?.listeners?.addIfAbsent(listener) }, 1500)
        ui.removeCallbacks(pauseCancel)
        voice.prewarm()
        hinge.start()
        if (listenOnResume) {
            listenOnResume = false
            if (AgentService.instance != null && !voice.active) {
                // 불러서 바로 듣기 = 새 요청 입구 → 홈 입력줄(칩)로 받는다. 1칸 자세에서 대화방이 열려 있으면 홈으로 — 칩과 들은 말이 보이게
                st.voiceVia = "choice"
                if ((st.posture == Posture.COVER || st.posture == Posture.COVER_LAND) && st.screen != "home") st.goHome()
                startSession()
            }
        }
        maybeAskNotifications()
    }

    /**
     * 알림 권한(Android 13+) — 맡긴 작업이 실제로 있을 때만 묻는다(처음 켤 때 묻지 않는다). 듣는 중이면 마이크 권한 창과 겹치니 미룬다.
     * 거부해도 결과 턴은 방에 붙는다 — 알림만 없다.
     */
    private fun maybeAskNotifications() {
        if (android.os.Build.VERSION.SDK_INT < 33 || askedNotif || voice.active || current !== this) return
        val jobs = kr.joonlab.foldagent.jobs.Jobs
        if (jobs.pendingCount() == 0 && !jobs.wantNotifPermission) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) { jobs.wantNotifPermission = false; return }
        askedNotif = true
        jobs.wantNotifPermission = false
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    override fun onPause() {
        super.onPause()
        if (current === this) current = null
        // 작업 중에 채팅을 떠나면 패널(■ 정지)·빛을 곧바로 — 다음 상태 갱신까지 정지 버튼이 없던 틈(리뷰 M2)
        AgentService.instance?.let { if (it.busy) it.onChatLeft() }
        hinge.stop()
        // 단계 이벤트 연결은 끊지 않는다 — 뒤로 물러난 사이의 단계도 모아 두었다가 돌아오면 진행 중 턴에 그대로 보인다(onDestroy 에서 끊음)
        // 측면 버튼을 다시 누르면 onPause → onNewIntent 순으로 온다. 바로 취소하면 「끝」 신호를 받기 전에 말이 날아가므로 잠깐 미룬다.
        if (voice.active && !voice.finishing) ui.postDelayed(pauseCancel, 800)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        AgentService.instance?.listeners?.remove(listener)
        Records.store.removeListener(st.recordListener)
        voice.destroy()
        hinge.stop()
        st.release()
        super.onDestroy()
    }

    /** --es screen 값 → 화면. room:<id> 는 그 방을 연다(이어 갈 방을 바꾸지는 않는다) */
    private fun openScreen(s: String) {
        when {
            s == "home" -> { st.goHome(); st.pickChip("all") }
            s == "trash" -> { st.goHome(); st.pickChip("trash") }
            s == "settings" -> st.openSettings()
            s.startsWith("room:") -> s.removePrefix("room:").takeIf { it.isNotBlank() }?.let { st.openRoom(it) }
            else -> Log.w(TAG, "screen: 모르는 화면 $s")
        }
    }

    // ---------------------------------------------------------------- 음성

    private val voiceCb: VoiceInput.Callback = object : VoiceInput.Callback {
        override fun onState(listening: Boolean, ready: Boolean) {
            st.listening = listening; st.voiceFinishing = voice.finishing
            if (listening) { if (voice.finishing) st.heard = "⏳ ${voice.transcript().ifEmpty { "…" }}" else showHeard() }
            else if (st.heard.startsWith("🎙") || st.heard.startsWith("⏳ 준비")) st.heard = ""
        }
        override fun onPartial(text: String) { if (!voice.finishing) showHeard() else st.heard = "⏳ $text" }
        override fun onFinal(text: String, alts: List<String>, conf: FloatArray?) {
            if (text.isBlank()) { st.heard = "아무 말도 못 들었어요 — 다시 눌러 주세요"; return }
            val meta = JSONObject().put("source", "voice").put("stt", voice.meta())
            // 홈 입력줄·상태 카드·테이블톱 마이크로 시작한 말은 칩대로, 대화방 입력줄 마이크는 그 방(selectThen 으로 이미 바꿈)
            // 말로 보내도 그 줄의 첨부 칩은 같이 간다(먼저 올림)
            val via = st.voiceVia
            st.sendWithFiles(via, text, meta) { m -> if (via == "choice") sendViaChoice(text, m) else run(text, m) }
        }
        override fun onError(msg: String) { st.heard = msg }
    }

    /** via = "choice"(홈 입력줄 — 칩대로) · "room"(대화방 입력줄 — 그 방). 듣는 중에 누르면 = 다 말했다 */
    private fun toggleMic(via: String) {
        if (AgentService.instance == null) { st.poll(); return }
        if (voice.active) finishSession() else { st.voiceVia = via; startSession() }
    }

    /** 마이크 권한은 여기(액티비티)서 요청한다 — VoiceInput 은 권한이 있을 때만 듣는다. */
    private fun startSession() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        voice.start()
    }

    /** 사용자가 「다 말했다」고 알림 — 지금 듣던 조각의 최종 결과를 받고 실행한다(2.5초 안에 안 오면 모은 것으로). */
    private fun finishSession() = voice.finish()

    private fun cancelSession(quiet: Boolean = false) {
        if (!voice.active) return
        voice.cancel(quiet)
        st.heard = if (!quiet) "취소했어요" else ""
    }

    private fun showHeard() {
        val t = voice.transcript()
        st.heard = when {
            !voice.ready && t.isEmpty() -> "⏳ 준비 중… 잠깐만요 (진동이 오면 말하세요)"
            t.isEmpty() -> "🎙 말하세요… (다 말했으면 ■ 를 한 번 더)"
            else -> "🎙 $t"
        }
    }

    @Deprecated("ComponentActivity 권한 결과 — 요청이 둘(마이크·알림)뿐이라 콜백 API 로 옮기지 않는다")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED)
                st.toast("알림이 꺼져 있어 맡긴 일이 끝나도 알리지 못해요 — 결과는 그 대화방에 붙어요", ms = 6000)
            return
        }
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startSession()
        else st.heard = "마이크 권한이 없어 들을 수 없습니다"
    }

    // ---------------------------------------------------------------- 실행

    /**
     * 홈 입력줄에서 보내기 — 고른 대화방 칩대로 방을 바꾼 뒤(RoomChoice.apply, IO 스레드) 실행.
     * 보낸 방은 AgentService 가 meta 의 roomLabel 을 상태 패널에 짧게 띄운다(「↳ 「알람 10시」에 이어서」 / 「＋ 새 대화」).
     */
    private fun sendViaChoice(text: String, meta: JSONObject) {
        if (AgentService.instance == null) { run(text, meta); return }   // 연결 안 됨 안내는 run 이 한다
        st.applyChoice { label -> meta.put("roomLabel", label); run(text, meta) }
    }

    private fun run(text: String, meta: JSONObject) {
        val svc = AgentService.instance
        if (svc == null) {
            st.poll()
            st.toast("접근성 서비스가 연결되지 않아 실행하지 못했어요 — 설정 › 「접근성 설정 열기」에서 폴드 에이전트를 껐다 켜 주세요", ms = 7000)
            return
        }
        st.heard = ""
        st.liveSteps.clear(); st.liveRequest = text
        st.liveFiles = Uploads.parse(meta).map { u ->
            kr.joonlab.foldagent.records.UserFile(u.optString("name"), u.optString("mime"), u.optLong("size", -1).takeIf { it >= 0 }, u.optString("fileId"))
        }
        // 여기서 바로 물러나지 않는다 — 「한국에 대해 알려 줘」 같은 대화형 요청까지 홈으로 나갔다가 돌아와 답을 읽었다(2026-09-27 사용자).
        // 화면 조작이 필요한 도구를 처음 부를 때 에이전트가 이 화면을 뒤로 물린다(Agent.execute → AgentService.yieldApp).
        if (svc.startTask(text, meta)) st.poll() else st.liveRequest = ""
    }
}
