package kr.joonlab.foldagent

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 클립보드 읽기 전용 투명 화면. 안드로이드 10+ 는 「입력 포커스를 가진 앱」만 클립보드를 읽게 한다 —
 * 접근성 서비스(뒤에 있음)로 읽으면 ClipboardService 가 거부했다(2026-09-26 실측). 그래서 잠깐 앞에 떠서 포커스를 받는 순간 읽고 닫는다.
 * 별도 taskAffinity 라 채팅 화면(MainActivity 태스크)을 끌어올리지 않고, 닫히면 원래 앱이 그대로 돌아온다.
 */
class ClipReadActivity : Activity() {

    companion object {
        @Volatile private var latch: CountDownLatch? = null
        @Volatile private var result: String? = null

        /** @return 클립보드 글(비었으면 "") · 읽기 실패면 null */
        fun read(ctx: Context): String? {
            val l = CountDownLatch(1)
            latch = l; result = null
            ctx.startActivity(Intent(ctx, ClipReadActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            return if (l.await(3, TimeUnit.SECONDS)) result else null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    // onResume 에선 아직 포커스가 없어 거부된다 — 창이 포커스를 받은 뒤에 읽는다
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val cm = getSystemService(ClipboardManager::class.java)
        result = runCatching {
            val clip = cm.primaryClip
            if (clip == null || clip.itemCount == 0) "" else clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        }.getOrNull()
        latch?.countDown()
        finish()
        overridePendingTransition(0, 0)
    }
}
