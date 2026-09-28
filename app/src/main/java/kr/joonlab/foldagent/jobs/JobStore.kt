package kr.joonlab.foldagent.jobs

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 맡긴 작업 한 건(폰 쪽 기억). state 는 서버 값 그대로 — queued·running·waiting(폰 확인 대기)·done·failed·cancelled. 모르는 값은 끝나지 않은 것으로 */
data class JobInfo(val jobId: String, val sessionId: String, val runId: String, val title: String, val tool: String, val skill: String,
                   val state: String, val progress: String, val registeredAt: Long, val updatedAt: Long, val lastPollAt: Long,
                   val delivered: Boolean,
                   /** 끝나면 이어서 할 일(모델이 run 의 then 으로 남김) — 결과가 오면 폰이 이어서 시작한다(JobWatcher) */
                   val then: String = "",
                   /** 이어가기 사슬 깊이(계약 INPUTS §5) — 사람이 시킨 실행에서 맡김 = 1, 이어가기 실행이 맡김 = 앞 depth + 1 */
                   val depth: Int = 1) {
    val terminal get() = state in TERMINAL
    companion object { val TERMINAL = setOf("done", "failed", "cancelled") }
}

/**
 * 맡긴 작업 목록 — 파일 하나(files/jobs/jobs.json), 쓰기는 임시 파일 + fsync + rename(기록 저장소와 같은 방식).
 * 모양: `{v:1, cursor, jobs:{<jobId>:{jobId, sessionId, runId, title, tool, skill, state, progress, registeredAt, updatedAt, lastPollAt, delivered}}}`
 *
 * 한 프로세스 안에서 서비스(감시)와 화면(칩)이 같은 메모리 사본을 쓴다 — 화면은 [pending] 만 읽는다(디스크 안 건드림).
 * 결과를 방에 붙인 작업(delivered)은 7일 뒤 지운다(목록 100개 상한).
 */
object JobStore {
    private const val TAG = "FoldAgent"
    private const val KEEP_MS = 7L * 86_400_000L
    private const val MAX = 100
    private val LOCK = Any()
    private var loaded = false
    private var file: File? = null
    private var cursor = ""
    private val jobs = LinkedHashMap<String, JobInfo>()

    /** 아직 방에 결과를 안 붙인 작업(칩·감시용) — 바뀔 때마다 새 목록으로 바꿔 끼운다(읽는 쪽은 잠금 없이) */
    @Volatile var pending: List<JobInfo> = emptyList()
        private set

    fun load(ctx: Context) {
        if (loaded) return
        synchronized(LOCK) {
            if (loaded) return
            val f = File(ctx.applicationContext.getExternalFilesDir(null) ?: ctx.applicationContext.filesDir, "jobs/jobs.json")
            file = f
            runCatching {
                if (f.exists()) {
                    val j = JSONObject(f.readText())
                    cursor = j.optString("cursor")
                    val js = j.optJSONObject("jobs") ?: JSONObject()
                    js.keys().forEach { k -> js.optJSONObject(k)?.let { o -> fromJson(o)?.let { jobs[it.jobId] = it } } }
                }
            }.onFailure { Log.w(TAG, "jobs: 목록 읽기 실패(빈 목록으로) $it") }
            loaded = true
            refresh()
        }
    }

    /** 새로 등록했으면 true(같은 jobId 두 번째부터는 false — 멱등) */
    fun register(ctx: Context, jobId: String, sessionId: String, runId: String, title: String, tool: String, skill: String, then: String = "", depth: Int = 1): Boolean {
        load(ctx)
        synchronized(LOCK) {
            if (jobs.containsKey(jobId)) return false
            val now = System.currentTimeMillis()
            jobs[jobId] = JobInfo(jobId, sessionId, runId, title.take(80), tool, skill, "queued", "", now, now, now, false, then.take(1000), depth.coerceAtLeast(1))
            prune(now)
            save()
            return true
        }
    }

    fun get(jobId: String): JobInfo? = synchronized(LOCK) { jobs[jobId] }

    /** 서버 상태 반영. 모르는 jobId 면 null(폰이 맡긴 게 아니다) */
    fun update(jobId: String, state: String, progress: String): JobInfo? = synchronized(LOCK) {
        val j = jobs[jobId] ?: return null
        val now = System.currentTimeMillis()
        val n = j.copy(state = state.ifBlank { j.state }, progress = progress.take(200), updatedAt = now, lastPollAt = now)
        jobs[jobId] = n
        save()
        n
    }

    /** job_status 로 따로 물어본 시각(늦은 작업 확인 주기용) — 파일엔 쓰지 않는다 */
    fun touchPoll(jobId: String) = synchronized(LOCK) { jobs[jobId]?.let { jobs[jobId] = it.copy(lastPollAt = System.currentTimeMillis()); refresh() } }

    fun markDelivered(jobId: String) = synchronized(LOCK) {
        val j = jobs[jobId] ?: return@synchronized
        jobs[jobId] = j.copy(delivered = true, updatedAt = System.currentTimeMillis())
        save()
    }

    fun cursor(): String = synchronized(LOCK) { cursor }
    fun setCursor(c: String) = synchronized(LOCK) { if (c != cursor) { cursor = c; save() } }

    // ---------------------------------------------------------------- 내부

    private fun refresh() { pending = jobs.values.filter { !it.delivered }.sortedBy { it.registeredAt } }

    private fun prune(now: Long) {
        jobs.values.filter { it.delivered && now - it.updatedAt > KEEP_MS }.forEach { jobs.remove(it.jobId) }
        if (jobs.size > MAX) jobs.values.filter { it.delivered }.sortedBy { it.updatedAt }.take(jobs.size - MAX).forEach { jobs.remove(it.jobId) }
    }

    /** LOCK 안에서만. 실패해도 메모리 사본은 살아 있다(다음 쓰기에 다시 시도) */
    private fun save() {
        refresh()
        val f = file ?: return
        runCatching {
            val js = JSONObject()
            jobs.values.forEach { js.put(it.jobId, toJson(it)) }
            writeAtomic(f, JSONObject().put("v", 1).put("cursor", cursor).put("jobs", js).toString())
        }.onFailure { Log.w(TAG, "jobs: 목록 저장 실패 $it") }
    }

    private fun writeAtomic(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        FileOutputStream(tmp).use { it.write(text.toByteArray()); it.fd.sync() }
        if (!tmp.renameTo(f)) { tmp.delete(); throw IOException("rename 실패: ${f.name}") }
    }

    private fun toJson(j: JobInfo) = JSONObject().put("jobId", j.jobId).put("sessionId", j.sessionId).put("runId", j.runId)
        .put("title", j.title).put("tool", j.tool).put("skill", j.skill).put("state", j.state).put("progress", j.progress)
        .put("registeredAt", j.registeredAt).put("updatedAt", j.updatedAt).put("lastPollAt", j.lastPollAt).put("delivered", j.delivered).put("then", j.then).put("depth", j.depth)

    private fun fromJson(o: JSONObject): JobInfo? {
        val id = o.optString("jobId").ifBlank { return null }
        val sid = o.optString("sessionId").ifBlank { return null }
        return JobInfo(id, sid, o.optString("runId"), o.optString("title"), o.optString("tool"), o.optString("skill"),
            o.optString("state", "queued"), o.optString("progress"), o.optLong("registeredAt"), o.optLong("updatedAt"),
            o.optLong("lastPollAt"), o.optBoolean("delivered"), o.optString("then"), o.optInt("depth", 1).coerceAtLeast(1))
    }
}
