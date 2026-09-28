> 2026-09-27 추론 유연화 세션의 설계·분석 결과(워크플로 에이전트 산출, 원문 그대로). 구현과 다른 점은 README·인계 문서가 정본.

## ③ 시스템 설정 읽기·쓰기 도구 설계 (`system_setting`)

### 1. 폰에서 읽기 전용으로 잰 값 (2026-09-27)
- 기기는 SM-F971N, Android 17(SDK 37)입니다. 앱은 `minSdk=30`, `targetSdk=36`이고, 빌드 파일 `app/build.gradle.kts:15-16`과 dumpsys 결과가 같습니다.
- 권한 상태(`dumpsys package kr.joonlab.foldagent`)
  - WRITE_SETTINGS: 매니페스트에 선언돼 있고(`AndroidManifest.xml:9`) appops 값은 `allow`입니다. `./dev.sh grant` 34행에서 허용합니다.
  - ACCESS_NOTIFICATION_POLICY: 허용돼 있습니다(`granted=true`).
  - **WRITE_SECURE_SETTINGS: 매니페스트에 선언이 없습니다.** dumpsys의 요청 권한 목록에도 없습니다. 이 상태에서는 `pm grant`를 해도 "has not requested permission" 오류로 실패합니다.
    - 매니페스트 선언 추가: `<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" tools:ignore="ProtectedPermissions"/>`(manifest 태그에 `xmlns:tools`도 넣어야 합니다)
    - 권한 부여: `dev.sh grant`에 `adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS` 한 줄 추가
    - 이 권한은 development 플래그라 adb로 부여할 수 있습니다. 앱을 지우면 없어지고, `install -r`로 덮어 설치하면 유지됩니다.
- **지금 `global stay_on_while_plugged_in=7`입니다.** 이미 켜져 있다는 뜻이고, 전에 adb로 넣은 값으로 보입니다. 실기에서 「켜기」 경로를 시험하려면 먼저 0으로 되돌려야 하는데, 이건 사용자 허락을 받은 뒤 메인이 합니다.
- 후보 키와 현재 값
  - global: `development_settings_enabled=1`, `adb_enabled=1`, `airplane_mode_on=0`, `wifi_on=1`, `bluetooth_on=1`, `mobile_data=1`, `low_power=0`, `auto_time=1`, `auto_time_zone=1`, `protect_battery=3`, `animator_duration_scale=1`
  - system: `screen_off_timeout=600000`(10분), `accelerometer_rotation=1`, `user_rotation=0`, `screen_brightness_mode=0`, `font_scale=1.0`, `haptic_feedback_enabled=1`, `time_12_24=12`
  - system(삼성 전용): `aod_mode=1`, `blue_light_filter=0`, `display_night_theme=1`, `intelligent_sleep_mode=1`, `double_tab_to_wake_up=1`, `lift_to_wake=0`
  - secure: `location_mode=3`, `ui_night_mode=2`, `screensaver_enabled=0`, `lock_screen_lock_after_timeout=5000`, `navigation_mode=2`, `one_handed_mode_enabled=0`, `sleep_timeout=-1`
- 주의할 점: `adb shell settings`는 shell 권한으로 돌기 때문에 모든 키가 읽힙니다. 앱 권한으로도 읽히는지는 이 방법으로 확인되지 않으니, 실기 항목 V2에서 확인합니다.

### 2. 안드로이드 API 확인
- **「켜기」 값은 7로 정합니다.** 비트 구성은 AC 1, USB 2, 무선 4, 도크 8(API 33에서 공개)입니다.
  - AOSP 개발자 옵션의 StayAwake 토글은 7(AC|USB|무선)을 씁니다. 판정은 `!= 0`입니다. 최신 버전은 도크까지 더해 15를 쓸 수도 있지만 확실하지 않습니다.
  - 인계 문서의 「0/7」, 폰에 지금 들어 있는 값과도 같습니다. 폴드에는 도크가 사실상 없어서 7과 15의 차이도 없습니다.
  - 읽을 때는 `값 != 0`이면 켜짐으로 봅니다. 7이든 15든 켜짐으로 나옵니다.
  - 기기 관리자가 최대 잠금 시간을 걸어 두면 이 값은 무시됩니다. 결과 문구에서 보증하지 않습니다.
- 쓰기 권한
  - `Settings.Global.putInt`에는 WRITE_SECURE_SETTINGS가 필요합니다. 없으면 SecurityException이 납니다.
  - `Settings.System`의 공개 키(`screen_off_timeout` 등)는 WRITE_SETTINGS(`canWrite`)로 쓸 수 있습니다.
  - `aod_mode`, `blue_light_filter` 같은 비공개 System 키는 targetSdk가 23 이상이면 IllegalArgumentException이 납니다. WRITE_SECURE_SETTINGS가 있으면 우회되지만, 이번 쓰기 허용 목록에는 넣지 않습니다.
- 읽기 제한(targetSdk 31 이상, SettingsProvider)
  - 프레임워크가 `@hide`로 정의했고 `@Readable`이 아닌 키는 앱이 읽으면 SecurityException이 납니다.
  - 공개 키는 읽힙니다. 삼성 OEM 키는 프레임워크에 정의가 없어서 대개 읽힐 것으로 추정합니다.
  - `ui_night_mode`, `navigation_mode` 같은 @hide 키는 막힐 수 있습니다. 그래서 읽기는 전부 try/catch로 감싸고, 막히면 「읽기 불가」로 돌려줍니다.
  - 다크 모드는 `UiModeManager.nightMode`, 절전은 `PowerManager.isPowerSaveMode` 같은 공개 API로 대신하는 쪽을 권합니다.

### 3. 허용 목록 표
쓰기는 1단계에서 첫 줄 하나만 엽니다. 나머지는 모두 읽기 전용입니다.

| 키(도구 enum) | 네임스페이스 | 읽기 | 쓰기 | 필요 권한 | 값 변환(사람 말 ↔ 코드) |
|---|---|---|---|---|---|
| `stay_on_while_plugged_in` | global | O | **O(확인 게이트)** | 쓰기: WRITE_SECURE_SETTINGS | on/켜기/true → 7 · off/끄기/false → 0 · 읽기: 0이면 꺼짐, 아니면 켜짐(비트 설명 붙임) |
| `screen_off_timeout` | system | O | 2단계 후보(WRITE_SETTINGS로 가능) | — | ms → 「10분」 · 쓸 때는 One UI 선택지(15초·30초·1·2·5·10분)에 맞춤 |
| `development_settings_enabled` | global | O | X | — | 1/0 → 개발자 옵션 켜짐/꺼짐 |
| `accelerometer_rotation` | system | O | X(quick_toggle auto_rotate가 이미 있음) | — | 1/0 → 자동 회전 |
| `low_power` | global(또는 PowerManager) | O | X(quick_toggle) | — | 절전 모드 |
| `dark_mode` | UiModeManager.nightMode | O | X(quick_toggle) | — | 2 → 켜짐 · 1 → 꺼짐 · 0 → 자동 |
| `airplane_mode_on` / `wifi_on` / `bluetooth_on` / `mobile_data` | global | O | **X(끄면 비서 연결이 끊김)** | — | 1/0 |
| `location_mode` | secure(공개, deprecated) | O | X | — | 0 → 꺼짐 · 3 → 켜짐(높은 정확도) |
| `auto_time` / `auto_time_zone` | global | O | X | — | 1/0 |
| `font_scale` | system | O | X | — | 1.0 → 「기본(100%)」 |
| `screen_brightness_mode` | system | O | X(brightness 도구가 있음) | — | 1 → 자동 |
| `aod_mode`(삼성) | system | O(실측 필요) | X | — | 1/0 → Always On Display |
| `blue_light_filter`(삼성) | system | O(실측 필요) | X | — | 1/0 → 눈 보호 모드 |
| `lock_screen_lock_after_timeout` | secure | O(@hide라 막힐 수 있음) | X | — | ms → 「화면 꺼진 뒤 5초」 |

- 넣지 않는 키: `android_id`, 블루투스 주소, `enabled_accessibility_services`, `default_input_method`, `always_on_vpn_app`, 잠금·지문 관련 키 전부
- key는 enum으로만 받고, 목록 밖의 자유 키는 받지 않습니다.

### 4. 도구 JSON 스키마 (Agent.kt TOOLS에 추가, `fn()` 모양 그대로)
```kotlin
.put(fn("system_setting", "허용된 시스템 설정값을 화면을 거치지 않고 읽거나 바꾼다. 설정 화면에 항목이 안 보여도 값은 있다 — 화면을 뒤지기 전에 get 으로 확인한다. key 를 생략한 get = 허용 목록 전체 값. set 은 사용자 확인을 거친다(지금은 stay_on_while_plugged_in = 충전 중 화면 계속 켜짐만)", JSONObject()
    .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("get", "set"))))
    .put("key", JSONObject().put("type", "string").put("enum", JSONArray(SysSettings.KEYS.keys.toList()))
        .put("description", "get 에서 생략하면 전체"))
    .put("value", p("string", "set 일 때 사람 말 그대로: on/off · 켜기/끄기")), listOf("action")))
```
- 두는 곳: `NO_SCREEN_TOOLS`(Agent.kt:41)에 `"system_setting"`을 추가합니다. 화면을 다시 읽지 않고 채팅 화면도 물리지 않습니다. `trash_captures`가 이 목록에 있으면서 게이트를 거는 선례가 있습니다.
- 기기 도구 분기(Agent.kt:474의 `setOf(...)`)에 `"system_setting"`을 넣습니다. set에는 게이트가 필요하므로, Device 안이 아니라 Agent 안에서 부릅니다(아래 스케치).
- `summarize()`(≈600행)에 `"system_setting" -> a.optString("action") + " " + a.optString("key").ifBlank { "전체" } + a.optString("value").let { if (it.isBlank()) "" else "=$it" }`를 추가합니다.
- BASE_PROMPT 74행의 시스템 기능 줄에 「설정값 확인·충전 중 화면 켜짐 = system_setting」을 넣습니다.

### 5. Kotlin 구현 스케치
```kotlin
// SysSettings.kt(새 파일) — 허용 목록 · 읽기 · 변환. 쓰기 자체는 게이트 뒤 Agent 에서만 부른다
object SysSettings {
    enum class Ns { GLOBAL, SYSTEM, SECURE, API }
    data class Spec(val ns: Ns, val label: String, val writable: Boolean = false,
                    val show: (String) -> String = { it }, val parse: ((String) -> Int?)? = null)
    private fun onOff(v: String) = when (v.trim().lowercase()) {
        "on","켜기","켜","true","1","켬" -> true; "off","끄기","꺼","false","0","끔" -> false; else -> null }
    val KEYS = linkedMapOf(
        "stay_on_while_plugged_in" to Spec(Ns.GLOBAL, "충전 중 화면 계속 켜짐", writable = true,
            show = { v -> if (v == "0" || v == "null") "꺼짐" else "켜짐($v: 1=AC 2=USB 4=무선)" },
            parse = { onOff(it)?.let { on -> if (on) 7 else 0 } }),
        "screen_off_timeout" to Spec(Ns.SYSTEM, "화면 자동 꺼짐", show = { v -> v.toLongOrNull()?.let { ms -> if (ms >= 60000) "${ms/60000}분" else "${ms/1000}초" } ?: v }),
        "development_settings_enabled" to Spec(Ns.GLOBAL, "개발자 옵션", show = ::bool),
        // … 표의 나머지(읽기 전용)
        "dark_mode" to Spec(Ns.API, "다크 모드"),
    )
    private fun bool(v: String) = when (v) { "1" -> "켜짐"; "0" -> "꺼짐"; else -> v }

    fun raw(ctx: Context, key: String): String? = try {
        val cr = ctx.contentResolver
        when (KEYS[key]?.ns) {
            Ns.GLOBAL -> Settings.Global.getString(cr, key)
            Ns.SYSTEM -> Settings.System.getString(cr, key)
            Ns.SECURE -> Settings.Secure.getString(cr, key)
            Ns.API -> when (key) { "dark_mode" -> ctx.getSystemService(UiModeManager::class.java).nightMode.toString(); else -> null }
            null -> null
        }
    } catch (e: SecurityException) { "읽기 불가(시스템이 막음)" }

    fun describe(ctx: Context, key: String) = KEYS.getValue(key).let { "${it.label}($key) = ${it.show(raw(ctx, key) ?: "없음")}" }

    fun canWriteSecure(ctx: Context) =
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun pluggedNow(ctx: Context): Boolean =
        (ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
}

// Agent.kt — 기기 도구 분기 안
"system_setting" -> systemSetting(a.optString("action"), a.optString("key"), a.optString("value"))

private fun systemSetting(action: String, key: String, value: String): String {
    if (action == "get") {
        if (key.isBlank()) return "설정값(허용 목록):\n" + SysSettings.KEYS.keys.joinToString("\n") { "- " + SysSettings.describe(svc, it) }
        if (key !in SysSettings.KEYS) return "실패: 허용 목록에 없는 키 $key — 허용: ${SysSettings.KEYS.keys.joinToString()}"
        return SysSettings.describe(svc, key)
    }
    if (action != "set") return "실패: 알 수 없는 동작 $action"
    val spec = SysSettings.KEYS[key] ?: return "실패: 허용 목록에 없는 키 $key"
    if (!spec.writable) return "실패: $key 는 읽기만 허용된다 — 바꾸려면 open_settings 로 화면을 열어 사용자에게 안내"
    val target = spec.parse?.invoke(value) ?: return "실패: 값 '$value' 을 이해 못함 — on/off 로"
    val before = SysSettings.raw(svc, key)
    if (before?.toIntOrNull()?.let { (it != 0) == (target != 0) } == true)
        return "이미 ${spec.show(before)} — 바꾸지 않음"          // 바꿀 게 없으면 묻지 않는다
    if (!SysSettings.canWriteSecure(svc))
        return "실패: 이 설정을 바꿀 권한(WRITE_SECURE_SETTINGS)이 없다 — 맥에서 ./dev.sh grant 가 필요하다고 사용자에게 말하고 finish(success=false). 화면에서 찾지 말 것(이 폰 개발자 옵션 목록에 안 보인다)"
    val q = gateQuestion(key, before, target)
    val ok = svc.confirm(q)                                    // 40초 무응답 = false
    trace.event("confirm", JSONObject().put("label", key).put("what", "설정 변경 ${before}→$target").put("ok", ok))
    if (!ok) return "사용자가 거부함(또는 응답 없음) — 바꾸지 않았다. 다시 묻지 말 것"
    val wrote = try { Settings.Global.putInt(svc.contentResolver, key, target) } catch (e: SecurityException) { return "실패: 시스템이 막음(${e.message?.take(60)})" }
    val after = SysSettings.raw(svc, key)                      // 다시 읽어 확인한 값만 보고
    trace.event("sys_setting", JSONObject().put("key", key).put("before", before).put("after", after).put("wrote", wrote))
    if (after?.toIntOrNull() != target) return "실패: 썼지만 값이 ${after} — 적용 안 됨"
    val plug = if (target != 0 && !SysSettings.pluggedNow(svc)) " · 지금은 충전 중이 아니라 충전기를 꽂으면 적용된다" else ""
    return "${spec.label} ${spec.show(before ?: "0")} → ${spec.show(after)}$plug. 되돌리기: system_setting(set, $key, off) 또는 「충전 중 화면 켜짐 꺼 줘」"
}
```
- 모델이 확인을 건너뛸 수 있는 인자(`confirmed` 같은 것)는 두지 않습니다. 쓰기는 반드시 `svc.confirm`의 true 뒤에만 합니다.
- 실패 문구는 기존처럼 "실패"로 시작하므로 `failStreak` 규칙이 그대로 적용됩니다.

### 6. 게이트 문구
기존 `gate()`는 「「X」 눌러도 할까요?」 모양이라 설정 변경에는 문장이 어색합니다. 그래서 `svc.confirm()`을 직접 부르고 trace 스키마만 같게 둡니다. 게이트 동작은 약해지지 않습니다.
- 켜기: `충전하는 동안 화면이 꺼지지 않게 바꿀까요? (충전 중 화면 계속 켜짐: 꺼짐 → 켜짐 · 켜 둔 채 두면 화면 잔상·발열이 생길 수 있어요)`
- 끄기: `충전 중에도 화면이 원래대로 꺼지게 바꿀까요? (충전 중 화면 계속 켜짐: 켜짐 → 꺼짐)`
- 음성은 앞에 "확인이 필요해요."가 자동으로 붙어 읽힙니다(`AgentService.kt:192`).

### 7. 설정 검색 요령
- 폰에서 읽기 전용으로 확인한 인텐트
  - `android.settings.APP_SEARCH_SETTINGS`와 `com.android.settings.action.SETTINGS_SEARCH`는 둘 다 `com.android.settings.intelligence/.search.SearchActivity`(versionName 17)로 연결됩니다.
  - `android.search.action.SEARCH_SETTINGS`는 구글 앱 설정으로 연결되므로 쓰지 않습니다.
  - `android.settings.SETTINGS_SEARCH`와 `com.samsung.android.settings.SEARCH`는 연결되는 곳이 없습니다.
- 모델이 지어낼 이름을 막는 별칭 제안: `SETTINGS_ALIAS`(Agent.kt:29)에 `"SETTINGS_SEARCH" to "android.settings.APP_SEARCH_SETTINGS"`, `"SEARCH_SETTINGS" to "android.settings.APP_SEARCH_SETTINGS"`를 추가합니다.
- 같은 목록의 다른 화면: DEVELOPMENT → `Settings$DevelopmentSettingsActivity`, SCREEN_TIMEOUT → `ScreenTimeoutActivity`, AUTO_ROTATE → `SmartAutoRotateSettingsActivity`, NIGHT_DISPLAY → `EyeComfortSettingsActivity`, DARK_THEME → `DarkThemeSettingsActivity`

`knowledge/overrides/com.android.settings.md` 교체 초안(첫 줄은 knowledge.md 「실측 절차」 요약으로 들어갑니다):
```
★ 설정값은 화면보다 system_setting 이 먼저 — 없는 항목을 찾아 스크롤로 위아래를 오가지 말 것(2026-09-26 개발자 옵션 16번 왕복)
- 「충전 중 화면 계속 켜짐(Stay awake)」 = system_setting(set, stay_on_while_plugged_in, on) 한 번. 이 폰 개발자 옵션 목록엔 안 보인다 — 화면에서 찾지 않는다
- 「~가 켜져 있나/몇 분이냐」는 system_setting(get) 으로 값을 읽고 답한다(화면을 열지 않는다)
- 설정 항목이 어디 있는지 모르면: open_settings(APP_SEARCH_SETTINGS) → type_text(핵심 낱말 하나, submit=true). 결과 줄에 경로(예: 디스플레이 > …)가 나오면 그 줄을 tap. 「결과 없음」이면 이 폰에 없는 항목 — 바로 finish(success=false)로 말하고 대안(SCREEN_TIMEOUT_SETTINGS 등)을 안내
- 목록을 직접 볼 땐 한 방향으로 끝까지 내리고, 끝에서 못 찾으면 거기서 멈춘다(다시 올라가며 찾지 않는다)
```
- knowledge.md는 `tools/build_knowledge.py`가 만드는 파일이라 직접 고치지 않습니다.
  - 바로가기 줄(182행 `settings_line`)은 `verify_settings.py`의 pass 결과에서 옵니다. `설정 검색=APP_SEARCH_SETTINGS`는 메인이 verify_settings로 실제로 열어 본 뒤 자동으로 들어가게 합니다.
  - `SUPERSEDED`(≈200행)에 한 줄을 추가합니다: `(r"^- 퀵 설정에 손전등", …)` 문구 끝에 「설정값 읽기·충전 중 화면 켜짐 = system_setting」을 덧붙입니다.

### 8. 메인이 실기로 검증할 항목
- V1: 매니페스트 선언 → 빌드·설치 → `pm grant … WRITE_SECURE_SETTINGS` → dumpsys에서 `granted=true`
- V2: 앱 권한으로 get(key 생략)을 실행해 표의 각 키가 읽히는지 확인합니다. 특히 `aod_mode`, `blue_light_filter`, `lock_screen_lock_after_timeout`은 「읽기 불가」가 나오는지 봅니다. 막히는 키는 목록에서 뺍니다.
- V3: 현재 값이 7이므로 set on을 하면 게이트 없이 「이미 켜짐」이 나오는지 확인합니다.
- V4: set off → 게이트 표시(logcat `confirm_ask`) → 「예」 → `settings get`이 0인지 확인합니다. 다시 set on → 7이 되는지, 충전 여부 문구가 맞는지 봅니다.
- V5: 게이트에서 거부할 때와 40초 동안 응답하지 않을 때 모두 값이 그대로인지 확인합니다.
- V6: 권한을 회수(`pm revoke`)한 상태에서 set하면 「권한 없음」 문구가 나오고 화면 탐색으로 넘어가지 않는지 봅니다.
- V7: `open_settings(APP_SEARCH_SETTINGS)`가 삼성 설정 검색 화면을 여는지 확인합니다. 입력칸 포커스, 결과 줄에 경로가 나오는지, 「결과 없음」 문구의 실제 표기, 개발자 옵션 항목이 검색에 잡히는지를 봅니다. 화면 캡처와 trace로 판정하고, 실행 중 dump는 하지 않습니다.
- V8: 평가 재현은 과거와 다른 문장으로 합니다(예: 「폰 충전하는 동안 안 꺼지게 해 줘」). 근거 run `20260926-232447-007`은 16단계 끝에 취소됐는데, 이게 2단계 이하(system_setting 한 번 + finish)로 줄어드는지 봅니다.

바꾼 저장소 파일은 없습니다. 임시 파일은 폰의 설정 목록 원본뿐이며 아래에 있습니다.
- (scratchpad — 세션 재개로 사라짐)/settings/global.txt
- (scratchpad — 세션 재개로 사라짐)/settings/system.txt
- (scratchpad — 세션 재개로 사라짐)/settings/secure.txt