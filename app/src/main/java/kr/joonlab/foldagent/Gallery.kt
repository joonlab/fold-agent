package kr.joonlab.foldagent

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore.Images.Media as M
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 갤러리(Pictures/FoldAgent)에 그림 한 장 저장 — 우리 앱이 만든 FoldAgent_… 이미지의 단 하나의 저장 창구.
 * 쓰는 곳: AgentService.saveImageBytes(image_gen 등 바이트 그대로) · CapTools(서버 첨부 스트림) · JobFiles(맡긴 작업 결과).
 * share_images·write_note 가 DISPLAY_NAME 「FoldAgent_%」로 다시 찾는다 — 이름은 부르는 쪽이 FoldAgent_ 로 시작하게 준다.
 * 우리 앱이 만든 항목이라 권한 없이 쓰고, 실패하면 방금 만든 미완성 항목만 지운다.
 */
object Gallery {
    const val REL_PATH = "Pictures/FoldAgent"

    /** 파일 이름에 붙일 시각 yyyyMMdd_HHmmss(KST 기기 시각) */
    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())

    /** 갤러리에 둘 이미지 형식 → 확장자. 모르는 형식이면 null(부르는 쪽이 파일로 저장) */
    fun extOf(mime: String): String? = when (mime.lowercase()) {
        "image/png" -> "png"; "image/jpeg", "image/jpg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> null
    }

    /**
     * 항목을 만들고(IS_PENDING) [write] 로 내용을 쓴 뒤 공개한다. [write] 는 쓴 바이트 수를 돌려준다.
     * 실패(예외 포함)면 만든 항목을 지우고 예외를 그대로 던진다. @return (content uri, 바이트 수)
     */
    fun save(ctx: Context, name: String, mime: String, relPath: String = REL_PATH, write: (OutputStream) -> Long): Pair<Uri, Long> {
        val cv = ContentValues().apply {
            put(M.DISPLAY_NAME, name)
            put(M.MIME_TYPE, mime)
            put(M.RELATIVE_PATH, relPath)
            put(M.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(M.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("insert")
        try {
            val size = (cr.openOutputStream(uri) ?: throw IOException("open")).use(write)
            cr.update(uri, ContentValues().apply { put(M.IS_PENDING, 0) }, null, null)
            return uri to size
        } catch (e: Throwable) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
    }
}
