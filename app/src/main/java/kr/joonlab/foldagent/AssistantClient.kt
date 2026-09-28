package kr.joonlab.foldagent

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

/**
 * 홈맥 홈 비서 폰 라우트(`<base>/exec`·`<base>/health`) 클라이언트 — 계약 docs/server-contract/CONTRACT.md §2·§3.
 *
 * - HttpURLConnection(LlmClient 와 같은 방식) · 연결 10초 · 읽기 = timeoutMs · 헤더 `Authorization: Bearer <token>`
 *   토큰·요청 본문·응답 본문은 로그·예외 메시지에 넣지 않는다(로그는 op·HTTP 상태·ms·code 만).
 * - 5xx(서버 판정 실패·504 제외)·연결 오류 1회 재시도는 **읽기 op 만**. 읽기 시간초과는 재시도하지 않는다(op 시간초과만큼 또 기다리게 된다).
 *   쓰기 판정 = confirmId 가 있거나 op 가 [WRITE_OPS] 안에 있음(둘 중 하나라도) —
 *   멱등 캐시가 있어도 두 번 보내지 않는다.
 * - 정지: cancelled() 는 요청 전·재시도 사이·요청 중(감시 스레드가 200ms 마다) 본다. true 거나 abort() 가 불리면
 *   연결을 끊고 Reply(ok=false, code="cancelled").
 * - 서버 실패 본문 `{ok:false, error:{code, message}}` → Reply(ok=false, code, message). 본문을 못 읽으면 HTTP 상태로 code 를 정한다([codeForHttp]).
 */
class AssistantClient(base: String, private val token: String) {

    /** 서버 응답 한 건. 성공이면 data, 실패면 code(계약 §2-3 의 code 또는 cancelled·unreachable)·message */
    data class Reply(val ok: Boolean, val data: JSONObject?, val code: String?, val message: String?)

    private val base = base.trim().trimEnd('/')

    /** abort() 가 한 번 불렸으면 이 클라이언트는 더 보내지 않는다(Agent 는 도구 호출마다 새로 만든다) */
    @Volatile private var aborted = false

    /**
     * 연결된 뒤 요청 본문을 연결에 넘긴 적이 있나(= 서버에 닿았을 수 있다). 쓰기 op 가 그 뒤 끊김·시간초과로 끝나면
     * 서버가 이미 실행했을 수 있다 — Agent 가 「결과 불명」과 「안 보내짐」을 가르는 데 쓴다(리뷰 safety#3·runtime#4).
     * 연결 전 실패면 false 그대로. 본문이 버퍼에만 있다 응답 요청 때 나가는 경우도 true 라 「불명」 쪽으로 기운다(안전한 쪽).
     */
    @Volatile var bodySent = false
        private set

    /**
     * POST `<base>/exec` 본문 `{op, args, confirmId?}`.
     * @param confirmId 쓰기 op 일 때 게이트를 통과하며 만든 UUID. 읽기 op 는 null
     * @param timeoutMs 읽기 시간초과(op 별 — Agent 가 정한다)
     * @param cancelled 사용자가 정지했는지 — 기다리는 동안 주기적으로 본다
     * @param confirmToken 서버가 need_confirm 과 함께 준 토큰(계약 cc-capabilities §0-2) — 승인 뒤 재전송 때 confirmId 옆 최상위 필드로 보낸다
     */
    fun exec(op: String, args: JSONObject, confirmId: String?, timeoutMs: Int, confirmToken: String? = null, cancelled: () -> Boolean): Reply {
        val body = JSONObject().put("op", op).put("args", args)
        if (confirmId != null) body.put("confirmId", confirmId)
        if (confirmToken != null) body.put("confirmToken", confirmToken)
        val retryable = confirmId == null && op !in WRITE_OPS && op !in EXEC_OPS
        return call("exec:$op", "POST", "$base/exec", body.toString(), timeoutMs, retryable, cancelled, wrapWhole = false)
    }

    /** GET `<base>/health` → data = `{ok, version, ops, mailTestOnly}`(응답 전체 — health 는 data 로 감싸지 않는다) */
    fun health(): Reply = call("health", "GET", "$base/health", null, HEALTH_TIMEOUT_MS, retryable = true, cancelled = { false }, wrapWhole = true)

    /**
     * 폰 파일 올리기 — `POST <base>/upload`(계약 INPUTS §2). 본문 = 파일 바이트 그대로, 헤더 Content-Type(mime)·X-File-Name(percent-encoding UTF-8).
     * 성공 data = `{fileId, name, mime, size}`. **메인 스레드에서 부르지 말 것**.
     *
     * - uri 는 contentResolver.openInputStream 으로 스트리밍한다(메모리에 통째로 올리지 않는다). 크기를 알면 고정 길이, 모르면 청크.
     * - 보내는 도중 [MAX_UPLOAD_BYTES] 를 넘으면 끊고 code="too_large"(크기를 모르는 uri 대비 — 서버도 413).
     * - 재시도하지 않는다(스트림은 한 번만 읽힌다). 파일 이름·토큰은 로그에 넣지 않는다.
     * @param progress 보낸 바이트(대략 64KB 마다) — 부르는 스레드에서 불린다
     */
    fun upload(ctx: Context, uri: Uri, name: String, mime: String, size: Long?, progress: (Long) -> Unit = {}, cancelled: () -> Boolean = { false }): Reply {
        val stop = { aborted || runCatching { cancelled() }.getOrDefault(false) }
        if (stop()) return CANCELLED
        if (size != null && size > MAX_UPLOAD_BYTES) return Reply(false, null, "too_large", "너무 큼(25MB 초과)")
        val t0 = System.currentTimeMillis()
        val conn = try {
            URL("$base/upload").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Reply(false, null, "unreachable", "주소가 잘못됨(${e.javaClass.simpleName})")
        }
        val done = AtomicBoolean(false)
        var connected = false
        val watcher = Thread {
            while (!done.get()) {
                if (stop()) { runCatching { conn.disconnect() }; break }
                try { Thread.sleep(WATCH_MS) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; this.name = "assistant-upload-watch" }
        watcher.start()
        var sent = 0L
        val reply: Reply = try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return Reply(false, null, "exec_failed", "파일을 열 수 없음")
            input.use { ins ->
                conn.requestMethod = "POST"
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = UPLOAD_READ_TIMEOUT_MS
                conn.useCaches = false
                conn.doOutput = true
                if (size != null && size >= 0) conn.setFixedLengthStreamingMode(size) else conn.setChunkedStreamingMode(64 * 1024)
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", mime.ifBlank { "application/octet-stream" })
                conn.setRequestProperty("X-File-Name", URLEncoder.encode(name, "UTF-8").replace("+", "%20"))
                conn.connect()
                connected = true
                conn.outputStream.use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (stop()) throw InterruptedIOException("cancelled")
                        val n = ins.read(buf)
                        if (n < 0) break
                        sent += n
                        if (sent > MAX_UPLOAD_BYTES) throw TooLarge()
                        out.write(buf, 0, n)
                        progress(sent)
                    }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code == 413) Reply(false, null, "too_large", "너무 큼(서버 거부)") else parse(code, text)
        } catch (_: TooLarge) {
            Reply(false, null, "too_large", "너무 큼(25MB 초과)")
        } catch (e: IOException) {
            if (stop()) CANCELLED else ioFailure(e, connected).first
        } catch (e: SecurityException) {
            // 공유받은 uri 읽기 권한이 사라짐(보낸 앱이 권한을 거둠·액티비티가 닫힘)
            Reply(false, null, "exec_failed", "파일 읽기 권한이 없음 — 다시 공유해 주세요")
        } catch (e: Exception) {
            if (stop()) CANCELLED else Reply(false, null, "exec_failed", "올리기 실패(${e.javaClass.simpleName})")
        } finally {
            done.set(true)
            watcher.interrupt()
            runCatching { conn.disconnect() }
        }
        Log.i(TAG, "upload ok=${reply.ok} code=${reply.code ?: "-"} bytes=$sent ${System.currentTimeMillis() - t0}ms")
        return reply
    }

    private class TooLarge : IOException("too large")

    /**
     * 진행 중인 요청을 끊는다(정지 버튼). 요청이 없으면 이후 요청만 막는다.
     * 표지만 세운다 — 연결은 감시 스레드가 200ms 안에 끊는다. 정지는 UI 스레드에서 불리는데
     * HTTPS disconnect 가 close_notify 를 쓰다 메인 스레드 네트워크 예외를 낼 수 있다(리뷰 runtime#9)
     */
    fun abort() {
        aborted = true
    }

    private fun call(
        tag: String, method: String, url: String, body: String?, timeoutMs: Int,
        retryable: Boolean, cancelled: () -> Boolean, wrapWhole: Boolean,
    ): Reply {
        val stop = { aborted || runCatching { cancelled() }.getOrDefault(false) }
        val attempts = if (retryable) 2 else 1
        var last: Reply? = null
        for (attempt in 1..attempts) {
            if (stop()) return CANCELLED
            if (attempt > 1) {
                // 재시도 전 잠깐 쉰다 — 쉬는 동안에도 정지를 본다
                val until = System.currentTimeMillis() + RETRY_DELAY_MS
                while (System.currentTimeMillis() < until) {
                    if (stop()) return CANCELLED
                    try { Thread.sleep(100) } catch (_: InterruptedException) { return CANCELLED }
                }
                Log.i(TAG, "$tag 재시도 (${last?.code})")
            }
            val t0 = System.currentTimeMillis()
            val (reply, transient) = once(method, url, body, timeoutMs, stop, wrapWhole)
            Log.i(TAG, "$tag #$attempt ok=${reply.ok} code=${reply.code ?: "-"} ${System.currentTimeMillis() - t0}ms")
            if (reply.code == "cancelled") return reply
            if (reply.ok || !transient) return reply
            last = reply
        }
        return last ?: CANCELLED
    }

    /** 한 번 보낸다. second = 재시도할 만한 실패인가(5xx·연결 오류) */
    private fun once(
        method: String, url: String, body: String?, timeoutMs: Int, stop: () -> Boolean, wrapWhole: Boolean,
    ): Pair<Reply, Boolean> {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Reply(false, null, "unreachable", "주소가 잘못됨(${e.javaClass.simpleName})") to false
        }
        // 요청 중 정지 감시 — 막힌 읽기는 cancelled() 를 못 보므로 따로 보고 끊는다
        val done = AtomicBoolean(false)
        var connected = false
        val watcher = Thread {
            while (!done.get()) {
                if (stop()) { runCatching { conn.disconnect() }; break }
                try { Thread.sleep(WATCH_MS) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "assistant-watch" }
        watcher.start()
        try {
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = timeoutMs.coerceAtLeast(1_000)
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.connect()
            connected = true
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                bodySent = true
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            // 응답을 끝까지 받았으면 그 사이 정지가 눌렸어도 결과를 그대로 돌려준다 — 쓰기 op 가 서버에서 이미 실행됐는데
            // 「사용자가 멈춤」으로 덮으면 모델·사람이 안 보내진 줄 안다(통합 2026-09-27). 정지 판단은 Agent 가 따로 한다
            val reply = parse(code, text, wrapWhole)
            return reply to (!reply.ok && (code >= 500 && reply.code in TRANSIENT_CODES))
        } catch (e: IOException) {
            if (stop()) return CANCELLED to false
            return ioFailure(e, connected)
        } catch (e: Exception) {
            if (stop()) return CANCELLED to false
            return Reply(false, null, "exec_failed", "요청 실패(${e.javaClass.simpleName})") to false
        } finally {
            done.set(true)
            watcher.interrupt()
            runCatching { conn.disconnect() }
        }
    }

    companion object {
        private const val TAG = "FoldAgent"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val HEALTH_TIMEOUT_MS = 10_000
        private const val RETRY_DELAY_MS = 800L
        private const val WATCH_MS = 200L
        private const val UPLOAD_READ_TIMEOUT_MS = 60_000
        /** 파일 하나 상한(계약 INPUTS §1·§2) */
        const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024

        /** 쓰기 op(계약 §2-4 ✍) — confirmId 가 없어도 이 이름이면 재시도하지 않는다(이중 안전) */
        val WRITE_OPS = setOf("memory_add", "event_create", "event_delete", "mail_send", "mail_reply")

        /** 홈맥에서 무언가를 실행하는 op(계약 cc-capabilities §1-2) — 승인 없는 auto 실행·job 생성도 두 번 보내면 두 번 돈다. 재시도 금지 */
        val EXEC_OPS = setOf("run", "code_run", "mcp_call", "job_cancel", "claude_task")

        /** 5xx 중 다시 보내 볼 만한 것 — 서버가 판정한 exec_failed·tt_auth_expired·not_configured·timeout(504, op 시간초과)은 다시 보내도 같다 */
        private val TRANSIENT_CODES = setOf("unreachable", "server_error")

        val CANCELLED = Reply(false, null, "cancelled", "사용자가 멈춤")

        /** 연결 단계 예외 → Reply·재시도 여부. 메시지에 URL·토큰을 넣지 않는다(예외 이름만) */
        internal fun ioFailure(e: IOException, connected: Boolean): Pair<Reply, Boolean> = when {
            e is SocketTimeoutException && !connected ->
                // 연결 10초 초과 = 홈맥에 닿지 못함 → 연결 오류로 보고 읽기 op 면 한 번 더
                Reply(false, null, "unreachable", "홈맥에 연결 못 함(연결 시간 초과) — tailscale 연결 확인") to true
            e is SocketTimeoutException || e is InterruptedIOException ->
                // 읽기 시간 초과 — 서버가 일하는 중일 수 있다. 다시 보내면 op 시간초과만큼 또 기다리므로 재시도하지 않는다
                Reply(false, null, "timeout", "홈맥 응답 없음(시간 초과)") to false
            e is UnknownHostException || e is ConnectException || e is NoRouteToHostException ->
                Reply(false, null, "unreachable", "홈맥에 연결 못 함(${e.javaClass.simpleName}) — tailscale 연결 확인") to true
            e is SSLException ->
                Reply(false, null, "unreachable", "HTTPS 연결 실패(${e.javaClass.simpleName})") to true
            else ->
                Reply(false, null, "unreachable", "연결 오류(${e.javaClass.simpleName})") to true
        }

        /** 본문에서 서버 code 를 못 읽었을 때 HTTP 상태로 정한다(계약 §2-3) */
        internal fun codeForHttp(http: Int): String = when (http) {
            400, 413, 422 -> "bad_args"
            401 -> "auth"
            403 -> "not_allowed"
            404 -> "unreachable"            // 라우트가 없다 = 주소가 틀렸거나 서버가 아직 배포 전
            408, 504 -> "timeout"
            502 -> "unreachable"            // tailscale serve 뒤 서버(:3100)가 꺼져 있으면 502 가 본문 없이 온다
            503 -> "not_configured"
            else -> if (http >= 500) "server_error" else "exec_failed"
        }

        /**
         * HTTP 상태 + 본문 → Reply. JVM 에서 시험할 수 있게 연결과 떼어 둔다.
         * 성공 `{ok:true, data}` → data(객체가 아니면 감싼다: 배열 = {items:[…]}, 그 밖 = {value:…}). `replay:true` 면 data 에 `replay:true` 를 얹는다.
         * wrapWhole = true(health) 면 응답 객체 전체가 data.
         * 실패 `{ok:false, error:{code, message}}` → 그 code·message. 본문이 JSON 이 아니면 [codeForHttp] + message 에 HTTP 상태.
         */
        fun parse(http: Int, text: String, wrapWhole: Boolean = false): Reply {
            val json = runCatching { JSONObject(text.trim()) }.getOrNull()
            if (json == null) {
                val c = if (http in 200..299) "exec_failed" else codeForHttp(http)
                return Reply(false, null, c, "HTTP $http — 응답을 읽지 못함")
            }
            val ok = json.optBoolean("ok", false)
            if (http in 200..299 && ok) {
                if (wrapWhole) return Reply(true, json, null, null)
                val data = when (val d = json.opt("data")) {
                    is JSONObject -> d
                    is JSONArray -> JSONObject().put("items", d)
                    null, JSONObject.NULL -> JSONObject()
                    else -> JSONObject().put("value", d)
                }
                if (json.optBoolean("replay", false)) data.put("replay", true)
                return Reply(true, data, null, null)
            }
            val err = json.optJSONObject("error")
            val code = err?.optString("code")?.takeIf { it.isNotBlank() }
                ?: json.optString("code").takeIf { it.isNotBlank() }   // error 가 문자열이거나 평평한 모양이어도 받는다
                ?: if (http in 200..299) "exec_failed" else codeForHttp(http)
            val message = err?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.opt("error").let { if (it is String && it.isNotBlank()) it else null }
                ?: json.optString("message").takeIf { it.isNotBlank() }
                ?: "HTTP $http"
            // 실패여도 data 는 넘긴다 — need_confirm 은 data 에 확인 문구·confirmToken 을 싣는다(계약 cc-capabilities §1-2)
            return Reply(false, json.optJSONObject("data"), code, message)
        }
    }
}
