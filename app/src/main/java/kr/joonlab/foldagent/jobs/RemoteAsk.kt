package kr.joonlab.foldagent.jobs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import kr.joonlab.foldagent.AgentService
import kr.joonlab.foldagent.AssistantClient
import kr.joonlab.foldagent.MainActivity
import kr.joonlab.foldagent.Prefs
import kr.joonlab.foldagent.R
import org.json.JSONObject

/**
 * 원격 확인 — 홈맥에 맡긴 일(claude_task)이 폰 승인을 기다린다(계약 docs/server-contract/CONTRACT.md §2-2·§3·§3-1).
 *
 * JobWatcher 가 서버 job 객체를 받을 때마다 [onJob] 을 부른다. `state:"waiting"` + `ask{reqId,tool,text,confirmToken,askedAt,expiresAt}` 면:
 *   - 채팅(앱)이 앞이면 바로 확인 창, 아니면 알림 채널 「원격 확인」(heads-up)으로 알린다.
 *     **알림에는 승인·거부 버튼을 두지 않는다**(잘린 글로 승인하지 않게) — 누르면 앱이 열리고 [open] 이 전문 확인 창을 띄운다.
 *   - 확인 창 = AgentService.confirmRemote(에이전트 확인과 같은 패널 카드, 시한 = expiresAt 까지 남은 시간).
 *     실행/거부 → op `job_answer {jobId, reqId, allow, confirmToken}`. 답 없이 닫힘·시한 만료 = 답하지 않음(서버가 10분에 자동 거부).
 *   - 같은 reqId 로 알림·창을 두 번 띄우지 않는다. 상태가 waiting 에서 벗어나면(또는 다른 reqId 로 바뀌면) 알림을 지우고 떠 있는 창을 치운다.
 * 기억은 메모리에만 — 프로세스가 다시 뜨면 JobWatcher 가 waiting 작업을 job_status 로 한 번 물어 다시 받는다.
 */
object RemoteAsk {
    private const val TAG = "FoldAgent"
    const val CHANNEL = "remote_ask"
    /** MainActivity 가 읽는 extra — 확인을 띄울 작업 id(방은 JobNotify.EXTRA_ROOM 으로 같이 온다) */
    const val EXTRA_ASK_JOB = "askJob"
    /** 남은 시간이 이보다 짧으면 띄우지 않는다(누르는 사이 서버가 먼저 거부한다) */
    private const val MIN_LEFT_MS = 5_000L
    private const val ANSWER_TIMEOUT_MS = 20_000
    private const val ANSWER_TRIES = 3

    /** 서버가 보낸 확인 요청 한 건 */
    data class Ask(val jobId: String, val reqId: String, val tool: String, val text: String, val confirmToken: String,
                   val askedAt: Long, val expiresAt: Long) {
        val key get() = "$jobId:$reqId"
    }

    private val LOCK = Any()
    /** jobId → 지금 기다리는 확인(서버 기준) */
    private val current = HashMap<String, Ask>()
    /** 알림을 올렸거나 창을 띄운 reqId(jobId:reqId) — 두 번 알리지 않게 */
    private val notified = HashSet<String>()
    /** 창이 떠 있는 reqId */
    private val showing = HashSet<String>()
    /** 끝난 reqId(답 보냄·답 없이 닫힘·시한 만료) — 다시 띄우지 않는다 */
    private val done = LinkedHashSet<String>()

    /** 이 작업의 확인 요청을 아직 모른다(프로세스 재시작 뒤 state 만 waiting 으로 남은 경우) — JobWatcher 가 job_status 로 한 번 묻는다 */
    fun unknown(jobId: String): Boolean = synchronized(LOCK) { !current.containsKey(jobId) }

    private fun parse(jobId: String, o: JSONObject?): Ask? {
        o ?: return null
        fun str(k: String) = o.opt(k).let { if (it == null || it == JSONObject.NULL) "" else it.toString() }
        val reqId = str("reqId").trim().ifEmpty { return null }
        val tok = str("confirmToken").trim().ifEmpty { return null }
        val text = str("text")
        if (text.isBlank()) return null   // 무엇을 승인하는지 모르는 채로 묻지 않는다
        val asked = o.optLong("askedAt", System.currentTimeMillis())
        return Ask(jobId, reqId, str("tool").trim().ifEmpty { "?" }, text, tok, asked, o.optLong("expiresAt", asked + 10 * 60_000L))
    }

    /** 서버 job 객체 하나 반영(JobWatcher.apply — 우리가 맡긴 작업만). 감시 스레드에서 불린다 */
    fun onJob(ctx: Context, j: JobInfo, o: JSONObject) {
        val ask = if (o.optString("state") == "waiting") parse(j.jobId, o.optJSONObject("ask")) else null
        val (gone, fresh) = synchronized(LOCK) {
            val prev = current[j.jobId]
            if (ask == null) { current.remove(j.jobId); prev to false }
            else {
                current[j.jobId] = ask
                val g = prev?.takeIf { it.reqId != ask.reqId }
                g to (ask.key !in notified && ask.key !in done)
            }
        }
        gone?.let { clear(ctx, it) }
        if (ask == null && o.optString("state") == "waiting") Log.w(TAG, "remote_ask: waiting 인데 ask 가 비었거나 형식이 틀림 ${j.jobId}")
        if (ask != null && fresh) announce(ctx, j, ask)
    }

    /** 끝난 작업(결과 붙임) — 남은 알림·창을 치운다 */
    fun forget(ctx: Context, jobId: String) {
        val prev = synchronized(LOCK) { current.remove(jobId) } ?: return
        clear(ctx, prev)
    }

    private fun clear(ctx: Context, a: Ask) {
        cancelNotif(ctx, a)
        AgentService.instance?.withdrawRemote(a.key)
        synchronized(LOCK) { notified.remove(a.key) }
        Log.i(TAG, "remote_ask: 풀림 ${a.key}")
    }

    /** 새 확인 요청 — 채팅이 앞이면 바로 창, 아니면 알림 */
    private fun announce(ctx: Context, j: JobInfo, a: Ask) {
        synchronized(LOCK) { notified += a.key }
        val left = a.expiresAt - System.currentTimeMillis()
        Log.i(TAG, "remote_ask: 새 확인 ${a.key} tool=${a.tool} 남은 ${left / 1000}초")
        if (left < MIN_LEFT_MS) return
        // 채팅 화면이 앞이면 알림 대신 바로 확인 창(에이전트 확인 창은 늘 뜨는 규칙 그대로). 알림 권한이 없으면 알릴 길이 없으니 창으로
        if (MainActivity.current != null || !JobNotify.allowed(ctx)) show(ctx, a, j.title)
        else postNotif(ctx, j, a)
    }

    /**
     * 앱이 앞으로 왔다(MainActivity.onResume) — 기다리는 확인이 있으면 띄운다. 헤즈업 알림이 사라진 뒤 알림을 안 누르고
     * 앱을 직접 열면 확인이 어디에도 안 보였다(2026-09-27 실기 cc-phone-1). 답하지 않고 닫은 것(done)은 다시 띄우지 않는다
     */
    fun resumePending(ctx: Context) {
        val pending = synchronized(LOCK) { current.values.filter { it.key !in done && it.key !in showing } }
        for (a in pending) {
            Log.i(TAG, "remote_ask: 앱 열림 — 기다리는 확인 띄움 ${a.key}")
            show(ctx, a, JobStore.get(a.jobId)?.title.orEmpty())
        }
    }

    /** 알림을 눌렀다(MainActivity) — 그 작업의 지금 확인을 띄운다. 이미 끝났으면 아무것도 안 한다 */
    fun open(ctx: Context, jobId: String) {
        val a = synchronized(LOCK) { current[jobId] } ?: run { Log.i(TAG, "remote_ask: 누른 알림의 확인이 이미 풀림 $jobId"); return }
        show(ctx, a, JobStore.get(jobId)?.title.orEmpty())
    }

    private fun confirmText(a: Ask, title: String) = buildString {
        append("🤖 맡긴 일이 확인을 기다려요 · ").append(a.tool).append('\n')
        if (title.isNotBlank()) append("작업: ").append(title.take(60)).append('\n')
        // 서버 문구(실행할 원문)를 그대로 — 보여 준 것 = 실행하는 것
        append(a.text)
    }

    private fun show(ctx: Context, a: Ask, title: String) {
        val svc = AgentService.instance ?: run { Log.i(TAG, "remote_ask: 서비스 없음 — 창 못 띄움 ${a.key}"); return }
        val left = a.expiresAt - System.currentTimeMillis()
        if (left < MIN_LEFT_MS) { Log.i(TAG, "remote_ask: 시한이 거의 지남 — 안 띄움 ${a.key}"); return }
        synchronized(LOCK) {
            if (a.key in done || a.key in showing || current[a.jobId]?.reqId != a.reqId) return
            showing += a.key
        }
        val app = ctx.applicationContext
        Thread({
            try {
                cancelNotif(app, a)   // 창을 띄우면 알림은 치운다(답이 안 나면 아래에서 다시 올린다)
                when (val r = svc.confirmRemote(a.key, confirmText(a, title), left, "🤖 맡긴 일" + (if (title.isNotBlank()) ": ${title.take(40)}" else ""))) {
                    AgentService.RemoteAnswer.ALLOW, AgentService.RemoteAnswer.DENY -> answer(app, a, r == AgentService.RemoteAnswer.ALLOW)
                    AgentService.RemoteAnswer.NONE -> { synchronized(LOCK) { done += a.key; trimDone() }; Log.i(TAG, "remote_ask: 답 안 함 ${a.key}") }
                    // 다른 확인 창이 떠 있었거나 밀렸다 — 아직 기다리는 중이면 알림을 다시 올려 둔다(누르면 다시 뜬다)
                    AgentService.RemoteAnswer.BUSY, AgentService.RemoteAnswer.WITHDRAWN -> repost(app, a)
                }
            } finally {
                synchronized(LOCK) { showing -= a.key }
            }
        }, "remote-ask").start()
    }

    private fun repost(ctx: Context, a: Ask) {
        val still = synchronized(LOCK) { current[a.jobId]?.reqId == a.reqId && a.key !in done }
        if (!still) return
        val j = JobStore.get(a.jobId) ?: return
        if (JobNotify.allowed(ctx)) postNotif(ctx, j, a) else Log.i(TAG, "remote_ask: 알림 권한 없음 — 다음 상태 변화 때 다시 ${a.key}")
    }

    /** 답 보내기 — reqId 멱등(서버가 두 번째 답은 무시)이라 끊기면 다시 보낸다 */
    private fun answer(ctx: Context, a: Ask, allow: Boolean) {
        val base = Prefs.assistantBase(ctx).trim().trimEnd('/')
        val token = Prefs.assistantToken(ctx)
        val args = JSONObject().put("jobId", a.jobId).put("reqId", a.reqId).put("allow", allow).put("confirmToken", a.confirmToken)
        var last = "토큰 없음"
        if (token.isNotBlank()) for (k in 1..ANSWER_TRIES) {
            val r = runCatching { AssistantClient(base, token).exec("job_answer", args, null, ANSWER_TIMEOUT_MS) { false } }.getOrNull()
            if (r != null && r.ok) {
                val d = r.data ?: JSONObject()
                synchronized(LOCK) { done += a.key; trimDone() }
                Log.i(TAG, "remote_ask: 답 보냄 ${a.key} allow=$allow → ${d.optString("decision")}${if (d.optBoolean("replay")) "(이미 답함)" else ""}")
                JobWatcher.kick()
                return
            }
            last = r?.code ?: "예외"
            // 토큰 불일치·없는 작업은 다시 보내도 같다
            if (r != null && r.code in setOf("bad_args", "not_found")) break
            if (k < ANSWER_TRIES) Thread.sleep(2_000L * k)
        }
        Log.w(TAG, "remote_ask: 답 보내기 실패 ${a.key} ($last)")
        if (last in setOf("bad_args", "not_found")) { synchronized(LOCK) { done += a.key; trimDone() }; return }
        // 못 보냈다 — 아직 기다리는 중이면 알림을 다시 올려 한 번 더 답할 수 있게
        repost(ctx, a)
    }

    private fun trimDone() { while (done.size > 200) done.remove(done.first()) }

    // ---------------------------------------------------------------- 알림

    private fun notifId(a: Ask) = ("ask:${a.jobId}").hashCode()

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "원격 확인", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "홈맥에 맡긴 일이 파일 쓰기·명령 실행 전에 확인을 기다릴 때 알려요. 누르면 앱에서 전문을 보고 답해요"
        })
    }

    private fun postNotif(ctx: Context, j: JobInfo, a: Ask) {
        runCatching {
            ensureChannel(ctx)
            val open = Intent(ctx, MainActivity::class.java).putExtra(JobNotify.EXTRA_ROOM, j.sessionId).putExtra(EXTRA_ASK_JOB, a.jobId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, notifId(a), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val body = "${a.tool}: ${a.text.replace(Regex("\\s+"), " ").trim().take(60)}"
            // 승인·거부 버튼은 두지 않는다 — 잘린 글로 승인하지 않게(DELEGATION §3). 누르면 앱에서 전문 확인 창
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("맡긴 일이 확인을 기다려요")
                .setContentText(body)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setTimeoutAfter((a.expiresAt - System.currentTimeMillis()).coerceAtLeast(1_000L))
                .setContentIntent(pi)
                .build()
            ctx.getSystemService(NotificationManager::class.java)?.notify(notifId(a), n)
        }.onFailure { Log.w(TAG, "remote_ask: 알림 실패 $it") }
    }

    private fun cancelNotif(ctx: Context, a: Ask) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(notifId(a)) }
    }

    // ---------------------------------------------------------------- 디버그

    /** 디버그 전용(MainActivity `--es remoteasktest "글"`) — 서버 없이 원격 확인 창만 띄워 결과를 로그로. 답은 보내지 않는다 */
    fun debugShow(text: String, sec: Int) {
        val svc = AgentService.instance ?: return
        Thread({
            val r = svc.confirmRemote("debug:${System.currentTimeMillis()}",
                confirmText(Ask("debug", "debug", "Bash", text, "-", 0, 0), "원격 확인 시험"), sec.coerceIn(5, 600) * 1000L)
            Log.i(TAG, "remoteasktest result=$r")
        }, "remote-ask-test").start()
    }
}
