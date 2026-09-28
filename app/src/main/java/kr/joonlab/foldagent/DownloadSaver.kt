package kr.joonlab.foldagent

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kr.joonlab.foldagent.records.Attachment
import kr.joonlab.foldagent.records.Attachments
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 받은 첨부를 공용 「다운로드」 폴더에 둔다(`save_to_downloads` 도구) — MediaStore.Downloads 라 권한이 필요 없다.
 * 모델이 경로를 지어내지 못하게 이번 대화의 첨부 목록(Agent 가 넘긴다)에서만 고른다.
 *
 * MIME 은 확장자로 정한다: MediaStore 는 이름의 확장자와 MIME 이 어긋나면 맞는 확장자를 덧붙인다(docx 를 zip 으로 주면 「…docx.zip」).
 * MimeTypeMap 이 모르는 한글 문서 등은 직접 매핑, 그래도 모르면 octet-stream(이 경우 이름을 그대로 둔다).
 * (PKM android_mac-to-phone-push-traps_260921 함정 1)
 */
object DownloadSaver {

    private val EXTRA_MIME = mapOf(
        "hwp" to "application/x-hwp",
        "hwpx" to "application/haansofthwpx",
        "md" to "text/markdown",
        "heic" to "image/heic",
        "m4a" to "audio/mp4",
        "apk" to "application/vnd.android.package-archive",
    )

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return EXTRA_MIME[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /**
     * @param known 이번 대화의 첨부(새것부터) · file 모델이 준 이름(첨부 제목·파일 이름, 일부 가능) · name 저장할 이름(선택)
     */
    fun save(ctx: Context, known: List<Attachment>, file: String, name: String): String {
        val cands = known.filter { !it.isLink && it.uri != null }.distinctBy { it.uri }
        if (cands.isEmpty()) return "실패: 이 대화에 받은 첨부 파일이 없다 — 저장할 수 있는 건 이 대화에서 받은(채팅에 붙은) 파일뿐이다"
        val key = file.trim()
        if (key.isEmpty()) return "실패: file 에 첨부 이름을 줄 것. 받은 첨부: " + names(cands)
        // files/attach 파일 이름은 「<작업·시각>__<원래 이름>」 — 뒤쪽만 이름으로 본다. 갤러리 content uri 는 번호뿐이라 제목으로
        fun base(a: Attachment) = if (a.uri!!.startsWith("content://")) a.title else a.uri.substringAfter("__")
        val exact = cands.filter { it.title.equals(key, true) || it.uri.equals(key, true) || base(it).equals(key, true) }
        val hits = exact.ifEmpty { cands.filter { it.title.contains(key, true) || base(it).contains(key, true) } }
        val src = when {
            hits.isEmpty() -> return "실패: 「$key」 에 맞는 첨부가 없다(이름을 지어내지 말 것). 받은 첨부: " + names(cands)
            hits.map { it.title }.distinct().size > 1 -> return "실패: 「$key」 에 맞는 첨부가 여럿이다 — 정확한 이름으로 다시: " + names(hits)
            else -> hits.first()   // 같은 이름이면 가장 최근 것
        }
        // 저장 이름: 준 이름(확장자가 없으면 원래 확장자를 붙인다) → 첨부 제목. 경로 문자는 걷는다
        val srcExt = src.title.substringAfterLast('.', "").ifEmpty { base(src).substringAfterLast('.', "") }
        var want = name.trim().ifEmpty { src.title }.replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1f]"), "_").trimStart('.').take(120).ifBlank { "file" }
        if (srcExt.isNotEmpty() && !want.endsWith(".$srcExt", true)) want += ".$srcExt"
        val mime = mimeOf(want)

        val resolver = ctx.contentResolver
        val coll = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val display = uniqueName(ctx, coll, want)
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, display)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(coll, cv) ?: return "실패: 다운로드 폴더에 항목을 만들지 못했다"
        val size = try {
            val n = open(ctx, src).use { inp -> (resolver.openOutputStream(uri) ?: throw IOException("쓰기 열기 실패")).use { out -> inp.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            n
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            return "실패: 「${src.title}」 복사 중 오류 — ${e.message?.take(80)}"
        }
        // 실제 이름은 MediaStore 가 정한다(남의 앱이 만든 같은 이름이 있으면 「(1)」 등을 붙인다) — 읽어서 알린다
        val actual = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: display
        return "다운로드/$actual 에 저장(${kb(size)} · $mime) — 내 파일 앱의 다운로드 폴더에서 보인다" +
            if (actual != want) " · 같은 이름이 있어 「$actual」로 저장" else ""
    }

    private fun open(ctx: Context, a: Attachment): InputStream {
        val u = a.uri!!
        if (u.startsWith("content://")) return ctx.contentResolver.openInputStream(Uri.parse(u)) ?: throw IOException("원본을 열 수 없다")
        if (!Attachments.safeName(u)) throw IOException("이상한 파일 이름")
        val f = File(Attachments.dir(ctx), u)
        if (!f.isFile) throw IOException("원본 파일이 없다(지워졌거나 앱을 다시 깔았다)")
        return f.inputStream()
    }

    /** 우리 앱이 이미 다운로드에 둔 같은 이름이 있으면 「이름 (2).확장자」… (남의 앱 파일은 안 보인다 — 그건 MediaStore 가 이름을 바꾼다) */
    private fun uniqueName(ctx: Context, coll: Uri, want: String): String {
        fun exists(n: String) = runCatching {
            ctx.contentResolver.query(coll, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf(n, Environment.DIRECTORY_DOWNLOADS + "/%"), null)?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
        if (!exists(want)) return want
        val dot = want.lastIndexOf('.').takeIf { it > 0 } ?: want.length
        for (k in 2..50) {
            val n = want.substring(0, dot) + " ($k)" + want.substring(dot)
            if (!exists(n)) return n
        }
        return want
    }

    private fun names(l: List<Attachment>) = l.take(8).joinToString(", ") { "「${it.title}」" } + if (l.size > 8) " 외 ${l.size - 8}개" else ""

    private fun kb(n: Long) = if (n >= 1_048_576) "%.1fMB".format(n / 1_048_576.0) else "${(n + 1023) / 1024}KB"
}
