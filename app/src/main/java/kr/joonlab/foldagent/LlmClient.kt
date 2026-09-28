package kr.joonlab.foldagent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 호환 /chat/completions 클라이언트 — 공급자 교체 지점.
 * openai-oauth 프록시든 다른 호환 엔드포인트든 base·model·key 만 바꾸면 된다.
 * 프록시는 부하 시 일시적 5xx 를 낸다(개구리 퀘스트 실측) → 5xx 만 1회 재시도.
 */
class LlmClient(
    private val base: String, private val model: String, private val apiKey: String,
    private val onRetry: (String) -> Unit = {},
) {

    class Retryable(msg: String) : IOException(msg)

    /** 응답 전체를 돌려준다(choices[0].message · usage). 기록에 usage 까지 남기려고. */
    fun chat(messages: JSONArray, tools: JSONArray): JSONObject {
        val body = JSONObject().put("model", model).put("messages", messages).put("tools", tools)
        // 5xx 뿐 아니라 시간초과·연결 끊김도 한 번 더 — 프록시 너머 OpenAI 가 가끔 한 호출만 2분 넘게 늦는다(137초 실측, 평소 2~10초).
        // 다시 보내면 대개 곧바로 온다. 기다림은 호출당 60초.
        var last: IOException? = null
        repeat(3) { attempt ->
            try {
                return post(body)
            } catch (e: IOException) {
                val transient = e is Retryable || e is java.net.SocketTimeoutException || e is java.net.ConnectException ||
                    e is java.net.UnknownHostException || e.message?.contains("reset", true) == true
                if (!transient) throw e
                last = e
                onRetry("모델 응답이 늦어 다시 시도 중… (${attempt + 1}/2)")
                Thread.sleep(1500)
            }
        }
        throw last!!
    }

    /** chatJson 결과 — parsed 는 응답 글에서 뽑은 첫 JSON 객체(없으면 null) */
    class JsonReply(val parsed: JSONObject?, val raw: String, val usage: JSONObject?)

    /**
     * 도구 없이 JSON 한 개를 받는 호출(재계획용). 재시도 없음 — 늦으면 부르는 쪽이 다른 모델로 한 번 더 부른다.
     * response_format 은 프록시가 받는지 몰라 넣지 않고, 응답 글에서 첫 { … } 를 꺼내 파싱한다. reasoning_effort 도 보내지 않는다
     * (설계 실험 2026-09-27: gpt-6-sol 은 high 를 줘도 추론 토큰이 같았다 — 프록시가 무시).
     */
    fun chatJson(messages: JSONArray, model: String, timeoutMs: Int): JsonReply {
        val resp = post(JSONObject().put("model", model).put("messages", messages), timeoutMs)
        val text = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")
            .let { if (it is String) it else "" }
        return JsonReply(firstJsonObject(text), text, resp.optJSONObject("usage"))
    }

    /**
     * 이미지 + 질문 → 답(글). 프록시는 /chat/completions 에 이미지를 못 받으므로 /responses + stream(SSE)로 보낸다
     * (개구리 퀘스트 실측). output_text.delta 를 이어 붙인다.
     */
    fun vision(question: String, jpegBase64: String): String = visionMany(question, listOf(jpegBase64))

    /** 이미지 여러 장 + 질문 → 답(받은 이미지 비교 등). 순서대로 붙인다 */
    fun visionMany(question: String, jpegsBase64: List<String>): String {
        val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", question))
        for (j in jpegsBase64) content.put(JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64,$j"))
        val body = JSONObject().put("model", model).put("stream", true).put("input", JSONArray().put(
            JSONObject().put("role", "user").put("content", content)
        ))
        var last: IOException? = null
        repeat(2) {
            try { return postSse(body) } catch (e: Retryable) { last = e; Thread.sleep(1200) }
        }
        throw last!!
    }

    private fun postSse(body: JSONObject): String {
        val conn = URL("$base/responses").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${apiKey.ifEmpty { "dummy" }}")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val t = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code >= 500) throw Retryable("HTTP $code ${t.take(200)}")
                throw IOException("HTTP $code ${t.take(300)}")
            }
            val sb = StringBuilder()
            conn.inputStream.bufferedReader().useLines { lines ->
                lines.filter { it.startsWith("data:") }.forEach { line ->
                    val ev = runCatching { JSONObject(line.substring(5).trim()) }.getOrNull() ?: return@forEach
                    if (ev.optString("type") == "response.output_text.delta") sb.append(ev.optString("delta"))
                }
            }
            return sb.toString().trim()
        } finally {
            conn.disconnect()
        }
    }

    private fun post(body: JSONObject, readTimeoutMs: Int = 60_000): JSONObject {
        val conn = URL("$base/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${apiKey.ifEmpty { "dummy" }}")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 500) throw Retryable("HTTP $code ${text.take(200)}")
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(300)}")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** 글 속 첫 번째 완결된 { … } 를 JSON 으로 — 코드펜스·앞뒤 설명이 붙어도 된다. 문자열 안의 괄호는 센다에서 뺀다 */
        fun firstJsonObject(text: String): JSONObject? {
            var from = text.indexOf('{')
            while (from >= 0) {
                var depth = 0; var inStr = false; var esc = false
                for (i in from until text.length) {
                    val ch = text[i]
                    if (inStr) {
                        if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false
                        continue
                    }
                    when (ch) {
                        '"' -> inStr = true
                        '{' -> depth++
                        '}' -> if (--depth == 0) {
                            runCatching { JSONObject(text.substring(from, i + 1)) }.getOrNull()?.let { return it }
                            break
                        }
                    }
                }
                from = text.indexOf('{', from + 1)
            }
            return null
        }
    }
}
