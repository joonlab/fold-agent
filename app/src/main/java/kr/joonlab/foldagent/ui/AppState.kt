package kr.joonlab.foldagent.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kr.joonlab.foldagent.AgentService
import kr.joonlab.foldagent.Prefs
import kr.joonlab.foldagent.records.RecordEvent
import kr.joonlab.foldagent.records.RecordFilter
import kr.joonlab.foldagent.records.RecordSummary
import kr.joonlab.foldagent.records.Records
import kr.joonlab.foldagent.records.RoomChoice
import kr.joonlab.foldagent.records.Shelf
import kr.joonlab.foldagent.records.TurnCard
import java.util.concurrent.Executors
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kr.joonlab.foldagent.Uploads
import kr.joonlab.foldagent.records.UserFile
import org.json.JSONObject

/**
 * 앱 화면의 상태 한 벌 — MainActivity 가 하나 들고 있는다(configChanges 로 액티비티가 다시 안 만들어지므로
 * 폴드를 접고 펴도 선택 방·필터·입력 중인 글이 그대로다, DESIGN §2).
 * 기록은 RecordStore 인터페이스(Records.store)만 쓴다 — C 의 파일 배치에 기대지 않는다(§4-3).
 * 저장소 호출은 IO 스레드 하나(io)에서, 결과 반영은 메인에서.
 */
class AppState(private val ctx: Context) {

    companion object { private const val TAG = "FoldAgent" }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ui-io").apply { isDaemon = true } }
    private val store get() = Records.store

    // ---- 화면 이동
    /** home · room · settings. 폭≥600dp 에선 home 이 왼쪽에 늘 있고 room/settings 가 오른쪽 칸 */
    var screen by mutableStateOf("home")
    var roomId by mutableStateOf<String?>(null)

    // ---- 목록
    /** 필터 칩: all · today · pinned · failed · archived · trash · tests */
    var chip by mutableStateOf("all")
    var query by mutableStateOf("")
    var searchOpen by mutableStateOf(false)
    var rows by mutableStateOf<List<RecordSummary>>(emptyList())
    var listLoaded by mutableStateOf(false)
    var currentId by mutableStateOf("")
    var showTests by mutableStateOf(Prefs.showTests(ctx))
    /** 답을 소리 내어 읽는 중 — 입력줄 위에 「■ 읽기 멈춤」 */
    var speaking by mutableStateOf(kr.joonlab.foldagent.AgentService.instance?.speaking == true)

    init {
        ThemeState.mode = Prefs.theme(ctx)
        io.execute { runCatching { kr.joonlab.foldagent.jobs.JobStore.load(ctx) } }   // 칩용 — 디스크 읽기는 IO 에서 한 번
    }   // 첫 프레임부터 저장된 테마로(늦게 읽으면 한 번 번쩍인다)

    // ---- 대화방
    var turns by mutableStateOf<List<TurnCard>?>(null)
    var turnsFor by mutableStateOf<String?>(null)
    /** 평가를 누른 run — 저장소가 rating 을 아직 안 돌려줘도(P0 Legacy) 곧바로 「평가함」으로 보이게 */
    val rated = mutableStateMapOf<String, Boolean>()
    /** 방마다 스크롤 자리 — 폭이 바뀌어 화면 배치가 달라져도(1칸↔2칸) 읽던 자리 그대로 */
    private val roomScroll = HashMap<String, LazyListState>()
    fun roomScroll(id: String) = roomScroll.getOrPut(id) { LazyListState() }
    /** 방마다 마지막으로 맨 아래로 내린 내용 서명 — 같은 내용이면 다시 내리지 않는다 */
    val autoScrolled = HashMap<String, String>()
    val listScroll = LazyListState()

    // ---- 에이전트 상태(AgentService 공개 필드를 0.5초마다 읽는다 — status() 는 이벤트를 안 쏘므로)
    var svcOn by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var confirming by mutableStateOf(false)
    var lastStatus by mutableStateOf("")
    var stepCount by mutableIntStateOf(0)
    var startedAt by mutableLongStateOf(0L)
    var runningId by mutableStateOf<String?>(null)
    /** 사람 개입 — "" 에이전트가 하는 중 · "pending" 지금 동작을 마치는 중 · "paused" 사용자 차례(AgentService.hitl) */
    var hitl by mutableStateOf("")
    var now by mutableLongStateOf(System.currentTimeMillis())
    /**
     * 홈맥에 맡긴 작업 중 결과가 아직 안 붙은 것(jobs/JobStore 메모리 사본, 0.5초마다) — busy 와 별개다(맡긴 뒤 폰은 다음 요청을 받는다).
     * 방 id → 개수. 홈 「원격 작업 N개」 칩·방 머리 줄이 읽는다
     */
    var remoteJobs by mutableStateOf<List<kr.joonlab.foldagent.jobs.JobInfo>>(emptyList())
    fun remoteJobsIn(id: String) = remoteJobs.count { it.sessionId == id }
    /** 진행 중 턴: 요청 글 + 실시간 단계 줄(listeners 의 step) */
    var liveRequest by mutableStateOf("")
    val liveSteps = mutableStateListOf<String>()

    // ---- 음성·입력
    var listening by mutableStateOf(false)
    var voiceFinishing by mutableStateOf(false)
    var heard by mutableStateOf("")
    /** 홈 입력줄(대화방 칩으로 보내는 줄)의 글 */
    var draft by mutableStateOf("")
    /** 대화방 입력줄의 글 — 펼친 화면에선 두 줄이 함께 보여서 나눴다(한 값이면 양쪽에 같은 글이 찍힌다) */
    var roomDraft by mutableStateOf("")
    /** 지금 듣는 말이 어느 입력줄 것인가 — "choice"(홈 입력줄 = 대화방 칩대로) · "room"(대화방 입력줄 = 그 방) */
    var voiceVia by mutableStateOf("choice")
    // ---- 입력줄 첨부(📎, 계약 INPUTS §1 — 공유 시트 빠른 입력과 같은 규칙: 5개 · 각 25MB · 보내기 때 먼저 올림)
    /** 홈 입력줄 칩 · 대화방 입력줄 칩 — 입력 글처럼 줄마다 따로(펼친 화면엔 두 줄이 함께 보인다) */
    val homeFiles = mutableStateListOf<Uploads.Pending>()
    val roomFiles = mutableStateListOf<Uploads.Pending>()
    /** 칩 안 상태(진행 %·✓·오류)는 일반 필드라 바뀔 때 이 값을 늘려 다시 그린다 */
    var filesTick by mutableIntStateOf(0)
    /** 올리는 중인 줄("choice"·"room") — 그동안 두 줄 모두 보내기·📎·칩 빼기를 막는다 */
    var uploadingLine by mutableStateOf<String?>(null)
    @Volatile private var uploadCancelled = false
    /** 보낸 이미지·영상의 작은 썸네일(fileId → 그림) — 원본 uri 권한은 곧 사라지니 올릴 때 만들어 이 화면이 살아 있는 동안만 둔다 */
    val thumbs = mutableStateMapOf<String, ImageBitmap>()
    /** 진행 중 턴 말풍선 아래 파일 줄 — MainActivity.run 이 meta.uploads 에서 채운다 */
    var liveFiles by mutableStateOf<List<UserFile>>(emptyList())

    fun filesOf(line: String) = if (line == "room") roomFiles else homeFiles

    /** 📎 로 고른 uri 들 → 그 줄 칩(이름·크기는 IO 에서 읽는다). 넘치는 건 빼고 안내 */
    fun addFiles(line: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (uploadingLine != null) { toast("파일을 올리는 중이에요 — 끝난 뒤에 더해 주세요"); return }
        val list = filesOf(line)
        val take = uris.distinct().filter { u -> list.none { it.uri == u } }
        val add = take.take(maxOf(0, Uploads.MAX_FILES - list.size))
        val dropped = take.size - add.size
        if (add.isEmpty()) { if (dropped > 0) toast(Uploads.hintTooMany(dropped)); return }
        io.execute {
            val got = add.map { u ->
                // 문서 고르기 uri 권한은 이 화면이 살아 있는 동안 유지되지만, 보내기 전에 화면이 새로 붙는 경우를 대비해 잡아 둔다(올린 뒤·뺄 때 놓는다)
                runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                Uploads.resolve(ctx, u, null)
            }
            main.post {
                got.forEach { g -> if (list.size < Uploads.MAX_FILES && list.none { it.uri == g.uri }) list += g else releaseUri(g.uri) }
                filesTick++
                when {
                    dropped > 0 -> toast(Uploads.hintTooMany(dropped))
                    list.any { it.state == "too_big" } -> toast(Uploads.HINT_TOO_BIG)
                    list.any { it.state == "error" } -> toast("읽을 수 없는 파일이 있어요 — 칩을 눌러 빼 주세요")
                }
            }
        }
    }

    fun removeFile(line: String, p: Uploads.Pending) {
        if (uploadingLine != null) return
        filesOf(line).remove(p); releaseUri(p.uri); filesTick++
    }

    private fun releaseUri(u: Uri) { runCatching { ctx.contentResolver.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }

    /**
     * 입력줄 보내기(글·음성)의 앞단 — 그 줄에 칩이 없으면 곧바로 go(meta).
     * 있으면 **먼저 올리고**(백그라운드 스레드) 모두 올라가면 meta.uploads 를 싣고 go. 하나라도 못 올리면 실행하지 않고
     * 칩에 오류 + 보낸 글을 입력줄에 되돌린다(다시 보내면 못 올린 것만 다시 — 빠른 입력 카드와 같은 규칙).
     */
    fun sendWithFiles(line: String, text: String, meta: JSONObject, go: (JSONObject) -> Unit) {
        val list = filesOf(line)
        if (list.isEmpty()) { go(meta); return }
        if (uploadingLine != null) return
        fun restore() { if (line == "room") { if (roomDraft.isBlank()) roomDraft = text } else if (draft.isBlank()) draft = text }
        if (busy) { restore(); toast("작업 중 — 끝나거나 정지한 뒤에 보낼 수 있어요"); return }
        if (list.any { it.state == "too_big" }) { restore(); toast(Uploads.HINT_TOO_BIG); return }
        val token = Prefs.assistantToken(ctx)
        if (token.isBlank() || Prefs.assistantBase(ctx).isBlank()) {
            list.filter { it.state != "done" }.forEach { it.state = "error"; it.error = "연결 설정 없음" }
            filesTick++; restore()
            toast("홈 비서 연결이 설정되지 않아 파일을 못 올려요(설정 › 홈 비서)", ms = 6000); return
        }
        val base = Prefs.assistantBase(ctx)
        val todo = list.filter { it.state != "done" }
        todo.forEach { it.state = "uploading"; it.sent = 0; it.error = null }
        uploadingLine = line; uploadCancelled = false; filesTick++
        Thread({
            Uploads.uploadAll(ctx, base, token, todo, cancelled = { uploadCancelled }, changed = { main.post { filesTick++ } },
                afterDone = { p ->
                    val id = p.fileId
                    val b = Uploads.thumbnail(ctx, p)?.asImageBitmap()
                    if (id != null && b != null) main.post { if (thumbs.size > 40) thumbs.clear(); thumbs[id] = b }
                })
            main.post {
                uploadingLine = null; filesTick++
                if (uploadCancelled) return@post
                val failed = list.filter { it.state != "done" }
                if (failed.isNotEmpty()) {
                    restore()
                    val why = failed.first().error?.let { " ($it)" } ?: ""
                    toast("파일 ${failed.size}개를 못 올려 실행하지 않았어요$why — 다시 보내면 그것만 다시 올려요", ms = 6000)
                    return@post
                }
                val done = list.toList()
                meta.put("uploads", Uploads.metaOf(done))
                list.clear(); done.forEach { releaseUri(it.uri) }
                go(meta)
            }
        }, "app-upload").start()
    }

    /** 늘리면 지금 보이는 주 입력칸이 포커스를 받는다(상태 카드의 큰 입력 알약) */
    var focusTick by mutableIntStateOf(0)

    // ---- 자세(Posture.kt) — 힌지 센서는 MainActivity 가 onResume/onPause 에 켜고 끈다
    var halfOpen by mutableStateOf(false)
    /** 디버그 --es posture 로 강제한 자세(null = 실제 자세) */
    var forcedPosture by mutableStateOf<String?>(null)
    /** 칸 폭·여백 등 자세별로 다른 걸 그리는 쪽이 읽는다(App.kt 가 매 프레임 채움) */
    var posture by mutableStateOf(Posture.COVER)

    // ---- 대화방 칩(RoomChoice, 2026-09-27 사용자 A안) — 홈 입력줄 위 한 줄
    var choice by mutableStateOf<RoomChoice.Choice?>(null)
    /** 사람이 고른 칩(id, 새 대화는 "new"). null = 기본값(1분 안이면 이어서, 아니면 새 대화) */
    var pickKey by mutableStateOf<String?>(null)
    private var choiceAt = 0L

    fun keyOf(o: RoomChoice.Option) = o.id ?: "new"
    fun pickedIndex(): Int {
        val c = choice ?: return -1
        val k = pickKey ?: return c.defaultIndex
        return c.options.indexOfFirst { keyOf(it) == k }.takeIf { it >= 0 } ?: c.defaultIndex   // 고른 방이 사라졌으면(휴지통 등) 기본값
    }
    fun pickedOption(): RoomChoice.Option? = choice?.options?.getOrNull(pickedIndex())
    fun pick(o: RoomChoice.Option) { pickKey = keyOf(o) }
    /** 새로 불렸을 때·보낸 뒤 — 지난번에 고른 칩을 끌고 가지 않는다 */
    fun resetPick() { pickKey = null; rebuildChoice() }

    fun rebuildChoice() {
        choiceAt = System.currentTimeMillis()
        io.execute {
            val c = runCatching { RoomChoice.build(store, Prefs.continueWindowSec(ctx) * 1000L) }.onFailure { Log.w(TAG, "choice: $it") }.getOrNull()
            main.post { if (c != null) choice = c }
        }
    }

    /**
     * 고른 칩대로 방을 바꾼 뒤 then(보낸 방 표시 글). 보이는 칩 그대로 보낸다(WYSIWYG) — 기본값 칩도 화면에 보인 것 기준.
     * 작업 중이면 저장소 select 가 던지므로 먼저 거른다(RoomChoice.apply 주석).
     */
    fun applyChoice(then: (String) -> Unit) {
        if (busy) { toast("작업 중 — 끝나거나 정지한 뒤에 보낼 수 있어요"); return }
        val shown = pickedOption()
        io.execute {
            val opt = shown ?: RoomChoice.build(store, Prefs.continueWindowSec(ctx) * 1000L).let { it.options[it.defaultIndex] }
            val r = runCatching { RoomChoice.apply(store, opt) }
            main.post {
                r.onFailure { toast(it.message ?: "대화방을 바꾸지 못했어요") }
                r.onSuccess { id ->
                    currentId = id
                    // 2칸·테이블톱이면 오른쪽(위) 칸을 보낸 방으로 — 좁은 화면 홈은 그대로(보내면 뒤로 물러난다)
                    if (screen == "room") openRoom(id)
                    pickKey = null
                    then(RoomChoice.sentLabel(opt))
                }
            }
        }
    }

    // ---- 알림 줄(스낵바)
    data class Snack(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null, val id: Long = System.nanoTime())
    var snack by mutableStateOf<Snack?>(null)
    fun toast(text: String, action: String? = null, ms: Long = 3500, onAction: (() -> Unit)? = null) {
        val s = Snack(text, action, onAction)
        snack = s
        main.postDelayed({ if (snack?.id == s.id) snack = null }, ms)
    }

    // ---- 목록 불러오기

    private fun filterOf(): RecordFilter {
        val q = query.trim()
        return when (chip) {
            "today" -> RecordFilter(query = q, since = Fmt.dayStart())
            "pinned" -> RecordFilter(query = q, pinnedOnly = true)
            "archived" -> RecordFilter(shelf = Shelf.ARCHIVED, query = q)
            "trash" -> RecordFilter(shelf = Shelf.TRASH, includeTests = true, query = q)
            "tests" -> RecordFilter(query = q, includeTests = true)
            else -> RecordFilter(query = q)
        }
    }

    private var reloadSeq = 0
    fun reload() {
        val f = filterOf(); val c = chip; val seq = ++reloadSeq
        io.execute {
            val r = runCatching { store.list(f) }.onFailure { Log.w(TAG, "list: $it") }.getOrDefault(emptyList())
            val out = when (c) {
                "failed" -> r.filter { Fmt.failed(it.lastStatus) }
                "tests" -> r.filter { it.source == "adb" }
                else -> r
            }
            val cur = runCatching { store.currentId() }.getOrDefault("")
            main.post { if (seq == reloadSeq) { rows = out; listLoaded = true; currentId = cur } }
        }
        // 목록이 바뀌면 칩도 — 단 듣는 중엔 그대로(말하는 사이 1분이 지나 기본 칩이 「새 대화」로 바뀌면 헷갈린다)
        if (!listening) rebuildChoice()
    }

    fun loadRoom(id: String) {
        io.execute {
            val t = runCatching { store.get(id) }.onFailure { Log.w(TAG, "get: $it") }.getOrNull()
            main.post { if (roomId == id) { turns = t; turnsFor = id } }
        }
    }

    fun pickChip(c: String) { chip = c; reload() }

    // ---- 이동

    fun openRoom(id: String) {
        if (roomId != id) { turns = null; turnsFor = null }
        roomId = id; screen = "room"
        loadRoom(id)
    }
    fun openCurrentRoom() { io.execute { val id = store.currentId(); main.post { currentId = id; openRoom(id) } } }
    fun openSettings() { screen = "settings" }
    fun goHome() { screen = "home" }

    // ---- 에이전트 상태 읽기

    // ⚠️ 확인 대기 판정 필드가 AgentService 에 아직 없다(P0 훅에 빠짐 — AgentService 는 D 가 못 고친다).
    // 메인 세션이 `@Volatile var confirming: Boolean`(confirm() 진입 true · clearAsk 옆 false)을 넣으면 그대로 읽힌다.
    // 그 전엔 늘 false — 상태 카드가 「작업 중」으로 보일 뿐 게이트 동작과는 무관하다.
    private val confirmingGetter by lazy { runCatching { AgentService::class.java.getMethod("getConfirming") }.getOrNull() }

    fun poll() {
        val svc = AgentService.instance
        now = System.currentTimeMillis()
        svcOn = svc != null
        val b = svc?.busy == true
        val wasBusy = busy
        busy = b
        confirming = b && svc != null && runCatching { confirmingGetter?.invoke(svc) as? Boolean }.getOrNull() == true
        lastStatus = svc?.lastStatus.orEmpty()
        stepCount = svc?.stepCount ?: 0
        startedAt = svc?.startedAt ?: 0L
        runningId = svc?.runningSessionId
        hitl = svc?.hitl?.let { if (it.acked) "paused" else "pending" } ?: ""
        val rj = kr.joonlab.foldagent.jobs.JobStore.pending
        if (rj != remoteJobs) remoteJobs = rj   // 같은 목록이면 다시 그리지 않는다(data class 비교)
        if (b && !wasBusy) {
            // 다른 입구(팝업·adb)에서 시작된 작업 — 요청 글은 첫 상태 문장 「🎙 「…」」에서 꺼낸다
            if (liveRequest.isEmpty()) liveRequest = Regex("「(.*)」").find(lastStatus)?.groupValues?.get(1).orEmpty()
            reload()
        }
        if (!b && wasBusy) onFinished()
        // 「이어서」 칩은 1분이 지나면 빠져야 한다 — 저장소 이벤트가 없으니 5초마다 다시 만든다(듣는 중엔 멈춤)
        if (!listening && now - choiceAt > 5_000) rebuildChoice()
    }

    fun onAgentEvent(kind: String, text: String) {
        when (kind) {
            "step" -> liveSteps.add(text)
            "finish" -> onFinished()
            "tts" -> speaking = text == "1"
        }
    }

    private fun onFinished() {
        liveSteps.clear(); liveRequest = ""; liveFiles = emptyList()
        reload()
        roomId?.let { loadRoom(it) }
    }

    /** RecordStore 리스너(메인 스레드) — 목록·열린 방을 다시 읽는다 */
    val recordListener: (RecordEvent) -> Unit = { e ->
        reload()
        val r = roomId
        when (e) {
            is RecordEvent.Changed -> if (e.id == r) loadRoom(e.id)
            is RecordEvent.Restored -> if (e.id == r) loadRoom(e.id)
            is RecordEvent.Removed -> {}
            is RecordEvent.Purged -> if (r != null && r in e.ids) { roomId = null; if (screen == "room") screen = "home" }
            RecordEvent.Rebuilt -> r?.let { loadRoom(it) }
        }
    }

    // ---- 기록 조작(사용자 조작 — 에이전트 확인 게이트와 별개, §3-5)

    private fun storeOp(what: String, op: () -> Unit, then: () -> Unit = {}) {
        io.execute {
            val err = runCatching(op).exceptionOrNull()
            main.post {
                if (err != null) toast(err.message ?: "$what 실패") else then()
                reload(); roomId?.let { loadRoom(it) }
            }
        }
    }

    fun isRunning(id: String) = busy && runningId == id

    fun rename(id: String, title: String) {
        val t = title.trim(); if (t.isEmpty()) return
        storeOp("이름 바꾸기", { store.rename(id, t) })
    }
    fun pin(id: String, on: Boolean) = storeOp("고정", { store.pin(id, on) })
    fun archive(id: String, on: Boolean) {
        if (on && isRunning(id)) { toast("작업 중인 대화는 보관할 수 없어요"); return }
        storeOp("보관", { store.archive(id, on) }) { toast(if (on) "보관함으로 옮겼어요" else "보관을 풀었어요") }
    }
    fun trash(id: String) {
        if (isRunning(id)) { toast("작업 중인 대화는 휴지통으로 옮길 수 없어요"); return }
        storeOp("휴지통", { store.delete(id) }) {
            if (roomId == id) { roomId = null; if (screen == "room") screen = "home" }
            toast("휴지통으로 옮겼어요", "실행 취소", 5000) { restore(id) }
        }
    }
    fun restore(id: String) = storeOp("복원", { store.restore(id) }) { toast("복원했어요") }
    fun purge(ids: List<String>) = storeOp("영구 삭제", { store.purge(ids, 0) }) { toast("${ids.size}개 대화를 영구 삭제했어요") }

    fun startNew() {
        if (busy) { toast("작업 중에는 새 대화를 시작할 수 없어요 — 끝나거나 정지한 뒤에"); return }
        io.execute { val id = store.startNew(); main.post { currentId = id; openRoom(id); reload(); focusTick++ } }
    }

    /** 이 방으로 이어 가기로 바꾼 뒤 then — 작업 중이면 저장소가 IllegalStateException 을 던진다 */
    fun selectThen(id: String, then: () -> Unit) {
        if (id == currentId) { then(); return }
        if (busy) { toast("작업 중에는 대화를 바꿀 수 없어요"); return }
        io.execute {
            val err = runCatching { store.select(id) }.exceptionOrNull()
            main.post {
                if (err != null) toast(err.message ?: "대화를 바꾸지 못했어요") else { currentId = id; then() }
            }
        }
    }

    /**
     * 대화방의 잘했어/틀렸어 — 계약(§4-3)대로 저장소 rate 만 부른다.
     * run jsonl 의 feedback 이벤트·recipes bad 는 C 의 파일이라 D 가 직접 쓰지 않는다 — 패널 경로(agent.feedback)나 C 의 rate 가 맡는다.
     * (직접 쓰던 판: 패널·대화방 두 번 평가하면 두 줄, Legacy 에선 재시작마다 버튼이 돌아와 같은 run 이 거듭 bad 로 찍혀 👎 통계가 틀어졌다)
     */
    fun rate(run: String, good: Boolean) {
        if (run.isBlank() || rated.containsKey(run)) return
        rated[run] = good
        io.execute { runCatching { store.rate(run, good) } }
    }

    fun release() { uploadCancelled = true; io.shutdown() }
}
