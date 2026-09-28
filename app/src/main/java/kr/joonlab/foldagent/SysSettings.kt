package kr.joonlab.foldagent

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings

/**
 * 허용 목록 시스템 설정 — 화면을 거치지 않고 값을 읽고, 세 키만 쓴다(system_setting 도구 · 쓰기 범위는 사용자 결정 2026-09-27).
 * 왜: 「충전 중 화면 켜짐」은 이 폰 개발자 옵션 목록에 보이지 않는데, 에이전트는 보이는 화면뿐이라 두 화면을 16번 오갔다
 * (2026-09-26 run 20260926-232447-007). 값은 화면에 없어도 설정에 있다.
 *
 * 쓰기는 이 파일에서 직접 부르지 않는다 — Agent.systemSetting 이 확인 게이트(AgentService.confirm)를 통과한 뒤에만 put 한다.
 * 자동 회전은 quick_toggle(auto_rotate) 가 먼저다 — 타일을 못 찾거나 실패할 때만 여기로(게이트 뒤). 순서를 BASE_PROMPT·도구 설명에 박아
 *   같은 요청이 run 마다 게이트가 떴다 안 떴다 하지 않게 한다(2026-09-26 run 165337-504: 타일이 없어 실패).
 * 와이파이·비행기·모바일 데이터는 읽기만(끄는 순간 비서 연결이 끊긴다 — 사용자 결정 2026-09-26). 자유 키는 받지 않는다(enum).
 * 읽기 제한: targetSdk 31+ 에서 @hide·비 @Readable 키는 SecurityException — 그래서 읽기는 전부 「읽기 불가」로 받는다.
 */
object SysSettings {

    enum class Ns { GLOBAL, SYSTEM, SECURE, API }

    /** 쓰기 권한 종류 — global 은 WRITE_SECURE_SETTINGS(adb pm grant), system 공개 키는 WRITE_SETTINGS(canWrite) */
    enum class Perm { SECURE, SYSTEM }

    class Spec(
        val ns: Ns,
        val label: String,
        val show: (String) -> String = ::bool,
        val perm: Perm? = null,               // null = 읽기만
        val readOnlyWhy: String = "",
    ) { val writable get() = perm != null }

    /** One UI 「화면 자동 꺼짐 시간」 선택지(ms) — 쓸 땐 이 중 가장 가까운 칸으로 맞춘다 */
    val TIMEOUT_CHOICES = listOf(15_000, 30_000, 60_000, 120_000, 300_000, 600_000)

    private const val NET_WHY = "끄는 순간 이 비서의 인터넷 연결이 끊겨 멈춘다 — 바꾸지 않는다. 사용자가 직접 하게 finish"

    val KEYS: LinkedHashMap<String, Spec> = linkedMapOf(
        // 7 = AC 1 | USB 2 | 무선 4 — AOSP 개발자 옵션 StayAwake 토글과 같은 값. 읽을 땐 0 이 아니면 켜짐(15 여도)
        "stay_on_while_plugged_in" to Spec(Ns.GLOBAL, "충전 중 화면 계속 켜짐", perm = Perm.SECURE,
            show = { v -> if (v == "0") "꺼짐" else "켜짐($v: 1=AC 2=USB 4=무선)" }),
        "screen_off_timeout" to Spec(Ns.SYSTEM, "화면 자동 꺼짐 시간", perm = Perm.SYSTEM, show = { v -> v.toLongOrNull()?.let(::dur) ?: v }),
        "accelerometer_rotation" to Spec(Ns.SYSTEM, "자동 회전", perm = Perm.SYSTEM,
            show = { v -> when (v) { "1" -> "켜짐"; "0" -> "꺼짐(지금 방향 고정)"; else -> v } }),
        "development_settings_enabled" to Spec(Ns.GLOBAL, "개발자 옵션"),
        "low_power" to Spec(Ns.API, "절전 모드", readOnlyWhy = "quick_toggle(power_saving) 으로"),
        "dark_mode" to Spec(Ns.API, "다크 모드", readOnlyWhy = "quick_toggle(dark_mode) 으로",
            show = { v -> when (v) { "2" -> "켜짐"; "1" -> "꺼짐"; "0" -> "자동(일몰~일출)"; "3" -> "예약"; else -> v } }),
        "airplane_mode_on" to Spec(Ns.GLOBAL, "비행기 모드", readOnlyWhy = NET_WHY),
        "wifi_on" to Spec(Ns.GLOBAL, "와이파이", readOnlyWhy = NET_WHY),
        "bluetooth_on" to Spec(Ns.GLOBAL, "블루투스", readOnlyWhy = "quick_toggle(bluetooth) 으로"),
        "mobile_data" to Spec(Ns.GLOBAL, "모바일 데이터", readOnlyWhy = NET_WHY),
        "location_mode" to Spec(Ns.SECURE, "위치",
            show = { v -> when (v) { "0" -> "꺼짐"; "1" -> "켜짐(센서만)"; "2" -> "켜짐(절전)"; "3" -> "켜짐(높은 정확도)"; else -> v } }),
        "auto_time" to Spec(Ns.GLOBAL, "날짜·시간 자동 설정"),
        "auto_time_zone" to Spec(Ns.GLOBAL, "시간대 자동 설정"),
        "font_scale" to Spec(Ns.SYSTEM, "글자 크기",
            show = { v -> v.toFloatOrNull()?.let { f -> if (f == 1f) "기본(100%)" else "${Math.round(f * 100)}%" } ?: v }),
        "screen_brightness_mode" to Spec(Ns.SYSTEM, "자동 밝기", readOnlyWhy = "brightness(auto) 로",
            show = { v -> when (v) { "1" -> "켜짐"; "0" -> "꺼짐(수동)"; else -> v } }),
        "aod_mode" to Spec(Ns.SYSTEM, "Always On Display"),                 // 삼성 전용 — 앱 권한으로 읽히는지 실기 확인 전
        "blue_light_filter" to Spec(Ns.SYSTEM, "눈 보호 모드"),              // 삼성 전용 — 같은 이유
        "lock_screen_lock_after_timeout" to Spec(Ns.SECURE, "화면 꺼진 뒤 자동 잠금",   // @hide — 막힐 수 있다
            show = { v -> v.toLongOrNull()?.let { "화면 꺼진 뒤 ${dur(it)}" } ?: v }),
    )

    private fun bool(v: String) = when (v) { "1" -> "켜짐"; "0" -> "꺼짐"; else -> v }

    fun dur(ms: Long): String = when {
        ms <= 0 -> "바로"
        ms >= 3_600_000 && ms % 3_600_000 == 0L -> "${ms / 3_600_000}시간"
        ms >= 60_000 -> if (ms % 60_000 == 0L) "${ms / 60_000}분" else "${ms / 60_000}분 ${ms % 60_000 / 1000}초"
        else -> "${ms / 1000}초"
    }

    /** 원래 값(문자열). null = 설정에 없음. 막힌 키는 SecurityException 을 그대로 던진다 */
    fun raw(ctx: Context, key: String): String? {
        val cr = ctx.contentResolver
        return when (KEYS[key]?.ns) {
            Ns.GLOBAL -> Settings.Global.getString(cr, key)
            Ns.SYSTEM -> Settings.System.getString(cr, key)
            Ns.SECURE -> Settings.Secure.getString(cr, key)
            // @hide 키 대신 공개 API
            Ns.API -> when (key) {
                "dark_mode" -> ctx.getSystemService(UiModeManager::class.java).nightMode.toString()
                "low_power" -> if (ctx.getSystemService(PowerManager::class.java).isPowerSaveMode) "1" else "0"
                else -> null
            }
            null -> null
        }
    }

    fun rawOrNull(ctx: Context, key: String): String? = try { raw(ctx, key) } catch (e: Exception) { null }

    fun describe(ctx: Context, key: String): String {
        val s = KEYS.getValue(key)
        val v = try { raw(ctx, key)?.let(s.show) ?: "값 없음(이 폰 설정에 없는 키)" } catch (e: Exception) { "읽기 불가(시스템이 막음)" }
        return "${s.label}($key) = $v"
    }

    fun canWrite(ctx: Context, spec: Spec): Boolean = when (spec.perm) {
        Perm.SECURE -> ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        Perm.SYSTEM -> Settings.System.canWrite(ctx)
        null -> false
    }

    private fun onOff(v: String): Boolean? = when (v.trim().lowercase().removeSuffix("요").removeSuffix("줘").trim()) {
        "on", "켜기", "켜", "켬", "켜짐", "true", "1", "enable", "enabled" -> true
        "off", "끄기", "꺼", "끔", "꺼짐", "false", "0", "disable", "disabled" -> false
        else -> null
    }

    /** 쓸 값. first = 코드 값, second = 사람 말을 맞춘 설명(「3분 → 가장 가까운 칸 2분」 · 없으면 null). 못 알아들으면 null */
    fun parse(key: String, value: String): Pair<Int, String?>? = when (key) {
        "stay_on_while_plugged_in" -> onOff(value)?.let { (if (it) 7 else 0) to null }
        "accelerometer_rotation" -> onOff(value)?.let { (if (it) 1 else 0) to null }
        "screen_off_timeout" -> timeoutMs(value)?.let { ms ->
            val snap = TIMEOUT_CHOICES.minWith(compareBy<Int> { Math.abs(it - ms) }.thenByDescending { it })
            snap to (if (snap.toLong() != ms) "요청 ${dur(ms)} → 가장 가까운 칸 ${dur(snap.toLong())}" else null)
        }
        else -> null
    }

    /** 「5분」「30초」「10 min」「1분 30초」「최대」 → ms. 단위 없는 수는 1000 이상일 때만 ms 로 본다(「30」은 초인지 분인지 모른다) */
    fun timeoutMs(v: String): Long? {
        val s = v.trim().lowercase()
        if (Regex("최대|가장 길|제일 길|max|longest").containsMatchIn(s)) return TIMEOUT_CHOICES.last().toLong()
        if (Regex("최소|가장 짧|제일 짧|minimum|shortest").containsMatchIn(s)) return TIMEOUT_CHOICES.first().toLong()
        val unit = Regex("(\\d+(?:\\.\\d+)?)\\s*(ms|밀리초|초|secs?|seconds?|s|분|mins?|minutes?|m|시간|hours?|hrs?|h)")
        val hits = unit.findAll(s).toList()
        if (hits.isNotEmpty()) return hits.sumOf { m ->
            val n = m.groupValues[1].toDouble()
            val mul = when (m.groupValues[2]) {
                "ms", "밀리초" -> 1.0
                "초", "sec", "secs", "second", "seconds", "s" -> 1000.0
                "시간", "hour", "hours", "hr", "hrs", "h" -> 3_600_000.0
                else -> 60_000.0
            }
            (n * mul).toLong()
        }.takeIf { it > 0 }
        return s.toLongOrNull()?.takeIf { it >= 1000 }
    }

    /** 확인 게이트 문구(음성으로도 읽힌다 — 앞에 「확인이 필요해요.」가 붙는다) */
    fun question(key: String, before: String?, target: Int, note: String?): String {
        val s = KEYS.getValue(key)
        // stay_on 의 show 는 비트 설명(「켜짐(7: 1=AC …)」)까지 붙어 음성으로 그대로 읽힌다 — 게이트엔 켜짐/꺼짐만(설계 §6)
        val from = before?.let { if (key == "stay_on_while_plugged_in") (if (it == "0") "꺼짐" else "켜짐") else s.show(it) } ?: "알 수 없음"
        val to = s.show(target.toString())
        return when (key) {
            "stay_on_while_plugged_in" -> if (target != 0)
                "충전하는 동안 화면이 꺼지지 않게 바꿀까요? (${s.label}: $from → 켜짐 · 켜 둔 채 두면 화면 잔상·발열이 생길 수 있어요)"
            else "충전 중에도 화면이 원래대로 꺼지게 바꿀까요? (${s.label}: $from → 꺼짐)"
            "accelerometer_rotation" -> if (target != 0)
                "화면이 폰 방향 따라 돌아가게 자동 회전을 켤까요? (${s.label}: $from → 켜짐)"
            else "자동 회전을 끄고 지금 방향으로 고정할까요? (${s.label}: $from → 꺼짐)"
            else -> "화면이 ${to} 뒤에 꺼지게 바꿀까요? (${s.label}: $from → $to" + (note?.let { " · $it" } ?: "") + ")"
        }
    }

    /** 게이트를 통과한 뒤에만 부른다(Agent.systemSetting). 쓴 결과는 부른 쪽이 다시 읽어 확인한다 */
    fun put(ctx: Context, key: String, value: Int): Boolean {
        val cr = ctx.contentResolver
        return when (KEYS.getValue(key).ns) {
            Ns.GLOBAL -> Settings.Global.putInt(cr, key, value)
            Ns.SYSTEM -> Settings.System.putInt(cr, key, value)
            else -> false
        }
    }

    /** 되돌리는 말 — 결과 끝에 붙여 사용자가 그대로 말하면 되게. 켜고 끄는 키는 before 가 아니라 target 기준(before 를 못 읽으면 null 이라 방향이 뒤집혔다) */
    fun undo(key: String, before: String?, target: Int): String = when (key) {
        "stay_on_while_plugged_in" -> if (target != 0) "「충전 중 화면 켜짐 꺼 줘」" else "「충전 중 화면 켜짐 켜 줘」"
        "accelerometer_rotation" -> if (target != 0) "「자동 회전 꺼 줘」" else "「자동 회전 켜 줘」"
        else -> before?.toLongOrNull()?.let { "「화면 꺼짐 시간 ${dur(it)}로 해 줘」" } ?: "system_setting(set, $key, 원래 값)"
    }

    fun pluggedNow(ctx: Context): Boolean =
        (ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
}
