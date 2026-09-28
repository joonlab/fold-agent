package kr.joonlab.foldagent.jobs

import android.content.Context
import android.net.Uri
import android.util.Log
import kr.joonlab.foldagent.AssistantClient
import kr.joonlab.foldagent.Prefs
import kr.joonlab.foldagent.records.Attachments
import kr.joonlab.foldagent.records.FileRecordStore
import kr.joonlab.foldagent.records.Records
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 맡긴 작업 감시(계약 cc-capabilities §2 `GET /api/phone/jobs?since=<cursor>&wait=<초≤25>`). AgentService 가 켜고 끈다.
 *
 * - **결과를 안 붙인 작업이 있을 때만** long-poll 한다. 없으면 스레드는 [kick] 을 기다리며 잔다(네트워크 없음).
 * - 주소·토큰은 홈 비서와 같은 Prefs(assistantBase·assistantToken). 토큰은 로그·기록에 남기지 않는다.
 * - 실패하면 2·4·8…초(최대 60초) 뒤 다시. 인증 실패·토큰 없음은 5분.
 * - 끝난 작업(done·failed·cancelled): 첨부 파일 받기(`GET <base>/files/<fileId>`) → 맡긴 방(sessionId)에 턴 덧붙이기(source="job")
 *   → 알림(채널 「작업 완료」, 누르면 그 방) → delivered 표시. 턴은 run = "job-<jobId>" 로 붙여, 이미 붙어 있으면 다시 안 붙인다(멱등).
 * - 등록 전에 먼저 도착한 상태(빠른 작업)는 [early] 에 잠깐 두었다 등록되면 반영한다 — cursor 가 이미 지나가 영영 못 받는 일을 막는다.
 * - 오래 소식이 없는 작업(5분)은 `job_status`(읽기 op)로 한 번씩 직접 물어본다. 서버가 `not_found`(7일 정리·기록 손실)면
 *   실패로 붙이고 끝낸다 — 안 그러면 long-poll 과 칩이 영원히 남는다.
 * - 붙이기는 다시 시도한다: 결과 조회 실패·첨부 받기 실패·턴 저장 실패면 붙이지 않고 30초·1분·2분…(최대 10분) 뒤 다시,
 *   [MAX_TRIES] 번 넘게 실패하면 받은 것만으로 붙이고 못 받은 것을 결과 글에 밝힌다. 받은 첨부는 기억해 두고 다시 받지 않는다
 *   (갤러리에 같은 그림이 쌓이지 않게).
 * - 맡긴 방이 휴지통에 있으면 되살리지 않고 「<방 id>-job」 방에 붙인다(같은 id 로 활성 방이 새로 생겨 휴지통 방과 겹치지 않게).
 * - `state:"waiting"`(맡긴 일이 폰 승인을 기다림, DELEGATION §3-1)은 끝나지 않은 상태로 두고 [RemoteAsk] 가 알림·확인 창을 맡는다.
 *   모르는 state 도 끝나지 않은 것(running 처럼)으로 다룬다.
 */
object JobWatcher {
    private const val TAG = "FoldAgent"
    /** 자동 이어가기 사슬 상한(계약 INPUTS §5) — 이 깊이를 넘는 job 이 끝나면 자동으로 잇지 않고 「이어서 하기」 알림만 */
    const val MAX_AUTO_CHAIN = 3
    private const val WAIT_SEC = 25
    private const val READ_TIMEOUT_MS = (WAIT_SEC + 15) * 1000
    private const val AUTH_BACKOFF_MS = 5 * 60_000L
    private const val MAX_BACKOFF_MS = 60_000L
    private const val STALE_MS = 5 * 60_000L
    private const val MAX_FILE = 20L * 1024 * 1024
    private const val MAX_TRIES = 4
    private const val RETRY_BASE_MS = 30_000L
    private const val RETRY_MAX_MS = 10 * 60_000L

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")   // wait/notify 가 필요하다
    private val wake = Object()
    private val deliverLock = Any()
    @Volatile private var gen = 0
    @Volatile private var app: Context? = null
    @Volatile private var conn: HttpURLConnection? = null
    /** 붙이기 재시도 상태(jobId → …) — deliverLock 안에서만. 메모리에만(재시작하면 처음부터 — 받은 첨부는 다시 받는다) */
    private class Retry(var n: Int = 0, var nextAt: Long = 0L, var src: JSONObject? = null, val got: LinkedHashMap<String, JSONObject> = LinkedHashMap(), var waitingRun: Boolean = false)
    private val retries = HashMap<String, Retry>()

    /** job_status 결과 — 찾음 · 서버에 없음(not_found) · 조회 실패(네트워크·인증 등, 다음에 다시) */
    private sealed class St {
        class Found(val o: JSONObject) : St()
        object NotFound : St()
        object Failed : St()
    }

    /** 확인 요청을 모르는 채 waiting 인 작업을 job_status 로 물어본 적 있나(프로세스 재시작 뒤 한 번) */
    private val askProbed = HashSet<String>()

    /** 등록 전에 먼저 온 상태(jobId → 서버 job 객체) — 최근 50개만 */
    private val early = object : LinkedHashMap<String, JSONObject>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, JSONObject>?) = size > 50
    }

    /** AgentService.onServiceConnected 에서. 여러 번 불려도 스레드 하나 */
    fun start(ctx: Context) {
        synchronized(this) {
            app = ctx.applicationContext
            val my = ++gen
            // 옛 세대가 기다리던 long-poll 은 끊는다(서비스 재연결로 start 가 연달아 불릴 때 옛 스레드가 25초 더 붙잡지 않게)
            conn?.let { c -> Thread({ runCatching { c.disconnect() } }, "jobs-stop").start() }
            Thread({ loop(my) }, "jobs-watch").apply { isDaemon = true; start() }
        }
        kick()
    }

    /** AgentService.onUnbind 에서. 기다리던 long-poll 연결은 끊는다(메인 스레드에서 끊지 않게 따로) */
    fun stop() {
        synchronized(this) { gen++ }
        kick()
        conn?.let { c -> Thread({ runCatching { c.disconnect() } }, "jobs-stop").start() }
    }

    /** 새 작업이 등록됐다 — 자던 감시를 깨운다 */
    fun kick() { synchronized(wake) { wake.notifyAll() } }

    private fun alive(my: Int) = gen == my

    private fun sleep(my: Int, ms: Long) {
        if (!alive(my)) return
        synchronized(wake) { runCatching { wake.wait(ms) } }
    }

    private fun loop(my: Int) {
        val ctx = app ?: return
        JobStore.load(ctx)
        var backoff = 0L
        while (alive(my)) {
            // 등록 전에 먼저 와 있던 상태 반영
            JobStore.pending.forEach { p -> synchronized(early) { early.remove(p.jobId) }?.let { apply(ctx, it, my) } }
            // 이미 끝났는데 못 붙인 것(붙이다 죽었거나 결과 조회·파일 받기·저장 실패) 먼저 — 재시도 간격은 deliver 가 본다
            JobStore.pending.filter { it.terminal }.forEach { deliver(ctx, it, null, my) }
            val waiting = JobStore.pending.filter { !it.terminal }
            if (waiting.isEmpty()) {
                // 잠들기 직전 등록된 작업을 놓치지 않게 — 등록(JobStore)은 kick 전에 끝나므로 잠금 안에서 다시 보고 잔다.
                // 붙이기 재시도가 남아 있으면 그 시각까지만 잔다
                val nap = nextRetryIn() ?: (10 * 60_000L)
                synchronized(wake) {
                    if (alive(my) && JobStore.pending.none { !it.terminal }) runCatching { wake.wait(nap.coerceAtLeast(5_000L)) }
                }
                continue
            }

            val base = Prefs.assistantBase(ctx).trim().trimEnd('/')
            val token = Prefs.assistantToken(ctx)
            if (token.isBlank() || base.isBlank()) { Log.i(TAG, "jobs: 비서 토큰 없음 — 감시 쉼"); sleep(my, AUTH_BACKOFF_MS); continue }

            // 오래 소식 없는 작업은 직접 물어본다. 확인 요청 내용을 모르는 waiting(재시작 뒤 — ask 는 메모리에만)도 한 번
            val now = System.currentTimeMillis()
            waiting.filter { now - it.lastPollAt > STALE_MS || (it.state == "waiting" && RemoteAsk.unknown(it.jobId) && askProbed.add(it.jobId)) }.forEach { j ->
                JobStore.touchPoll(j.jobId)
                when (val s = statusOf(base, token, j.jobId, my)) {
                    is St.Found -> apply(ctx, s.o, my)
                    St.NotFound -> apply(ctx, lostJob(j.jobId, "failed"), my)   // 서버에 기록이 없다 — 실패로 붙이고 끝낸다
                    St.Failed -> Unit
                }
            }
            if (!alive(my)) break

            val r = poll(base, token, my)
            when {
                !alive(my) -> break
                r.ok -> {
                    backoff = 0L
                    r.jobs.forEach { apply(ctx, it, my) }
                    if (r.cursor.isNotEmpty()) JobStore.setCursor(r.cursor)
                }
                else -> {
                    backoff = if (r.auth) AUTH_BACKOFF_MS else (if (backoff == 0L) 2_000L else backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                    Log.i(TAG, "jobs: 감시 실패 ${r.why} — ${backoff / 1000}초 뒤 다시")
                    sleep(my, backoff)
                }
            }
        }
        Log.i(TAG, "jobs: 감시 끝")
    }

    // ---------------------------------------------------------------- 서버

    private class Poll(val ok: Boolean, val jobs: List<JSONObject> = emptyList(), val cursor: String = "", val auth: Boolean = false, val why: String = "")

    private fun poll(base: String, token: String, my: Int): Poll {
        val since = JobStore.cursor()
        val url = "$base/jobs?wait=$WAIT_SEC" + if (since.isNotEmpty()) "&since=" + Uri.encode(since) else ""
        val c = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrElse { return Poll(false, why = "주소 오류") }
        conn = c
        return try {
            c.requestMethod = "GET"
            c.connectTimeout = 10_000
            c.readTimeout = READ_TIMEOUT_MS
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/json")
            val http = c.responseCode
            if (http == 401 || http == 403 || http == 503) return Poll(false, auth = true, why = "HTTP $http")
            if (http !in 200..299) return Poll(false, why = "HTTP $http")
            val text = c.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(text)
            if (j.has("ok") && !j.optBoolean("ok")) return Poll(false, why = j.optJSONObject("error")?.optString("code") ?: "ok=false")
            val d = j.optJSONObject("data") ?: j   // {ok,data:{jobs,cursor}} 과 {jobs,cursor} 둘 다 받는다
            val arr = d.optJSONArray("jobs") ?: JSONArray()
            Poll(true, (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }, d.opt("cursor")?.takeIf { it != JSONObject.NULL }?.toString().orEmpty())
        } catch (e: Exception) {
            Poll(false, why = if (alive(my)) e.javaClass.simpleName else "멈춤")
        } finally {
            if (conn === c) conn = null   // 새 세대 스레드의 연결을 덮지 않게
            runCatching { c.disconnect() }
        }
    }

    /** job_status(읽기 op) — 모양이 {state…} · {jobs:[…]} · {items:[…]} 어느 쪽이든 첫 job 객체. 서버가 not_found 면 NotFound */
    private fun statusOf(base: String, token: String, jobId: String, my: Int): St {
        val r = runCatching {
            AssistantClient(base, token).exec("job_status", JSONObject().put("jobId", jobId), null, 20_000) { !alive(my) }
        }.getOrNull() ?: return St.Failed
        if (!r.ok) return if (r.code == "not_found") St.NotFound else St.Failed
        return statusObj(r.data, jobId)?.let { St.Found(it) } ?: St.Failed
    }

    private fun statusObj(d: JSONObject?, jobId: String): JSONObject? {
        d ?: return null
        val o = when {
            d.has("state") -> d
            d.optJSONArray("jobs") != null -> d.optJSONArray("jobs")?.optJSONObject(0)
            d.optJSONArray("items") != null -> d.optJSONArray("items")?.optJSONObject(0)
            else -> null
        } ?: return null
        if (!o.has("jobId")) o.put("jobId", jobId)
        return o.takeIf { it.optString("jobId") == jobId }
    }

    /** 서버에 기록이 없는 작업(7일 정리·기록 손실)의 대신 객체 — 결과 글에 그 사정을 밝힌다 */
    private fun lostJob(jobId: String, state: String) = JSONObject().put("jobId", jobId).put("state", state)
        .put("result", "홈맥에 이 작업 기록이 없어요(7일 정리 또는 서버 기록 손실) — 결과 글·첨부를 받을 수 없어요. 필요하면 다시 맡겨 주세요.")

    /** 서버 job 객체 하나 반영. 우리가 맡긴 게 아니면 early 에 잠깐 둔다 */
    private fun apply(ctx: Context, o: JSONObject, my: Int) {
        val id = o.optString("jobId").ifBlank { return }
        val known = JobStore.get(id)
        if (known == null) { synchronized(early) { early[id] = o }; return }
        if (known.delivered) return
        val j = JobStore.update(id, o.optString("state"), progressOf(o)) ?: return
        runCatching { RemoteAsk.onJob(ctx, j, o) }.onFailure { Log.w(TAG, "jobs: 원격 확인 처리 실패 $id $it") }
        if (j.state != "waiting") askProbed.remove(id)
        if (j.terminal) deliver(ctx, j, o, my)
    }

    /** 가장 가까운 붙이기 재시도까지 남은 시간(없으면 null) */
    private fun nextRetryIn(): Long? = synchronized(deliverLock) {
        val now = System.currentTimeMillis()
        retries.entries.filter { (id, _) -> JobStore.get(id)?.delivered == false }.minOfOrNull { it.value.nextAt - now }
    }

    /** 이번 붙이기 실패 — 다음 시도 시각을 뒤로(30초·1분·2분… 최대 10분). deliverLock 안에서 */
    private fun later(rt: Retry, jobId: String, why: String) {
        rt.n++
        rt.nextAt = System.currentTimeMillis() + (RETRY_BASE_MS shl (rt.n - 1).coerceIn(0, 5)).coerceAtMost(RETRY_MAX_MS)
        Log.i(TAG, "jobs: 결과 붙이기 미룸 $jobId — $why (${rt.n}/$MAX_TRIES)")
    }

    private fun progressOf(o: JSONObject): String {
        val p = o.opt("progress")
        if (p == null || p == JSONObject.NULL) return ""
        return if (p is Number) "${p}%" else p.toString()
    }

    // ---------------------------------------------------------------- 결과 붙이기

    private fun deliver(ctx: Context, j: JobInfo, o: JSONObject?, my: Int) = synchronized(deliverLock) {
        val cur = JobStore.get(j.jobId) ?: return@synchronized
        if (cur.delivered) { retries.remove(j.jobId); return@synchronized }
        val rt = retries.getOrPut(j.jobId) { Retry() }
        if (o != null) rt.src = o
        else if (System.currentTimeMillis() < rt.nextAt) return@synchronized   // 재시도 간격 안
        // 맡긴 방의 실행이 아직 돌고 있으면(결과가 모델의 마지막 답보다 먼저 옴) 끝날 때까지 미룬다 — 먼저 붙이면 결과가 요청 위로 가고
        // then 이어가기도 「작업 중」으로 못 했다(실기 2026-09-28 01:53). 횟수에 세지 않는다 — 실행 끝에 kick 이 깨운다
        if (kr.joonlab.foldagent.AgentService.instance?.let { it.busy && it.runningSessionId == j.sessionId } == true) {
            if (!rt.waitingRun) Log.i(TAG, "jobs: 결과 붙이기 미룸 ${j.jobId} — 맡긴 방의 실행이 아직 도는 중")
            rt.waitingRun = true
            rt.nextAt = System.currentTimeMillis() + 1_500
            return@synchronized
        }
        val run = "job-${j.jobId}"
        Records.init(ctx)
        val store = Records.store as? FileRecordStore
        // 맡긴 방이 휴지통에 있으면 되살리지 않고 옆 방에 붙인다(같은 id 로 활성 방이 생기면 휴지통 방과 id 가 겹친다)
        val target = if (store?.isTrashed(j.sessionId) == true) "${j.sessionId}-job" else j.sessionId
        val already = runCatching {
            val t = store?.raw(target)?.optJSONArray("turns")
            t != null && (0 until t.length()).any { t.optJSONObject(it)?.optString("run") == run }
        }.getOrDefault(false)
        if (!already) {
            // 서버 객체가 없으면(재시작 뒤 붙이기 재시도) job_status 로 결과를 다시 받는다
            var src = rt.src
            if (src == null) {
                val tok = Prefs.assistantToken(ctx)
                val s = if (tok.isBlank()) St.Failed else statusOf(Prefs.assistantBase(ctx).trim().trimEnd('/'), tok, j.jobId, my)
                when (s) {
                    is St.Found -> { src = s.o; rt.src = s.o }
                    St.NotFound -> src = lostJob(j.jobId, cur.state)
                    St.Failed -> {
                        if (!alive(my)) return@synchronized
                        if (rt.n < MAX_TRIES) { later(rt, j.jobId, "결과 조회 실패"); return@synchronized }
                        src = JSONObject().put("jobId", j.jobId).put("state", cur.state)
                            .put("result", "결과 글을 다시 받지 못했어요(홈맥 연결 실패) — job_status 로 확인해 주세요.")
                    }
                }
            }
            val missed = downloadAll(ctx, j, src, rt.got, my)
            if (!alive(my)) return@synchronized   // 정지 — 다음 세대가 이어 받는다(받은 첨부는 rt.got 에 있다)
            if (missed > 0 && rt.n < MAX_TRIES) { later(rt, j.jobId, "첨부 ${missed}개 받기 실패"); return@synchronized }
            val atts = JSONArray().apply { rt.got.values.forEach { put(it) } }
            val status = when (cur.state) { "done" -> "ok"; "cancelled" -> "cancel"; else -> "failed" }
            val request = "↳ 맡긴 일: ${j.title.ifBlank { j.skill.ifBlank { j.tool } }}"
            // 원문(종료코드·stdout)은 다음 턴 맥락에만 — 답은 정리·요약한 글(JobSummary)
            val raw = JobSummary.tidy(finalText(cur, src)).take(20_000)
            val names = (0 until atts.length()).mapNotNull { atts.optJSONObject(it)?.optString("title")?.takeIf { t -> t.isNotBlank() } }
            val summed = if (cur.state == "cancelled" || !alive(my)) null else JobSummary.answer(ctx, j.title, raw, names)
            var final = (summed ?: raw).take(6000)
            if (missed > 0) final = (final + "\n\n(첨부 ${missed}개를 받지 못했어요 — 홈맥 작업 폴더에는 남아 있어요)").take(6000)
            val context = if (summed == null) final.take(3000) else "${final.take(2000)}\n\n[홈맥 출력 원문 일부]\n${raw.take(1500)}"
            val turn = JSONObject().put("run", run).put("request", request).put("final", final).put("status", status)
                .put("steps", JSONArray()).put("t", System.currentTimeMillis())
                .put("ms", (System.currentTimeMillis() - j.registeredAt).coerceAtLeast(0)).put("source", "job")
                // 다음 요청의 맥락: 맡긴 일과 그 결과(요약) — 도구 호출이 없으니 user·assistant 한 짝으로
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "user").put("content", "$request (작업 ${j.jobId} — 홈맥에서 끝나 결과가 도착함)"))
                    .put(JSONObject().put("role", "assistant").put("content", context)))
            if (atts.length() > 0) turn.put("attachments", Attachments.sanitize(atts))
            val ok = runCatching { Records.store.appendTurn(target, turn, "job") }
                .onFailure { Log.e(TAG, "jobs: 결과 턴 저장 실패 ${j.jobId}", it) }.isSuccess
            if (!ok) { later(rt, j.jobId, "결과 턴 저장 실패"); return@synchronized }   // 받은 첨부는 rt.got 에 — 다시 받지 않는다
            JobNotify.post(ctx, if (target == j.sessionId) j else j.copy(sessionId = target), final, status)
            if (status == "ok" && cur.then.isNotBlank()) continueAfter(ctx, cur, target)
        }
        JobStore.markDelivered(j.jobId)
        retries.remove(j.jobId)
        RemoteAsk.forget(ctx, j.jobId)
        Log.i(TAG, "jobs: 결과 붙임 ${j.jobId} state=${cur.state}${if (already) "(이미 있음)" else ""}${if (target != j.sessionId) " (휴지통 방 대신 $target)" else ""}")
    }

    /**
     * 맡긴 일이 끝났고 모델이 「끝나면 이어서 할 일(then)」을 남겼다 — 그 방에서 화면을 쓰지 않는 실행으로 바로 잇는다(사용자 결정 2026-09-27:
     * 화면 안 쓰면 바로, 쓰면 알림 눌러 시작). 다른 작업이 돌고 있거나 서비스가 없으면 「이어서 하기」 알림만.
     * 사슬 상한(계약 INPUTS §5): 이어가기가 또 then 을 남기는 사슬은 [MAX_AUTO_CHAIN] 번까지 자동 — 그 뒤는 알림만(누르면 사람이 시킨 실행 = 깊이 1 부터).
     */
    private fun continueAfter(ctx: Context, j: JobInfo, room: String) {
        if (j.depth > MAX_AUTO_CHAIN) {
            Log.i(TAG, "jobs: 이어가기 ${j.jobId} 사슬 깊이 ${j.depth} > $MAX_AUTO_CHAIN — 자동 안 함 → 알림")
            JobNotify.postContinue(ctx, room, j.then, "자동으로 ${MAX_AUTO_CHAIN}번 이어서 했어요. 계속하려면 눌러 주세요.")
            return
        }
        val task = "↳ 이어서: ${j.then} (맡긴 일 ${j.jobId} 이 끝났다 — 결과는 바로 앞 턴. 그 작업 폴더 파일은 code_run from_job=${j.jobId} 로 /in 에서 읽는다)"
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val svc = kr.joonlab.foldagent.AgentService.instance
            val meta = JSONObject().put("source", "job").put("session", room).put("noScreen", true).put("then", j.then).put("fromJob", j.jobId)
                .put("chainDepth", j.depth)   // 이 실행이 또 맡기면 depth = chainDepth + 1(Agent.capTool)
            val started = svc != null && !svc.busy && svc.startTask(task, meta)
            Log.i(TAG, "jobs: 이어가기 ${j.jobId} ${if (started) "시작" else "못 함(작업 중·서비스 없음) → 알림"}")
            if (!started) JobNotify.postContinue(ctx, room, j.then, "맡긴 일이 끝났어요. 눌러서 이어서 할 일을 시작해요.")
        }
    }

    private fun finalText(j: JobInfo, o: JSONObject): String {
        val res = resultText(o.opt("result")).trim()
        return when (j.state) {
            "done" -> res.ifBlank { "끝났어요." }
            "cancelled" -> "취소된 작업이에요." + if (res.isNotBlank()) "\n\n$res" else ""
            else -> "작업이 실패했어요." + (res.ifBlank { j.progress }.takeIf { it.isNotBlank() }?.let { "\n\n$it" } ?: "")
        }.take(200_000)   // 자르기는 정리(JobSummary.tidy) 뒤에 — \r 진행률 한 줄이 앞 6000자를 다 먹었다
    }

    /** result 는 글이거나 객체({text|summary|message|stdout…}) — 사람이 읽을 글로 */
    private fun resultText(r: Any?): String = when (r) {
        null, JSONObject.NULL -> ""
        is String -> r
        is JSONObject -> listOf("text", "summary", "message", "output", "stdout").firstNotNullOfOrNull { k ->
            r.optString(k).takeIf { it.isNotBlank() && it != "null" }
        }?.let { t ->
            val exit = if (r.has("exitCode")) "종료코드 ${r.optInt("exitCode")}\n" else if (r.has("exit")) "종료코드 ${r.optInt("exit")}\n" else ""
            val err = r.optString("stderr").takeIf { it.isNotBlank() && it != "null" }?.let { "\n[stderr]\n${it.take(2000)}" } ?: ""
            exit + t + err
        } ?: r.toString(1).take(3000)
        else -> r.toString()
    }

    // ---------------------------------------------------------------- 첨부 받기

    /**
     * 첨부 받기 — 이미 받은 것(got, fileId → 첨부 JSON)은 건너뛰고 새로 받은 것을 got 에 더한다.
     * 돌려주는 값 = 이번에 못 받은 개수(일시 실패 — 다시 시도할 것). 20MB 넘는 것은 다시 해도 안 되니 세지 않는다.
     */
    private fun downloadAll(ctx: Context, j: JobInfo, o: JSONObject, got: LinkedHashMap<String, JSONObject>, my: Int): Int {
        val arr = o.optJSONArray("attachments") ?: return 0
        val base = Prefs.assistantBase(ctx).trim().trimEnd('/')
        val token = Prefs.assistantToken(ctx).takeIf { it.isNotBlank() } ?: return minOf(arr.length(), Attachments.MAX) - got.size
        var missed = 0
        for (i in 0 until minOf(arr.length(), Attachments.MAX)) {
            val a = arr.optJSONObject(i) ?: continue
            val fileId = a.optString("fileId")
            if (fileId.isBlank() || got.containsKey(fileId)) continue
            if (!alive(my)) { missed++; continue }   // 정지 — 오래 걸리는 받기를 더 시작하지 않는다
            val name = a.optString("name").ifBlank { fileId }
            val size = a.optLong("size", -1)
            if (size > MAX_FILE) { Log.i(TAG, "jobs: 첨부가 너무 큼(건너뜀) ${j.jobId}"); continue }
            val mime = a.optString("mime").takeIf { it.isNotBlank() && it != "null" } ?: Attachments.mimeOfName(name) ?: "application/octet-stream"
            runCatching { JobFiles.fetch(ctx, base, token, fileId, name, mime, j.jobId, i, MAX_FILE) }
                .onFailure { missed++; Log.w(TAG, "jobs: 첨부 받기 실패 ${j.jobId}#$i ${it.javaClass.simpleName}") }
                .getOrNull()?.let { got.put(fileId, it) }
        }
        return missed
    }
}
