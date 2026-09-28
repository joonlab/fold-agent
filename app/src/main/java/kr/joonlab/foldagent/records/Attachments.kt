package kr.joonlab.foldagent.records

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 턴 첨부(계약 cc-capabilities §1-3) — 저장 모양 `{kind:"image"|"file"|"link", title, uri?, url?, mime?, size?}`.
 * 모양 검사는 Agent.addAttachment 가 이미 했다. 여기서는 읽을 때 한 번 더 걸러(옛 파일·손으로 고친 파일) 화면·맥락에 넘긴다.
 *
 *   uri = 폰 content uri(갤러리 FoldAgent_… 이미지) 또는 앱 전용 폴더 files/attach/ 안 파일 이름
 *   url = 링크(http·https)
 */
data class Attachment(val kind: String, val title: String, val uri: String?, val url: String?, val mime: String?, val size: Long?) {
    val isImage get() = kind == "image"
    val isLink get() = kind == "link"
}

object Attachments {
    const val MAX = 30
    /** 앱 전용 첨부 폴더 이름 — getExternalFilesDir("attach") = /sdcard/Android/data/<pkg>/files/attach */
    const val DIR = "attach"
    const val AUTHORITY = "kr.joonlab.foldagent.attach"

    fun dir(ctx: Context): File = ctx.getExternalFilesDir(DIR) ?: File(ctx.filesDir, DIR).also { it.mkdirs() }

    /** 저장된 JSONArray → 목록. 모양이 틀린 것은 버린다(최대 [MAX]개) */
    fun parse(a: JSONArray?): List<Attachment> {
        if (a == null) return emptyList()
        val out = ArrayList<Attachment>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val kind = o.optString("kind")
            fun s(k: String) = o.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }
            val uri = s("uri"); val url = s("url")
            val ok = when (kind) {
                "link" -> url != null && (url.startsWith("https://") || url.startsWith("http://"))
                "image", "file" -> uri != null
                else -> false
            }
            if (!ok) continue
            out += Attachment(kind, s("title") ?: (url ?: uri!!).trimEnd('/').substringAfterLast('/'), uri, url, s("mime"),
                if (o.has("size")) o.optLong("size", -1).takeIf { it >= 0 } else null)
            if (out.size >= MAX) break
        }
        return out
    }

    /** 턴 저장용 — 알려진 키만 남긴 사본(Session.addTurn) */
    fun sanitize(a: JSONArray): JSONArray = JSONArray(parse(a).map { toJson(it) })

    fun toJson(a: Attachment): JSONObject = JSONObject().put("kind", a.kind).put("title", a.title).apply {
        a.uri?.let { put("uri", it) }; a.url?.let { put("url", it) }; a.mime?.let { put("mime", it) }; a.size?.let { put("size", it) }
    }

    /** 다음 턴 맥락용 요약 한 줄 — 「이미지 1장: 이름 · 링크: 제목」(계약 §1-3: 요약만, uri·url 전문은 싣지 않는다) */
    fun summary(list: List<Attachment>): String {
        if (list.isEmpty()) return ""
        val imgs = list.filter { it.isImage }; val files = list.filter { it.kind == "file" }; val links = list.filter { it.isLink }
        val parts = ArrayList<String>()
        if (imgs.isNotEmpty()) parts += "이미지 ${imgs.size}장: " + names(imgs)
        if (files.isNotEmpty()) parts += "파일 ${files.size}개: " + names(files)
        if (links.isNotEmpty()) parts += "링크: " + names(links)
        return parts.joinToString(" · ")
    }

    private fun names(l: List<Attachment>) = l.take(3).joinToString(", ") { it.title.take(40) } + if (l.size > 3) " 외 ${l.size - 3}" else ""

    // ---------------------------------------------------------------- 열기·공유

    /** 파일 이름(files/attach/ 안)이 안전한가 — 경로 구분자·숨김 이름·빈 이름 거부 */
    fun safeName(n: String): Boolean = n.isNotBlank() && !n.contains('/') && !n.contains('\\') && !n.startsWith(".") && n.length <= 200

    /** 첨부 → 다른 앱에 넘길 content uri. content:// 는 그대로, 파일 이름은 우리 제공자(AttachProvider) 주소로 */
    fun contentUri(ctx: Context, a: Attachment): Uri? {
        val u = a.uri ?: return null
        if (u.startsWith("content://")) return Uri.parse(u)
        if (!safeName(u) || !File(dir(ctx), u).exists()) return null
        return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(u).build()
    }

    fun mimeOf(a: Attachment): String = a.mime ?: mimeOfName(a.uri ?: a.title) ?: if (a.isImage) "image/*" else "*/*"

    fun mimeOfName(n: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substringAfterLast('.', "").lowercase().ifEmpty { return null })

    /** 보기 인텐트(이미지 = 갤러리, 파일 = 알맞은 앱, 링크 = 브라우저). 없으면 null */
    fun viewIntent(ctx: Context, a: Attachment): Intent? {
        if (a.isLink) return Intent(Intent.ACTION_VIEW, Uri.parse(a.url ?: return null)).addCategory(Intent.CATEGORY_BROWSABLE)
        val u = contentUri(ctx, a) ?: return null
        return Intent(Intent.ACTION_VIEW).setDataAndType(u, mimeOf(a)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** 공유 인텐트(고르기 창). 링크는 글로 */
    fun shareIntent(ctx: Context, a: Attachment): Intent? {
        val send = if (a.isLink) Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.url)
        else {
            val u = contentUri(ctx, a) ?: return null
            Intent(Intent.ACTION_SEND).setType(mimeOf(a)).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, a.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * 이미지 줄여 읽기 — 긴 변이 maxPx 안팎이 되게 inSampleSize(2의 거듭제곱)로. **메인 스레드에서 부르지 말 것**(디스크·디코딩).
     * 화소 수도 2·maxPx² 이하로 묶는다 — 긴 변만 보면 4000×3000 을 maxPx 2048 에 표본 1(약 48MB)로 읽는다(리뷰 지적 · OOM).
     * 못 읽으면(지워짐·재설치로 소유권 잃음) null.
     */
    fun decode(ctx: Context, a: Attachment, maxPx: Int): Bitmap? = runCatching {
        val u = a.uri ?: return null
        val open = {
            if (u.startsWith("content://")) ctx.contentResolver.openInputStream(Uri.parse(u))
            else if (safeName(u)) File(dir(ctx), u).takeIf { it.exists() }?.inputStream() else null
        }
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // inJustDecodeBounds 는 decodeStream 이 늘 null 을 돌려준다 — 그 null 로 「못 읽음」 판정하면 모든 썸네일이 자리표시로 남았다(2026-09-27 실기)
        (open() ?: return null).use { BitmapFactory.decodeStream(it, null, b) }
        if (b.outWidth <= 0 || b.outHeight <= 0) return null
        var s = 1
        val maxPixels = 2L * maxPx * maxPx
        while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= maxPx || (b.outWidth.toLong() / s) * (b.outHeight.toLong() / s) > maxPixels) s *= 2
        open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
    }.getOrNull()
}

/**
 * files/attach/ 안 파일을 다른 앱(열기·공유)에 넘기는 읽기 전용 제공자. FileProvider 는 res/xml 경로 파일이 필요해
 * 파일 하나로 끝나는 최소 제공자를 둔다. exported=false + grantUriPermissions — 우리가 인텐트로 권한을 준 앱만 읽는다.
 * 주소 = content://kr.joonlab.foldagent.attach/<파일 이름>(하위 경로 없음).
 */
class AttachProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileOf(uri: Uri): File? {
        val segs = uri.pathSegments
        if (segs.size != 1) return null
        val name = segs[0]
        if (!Attachments.safeName(name)) return null
        val d = Attachments.dir(context ?: return null)
        val f = File(d, name)
        // 이름 검사로 충분하지만 한 번 더 — 정규 경로가 첨부 폴더 바로 아래여야
        if (f.canonicalFile.parentFile != d.canonicalFile || !f.isFile) return null
        return f
    }

    override fun getType(uri: Uri): String? = fileOf(uri)?.let { Attachments.mimeOfName(it.name) } ?: "application/octet-stream"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("읽기 전용")
        val f = fileOf(uri) ?: throw java.io.FileNotFoundException(uri.lastPathSegment)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = fileOf(uri) ?: return null
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map<String, Any> { if (it == OpenableColumns.DISPLAY_NAME) displayName(f.name) else f.length() }.toTypedArray())
        }
    }

    /** 저장 이름은 「<job>_<원래 이름>」 — 받는 앱엔 원래 이름으로 */
    private fun displayName(n: String) = n.substringAfter("__", n)

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
