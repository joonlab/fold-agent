package kr.joonlab.foldagent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kr.joonlab.foldagent.records.Attachments
import org.json.JSONArray
import org.json.JSONObject

/**
 * 폰 파일 → 홈맥 작업(계약 docs/server-contract/CONTRACT.md §1).
 * 공유 시트로 받은 uri 를 빠른 입력 카드에 칩으로 두고, 보내기 때 올린 뒤(AssistantClient.upload) 실행 meta `uploads` 로 넘긴다.
 * Agent 는 [block] 을 첫 user 메시지에 붙여 모델이 파일 id 만 쓰게 한다(폰 경로를 지어내지 않게).
 *
 * 받은 uri 읽기 권한은 받은 액티비티가 살아 있는 동안만이라 uri 는 저장하지 않는다 — 턴 기록엔 이름·mime·크기·fileId 만([turnRecord]).
 */
object Uploads {
    const val MAX_FILES = 5

    /** 카드 칩 하나. state = ready · too_big · uploading · done · error */
    class Pending(val uri: Uri, val name: String, val mime: String, val size: Long?) {
        var state = if (size != null && size > AssistantClient.MAX_UPLOAD_BYTES) "too_big" else "ready"
        var fileId: String? = null
        var error: String? = null
        var sent = 0L
    }

    /** 공유 인텐트(ACTION_SEND·ACTION_SEND_MULTIPLE)의 EXTRA_STREAM uri 들. 공유가 아니면 빈 목록 */
    fun streamsOf(i: Intent?): List<Uri> {
        if (i == null) return emptyList()
        return when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty().filterNotNull()
            else -> emptyList()
        }.distinct()
    }

    fun isShare(i: Intent?) = i?.action == Intent.ACTION_SEND || i?.action == Intent.ACTION_SEND_MULTIPLE

    /** uri → 칩(이름·크기·mime). contentResolver 조회라 **메인 스레드에서 부르지 말 것**. 권한이 없으면 오류 칩 */
    fun resolve(ctx: Context, uri: Uri, typeHint: String?): Pending {
        var name: String? = null
        var size: Long? = null
        val err = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it).takeIf { s -> s >= 0 } }
                }
            }
        }.exceptionOrNull()
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "파일"
        val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
            ?: typeHint?.takeIf { it.isNotBlank() && '*' !in it }
            ?: Attachments.mimeOfName(n) ?: "application/octet-stream"
        return Pending(uri, n, mime, size).also { if (err is SecurityException) { it.state = "error"; it.error = "읽기 권한 없음" } }
    }

    fun sizeLabel(b: Long): String = when {
        b >= 1_048_576 -> String.format(java.util.Locale.US, "%.1fMB", b / 1_048_576.0)
        b >= 1024 -> "${b / 1024}KB"
        else -> "${b}B"
    }

    // ---- 칩·올리기 — 빠른 입력 카드(View)와 앱 입력줄(Compose)이 같이 쓴다

    fun isImage(p: Pending) = p.mime.startsWith("image/")
    fun isBad(p: Pending) = p.state == "too_big" || p.state == "error"

    /** 칩 글자 — 「📄 보고서.pdf · 1.2MB · 42%」. 상태 꼬리는 너무 큼 · 진행 % · ✓ · 오류 */
    fun chipLabel(p: Pending): String {
        val size = (p.size ?: p.sent.takeIf { it > 0 })?.let { " · ${sizeLabel(it)}" } ?: ""
        return "${if (isImage(p)) "🖼" else "📄"} ${p.name.take(28)}$size${chipTail(p)}"
    }

    fun chipTail(p: Pending): String = when (p.state) {
        "too_big" -> " · 너무 큼"
        "uploading" -> p.size?.takeIf { it > 0 }?.let { " · ${(p.sent * 100 / it).coerceIn(0, 99)}%" } ?: " · 올리는 중"
        "done" -> " · ✓"
        "error" -> " · 오류"
        else -> ""
    }

    const val HINT_TOO_BIG = "25MB 넘는 파일은 보낼 수 없어요 — 칩을 눌러 빼 주세요"
    const val HINT_NO_TEXT = "이 파일로 무엇을 할지 적거나 말해 주세요"
    fun hintTooMany(dropped: Int) = "파일은 ${MAX_FILES}개까지예요 — ${dropped}개는 뺐어요"

    /**
     * todo 를 차례로 올린다 — **백그라운드 스레드에서**. 칩의 state·sent·fileId·error 를 채우고,
     * 진행(250ms 간격)·칩 하나 끝날 때마다 changed() 를 부른다(부르는 스레드 그대로 — UI 반영은 부르는 쪽이 post).
     * afterDone = 칩 하나가 올라간 직후(썸네일 만들기 등, 같은 스레드).
     */
    fun uploadAll(ctx: Context, base: String, token: String, todo: List<Pending>, cancelled: () -> Boolean,
                  changed: () -> Unit, afterDone: (Pending) -> Unit = {}) {
        for (f in todo) {
            if (cancelled()) break
            var lastPost = 0L
            val r = AssistantClient(base, token).upload(ctx, f.uri, f.name, f.mime, f.size, progress = { n ->
                f.sent = n
                val now = System.currentTimeMillis()
                if (now - lastPost > 250) { lastPost = now; changed() }
            }) { cancelled() }
            val id = r.data?.optString("fileId")?.trim()?.takeIf { r.ok && it.isNotEmpty() && it != "null" }
            if (id != null) {
                f.fileId = id; f.state = "done"
                if (isImage(f)) runCatching { keepLocalImage(ctx, f, id) }.onFailure { android.util.Log.w("FoldAgent", "받은 이미지 사본 실패 $id: $it") }
                runCatching { afterDone(f) }
            }
            else { f.state = if (r.code == "too_large") "too_big" else "error"; f.error = r.message ?: r.code }
            changed()
        }
    }

    // ── 받은 이미지 폰 사본 — look(files=…) 가 홈맥을 거치지 않고 바로 본다(사용자 2026-09-28: 「이미지 분석은 폰 도구로 충분한데 왜 위임하나」).
    //    uri 읽기 권한은 곧 사라지므로 올린 직후 긴 변 1600px JPEG 로 앱 전용 폴더에 둔다. 7일 지난 것·40개 넘는 것은 지운다(우리 앱이 만든 사본만).
    private const val LOCAL_MAX_PX = 1600
    private fun inbox(ctx: Context) = java.io.File(ctx.filesDir, "inbox").apply { mkdirs() }
    fun localImage(ctx: Context, fileId: String): java.io.File? =
        java.io.File(inbox(ctx), "${fileId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }}.jpg").takeIf { it.isFile && it.length() > 0 }

    private fun keepLocalImage(ctx: Context, p: Pending, fileId: String) {
        val cr = ctx.contentResolver
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(p.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }   // 경계만 — 반환값은 늘 null
        if (o.outWidth <= 0 || o.outHeight <= 0) return
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= LOCAL_MAX_PX) sample *= 2
        val bm = cr.openInputStream(p.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return
        val scale = LOCAL_MAX_PX.toFloat() / maxOf(bm.width, bm.height)
        val out = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bm, (bm.width * scale).toInt().coerceAtLeast(1), (bm.height * scale).toInt().coerceAtLeast(1), true) else bm
        val dir = inbox(ctx)
        java.io.File(dir, "$fileId.jpg").outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
        val cut = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEachIndexed { i, f -> if (i >= 40 || f.lastModified() < cut) f.delete() }
    }

    /** 이미지·영상 칩의 작은 썸네일(긴 변 ≈ px). 못 만들면 null. **메인 스레드에서 부르지 말 것** */
    fun thumbnail(ctx: Context, p: Pending, px: Int = 320): android.graphics.Bitmap? {
        if (!isImage(p) && !p.mime.startsWith("video/")) return null
        return runCatching { ctx.contentResolver.loadThumbnail(p.uri, android.util.Size(px, px), null) }.getOrNull()
    }

    /** 올린 칩들 → 실행 meta 의 `uploads: [{fileId, name, mime, size}]` */
    fun metaOf(done: List<Pending>): JSONArray = JSONArray(done.mapNotNull { p ->
        val id = p.fileId ?: return@mapNotNull null
        JSONObject().put("fileId", id).put("name", p.name).put("mime", p.mime).put("size", p.size ?: p.sent)
    })

    /** meta.uploads 를 알려진 키만 남겨 읽는다(최대 5) */
    fun parse(meta: JSONObject): List<JSONObject> {
        val a = meta.optJSONArray("uploads") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .filter { it.optString("fileId").isNotBlank() }
            .take(MAX_FILES)
    }

    /**
     * 첫 user 메시지에 붙일 블록(계약 §1). 비면 "".
     * `[받은 파일] 1) 보고서.pdf (application/pdf · 1.2MB) — 파일 id up_xxx` + 규칙 한 줄.
     */
    fun block(list: List<JSONObject>): String {
        if (list.isEmpty()) return ""
        val lines = list.mapIndexed { k, u ->
            val size = u.optLong("size", -1).takeIf { it >= 0 }?.let { " · ${sizeLabel(it)}" } ?: ""
            val img = if (u.optString("mime").startsWith("image/")) " · 폰에서 바로 보기 가능" else ""
            "${k + 1}) ${u.optString("name").take(120)} (${u.optString("mime").ifBlank { "?" }}$size) — 파일 id ${u.optString("fileId")}$img"
        }
        return "[받은 파일] " + lines.joinToString("\n") +
            "\n이미지의 내용 파악(무엇이 있나·글자 읽기·비교·설명)은 look 의 files 에 id 를 넣어 폰에서 바로 본다 — 홈맥에 맡기지 않는다. 자르기·변환·편집 같은 가공만 아래처럼." +
            "\n이 파일은 code_run·claude_task·run 의 inputs 로 id 를 넘겨 쓴다. 폰 경로를 지어내지 말 것." +
            // 모델이 from_job 의 /in 과 헷갈려 경로를 지어냈다(2026-09-28 실기) — 어디에 놓이는지 그대로 적는다
            "\n놓이는 곳: 작업 폴더의 in/<이름>(code_run 에선 in/… 또는 /in/…, run 은 argv 에 in/…). 이름이 바뀔 수 있으니 확실치 않으면 os.listdir(\"in\") 로 찾는다."
    }

    /** 사용자 턴 기록용(계약 §1) — `{kind:"file", title, mime, size, fileId}`. content uri 는 남기지 않는다(권한이 사라진다) */
    fun turnRecord(list: List<JSONObject>): JSONArray = JSONArray(list.map { u ->
        JSONObject().put("kind", "file").put("title", u.optString("name").take(200)).put("mime", u.optString("mime"))
            .put("fileId", u.optString("fileId")).apply { u.optLong("size", -1).takeIf { it >= 0 }?.let { put("size", it) } }
    })
}
