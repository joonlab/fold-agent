package kr.joonlab.foldagent

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * 화면을 거치지 않는 기기 제어 — 권한 없이 되는 것만.
 * 전엔 이런 요청이 오면 모델이 알림창·퀵 설정을 뒤지며 헤맸다(2026-09-26 스크린샷 14단계 실패).
 * 결과 문자열은 모델에게 가므로 「무엇이 어떻게 바뀌었나」를 값으로 적는다.
 */
object Device {

    private val MEDIA_KEYS = mapOf(
        "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
        "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
        "play_pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
        "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        "stop" to KeyEvent.KEYCODE_MEDIA_STOP,
    )

    /** 시스템이 「지금 소리를 내는」 미디어 세션으로 키를 보낸다 — 앱을 열 필요가 없다 */
    fun media(ctx: Context, action: String): String {
        val code = MEDIA_KEYS[action] ?: return "실패: 알 수 없는 동작 $action"
        val am = ctx.getSystemService(AudioManager::class.java)
        val before = am.isMusicActive
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        Thread.sleep(700)
        val after = am.isMusicActive
        fun s(b: Boolean) = if (b) "재생 중" else "멈춤"
        return "미디어 $action 보냄 (소리 ${s(before)} → ${s(after)})" +
            if (!before && !after && action in setOf("play", "play_pause")) " — 재생이 시작되지 않았다. 최근 재생한 앱이 없으면 앱을 열어 재생해야 한다" else ""
    }

    private val STREAMS = mapOf(
        "media" to AudioManager.STREAM_MUSIC,
        "ring" to AudioManager.STREAM_RING,
        "notification" to AudioManager.STREAM_NOTIFICATION,
        "alarm" to AudioManager.STREAM_ALARM,
        "call" to AudioManager.STREAM_VOICE_CALL,
    )

    fun volume(ctx: Context, stream: String, action: String, percent: Int): String {
        val st = STREAMS[stream.ifBlank { "media" }] ?: return "실패: 알 수 없는 음량 종류 $stream"
        val am = ctx.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(st)
        val before = am.getStreamVolume(st)
        val flags = AudioManager.FLAG_SHOW_UI   // 폰에 음량 막대가 떠서 사용자도 바뀐 걸 본다
        try {
            when (action) {
                "get" -> {}
                "up" -> am.adjustStreamVolume(st, AudioManager.ADJUST_RAISE, flags)
                "down" -> am.adjustStreamVolume(st, AudioManager.ADJUST_LOWER, flags)
                "mute" -> am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, flags)
                "unmute" -> am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, flags)
                "set" -> {
                    if (percent !in 0..100) return "실패: percent 는 0-100 (받은 값 $percent)"
                    am.setStreamVolume(st, Math.round(max * percent / 100f), flags)
                }
                else -> return "실패: 알 수 없는 동작 $action"
            }
        } catch (e: SecurityException) {
            // 벨소리를 0 으로 내리는 것 등은 방해금지 권한이 없으면 막힌다
            return "실패: 시스템이 막음(${e.message?.take(60)}) — 소리 모드·방해금지 설정이 필요할 수 있다"
        }
        val after = am.getStreamVolume(st)
        val muted = am.isStreamMute(st)
        // 음량은 칸 단위라 30% 요청이 33% 가 된다 — 모델이 이걸 「조건 미달」로 보고 failed 를 냈다(2026-09-26)
        val snapped = if (action == "set" && after == Math.round(max * percent / 100f)) " — 음량은 ${max}칸 단위라 요청 ${percent}%에 가장 가까운 칸으로 맞춤(요청을 이룬 것)" else ""
        return "$stream 음량 $before/$max → $after/$max (${after * 100 / maxOf(1, max)}%)$snapped" + if (muted) " · 음소거 상태" else ""
    }

    /** 밝기 0~255(이 폰 실측). WRITE_SETTINGS 특수 권한 필요 — ./dev.sh grant */
    fun brightness(ctx: Context, action: String, percent: Int): String {
        val cr = ctx.contentResolver
        fun cur() = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, 0)
        fun auto() = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1
        // 반올림 — 내림하면 70% 요청이 69%로 보고돼 모델이 조건 미달로 봤다(2026-09-26)
        fun pct(v: Int) = Math.round((v - 1).coerceAtLeast(0) * 100 / 254f)
        val before = "${pct(cur())}%" + if (auto()) "(자동)" else ""
        if (action == "get") return "밝기 $before"
        if (!android.provider.Settings.System.canWrite(ctx))
            return "실패: 밝기를 바꿀 권한이 없다 — open_settings(DISPLAY_SETTINGS)로 사용자가 직접 조절하게 할 것"
        val target = when (action) {
            "auto" -> null
            "set" -> if (percent in 0..100) percent else return "실패: percent 는 0-100 (받은 값 $percent)"
            "up" -> (pct(cur()) + 15).coerceAtMost(100)
            "down" -> (pct(cur()) - 15).coerceAtLeast(0)
            else -> return "실패: 알 수 없는 동작 $action"
        }
        val mode = android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE
        if (target == null) {
            android.provider.Settings.System.putInt(cr, mode, 1)
        } else {
            // 자동 밝기가 켜져 있으면 값을 써도 곧 덮인다 — 수동으로 바꾼다
            android.provider.Settings.System.putInt(cr, mode, 0)
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, 1 + Math.round(target * 254 / 100f))
        }
        return "밝기 $before → ${pct(cur())}%" + if (auto()) "(자동)" else "(수동)"
    }

    /** 소리 모드와 방해금지. 무음·방해금지는 알림 정책 접근 권한 필요 — ./dev.sh grant */
    fun soundMode(ctx: Context, mode: String): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        fun state(): String {
            val r = when (am.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "무음"; AudioManager.RINGER_MODE_VIBRATE -> "진동"; else -> "소리" }
            val dnd = nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            return r + if (dnd) " · 방해금지 켜짐" else ""
        }
        val before = state()
        if (mode == "get") return "소리 모드: $before"
        val needsPolicy = mode in setOf("silent", "dnd_on", "dnd_off")
        if (needsPolicy && !nm.isNotificationPolicyAccessGranted)
            return "실패: 무음·방해금지를 바꿀 권한이 없다 — open_settings(ZEN_MODE_SETTINGS)로 사용자가 직접 하게 할 것"
        try {
            when (mode) {
                "sound" -> am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                "vibrate" -> am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                "silent" -> am.ringerMode = AudioManager.RINGER_MODE_SILENT
                "dnd_on" -> nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                "dnd_off" -> nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                else -> return "실패: 알 수 없는 모드 $mode"
            }
        } catch (e: SecurityException) {
            return "실패: 시스템이 막음(${e.message?.take(60)})"
        }
        Thread.sleep(300)
        return "소리 모드 $before → ${state()}"
    }

    /**
     * 클립보드. 안드로이드 10+ 는 앞에 있지 않은 앱의 읽기를 막는다 — 읽기는 투명 화면이 앞에 떠서 한다.
     * 쓰기도 뒤에서는 불안정했다(2026-09-26 붙여넣기 폴백) — 쓴 뒤 다시 읽혀야 성공으로 친다(못 읽으면 「확인 불가」).
     */
    fun clipboard(ctx: Context, action: String, text: String): String {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        fun read(): String? = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
        return when (action) {
            // 뒤에서 읽으면 거부된다 — 투명 화면을 잠깐 앞에 띄워 읽는다(ClipReadActivity)
            "get" -> when (val t = ClipReadActivity.read(ctx)) {
                null -> "클립보드를 읽지 못함(3초 안에 응답 없음) — 사용자에게 말로 알려 달라고 할 것"
                "" -> "클립보드가 비어 있음"
                else -> "클립보드: ${t.take(500)}"
            }
            "set" -> {
                if (text.isEmpty()) return "실패: text 가 비었음"
                val ok = runCatching { cm.setPrimaryClip(android.content.ClipData.newPlainText("foldagent", text)); true }.getOrElse { false }
                if (!ok) "실패: 클립보드에 넣지 못함"
                else when (read()) { text -> "클립보드에 넣음: ${text.take(60)}"; null -> "클립보드에 넣음(다시 읽기는 막혀 확인 불가): ${text.take(60)}"; else -> "실패: 넣었지만 다른 내용이 남아 있음" }
            }
            else -> "실패: 알 수 없는 동작 $action"
        }
    }

    /** 카메라 권한 없이 된다. 카메라 앱이 쓰는 중이면 실패한다 */
    fun flashlight(ctx: Context, on: Boolean): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            val c = cm.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return "실패: 플래시 달린 카메라를 못 찾음"
        return try {
            cm.setTorchMode(id, on)
            "손전등 ${if (on) "켬" else "끔"}"
        } catch (e: Exception) {
            "실패: 손전등 ${if (on) "켜기" else "끄기"} 안 됨(${e.message?.take(60)}) — 카메라를 쓰는 앱이 있으면 막힌다"
        }
    }
}
