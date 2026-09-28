package kr.joonlab.foldagent

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 직접 도구 — 폰이 :8531(Prefs.base) `/v1/responses` 에 `Prefs.oaiToolModel`(기본 gpt-5.5)로 바로 부른다.
 * 계약 docs/server-contract/CONTRACT.md §1-1 · §1-3.
 *
 * 스키마(TOOLS·NAMES)·PROMPT·summarize·status 는 P0, 실행(exec)·SSE·첨부는 W-P1.
 *
 * exec 규약(W-P1 이 지킬 것 — Agent.oaiTool 이 이 모양을 전제로 결선돼 있다):
 *   - 반환 = 모델이 읽을 결과 글. 실패는 「실패: 」로 시작(failStreak 에 셈). 사용자가 멈췄으면 정확히 「사용자가 멈춤」.
 *   - 정지: cancelled() 를 요청 중에도 주기적으로 보고 true 면 연결을 끊는다(AssistantClient 의 감시 스레드 방식). Agent 는 따로 끊지 않는다.
 *   - 첨부: 만든 이미지·출처 링크는 attach(첨부 JSON)로 보고한다 — 모양은 Agent.addAttachment 주석(계약 §1-3). 결과 글에 첨부를 지어 넣지 않는다.
 *   - 시간초과: web_search·read_url 60초, image_gen 150초. SSE 한 줄이 길다(이미지 base64 ~1.1MB) — 줄 길이 제한 없이 읽는다.
 *   - `stream:true`, `tool_choice` 보내지 않음. 키·토큰은 로그·결과에 넣지 않는다.
 */
object OaiTools {

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().put("type", "function").put(
            "function", JSONObject().put("name", name).put("description", desc).put(
                "parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))
            )
        )

    private fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

    val TOOLS: List<JSONObject> = listOf(
        fn("web_search", "웹을 검색해 답과 출처를 가져온다(폰 브라우저를 열지 않는다). 최신 정보·뉴스·장소·가격처럼 네가 모르거나 바뀌는 것은 지어내지 말고 이것으로 찾는다. " +
            "출처 링크는 채팅에 자동으로 붙는다", JSONObject()
            .put("query", p("string", "검색할 말(한국어 가능)"))
            .put("detail", JSONObject().put("type", "string").put("enum", JSONArray(listOf("짧게", "자세히")))
                .put("description", "답 길이(기본 짧게)")), listOf("query")),
        fn("read_url", "링크(URL) 하나를 열어 내용을 요약하거나 필요한 부분만 뽑는다(폰 브라우저를 열지 않는다). 사용자가 준 링크의 「핵심만」「요약」 요청에 쓴다", JSONObject()
            .put("url", p("string", "읽을 주소(http·https)"))
            .put("question", p("string", "무엇을 뽑을지(선택, 없으면 핵심 요약)")), listOf("url")),
        fn("image_gen", "그림을 새로 그리거나 사진을 고친다. 만든 그림은 갤러리(Pictures/FoldAgent)에 FoldAgent_gen_… 이름으로 저장되고 채팅에 붙는다 — " +
            "보내 달라는 요청이면 그 이름으로 share_images 를 부른다", JSONObject()
            .put("prompt", p("string", "무엇을 그릴지·어떻게 고칠지(자세할수록 좋다)"))
            .put("from", p("string", "고칠 사진(선택): 우리 앱이 만든 FoldAgent_ 로 시작하는 이름, 또는 \"screen\" = 지금 폰 화면"))
            .put("size", JSONObject().put("type", "string").put("enum", JSONArray(listOf("square", "portrait", "landscape")))
                .put("description", "크기(기본 square)")), listOf("prompt")),
    )

    val NAMES: Set<String> = TOOLS.map { it.getJSONObject("function").getString("name") }.toSet()

    /** BASE_PROMPT 뒤에 붙는 한 단락(Agent.systemPrompt) */
    val PROMPT: String = """
        ## 웹·그림 도구(폰 화면을 쓰지 않는다)
        - 최신 정보·장소·가격·뉴스처럼 바뀌거나 네가 확실히 모르는 것은 web_search 로 찾는다. 지어내지 않는다. 브라우저 앱을 열어 검색하지 않는다.
        - 사용자가 준 링크의 내용은 read_url 로 읽는다.
        - 그림 그리기·사진 고치기는 image_gen. 만든 그림은 갤러리에 저장되고 채팅에 붙는다 — 결과 글의 FoldAgent_ 이름을 share_images 에 그대로 쓴다.
        - 출처 링크·그림은 채팅에 자동으로 붙으니 finish 에 주소를 길게 읊지 않는다.
    """.trimIndent()

    /**
     * 도구 하나 실행 — 위 exec 규약을 따른다. **작업 스레드(Agent run 스레드)에서만** 부른다(네트워크·디스크·화면 캡처).
     * @param attach 첨부 보고 — Agent.addAttachment
     */
    fun exec(svc: AgentService, name: String, args: JSONObject, cancelled: () -> Boolean, attach: (JSONObject) -> Unit): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return "실패: $name 을 메인 스레드에서 부름(내부 오류) — finish 로 알릴 것"
        if (cancelled()) return STOPPED
        return when (name) {
            "web_search" -> webSearch(svc, args, cancelled, attach)
            "read_url" -> readUrl(svc, args, cancelled, attach)
            "image_gen" -> imageGen(svc, args, cancelled, attach)
            else -> "실패: 모르는 도구 $name"
        }
    }

    // ---- 도구별

    private fun webSearch(svc: AgentService, args: JSONObject, cancelled: () -> Boolean, attach: (JSONObject) -> Unit): String {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return "실패: query 가 비었음"
        val detail = args.optString("detail") == "자세히"
        val ask = "오늘은 ${today()}(한국 시간)이다. 웹을 검색해 아래 질문에 한국어로 답하라. " +
            (if (detail) "자세히(항목별로, 20줄 이내). " else "짧게(3~5문장). ") +
            "날짜·가격·장소·시간처럼 바뀌는 정보는 검색 결과에 나온 것만 쓰고, 찾지 못한 것은 못 찾았다고 적는다. 출처를 인용하라.\n\n질문: $query"
        val body = request(svc, JSONArray().put(inputText(ask)), JSONObject().put("type", "web_search"), lowEffort = true)
        val r = stream(svc, "web_search", body, SEARCH_TIMEOUT_MS, cancelled)
        stopText(r, SEARCH_TIMEOUT_MS)?.let { return it }
        r.error?.let { return "실패: 웹 검색 오류 — $it" }
        val text = cleanText(r.finalText)
        if (text.isEmpty()) return "실패: 웹 검색 답이 비어 있음 — 다시 한 번만 시도하거나 finish 로 알릴 것"
        val links = r.links.entries.take(MAX_LINKS)
        links.forEach { (url, title) -> attach(link(url, title)) }
        return clip(text, TEXT_MAX) + if (links.isEmpty()) "\n(출처 링크 없음)"
            else "\n출처 ${links.size}개(채팅에 링크로 붙음): " + links.joinToString(" · ") { hostOf(it.key) }
    }

    private fun readUrl(svc: AgentService, args: JSONObject, cancelled: () -> Boolean, attach: (JSONObject) -> Unit): String {
        val url = args.optString("url").trim()
        if (!(url.startsWith("https://", true) || url.startsWith("http://", true)) || hostOf(url).isEmpty())
            return "실패: http·https 주소가 아니다 — 「$url」"
        val question = args.optString("question").trim()
        val ask = "아래 주소의 웹페이지를 직접 열어(web_search 로 이 주소를 연다) 읽고, 한국어로 " +
            (if (question.isNotEmpty()) "다음을 뽑아 답하라: $question" else "핵심만 5줄 이내로 요약하라.") +
            " 페이지를 열 수 없거나 내용이 없으면 그렇다고 분명히 말하고 추측으로 채우지 말 것.\n\n주소: $url"
        val body = request(svc, JSONArray().put(inputText(ask)), JSONObject().put("type", "web_search"), lowEffort = true)
        val r = stream(svc, "read_url", body, SEARCH_TIMEOUT_MS, cancelled)
        stopText(r, SEARCH_TIMEOUT_MS)?.let { return it }
        r.error?.let { return "실패: 링크 읽기 오류 — $it" }
        val text = cleanText(r.finalText)
        if (text.isEmpty()) return "실패: 링크 내용을 받지 못함 — finish 로 알릴 것"
        // 출처 = 그 URL(계약 §1-1). 인용 목록에 같은 주소가 있으면 그 제목을 쓴다
        val title = r.links.entries.firstOrNull { sameUrl(it.key, url) }?.value.orEmpty()
        attach(link(url, title))
        return clip(text, TEXT_MAX) + "\n(출처 링크가 채팅에 붙음: ${hostOf(url)})"
    }

    private fun imageGen(svc: AgentService, args: JSONObject, cancelled: () -> Boolean, attach: (JSONObject) -> Unit): String {
        val prompt = args.optString("prompt").trim()
        if (prompt.isEmpty()) return "실패: prompt 가 비었음"
        val from = args.optString("from").trim().takeIf { it.isNotEmpty() && it != "null" }
        val content = JSONArray()
        if (from == null) {
            content.put(inputText("image_generation 도구로 다음 그림을 그려라. 글로 설명하지 말고 그림을 만든다.\n\n$prompt"))
        } else {
            val dataUrl = if (from.equals("screen", true)) {
                // image_gen 은 화면을 안 쓰는 도구라 앱이 뒤로 물러나지 않는다 — 채팅 앱 안에서 부르면 우리 채팅 화면을 찍어 고치게 된다
                if (svc.rootInActiveWindow?.packageName?.toString() == svc.packageName)
                    return "실패: 지금 앞 화면은 폴드 에이전트 채팅이라 고칠 화면이 아님 — 사용자에게 어떤 화면(앱)을 고칠지 묻거나, " +
                        "그 앱을 open_app 으로 연 뒤 다시 부르거나, from 없이 그릴 것"
                svc.screenshotJpegBase64()?.let { "data:image/jpeg;base64,$it" }
                    ?: return "실패: 지금 화면을 찍지 못함(보안 화면일 수 있음) — from 없이 그리거나 finish 로 알릴 것"
            } else {
                when (val src = ownImage(svc, from)) {
                    is Src.Found -> src.dataUrl
                    is Src.Missing -> return "실패: 고칠 사진 「$from」를 못 찾음 — 우리 앱 사진(최근): " +
                        src.recent.take(10).joinToString(" · ").ifEmpty { "없음" } + " (정확한 이름으로 다시)"
                }
            }
            if (cancelled()) return STOPPED
            content.put(inputText("image_generation 도구로 첨부한 사진을 다음 요청대로 고쳐 새 그림을 만들어라. 글로 설명하지 말고 그림을 만든다.\n\n$prompt"))
            content.put(JSONObject().put("type", "input_image").put("image_url", dataUrl))
        }
        val size = when (args.optString("size")) { "portrait" -> "1024x1536"; "landscape" -> "1536x1024"; else -> "1024x1024" }
        val body = request(svc, content, JSONObject().put("type", "image_generation").put("size", size), lowEffort = false)
        val r = stream(svc, "image_gen", body, IMAGE_TIMEOUT_MS, cancelled)
        // 시간초과여도 그림이 이미 왔으면 저장한다(끝 이벤트만 늦은 경우). 정지는 정지 — 저장하지 않는다
        if (r.stop == Stop.CANCEL) return STOPPED
        val b64 = r.images.lastOrNull()
        if (b64 == null) {
            stopText(r, IMAGE_TIMEOUT_MS)?.let { return it }
            r.error?.let { return "실패: 그림 생성 오류 — $it" }
            val said = cleanText(r.finalText)
            return "실패: 그림이 오지 않음" + if (said.isNotEmpty()) " — 모델 답: ${said.take(300)}" else ""
        }
        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
            ?: return "실패: 그림 데이터를 풀지 못함"
        val (ext, mime) = imageKind(bytes) ?: return "실패: 받은 그림 형식을 모름(${bytes.size}바이트)"
        val fileName = "FoldAgent_gen_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date()) + ".$ext"
        val uri = svc.saveImageBytes(bytes, fileName, mime) ?: return "실패: 갤러리에 저장하지 못함"
        attach(JSONObject().put("kind", "image").put("title", fileName).put("uri", uri.toString()).put("mime", mime).put("size", bytes.size.toLong()))
        val said = cleanText(r.finalText).take(200)
        return "그림 저장: $fileName (갤러리 Pictures/FoldAgent · 채팅에 붙음)" + (if (from != null) " — 「${from.take(30)}」를 고친 것" else "") +
            ". 보내 달라면 share_images 에 이 이름" + if (said.isNotEmpty()) "\n모델 설명: $said" else ""
    }

    // ---- /v1/responses SSE

    private enum class Stop { NONE, CANCEL, TIMEOUT }

    /**
     * 스트림 하나의 결과. images = image_generation_call.result(base64).
     * 출처: cited = 답이 인용한 것(url_citation 주석 + 답 속 마크다운 링크) · sources = 검색이 본 페이지(web_search_call.action.sources).
     * 2026-09-27 실측(:8531 프록시·gpt-5.5): url_citation 주석은 오지 않고 인용은 답 속 `[이름](url)` 로만 왔다. sources 는 include 를 줘야 온다.
     */
    private class Out {
        val text = StringBuilder()
        var doneText: String? = null          // 메시지 항목 완료 시 전문(델타를 못 받은 경우 대비)
        val cited = LinkedHashMap<String, String>()
        val sources = LinkedHashMap<String, String>()
        val images = ArrayList<String>()
        var error: String? = null
        @Volatile var stop = Stop.NONE
        val finalText get() = text.toString().ifBlank { doneText.orEmpty() }
        /** 첨부할 출처(url → 제목): 인용한 것이 있으면 그것만, 없으면 검색이 본 페이지 */
        val links: Map<String, String> get() {
            if (cited.isEmpty()) return sources
            val m = LinkedHashMap(cited)
            for ((u, t) in m) if (t.isEmpty()) sources[u]?.takeIf { it.isNotEmpty() }?.let { m[u] = it }
            return m
        }
    }

    private fun inputText(t: String) = JSONObject().put("type", "input_text").put("text", t)

    /** tool_choice 는 보내지 않는다(계약 §1-1 — 프록시가 받지 않는다). reasoning.effort 는 검색만 low */
    private fun request(svc: AgentService, content: JSONArray, tool: JSONObject, lowEffort: Boolean): JSONObject {
        val body = JSONObject().put("model", Prefs.oaiToolModel(svc)).put("stream", true)
            .put("input", JSONArray().put(JSONObject().put("type", "message").put("role", "user").put("content", content)))
            .put("tools", JSONArray().put(tool))
        if (lowEffort) body.put("reasoning", JSONObject().put("effort", "low"))
        // 검색이 본 페이지 목록 — 답이 인용을 안 달았을 때 출처로 쓴다(실측: 이걸 줘야 action.sources 에 url 이 온다)
        if (tool.optString("type") == "web_search") body.put("include", JSONArray().put("web_search_call.action.sources"))
        return body
    }

    /**
     * POST `<Prefs.base>/responses`(stream) 를 끝까지 읽는다. 정지·전체 시간초과는 감시 스레드가 200ms 마다 보고 연결을 끊는다
     * (막힌 읽기는 cancelled 를 못 보므로). SSE 한 줄이 1MB 를 넘는다(image_generation_call.result) —
     * BufferedReader.readLine 은 줄 길이 제한이 없다(내부 버퍼를 키워 이어 붙인다).
     * 요청·응답 본문·키는 로그에 남기지 않는다(도구·HTTP 상태·ms·바이트만).
     */
    private fun stream(svc: AgentService, tag: String, body: JSONObject, timeoutMs: Long, cancelled: () -> Boolean): Out {
        val out = Out()
        val t0 = System.currentTimeMillis()
        val deadline = t0 + timeoutMs
        val conn = try {
            URL(Prefs.base(svc).trim().trimEnd('/') + "/responses").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            out.error = "주소가 잘못됨(${e.javaClass.simpleName}) — 설정의 서버 주소 확인"; return out
        }
        val done = AtomicBoolean(false)
        val watcher = Thread {
            while (!done.get()) {
                val why = when {
                    runCatching { cancelled() }.getOrDefault(false) -> Stop.CANCEL
                    System.currentTimeMillis() > deadline -> Stop.TIMEOUT
                    else -> Stop.NONE
                }
                if (why != Stop.NONE) { out.stop = why; runCatching { conn.disconnect() }; break }
                try { Thread.sleep(WATCH_MS) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "oai-watch" }
        watcher.start()
        var bytes = 0L
        var http = 0
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = timeoutMs.toInt()
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.setRequestProperty("Authorization", "Bearer ${Prefs.apiKey(svc).ifEmpty { "dummy" }}")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            http = conn.responseCode
            if (http !in 200..299) {
                val t = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                out.error = "HTTP $http" + (errMessage(t)?.let { " — ${it.take(200)}" } ?: "")
                return out
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8), 64 * 1024)
            reader.use { rd ->
                val data = StringBuilder()
                while (true) {
                    val line = rd.readLine()
                    if (line == null || line.isEmpty()) {
                        // 빈 줄 = 이벤트 끝(여러 data: 줄은 줄바꿈으로 잇는다 — SSE 규칙)
                        if (data.isNotEmpty()) { onEvent(out, data.toString()); data.setLength(0) }
                        if (line == null) break
                        continue
                    }
                    bytes += line.length
                    if (line.startsWith("data:")) {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line, if (line.startsWith("data: ")) 6 else 5, line.length)
                    }
                    // event:·id:·주석(:) 줄은 type 이 data 안에 있으니 버린다
                }
            }
        } catch (e: IOException) {
            if (out.stop == Stop.NONE) out.error = when (e) {
                is SocketTimeoutException -> "서버 응답 없음(시간 초과)"
                is UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
                    "서버에 연결 못 함(${e.javaClass.simpleName}) — tailscale 연결 확인"
                else -> "연결 오류(${e.javaClass.simpleName})"
            }
        } catch (e: Exception) {
            if (out.stop == Stop.NONE) out.error = "요청 실패(${e.javaClass.simpleName})"
        } finally {
            done.set(true)
            watcher.interrupt()
            runCatching { conn.disconnect() }
            if (out.stop == Stop.NONE) collectTextLinks(out)
            Log.i(TAG, "oai $tag http=$http stop=${out.stop} ${System.currentTimeMillis() - t0}ms ${bytes}자 링크${out.links.size} 그림${out.images.size}")
        }
        return out
    }

    /** SSE 이벤트 하나(data 의 JSON) — 계약 §1-1 · probe 실측 이벤트 이름 */
    private fun onEvent(out: Out, data: String) {
        if (data == "[DONE]") return
        val e = runCatching { JSONObject(data) }.getOrNull() ?: return
        when (e.optString("type")) {
            "response.output_text.delta" -> out.text.append(e.optString("delta"))
            "response.output_text.annotation.added" -> e.optJSONObject("annotation")?.let { addCitation(out, it) }
            "response.output_item.done" -> {
                val item = e.optJSONObject("item") ?: return
                when (item.optString("type")) {
                    "image_generation_call" -> item.optString("result").takeIf { r -> r.isNotEmpty() && r != "null" }?.let { r -> out.images += r }
                    "web_search_call" -> item.optJSONObject("action")?.let { a ->
                        a.optString("url").takeIf { u -> u.isNotEmpty() }?.let { u -> addLink(out.sources, u, "") }   // open_page
                        // sources 에는 {type:"api", name:"oai-weather"} 같은 url 없는 항목도 온다 — addLink 가 버린다
                        a.optJSONArray("sources")?.let { s -> for (i in 0 until s.length()) s.optJSONObject(i)?.let { o -> addLink(out.sources, o.optString("url"), o.optString("title")) } }
                    }
                    "message" -> item.optJSONArray("content")?.let { c ->
                        val sb = StringBuilder()
                        for (i in 0 until c.length()) {
                            val part = c.optJSONObject(i) ?: continue
                            if (part.optString("type") == "output_text") sb.append(part.optString("text"))
                            part.optJSONArray("annotations")?.let { an -> for (j in 0 until an.length()) an.optJSONObject(j)?.let { a -> addCitation(out, a) } }
                        }
                        if (sb.isNotBlank()) out.doneText = (out.doneText.orEmpty() + sb).trim()
                    }
                }
            }
            "response.failed", "response.incomplete" -> {
                val resp = e.optJSONObject("response")
                out.error = resp?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: resp?.optJSONObject("incomplete_details")?.optString("reason")?.takeIf { it.isNotBlank() }?.let { "중간에 끊김($it)" }
                    ?: e.optString("type")
            }
            "error" -> out.error = e.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: e.optString("message").takeIf { it.isNotBlank() } ?: "오류 이벤트"
        }
    }

    private fun addCitation(out: Out, a: JSONObject) {
        if (a.optString("type") == "url_citation") addLink(out.cited, a.optString("url"), a.optString("title"))
    }

    /** 답 속 마크다운 링크 `[이름](url)` 을 인용으로 모은다 — 스트림을 다 읽은 뒤 한 번 */
    private fun collectTextLinks(out: Out) {
        MD_LINK.findAll(out.finalText).forEach { m -> addLink(out.cited, m.groupValues[2], m.groupValues[1]) }
    }

    private val MD_LINK = Regex("\\[([^\\]\\n]{0,200})]\\((https?://[^)\\s]+)\\)")

    /** 인용 url 을 모은다 — http·https 만, OpenAI 가 붙이는 utm_source=openai 는 뗀다. 먼저 온 제목이 비었으면 뒤의 제목으로 채운다 */
    private fun addLink(into: LinkedHashMap<String, String>, raw: String, title: String) {
        val url = stripUtm(raw.trim())
        if (!(url.startsWith("https://", true) || url.startsWith("http://", true)) || hostOf(url).isEmpty()) return
        val t = title.trim().takeIf { it != "null" }.orEmpty()
        val had = into[url]
        if (had == null) { if (into.size < 40) into[url] = t } else if (had.isEmpty() && t.isNotEmpty()) into[url] = t
    }

    /** 정지·시간초과면 모델용 결과 글, 아니면 null */
    private fun stopText(r: Out, timeoutMs: Long): String? = when (r.stop) {
        Stop.CANCEL -> STOPPED
        Stop.TIMEOUT -> "실패: 시간 초과(${timeoutMs / 1000}초) — 같은 요청을 곧바로 반복하지 말고 finish 로 알릴 것"
        Stop.NONE -> null
    }

    private fun errMessage(t: String): String? = runCatching {
        val j = JSONObject(t.trim())
        j.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: j.optString("message").takeIf { it.isNotBlank() }
    }.getOrNull() ?: t.trim().take(200).takeIf { it.isNotEmpty() && !it.startsWith("<") }

    // ---- 편집할 사진(우리 앱 파일만)

    private sealed class Src {
        class Found(val dataUrl: String) : Src()
        class Missing(val recent: List<String>) : Src()
    }

    /**
     * 우리 앱이 만든 FoldAgent_… 사진을 이름(또는 그 일부)으로 찾아 data URL 로 — share_images 와 같은 찾기(권한 없이 읽히는 건 우리 소유 파일뿐).
     * 큰 사진(원본 캡처)은 긴 변 2048px JPEG 로 줄여 보낸다(프록시 본문·지연↓).
     */
    private fun ownImage(svc: AgentService, key: String): Src {
        val all = ArrayList<Triple<String, android.net.Uri, String>>()   // (이름, uri, mime) 최신순
        runCatching {
            svc.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE),
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE 'FoldAgent_%'", null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext() && all.size < 500) all += Triple(c.getString(1),
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), c.getString(2) ?: "image/jpeg")
            }
        }.onFailure { Log.w(TAG, "ownImage query: $it") }
        fun n(x: String) = x.lowercase().replace(Regex("[\\s_]+"), "").substringBeforeLast('.')
        val k = n(key.substringAfterLast('/'))
        val hit = all.firstOrNull { n(it.first) == k } ?: all.firstOrNull { k.isNotEmpty() && n(it.first).contains(k) }
            ?: return Src.Missing(all.map { it.first })
        val bytes = runCatching { svc.contentResolver.openInputStream(hit.second)?.use { it.readBytes() } }.getOrNull()
            ?: return Src.Missing(all.map { it.first })
        if (bytes.size <= EDIT_RAW_MAX) return Src.Found("data:${hit.third};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 2048) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return Src.Missing(all.map { it.first })
        val scale = 2048f / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        return Src.Found("data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
    }

    /** 바이트 머리로 형식을 본다 — 계약상 PNG 가 온다. 모르는 형식이면 null */
    private fun imageKind(b: ByteArray): Pair<String, String>? = when {
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "png" to "image/png"
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg" to "image/jpeg"
        b.size > 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp" to "image/webp"
        else -> null
    }

    // ---- 글 다듬기

    private fun link(url: String, title: String) = JSONObject().put("kind", "link").put("url", url).put("title", title.ifBlank { hostOf(url) })

    /** 답 속 마크다운 인용 링크 `([제목](url))` 은 뺀다 — 출처는 첨부로 따로 붙고, 모델 맥락만 길어진다 */
    private fun cleanText(t: String): String = t
        .replace(Regex("\\s*\\(\\[[^\\]]*]\\(https?://[^)\\s]*\\)\\)"), "")
        .replace(Regex("\\[([^\\]]*)]\\(https?://[^)\\s]*\\)"), "$1")
        .trim()

    private fun clip(t: String, max: Int) = if (t.length <= max) t else t.take(max) + "…(이하 생략)"

    private fun stripUtm(u: String): String {
        val q = u.indexOf('?'); if (q < 0) return u
        val hashAt = u.indexOf('#', q)
        val query = if (hashAt < 0) u.substring(q + 1) else u.substring(q + 1, hashAt)
        val kept = query.split('&').filter { it.isNotEmpty() && !it.equals("utm_source=openai", true) }
        return u.substring(0, q) + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + (if (hashAt < 0) "" else u.substring(hashAt))
    }

    private fun hostOf(u: String): String = runCatching { java.net.URI(u).host?.removePrefix("www.") }.getOrNull().orEmpty()

    private fun sameUrl(a: String, b: String) = stripUtm(a).trimEnd('/').equals(stripUtm(b).trimEnd('/'), true)

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd(E)", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date())

    private const val TAG = "FoldAgent"
    private const val STOPPED = "사용자가 멈춤"
    private const val SEARCH_TIMEOUT_MS = 60_000L      // web_search·read_url — 계약 §1-1
    private const val IMAGE_TIMEOUT_MS = 150_000L      // image_gen — 계약 §1-1
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val WATCH_MS = 200L
    private const val TEXT_MAX = 4_000
    private const val MAX_LINKS = 6
    private const val EDIT_RAW_MAX = 4 * 1024 * 1024

    /** 레시피·기록용 한 줄 */
    fun summarize(name: String, args: JSONObject, outcome: String): String = when (name) {
        "web_search" -> "「${args.optString("query").take(40)}」"
        "read_url" -> args.optString("url").take(60)
        "image_gen" -> (if (args.optString("from").isNotBlank()) "고침(${args.optString("from").take(30)}) " else "") + "「${args.optString("prompt").take(30)}」"
        else -> args.toString().take(40)
    } + if (outcome.startsWith("실패")) " ✗" else ""

    /** 실행 중 상태 문구 */
    fun status(name: String): String = when (name) {
        "web_search" -> "🔎 웹 검색 중…"
        "read_url" -> "🔗 링크 읽는 중…"
        "image_gen" -> "🎨 그림 그리는 중…(1~2분)"
        else -> "⏳ $name…"
    }
}
