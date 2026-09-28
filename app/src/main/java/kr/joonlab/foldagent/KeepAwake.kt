package kr.joonlab.foldagent

import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 에이전트가 도는 동안 화면이 꺼지지 않게 한다 — 꺼지면 폰이 잠기고, 잠금은 사람만 푼다(2026-09-27 오래 기다리는 시험 중 실측).
 *
 * 방법: 1px 투명 접근성 창(TYPE_ACCESSIBILITY_OVERLAY)에 FLAG_KEEP_SCREEN_ON. 창이 붙어 있는 동안만 효력이고 떼면 원래 꺼짐 시간대로 돌아간다.
 * PowerManager 의 화면 wake lock(SCREEN_DIM·SCREEN_BRIGHT·FULL)은 deprecated 이고 WAKE_LOCK 권한·해제 누락 위험이 붙어 창 플래그로 했다.
 * 이미 꺼진 화면을 켜지는 않는다(맡긴 일 이어가기처럼 화면이 꺼진 채 시작한 실행엔 효과 없음 — 그대로 둔다).
 * 창은 터치·포커스를 받지 않는다(NOT_TOUCHABLE·NOT_FOCUSABLE — 뒤 앱 조작에 끼지 않는다). on/off 는 어느 스레드에서 불러도 된다.
 */
class KeepAwake(private val svc: AgentService) {

    private val main = Handler(Looper.getMainLooper())
    private val wm get() = svc.getSystemService(WindowManager::class.java)
    private var view: View? = null   // 메인 스레드에서만 만진다

    fun on() = main.post {
        if (view != null) return@post
        val v = View(svc)
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; title = "FoldAgentKeepAwake" }
        try {
            wm.addView(v, lp); view = v
            Log.i("FoldAgent", "keep_awake on")
        } catch (e: Exception) {   // 서비스 연결이 끊긴 토큰 등 — 화면 유지만 못 할 뿐 실행은 계속
            Log.w("FoldAgent", "keep_awake skipped: ${e.message}")
        }
    }

    fun off() = main.post {
        val v = view ?: return@post
        view = null
        runCatching { wm.removeView(v) }
        Log.i("FoldAgent", "keep_awake off")
    }
}
