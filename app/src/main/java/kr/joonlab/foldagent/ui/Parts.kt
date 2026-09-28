package kr.joonlab.foldagent.ui

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import kr.joonlab.foldagent.Uploads

// ───────────── 작은 부품(참고 앱 ListUi 의 Chip·IconBtn 을 복사해 Ember 토큰으로 고침) ─────────────

@Composable
fun Chip(label: String, on: Boolean = false, h: Dp = 34.dp, onClick: () -> Unit) {
    Box(Modifier.height(h).background(if (on) C.accentTint else C.panel, RoundedCornerShape(h / 2))
        .border(1.dp, if (on) C.accent else C.border, RoundedCornerShape(h / 2))
        .clickable(onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (on) C.accentText else C.text, fontSize = 13.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

/** 둥근 사각 아이콘 버튼 — 최소 44dp 터치(§1-5) */
@Composable
fun IconBtn(icon: String, desc: String, size: Dp = 44.dp, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    Box(Modifier.size(size).clickable(enabled = enabled, onClickLabel = desc, onClick = onClick).alpha(if (enabled) 1f else .35f),
        contentAlignment = Alignment.Center) {
        Ic(icon, tint ?: C.text, 20.dp)
    }
}

/** 작은 알약(상태·턴 수) */
@Composable
fun Pill(text: String, fg: Color, bg: Color) {
    Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.background(bg, RoundedCornerShape(FaTokens.R.tag.dp)).padding(horizontal = 7.dp, vertical = 2.dp))
}

/** 글자 버튼(알약 테두리). danger = 붉은 글자(영구 삭제) */
@Composable
fun TextBtn(label: String, color: Color = C.text, border: Color = C.border, fill: Color = Color.Transparent,
            enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 44.dp).background(fill, RoundedCornerShape(22.dp)).border(1.dp, border, RoundedCornerShape(22.dp))
        .clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else .4f).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * 사람 개입(사용자 2026-09-28) — 작업 중이면 「✋」(넘겨받기: 멈추고 채팅 화면을 물려 에이전트가 보던 화면으로), 사용자 차례면 「▶」(이어서).
 * 화면 위 패널의 「✋ 내가 할게」·「▶ 이어서」와 같은 AgentService 입구. 멈추는 중(pending)엔 안 보인다. 말하기는 패널의 💬.
 */
@Composable
fun HandBtn(st: AppState, size: Dp = 40.dp) {
    if (!st.busy || st.confirming || st.hitl == "pending") return
    val paused = st.hitl == "paused"
    Box(Modifier.size(size).background(if (paused) C.accent else C.panel, CircleShape).border(1.5.dp, if (paused) C.accent else C.border, CircleShape)
        .clickable(onClickLabel = if (paused) "이어서" else "내가 할게") {
            val svc = kr.joonlab.foldagent.AgentService.instance ?: return@clickable
            if (paused) svc.resumeTask()
            // 넘겨받으면 채팅 화면을 물린다 — 사용자가 조작할 화면은 에이전트가 보던 앱이다
            else if (svc.pauseTask()) kr.joonlab.foldagent.MainActivity.current?.moveTaskToBack(true)
        }, contentAlignment = Alignment.Center) {
        Text(if (paused) "▶" else "✋", color = if (paused) C.onAccent else C.text, fontSize = (size.value * .4f).sp)
    }
}

/** 사용자 차례일 때 상태 줄 문장 */
const val HITL_PAUSED_TEXT = "⏸ 내 차례 — 조작하거나 말한 뒤 ▶ 이어서"

/** 원형 정지(■) — 진행 중 카드·상태 카드·대화방 진행 턴이 같은 cancelTask() 를 부른다(§0 불변식 2) */
@Composable
fun StopBtn(size: Dp = 40.dp, onClick: () -> Unit) {
    Box(Modifier.size(size).background(C.panel, CircleShape).border(1.5.dp, C.accent, CircleShape)
        .clickable(onClickLabel = "정지", onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * .32f).background(C.accent, RoundedCornerShape(2.dp)))
    }
}

/** 숨쉬는 점 — 테두리 빛·패널 점과 같은 주기(FaTokens.GLOW_PERIOD_MS, 반주기 이징 0.4,0,0.2,1) */
@Composable
fun BreathingDot(color: Color, d: Dp = 8.dp) {
    val half = (FaTokens.GLOW_PERIOD_MS / 2).toInt()
    val a by rememberInfiniteTransition(label = "breath").animateFloat(.25f, 1f,
        infiniteRepeatable(tween(half, easing = CubicBezierEasing(.4f, 0f, .2f, 1f)), RepeatMode.Reverse), label = "breath")
    Box(Modifier.size(d).alpha(a).background(color, CircleShape))
}

/** 원형 표식(에이전트 얼굴 — 이름·아바타 없이 작은 원, §9 7-12) */
@Composable
fun Mark(d: Dp = 22.dp) {
    // 홈 화면 아이콘과 같은 그림(사용자 2026-09-28) — 적응형 아이콘은 painterResource 로 못 읽어 배경·전경 벡터를 겹친다.
    // 108 격자 중 가운데 72 가 보이는 영역이라 1.5배로 그리고 둥근 사각으로 자른다(삼성 홈 아이콘 모양)
    Box(Modifier.size(d).clip(RoundedCornerShape(d * .3f)), contentAlignment = Alignment.Center) {
        Image(painterResource(kr.joonlab.foldagent.R.drawable.ic_launcher_background), null, Modifier.requiredSize(d * 1.5f))
        Image(painterResource(kr.joonlab.foldagent.R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(d * 1.5f))
    }
}

// ───────────── 입력(Composer): 둥근 입력칸 16sp + 마이크 + 원형 보내기(§1-1) ─────────────

/**
 * 입력줄. value/onValue = 이 줄의 글(홈 = st.draft · 대화방 = st.roomDraft), voiceHere = 지금 듣는 말이 이 줄 것인가
 * (펼친 화면엔 홈·대화방 입력줄이 함께 보여 둘 다 「듣는 중」으로 그리면 어느 쪽으로 가는지 모른다).
 * header = 입력칸 위 한 줄(홈 입력줄의 대화방 칩).
 * attach = 📎 첨부 칩을 둘 줄("choice" 홈 · "room" 대화방, null = 첨부 없음) — 칩은 AppState.filesOf(attach), 보낼 때 올리기는 MainActivity(AppState.sendWithFiles).
 */
@Composable
fun Composer(st: AppState, hint: String, enabled: Boolean, primary: Boolean, disabledWhy: String = "",
             value: String = st.draft, onValue: (String) -> Unit = { st.draft = it }, voiceHere: Boolean = true,
             hPad: Dp = 12.dp, header: (@Composable () -> Unit)? = null, attach: String? = null,
             onSend: (String) -> Unit, onMic: () -> Unit, onMicCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    val listening = st.listening && voiceHere
    val heard = if (voiceHere) st.heard else ""
    val files = attach?.let { st.filesOf(it) }.orEmpty()
    val uploading = st.uploadingLine != null
    // 새로 그려질 때 예전 요청으로 포커스를 뺏지 않게(방을 열 때마다 키보드가 뜨지 않게) 본 값부터 기억한다
    var seen by remember { mutableIntStateOf(st.focusTick) }
    LaunchedEffect(st.focusTick) { if (primary && st.focusTick != seen) { seen = st.focusTick; runCatching { focus.requestFocus() } } }
    Column(Modifier.fillMaxWidth().background(C.bg).padding(horizontal = hPad, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 답을 읽는 동안 — 언제든 멈출 수 있게(2026-09-27 사용자). 다른 앱 위에서는 상태 패널의 같은 버튼
        if (st.speaking) Row(Modifier.fillMaxWidth().background(C.accentTint, RoundedCornerShape(FaTokens.R.card.dp))
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BreathingDot(C.accent)
            Text("답변을 읽는 중", color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextBtn("■ 읽기 멈춤", color = C.accentText, border = C.accent) {
                kr.joonlab.foldagent.AgentService.instance?.stopSpeaking(); st.speaking = false
            }
        }
        header?.invoke()
        // 음성 미리보기 — 준비 중/듣는 중/끝 기다리는 중
        if (listening || heard.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (listening) BreathingDot(C.accent)
            Text(heard, color = if (listening) C.text else C.dim, fontSize = 14.sp, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (listening) TextBtn("취소", onClick = onMicCancel)
        }
        if (!enabled && disabledWhy.isNotEmpty()) Text(disabledWhy, color = C.dim, fontSize = 12.sp)
        if (attach != null && files.isNotEmpty()) FileChips(st, attach, files)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (attach != null) AttachBtn(st, attach, enabled = enabled && !uploading && !listening && files.size < Uploads.MAX_FILES)
            Box(Modifier.weight(1f).heightIn(min = 48.dp).background(C.panel2, RoundedCornerShape(FaTokens.R.card.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, color = C.dim, fontSize = 16.sp, maxLines = 1)
                BasicTextField(value, onValue, enabled = enabled, maxLines = 4, cursorBrush = SolidColor(C.accent),
                    textStyle = TextStyle(color = C.text, fontSize = 16.sp, fontFamily = Pretendard),   // 16 미만이면 폰이 확대한다
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus))
            }
            // 마이크: 듣는 중이면 ■(= 다 말했다)
            Box(Modifier.size(48.dp).background(if (listening) C.accentTint else C.panel2, CircleShape)
                .border(1.dp, if (listening) C.accent else Color.Transparent, CircleShape)
                .clickable(enabled = enabled || listening, onClickLabel = if (listening) "말하기 끝" else "말하기", onClick = onMic)
                .alpha(if (enabled || listening) 1f else .4f), contentAlignment = Alignment.Center) {
                if (listening && st.voiceFinishing) Text("…", color = C.accentText, fontSize = 18.sp)
                else if (listening) Box(Modifier.size(14.dp).background(C.accent, RoundedCornerShape(3.dp)))
                else Ic("mic", C.text, 22.dp)
            }
            // 파일만 있고 글이 없으면 눌러서 안내만(빠른 입력 카드와 같은 문구). 올리는 중엔 막는다
            val canSend = enabled && !uploading && (value.isNotBlank() || files.isNotEmpty())
            Box(Modifier.size(48.dp).background(if (canSend && value.isNotBlank()) C.accent else C.panel2, CircleShape)
                .clickable(enabled = canSend, onClickLabel = "보내기") {
                    val t = value.trim()
                    if (t.isNotEmpty()) { onValue(""); onSend(t) } else st.toast(Uploads.HINT_NO_TEXT)
                }, contentAlignment = Alignment.Center) {
                Ic("send", if (canSend && value.isNotBlank()) C.onAccent else C.dim, 22.dp)
            }
        }
    }
}

/**
 * 📎 — 누르면 작은 메뉴: 「사진·동영상」(포토 피커) · 「파일」(문서 고르기, 모든 종류). 둘 다 여러 개.
 * 고른 uri 는 곧바로 올리지 않고 칩으로만 둔다 — 보내기 때 올린다(AppState.sendWithFiles).
 */
@Composable
private fun AttachBtn(st: AppState, line: String, enabled: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val docs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { st.addFiles(line, it) }
    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Uploads.MAX_FILES)) { st.addFiles(line, it) }
    Box {
        Box(Modifier.size(width = 36.dp, height = 48.dp).clickable(enabled = enabled, onClickLabel = "파일 첨부") { menu = true }
            .alpha(if (enabled) 1f else .35f), contentAlignment = Alignment.Center) {
            Ic("clip", C.text, 21.dp)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = C.raised) {
            DropdownMenuItem(text = { Text("🖼  사진·동영상", color = C.text, fontSize = 15.sp) }, onClick = {
                menu = false
                runCatching { media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                    .onFailure { st.toast("사진 고르기를 열지 못했어요") }
            })
            DropdownMenuItem(text = { Text("📄  파일", color = C.text, fontSize = 15.sp) }, onClick = {
                menu = false
                runCatching { docs.launch(arrayOf("*/*")) }.onFailure { st.toast("파일 고르기를 열지 못했어요") }
            })
        }
    }
}

/** 입력줄 위 첨부 칩 — 이름·크기·상태(너무 큼·%·✓·오류), 누르면 빼기(올리는 중엔 안 됨). 글자 규칙은 빠른 입력 카드와 공용(Uploads.chipLabel) */
@Composable
private fun FileChips(st: AppState, line: String, files: List<Uploads.Pending>) {
    @Suppress("UNUSED_VARIABLE") val tick = st.filesTick   // 칩 안 상태가 바뀌면 다시 그리게 읽어 둔다
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        files.forEach { f ->
            val bad = Uploads.isBad(f)
            Box(Modifier.height(32.dp).background(if (bad) C.warnTint else C.panel, RoundedCornerShape(16.dp))
                .border(1.dp, if (bad) C.warn else C.border, RoundedCornerShape(16.dp))
                .clickable(enabled = st.uploadingLine == null, onClickLabel = "첨부 ${f.name} 빼기") { st.removeFile(line, f) }
                .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(Uploads.chipLabel(f), color = if (bad) C.warn else C.text, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

/**
 * 대화방 칩 한 줄(A안, 2026-09-27 사용자 확정) — 가로 스크롤, 하나만 선택, 고른 칩은 accent 로 채움 + ✓.
 * [이어서 · 방](1분 안일 때만) [＋ 새 대화] [최근 방 3개]. 말하는 중에도 바꿀 수 있다(듣기는 그대로).
 */
@Composable
fun RoomChips(st: AppState, modifier: Modifier = Modifier) {
    val c = st.choice ?: return
    val sel = st.pickedIndex()
    val scroll = rememberScrollState()
    // 고른 칩이 화면 밖이면(최근 방 칩) 보이게 — 처음엔 맨 앞(기본값이 맨 앞 칩)
    LaunchedEffect(sel, c.options.size) { if (sel <= 0) scroll.scrollTo(0) }
    Row(modifier.fillMaxWidth().horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        c.options.forEachIndexed { i, o ->
            val on = i == sel
            Box(Modifier.height(34.dp).background(if (on) C.accent else C.panel, RoundedCornerShape(17.dp))
                .border(1.dp, if (on) C.accent else C.border, RoundedCornerShape(17.dp))
                .clickable(onClickLabel = if (o.id == null) "새 대화로 보내기" else "이 대화에 이어서 보내기") { st.pick(o) }
                .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text((if (on) "✓ " else "") + o.label, color = if (on) C.onAccent else C.text, fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

// ───────────── 대화상자 ─────────────

@Composable
fun FaDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth().background(C.raised, RoundedCornerShape(FaTokens.R.panel.dp))
            .border(1.dp, C.border, RoundedCornerShape(FaTokens.R.panel.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, color = C.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    FaDialog("이름 바꾸기", onDismiss) {
        Box(Modifier.fillMaxWidth().background(C.panel2, RoundedCornerShape(FaTokens.R.small.dp)).padding(14.dp)) {
            BasicTextField(v, { v = it.take(60) }, singleLine = true, cursorBrush = SolidColor(C.accent),
                textStyle = TextStyle(color = C.text, fontSize = 16.sp, fontFamily = Pretendard),
                modifier = Modifier.fillMaxWidth().focusRequester(focus))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            TextBtn("취소", onClick = onDismiss)
            TextBtn("저장", color = C.onAccent, fill = C.accent, border = C.accent, enabled = v.isNotBlank()) { onSave(v); onDismiss() }
        }
    }
}

/**
 * 휴지통 비우기 확인 — 되돌릴 수 없으니 개수를 보이고, 「영구 삭제」는 빨강 **글자** 버튼(채우지 않음)·기본 포커스 없음(§1-2).
 */
@Composable
fun PurgeDialog(count: Int, runs: Int, onDismiss: () -> Unit, onPurge: () -> Unit) {
    FaDialog("휴지통 비우기", onDismiss) {
        Text("${count}개 대화와 실행 기록 ${runs}개를 영구 삭제합니다. 되돌릴 수 없어요.", color = C.text, fontSize = 14.sp, lineHeight = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            TextBtn("취소", onClick = onDismiss)
            TextBtn("영구 삭제", color = C.accentText, border = Color.Transparent) { onPurge(); onDismiss() }
        }
    }
}

// ───────────── 스낵바(아래, 5초 실행 취소) ─────────────

@Composable
fun SnackBar(st: AppState, modifier: Modifier = Modifier) {
    val s = st.snack ?: return
    Row(modifier.padding(12.dp).widthIn(max = 520.dp).fillMaxWidth().background(C.raised, RoundedCornerShape(FaTokens.R.card.dp))
        .border(1.dp, C.border, RoundedCornerShape(FaTokens.R.card.dp)).padding(start = 16.dp, end = 6.dp)
        .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(s.text, color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        if (s.action != null) Box(Modifier.heightIn(min = 44.dp).clickable { st.snack = null; s.onAction?.invoke() }
            .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(s.action, color = C.accentText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 설정 묶음 제목 */
@Composable
fun SectionHead(t: String) {
    Text(t, color = C.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

/** 세 칸 중 하나 고르기(테마·빛 세기·빛 주기) */
@Composable
fun Segmented(options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    Row(Modifier.background(C.panel2, RoundedCornerShape(FaTokens.R.small.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEach { (k, label) ->
            val on = k == value
            Box(Modifier.heightIn(min = 40.dp).width(72.dp).background(if (on) C.raised else Color.Transparent, RoundedCornerShape(8.dp))
                .border(1.dp, if (on) C.border else Color.Transparent, RoundedCornerShape(8.dp)).clickable { onPick(k) },
                contentAlignment = Alignment.Center) {
                Text(label, color = if (on) C.text else C.dim, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** 한 줄 켜고 끄기(글자 + 오른쪽 스위치 모양) */
@Composable
fun ToggleRow(label: String, sub: String = "", on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onChange(!on) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = C.text, fontSize = 15.sp)
            if (sub.isNotEmpty()) Text(sub, color = C.dim, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Box(Modifier.width(44.dp).height(26.dp).background(if (on) C.accent else C.panel2, RoundedCornerShape(13.dp))
            .border(1.dp, if (on) C.accent else C.border, RoundedCornerShape(13.dp)).padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(20.dp).background(if (on) C.onAccent else C.dim, CircleShape))
        }
    }
}

