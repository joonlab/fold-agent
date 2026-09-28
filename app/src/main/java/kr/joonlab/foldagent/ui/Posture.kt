package kr.joonlab.foldagent.ui

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 폴드 자세 — 앱 화면 배치를 고르는 기준(2026-09-27 사용자 확정 「자세별 배치」).
 *
 * | 자세      | 창 크기(dp, 실측 기준)     | 배치 |
 * |-----------|---------------------------|------|
 * | cover     | 475×751 (커버 세로)        | 1칸(현행) |
 * | coverland | 751×475 (커버 가로, 높이<480) | 1칸 축약: 머리 한 줄 상태 · 목록 설명 1줄 · 필터는 머리 ≡ 메뉴 |
 * | open      | 933×704 (펼침)             | 2칸 목록 360 \| 대화방 |
 * | openrot   | 704×933 (펼침 돌림)         | 2칸 목록 300 \| 대화방, 좌우 여백 줄임 |
 * | table     | 펼침 돌림 + 반접힘          | 위 대화방 / 힌지 / 아래 입력줄·마이크·정지 |
 *
 * 크기는 LocalConfiguration(screenWidthDp·screenHeightDp)으로 본다 — 인셋을 뺀 BoxWithConstraints 는
 * 키보드가 뜨면 높이가 줄어 펼침이 「커버 가로」로 뒤집힌다.
 */
object Posture {
    const val COVER = "cover"
    const val COVER_LAND = "coverland"
    const val OPEN = "open"
    const val OPEN_ROT = "openrot"
    const val TABLE = "table"
    val ALL = listOf(COVER, COVER_LAND, OPEN, OPEN_ROT, TABLE)

    fun of(wDp: Int, hDp: Int, half: Boolean): String {
        val small = minOf(wDp, hDp)
        return when {
            // 반접힘 + 세로로 긴 펼침 화면 = 힌지가 가로로 누움(테이블톱). 933×704 로 반접힘은 책 자세 — v1 은 그냥 2칸
            half && small >= 600 && hDp > wDp -> TABLE
            wDp >= 600 && hDp < 480 -> COVER_LAND
            small >= 600 && hDp > wDp -> OPEN_ROT
            wDp >= 600 -> OPEN
            else -> COVER
        }
    }

    /** 강제 자세를 시험 캡처할 때 그 자세의 창 크기(dp) — 실제 화면보다 크면 잘라서 가운데 */
    fun sizeOf(p: String): Pair<Int, Int> = when (p) {
        COVER -> 475 to 751
        COVER_LAND -> 751 to 475
        OPEN -> 933 to 704
        else -> 704 to 933   // openrot · table
    }
}

/**
 * 힌지 각도 센서(TYPE_HINGE_ANGLE) — 폴드8(One UI)은 반쯤 접어도 FoldingFeature 를 FLAT 으로만 준다(참고 앱 history 실측 2026-09-25).
 * 그래서 접힌 정도는 공개 센서로 따로 읽는다. 참고 앱 Posture.kt 의 rememberHinge 를 옮겨 고친 것 —
 * 여기선 androidx.window 의존성이 없고, 액티비티 onResume/onPause 에 맞춰 켜고 끈다(뒤에 있을 때 센서를 붙잡지 않게).
 * 접거나 펼 때 90° 를 스쳐 지나가므로(실측: 0.1~0.5초 번쩍) 반접힘은 0.5초 머물러야 인정, 벗어나는 건 즉시.
 */
class HingeSensor(ctx: Context, private val onHalf: (Boolean) -> Unit) {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
    private val main = Handler(Looper.getMainLooper())
    private var on = false
    private var rawHalf = false
    private var logged = -1
    private val settle = Runnable { if (rawHalf) onHalf(true) }

    private val l = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values[0]
            val bucket = (v / 10).toInt()
            if (bucket != logged) { logged = bucket; Log.i("FoldAgent", "hinge angle=%.1f".format(v)) }
            val h = v in HALF_MIN..HALF_MAX
            if (h == rawHalf) return
            rawHalf = h
            main.removeCallbacks(settle)
            if (h) main.postDelayed(settle, HALF_SETTLE_MS) else onHalf(false)
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    fun start() {
        if (on || sensor == null) return
        on = true
        sm?.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        if (!on) return
        on = false
        sm?.unregisterListener(l)
        main.removeCallbacks(settle)
        // 멈춘 동안의 자세는 모른다 — 반접힘으로 남겨 두면 돌아왔을 때 펼친 화면이 테이블톱으로 보인다. 다시 켜면 첫 값이 곧 온다
        if (rawHalf) { rawHalf = false; onHalf(false) }
    }

    val available get() = sensor != null

    companion object {
        /** 반접힘으로 볼 각도(도) — 닫힘·완전히 펼침 사이(참고 앱과 같은 값) */
        private const val HALF_MIN = 30f
        private const val HALF_MAX = 150f
        private const val HALF_SETTLE_MS = 500L

        /**
         * 힌지의 창 기준 y(px). androidx.window 없이 — 앱이 전체 화면이면 힌지는 화면(최대 창) 한가운데다.
         * 분할 창이면 창 위치를 빼서 맞춘다. 창 밖이면 null(그땐 칸을 반으로 나눈다).
         */
        fun hingeYInWindow(act: Activity): Float? {
            val wm = act.windowManager
            val max = wm.maximumWindowMetrics.bounds
            val cur = wm.currentWindowMetrics.bounds
            val y = max.exactCenterY() - cur.top
            return if (y > 0 && y < cur.height()) y else null
        }
    }
}
