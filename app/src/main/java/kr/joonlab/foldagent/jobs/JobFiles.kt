package kr.joonlab.foldagent.jobs

import android.content.Context
import android.net.Uri
import kr.joonlab.foldagent.Gallery
import kr.joonlab.foldagent.records.Attachments
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 맡긴 작업의 서버 파일 받기(계약 cc-capabilities §1-3 `GET <base>/files/<fileId>`, Bearer).
 *   - 이미지(png·jpg·webp·gif) → 갤러리 Pictures/FoldAgent/FoldAgent_job_<시각>_<jobId 끝 6자>[_<n>].<확장자>(share_images·write_note 가 FoldAgent_ 이름으로 다시 쓴다)
 *   - 그 밖(확장자를 모르는 image 형식 포함) →앱 전용 폴더 files/attach/<jobId>__<원래 이름>(열기·공유는 records/AttachProvider)
 *   이미지 저장은 Gallery.save 하나로(AgentService.saveImageBytes·CapTools 와 같은 창구 — 통합 때 로컬 사본을 합쳤다).
 */
object JobFiles {

    /** 받아서 저장하고 첨부 JSON(Agent.addAttachment 모양)을 돌려준다. 너무 크거나 실패하면 예외 */
    fun fetch(ctx: Context, base: String, token: String, fileId: String, name: String, mime: String, jobId: String, n: Int, max: Long): JSONObject {
        val c = URL("$base/files/${Uri.encode(fileId)}").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer $token")
            val http = c.responseCode
            if (http !in 200..299) throw IOException("HTTP $http")
            if (c.contentLengthLong > max) throw IOException("너무 큼")
            val realMime = mime.takeIf { it != "application/octet-stream" } ?: c.contentType?.substringBefore(';')?.trim() ?: mime
            val title = name.substringAfterLast('/').take(120)
            // 갤러리에 넣을 수 있는 형식(png·jpg·webp·gif)만 이미지로. svg·heic 처럼 확장자를 모르는 image 형식은 파일로 둔다(CapTools 와 같은 규칙).
            // .jpg 로 억지로 저장하면 MediaStore 가 거부하거나 이름이 바뀌어 share_images 가 못 찾는다.
            val ext = Gallery.extOf(realMime)
            return if (ext != null) {
                // title = 갤러리 이름 — 결과 턴·다음 턴 맥락에 이 이름이 나가야 「그 그림 보내 줘」 때 share_images 가 찾는다(image_gen·CapTools 와 같은 규칙)
                // jobId 끝 6자를 넣는다 — 같은 초에 끝난 두 작업의 이름이 겹치면 MediaStore 가 이름을 바꿔 title 과 실제 이름이 어긋난다
                val jtag = jobId.replace(Regex("[^0-9A-Za-z]"), "").takeLast(6)
                val display = "FoldAgent_job_" + Gallery.stamp() + "_$jtag" + (if (n > 0) "_$n" else "") + ".$ext"
                val (uri, size) = c.inputStream.use { inp -> Gallery.save(ctx, display, realMime) { out -> copyLimited(inp, out, max) } }
                JSONObject().put("kind", "image").put("title", display).put("uri", uri.toString()).put("mime", realMime).put("size", size)
            } else {
                val fname = safeFileName(jobId, title)
                val dst = File(Attachments.dir(ctx), fname)
                val tmp = File(dst.parentFile, ".$fname.part")
                val size = try {
                    c.inputStream.use { inp -> FileOutputStream(tmp).use { out -> copyLimited(inp, out, max) } }
                } catch (e: Exception) { tmp.delete(); throw e }
                if (!tmp.renameTo(dst)) { tmp.delete(); throw IOException("rename 실패") }
                JSONObject().put("kind", "file").put("title", title).put("uri", fname).put("mime", realMime).put("size", size)
            }
        } finally { runCatching { c.disconnect() } }
    }

    /** 「<jobId>__<이름>」 — 경로 문자·숨김 이름을 걷고 길이를 줄인다 */
    private fun safeFileName(jobId: String, name: String): String {
        val j = jobId.replace(Regex("[^0-9A-Za-z_-]"), "_").take(40)
        val nm = name.replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1f]"), "_").trimStart('.').ifBlank { "file" }.take(100)
        return "${j}__$nm"
    }

    private fun copyLimited(inp: InputStream, out: java.io.OutputStream, max: Long): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val k = inp.read(buf)
            if (k < 0) break
            total += k
            if (total > max) throw IOException("너무 큼")
            out.write(buf, 0, k)
        }
        return total
    }
}
