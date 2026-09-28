package kr.joonlab.foldagent.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joonlab.foldagent.AgentService
import kr.joonlab.foldagent.AssistantClient
import kr.joonlab.foldagent.BuildConfig
import kr.joonlab.foldagent.Prefs

// 삼성 측면 버튼 설정 화면(One UI). 앱이 대신 바꾸지 않고 화면만 열어 준다(§1-7)
private const val SIDE_KEY_SETTINGS = "com.samsung.android.intent.action.SIDE_KEY_SETTINGS"
private const val GOOD_LOCK = "com.samsung.android.goodlock"
private const val HOME_UP = "com.samsung.android.app.homestar"

/** 토큰은 끝 4자만 보인다(계약 §0-6 — 값 전체를 화면·로그에 두지 않는다) */
internal fun tokenTail(t: String) = if (t.isBlank()) "없음" else "…" + t.takeLast(4)

/** health 응답을 한 줄로 — 설정 화면과 디버그 인텐트(MainActivity)가 같이 쓴다 */
internal fun healthLine(r: AssistantClient.Reply): String =
    if (r.ok) {
        val d = r.data
        val test = d?.optBoolean("mailTestOnly", false) == true
        val ops = d?.optJSONArray("ops")?.length() ?: 0
        "연결됨 · 메일 시험 제한 ${if (test) "켜짐" else "꺼짐"} · op ${ops}개" + (d?.optString("version")?.takeIf { it.isNotBlank() }?.let { " · v$it" } ?: "")
    } else "실패: ${r.code ?: "unknown"}" + (r.message?.takeIf { it.isNotBlank() }?.let { " — ${it.take(160)}" } ?: "")

private fun installed(ctx: Context, pkg: String) =
    runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.isSuccess

/** 설정(§1-7) — 커버에선 한 화면, 펼침에선 오른쪽 칸 */
@Composable
fun SettingsPane(st: AppState, showBack: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    fun open(i: Intent, what: String) {
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { st.toast("$what 을(를) 열 수 없어요") }
    }
    var autoListen by remember { mutableStateOf(Prefs.autoListen(ctx)) }
    var speak by remember { mutableStateOf(Prefs.speakAnswers(ctx)) }
    var popupListen by remember { mutableStateOf(Prefs.popupAutoListen(ctx)) }
    var glow by remember { mutableStateOf(Prefs.glowStrength(ctx)) }
    var period by remember { mutableStateOf(Prefs.glowPeriodMs(ctx)) }
    var llm by remember { mutableStateOf(Prefs.llmTitles(ctx)) }
    var askWay by remember { mutableStateOf(Prefs.askWay(ctx)) }
    var contSec by remember { mutableStateOf(Prefs.continueWindowSec(ctx)) }
    var rate by remember { mutableStateOf(Prefs.ttsRate(ctx)) }
    var pitch by remember { mutableStateOf(Prefs.ttsPitch(ctx)) }
    var ttsLen by remember { mutableStateOf(Prefs.ttsLength(ctx)) }
    var linger by remember { mutableStateOf(Prefs.panelLingerSec(ctx)) }
    var trashDays by remember { mutableStateOf(Prefs.trashDays(ctx)) }
    fun f1(v: Float) = String.format(java.util.Locale.US, "%.2f", v)
    var base by remember { mutableStateOf(Prefs.base(ctx)) }
    var model by remember { mutableStateOf(Prefs.model(ctx)) }
    var key by remember { mutableStateOf(Prefs.apiKey(ctx)) }
    // 홈 비서 — 토큰 칸은 늘 비어 있다(저장된 값을 다시 채우지 않는다). 저장된 건 끝 4자만 보인다
    var aBase by remember { mutableStateOf(Prefs.assistantBase(ctx)) }
    var aTokenInput by remember { mutableStateOf("") }
    var aTokenSaved by remember { mutableStateOf(tokenTail(Prefs.assistantToken(ctx))) }
    var aHealth by remember { mutableStateOf<Pair<Boolean, String>?>(null) }   // (ok, 문구)
    var aChecking by remember { mutableStateOf(false) }
    // 켜고 끄기는 누르는 즉시 저장 — 모델 칸은 편집 중일 수 있으니 저장된 값으로(Prefs.save 가 다섯 값을 한꺼번에 쓴다)
    fun saveSpeech(a: Boolean, s: Boolean) {
        Prefs.save(ctx, Prefs.base(ctx), Prefs.model(ctx), Prefs.apiKey(ctx), a, s)
        if (!s) AgentService.instance?.stopSpeaking()
    }

    Column(modifier.fillMaxSize().background(C.bg)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = if (showBack) 2.dp else 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconBtn("back", "뒤로") { st.goHome() }
            Text("설정", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (!showBack) IconBtn("x", "닫기") { st.goHome() }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            SectionHead("부르기")
            Text("Good Lock › Home Up › 멀티핑거 제스처에서 「핀치 인 → 앱 실행 → 에이전트 빠른 입력」으로 두면 " +
                "어느 화면에서든 빠른 입력이 뜹니다. 한 번 더 핀치 인 하면 말하기 끝.",
                color = C.text, fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (installed(ctx, GOOD_LOCK)) TextBtn("Good Lock 열기") {
                    ctx.packageManager.getLaunchIntentForPackage(GOOD_LOCK)?.let { open(it, "Good Lock") }
                }
                // Home Up 이 없으면 측면 버튼(두 번 누르기 → 앱 열기)이 대안 입구
                if (!installed(ctx, HOME_UP)) TextBtn("측면 버튼 설정 열기") { open(Intent(SIDE_KEY_SETTINGS), "측면 버튼 설정") }
            }

            SectionHead("말하기")
            ToggleRow("열자마자 듣기", "앱을 부르면 바로 마이크를 켭니다", autoListen) { autoListen = it; saveSpeech(it, speak) }
            ToggleRow("답변 읽기", "끝나면 결과를 소리 내어 읽습니다", speak) { speak = it; saveSpeech(autoListen, it) }
            ToggleRow("빠른 입력 자동 듣기", "빠른 입력 카드가 뜨면 바로 듣습니다", popupListen) { popupListen = it; Prefs.setPopupAutoListen(ctx, it) }

            ChoiceLine("음성 속도", "답을 읽는 빠르기(배)",
                listOf("0.80" to "0.8×", "1.00" to "1×", "1.20" to "1.2×", "1.50" to "1.5×", "2.00" to "2×"), f1(rate)) {
                rate = it.toFloat(); Prefs.setTtsRate(ctx, rate)
            }
            ChoiceLine("음성 높이", null, listOf("0.85" to "낮게", "1.00" to "보통", "1.15" to "높게"), f1(pitch)) {
                pitch = it.toFloat(); Prefs.setTtsPitch(ctx, pitch)
            }
            ChoiceLine("음성 답변 길이", "첫 문장만 읽으면 나머지는 화면으로 봅니다", listOf("all" to "전부", "first" to "첫 문장만"), ttsLen) {
                ttsLen = it; Prefs.setTtsLength(ctx, it)
            }
            Spacer(Modifier.height(4.dp))
            TextBtn("들어 보기") {
                val svc = AgentService.instance
                if (svc == null) st.toast("접근성 서비스가 연결돼야 들을 수 있어요")
                else if (!Prefs.speakAnswers(ctx)) st.toast("「답변 읽기」를 켜야 들을 수 있어요")
                else svc.speak("이 빠르기와 높이로 답을 읽어 드려요. 읽는 동안에는 언제든 멈출 수 있어요.")
            }

            SectionHead("대화")
            ChoiceLine("대화 이어 가기", "이 시간 안에 다시 시키면 방금 대화에 이어 붙입니다. 끄면 늘 새 대화가 기본이고, 칩으로 언제든 고를 수 있어요",
                listOf("0" to "끔", "30" to "30초", "60" to "1분", "180" to "3분", "300" to "5분", "600" to "10분"), contSec.toString()) {
                contSec = it.toInt(); Prefs.setContinueWindowSec(ctx, contSec)
            }
            ToggleRow("방법 고르기", "길이 여러 개면 처음에 골라요 · ${Prefs.chooseWaitSec(ctx)}초 지나면 추천안", askWay) { askWay = it; Prefs.setAskWay(ctx, it) }

            SectionHead("표시")
            ChoiceLine("끝난 뒤 패널", "결과를 화면 위에 남겨 두는 시간(읽는 중이면 다 읽을 때까지)",
                listOf("5" to "5초", "10" to "10초", "20" to "20초"), linger.toString()) {
                linger = it.toInt(); Prefs.setPanelLingerSec(ctx, linger)
            }
            SettingLine("테마") {
                Segmented(listOf("system" to "기기", "light" to "밝게", "dark" to "어둡게"), ThemeState.mode) {
                    ThemeState.mode = it; Prefs.setTheme(ctx, it)
                }
            }
            SettingLine("빛 세기") {
                Segmented(listOf("0.6" to "약", "0.8" to "중", "1.0" to "강"), String.format(java.util.Locale.US, "%.1f", glow)) {
                    glow = it.toFloat(); Prefs.setGlowStrength(ctx, glow)
                }
            }
            SettingLine("빛 주기") {
                Segmented(listOf("4400" to "느림", "3600" to "보통", "2800" to "빠름"), period.toString()) {
                    period = it.toLong(); Prefs.setGlowPeriodMs(ctx, period)
                }
            }

            SectionHead("기록")
            ToggleRow("시험 기록 보기", "맥에서 adb 로 보낸 시험 대화를 「시험」 칩으로 봅니다", st.showTests) {
                st.showTests = it; Prefs.setShowTests(ctx, it)
                if (!it && st.chip == "tests") st.pickChip("all")
            }
            ToggleRow("LLM 제목", "대화 제목을 모델로 다듬습니다(규칙 제목은 늘 바로)", llm) { llm = it; Prefs.setLlmTitles(ctx, it) }
            ChoiceLine("휴지통 보관", "지나면 휴지통의 대화와 실행 기록을 영구 삭제합니다", listOf("7" to "7일", "30" to "30일", "90" to "90일"), trashDays.toString()) {
                trashDays = it.toInt(); Prefs.setTrashDays(ctx, trashDays)
            }

            SectionHead("모델")
            Field("엔드포인트(/v1 까지)", base) { base = it }
            Field("모델", model) { model = it }
            Field("API 키(프록시는 비워 둠)", key) { key = it }
            Spacer(Modifier.height(8.dp))
            TextBtn("모델 설정 저장", color = C.onAccent, fill = C.accent, border = C.accent) {
                Prefs.save(ctx, base, model, key, autoListen, speak)
                base = Prefs.base(ctx); model = Prefs.model(ctx)
                st.toast("저장했어요")
            }

            SectionHead("홈 비서")
            Text("홈맥의 일정·메일·기억·브리핑을 부르는 연결입니다. 토큰은 홈맥 web/.env.local 의 PHONE_API_TOKEN.",
                color = C.dim, fontSize = 12.sp, lineHeight = 17.sp)
            Field("주소(/api/phone 까지)", aBase) { aBase = it }
            Field("토큰(저장된 것: $aTokenSaved — 바꿀 때만 입력)", aTokenInput, secret = true) { aTokenInput = it }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextBtn("비서 설정 저장", color = C.onAccent, fill = C.accent, border = C.accent) {
                    // https 만 — 토큰이 평문으로 나가지 않게(리뷰 safety#4). 빈 칸은 기본 주소로 돌아간다
                    if (aBase.isNotBlank() && !Prefs.assistantBaseOk(aBase)) { st.toast("주소는 https:// 로 시작해야 해요"); return@TextBtn }
                    Prefs.setAssistantBase(ctx, aBase)
                    if (aTokenInput.isNotBlank()) Prefs.setAssistantToken(ctx, aTokenInput)
                    aTokenInput = ""
                    aBase = Prefs.assistantBase(ctx); aTokenSaved = tokenTail(Prefs.assistantToken(ctx))
                    aHealth = null
                    st.toast("저장했어요")
                }
                TextBtn(if (aChecking) "확인 중…" else "연결 확인") {
                    if (aChecking) return@TextBtn
                    val b = Prefs.assistantBase(ctx); val t = Prefs.assistantToken(ctx)
                    if (t.isBlank()) { aHealth = false to "토큰을 먼저 저장하세요"; return@TextBtn }
                    aChecking = true; aHealth = null
                    val ui = android.os.Handler(android.os.Looper.getMainLooper())
                    // 네트워크는 메인 스레드에서 못 한다 — 결과만 메인으로 돌려 준다
                    Thread({
                        val r = runCatching { AssistantClient(b, t).health() }
                            .getOrElse { AssistantClient.Reply(false, null, "exec_failed", it.javaClass.simpleName) }
                        ui.post { aChecking = false; aHealth = r.ok to healthLine(r) }
                    }, "assistant-health").start()
                }
            }
            aHealth?.let { (ok, line) ->
                Text(line, color = if (ok) C.ok else C.stop, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
            }

            SectionHead("권한")
            TextBtn("접근성 설정 열기") { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "접근성 설정") }

            Spacer(Modifier.height(24.dp))
            Text("build ${BuildConfig.BUILD_TIME}", color = C.dim, fontSize = 11.sp)
        }
    }
}

/** 이름·설명 아래에 고르는 칩 한 줄(가로 스크롤) — 선택지가 많아 한 줄 오른쪽에 안 들어가는 설정용 */
@Composable
private fun ChoiceLine(label: String, desc: String?, options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = C.text, fontSize = 15.sp)
        if (desc != null) Text(desc, color = C.dim, fontSize = 12.sp, lineHeight = 17.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (k, l) -> Chip(l, on = k == value) { onPick(k) } }
        }
    }
}

@Composable
private fun SettingLine(label: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        control()
    }
}

@Composable
private fun Field(hint: String, v: String, secret: Boolean = false, onChange: (String) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(hint, color = C.dim, fontSize = 12.sp)
        Spacer(Modifier.width(2.dp))
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).background(C.panel2, RoundedCornerShape(FaTokens.R.small.dp)).padding(12.dp)) {
            BasicTextField(v, onChange, singleLine = true, cursorBrush = SolidColor(C.accent),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = TextStyle(color = C.text, fontSize = 16.sp, fontFamily = Pretendard), modifier = Modifier.fillMaxWidth())
        }
    }
}
