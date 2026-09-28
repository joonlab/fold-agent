package kr.joonlab.foldagent.ui

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kr.joonlab.foldagent.Plain
import kr.joonlab.foldagent.Prefs

class AppActions(val home: HomeActions, val room: RoomActions)

/**
 * 뿌리 화면 — 자세(Posture.kt)마다 배치를 고른다.
 * 커버 세로 = 한 화면씩(홈 → 대화방·설정, 뒤로) · 커버 가로 = 같은 1칸이되 홈을 축약 ·
 * 펼침 = 목록 360 | 대화방 · 펼침 돌림 = 목록 300 | 대화방 · 반접기 테이블톱 = 위 대화방 / 아래 입력줄·마이크·정지.
 * 상태는 전부 AppState(액티비티가 들고 있음)에 있고 configChanges 로 액티비티가 다시 만들어지지 않아
 * 접기·펴기·돌리기 때 선택 방·스크롤·입력 중 글·듣기가 그대로다.
 */
@Composable
fun FoldAgentApp(st: AppState, act: AppActions) {
    // 에이전트 상태 읽기 — status() 는 이벤트가 없어서 0.5초마다 공개 필드를 본다(경과 초 표시도 이걸로)
    LaunchedEffect(Unit) { while (true) { st.poll(); delay(500) } }
    FaTheme {
        val cfg = LocalConfiguration.current
        val real = Posture.of(cfg.screenWidthDp, cfg.screenHeightDp, st.halfOpen)
        val forced = st.forcedPosture
        val p = forced ?: real
        SideEffect { st.posture = p }
        LaunchedEffect(p) { Log.i("FoldAgent", "posture=$p (실제 $real ${cfg.screenWidthDp}x${cfg.screenHeightDp} half=${st.halfOpen})") }
        BoxWithConstraints(Modifier.fillMaxSize().background(C.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
            // 디버그 강제 자세 — 그 자세의 창 크기(dp)로 잘라 가운데에(실기에서 접지 않고 배치를 캡처하려고)
            val frame = if (forced != null) {
                val (w, h) = Posture.sizeOf(forced)
                Modifier.align(Alignment.Center).size(min(w.dp, maxWidth), min(h.dp, maxHeight)).border(1.dp, C.accent)
            } else Modifier.fillMaxSize()
            Box(frame) {
                when (p) {
                    Posture.OPEN, Posture.OPEN_ROT -> Wide(st, act, rot = p == Posture.OPEN_ROT)
                    Posture.TABLE -> Tabletop(st, act, forced = forced != null)
                    else -> Narrow(st, act, compact = p == Posture.COVER_LAND)
                }
                // 입력줄 위 — 홈 입력줄은 칩 줄만큼 높다
                val chipsShown = p != Posture.COVER && p != Posture.COVER_LAND || st.screen == "home"
                SnackBar(st, Modifier.align(Alignment.BottomCenter).imePadding().padding(bottom = if (chipsShown) 118.dp else 72.dp))
                if (forced != null) Text("강제 자세 · $forced", color = C.onAccent, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.TopEnd).background(C.accent, RoundedCornerShape(bottomStart = 6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

/**
 * 홈 입력줄 — 위에 대화방 칩 한 줄(A안). 보내면 칩대로 방을 바꾼 뒤 실행(MainActivity.sendViaChoice).
 * 커버 가로에서 키보드가 뜨면 칩 줄을 숨긴다(475dp 높이에 키보드·칩·입력줄이 다 들어가면 목록이 0줄).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeComposer(st: AppState, act: AppActions, primary: Boolean, hPad: Dp = 12.dp) {
    val hideChips = WindowInsets.isImeVisible && st.posture == Posture.COVER_LAND
    Composer(st, hint = "무엇을 해 드릴까요?", enabled = st.svcOn && !st.busy, primary = primary,
        disabledWhy = if (st.busy) "작업 중 — 끝나거나 정지한 뒤에 보낼 수 있어요" else "",
        voiceHere = st.voiceVia == "choice", hPad = hPad,
        header = if (hideChips) null else ({ RoomChips(st) }), attach = "choice",
        onSend = act.home.send, onMic = act.home.mic, onMicCancel = act.room.micCancel)
}

@Composable
private fun Narrow(st: AppState, act: AppActions, compact: Boolean) {
    BackHandler(enabled = st.screen != "home") { st.goHome() }
    when (st.screen) {
        "room" -> {
            val id = st.roomId
            if (id != null) RoomPane(st, id, st.roomScroll(id), showBack = true, act = act.room)
            else LaunchedEffect(Unit) { st.goHome() }
        }
        "settings" -> SettingsPane(st, showBack = true)
        else -> Column(Modifier.fillMaxSize()) {
            HomePane(st, st.listScroll, act.home, Modifier.weight(1f), compact = compact)
            HomeComposer(st, act, primary = true)
        }
    }
}

/**
 * 2칸 — 왼쪽 = 홈(목록 + 칩 입력줄), 오른쪽 = 대화방(그 방 입력줄)·설정.
 * 펼침 돌림(704dp 폭)은 목록 300·좌우 여백을 줄인다. 레일 끌기 폭은 자세마다 따로 저장(list-unfoldW · list-rotW).
 */
@Composable
private fun Wide(st: AppState, act: AppActions, rot: Boolean) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    val key = if (rot) "list-rotW" else "list-unfoldW"
    var listW by remember(key) { mutableFloatStateOf(prefs.getFloat(key, if (rot) 300f else 360f)) }
    var dragging by remember { mutableStateOf(false) }
    val railW = if (rot) 10f else 14f
    val minW = if (rot) 260f else 280f
    // 오른쪽 칸은 늘 무언가를 보인다 — 좁은 화면에서 마지막으로 연 방이 있으면 그 방(자세가 바뀌어도 선택 유지), 없으면 지금 이어 가는 방
    LaunchedEffect(st.screen, st.roomId) {
        if (st.screen == "home" || st.screen == "room") {
            val id = st.roomId
            if (id != null) { if (st.screen == "home") st.openRoom(id) } else st.openCurrentRoom()
        }
    }
    BackHandler(enabled = st.screen == "settings") { st.goHome() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxW = (maxWidth.value - railW - (if (rot) 300f else 320f)).coerceAtLeast(minW)
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(listW.coerceIn(minW, maxW).dp).fillMaxHeight()) {
                HomePane(st, st.listScroll, act.home, Modifier.weight(1f))
                HomeComposer(st, act, primary = false, hPad = if (rot) 8.dp else 12.dp)
            }
            // 가운데 레일 — 좌우로 끌어 목록 폭을 바꾸고 놓으면 이 자세의 키로 저장
            Column(Modifier.width(railW.dp).fillMaxHeight().background(if (dragging) C.accentTint else C.panel)
                .pointerInput(maxW, key) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false; prefs.edit().putFloat(key, listW).apply() },
                        onDragCancel = { dragging = false },
                    ) { ch, dx -> ch.consume(); listW = (listW.coerceIn(minW, maxW) + dx / density).coerceIn(minW, maxW) }
                },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                repeat(5) { Box(Modifier.size(4.dp).background(if (dragging) C.accent else C.dim.copy(alpha = .5f), CircleShape)) }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    st.screen == "settings" -> SettingsPane(st, showBack = false)
                    st.roomId != null -> st.roomId?.let { id -> RoomPane(st, id, st.roomScroll(id), showBack = false, act = act.room, tight = rot) }
                }
            }
        }
    }
}

/**
 * 반접기 테이블톱(펼침 돌림 + 힌지 30~150°) — 위: 대화방 읽기 / 힌지 띠 / 아래: 상태·정지 · 큰 마이크 · 칩 입력줄.
 * 위 칸은 작업 중이면 그 방, 아니면 고른 칩의 방(새 대화 칩이면 빈 안내) — 칩을 바꾸면 위가 따라 바뀐다.
 * 힌지 위치는 화면 한가운데(androidx.window 없음, HingeSensor.hingeYInWindow). 참고 앱 Tabletop 을 옮겨 고친 것.
 */
@Composable
private fun Tabletop(st: AppState, act: AppActions, forced: Boolean) {
    val activity = LocalContext.current as? Activity
    val cfg = LocalConfiguration.current
    val hingePx = remember(cfg.screenWidthDp, cfg.screenHeightDp, forced) { if (forced) null else activity?.let { HingeSensor.hingeYInWindow(it) } }
    var originY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    BackHandler(enabled = st.screen == "settings") { st.goHome() }
    // 위 칸이 칩의 방(또는 작업 중인 방)을 보이려고 st.openRoom 으로 roomId 를 바꾼다 — 테이블톱을 떠나면
    // 들어오기 전 방·화면으로 되돌린다. 안 그러면 펼침에서 보던 방 X 가 반접기→펼침 한 번에 칩의 방 Y 로 바뀐다(리뷰 지적).
    DisposableEffect(Unit) {
        val savedRoom = st.roomId
        val savedScreen = st.screen
        onDispose {
            if (st.roomId != savedRoom) {
                if (savedRoom != null) st.openRoom(savedRoom) else { st.roomId = null; st.turns = null; st.turnsFor = null }
            }
            st.screen = savedScreen
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { originY = it.positionInWindow().y }) {
        val band = 24.dp
        val total = maxHeight
        val mid = (hingePx?.let { with(density) { (it - originY).toDp() } } ?: (total / 2)).coerceIn(total * .3f, total * .7f)
        val showId = if (st.busy) st.runningId else st.pickedOption()?.id
        LaunchedEffect(showId) { if (showId != null && st.roomId != showId) st.openRoom(showId) }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(mid - band / 2)) {
                when {
                    st.screen == "settings" -> SettingsPane(st, showBack = true)
                    showId != null && st.roomId == showId ->
                        RoomPane(st, showId, st.roomScroll(showId), showBack = false, act = act.room, showComposer = false, tight = true)
                    else -> Column(Modifier.fillMaxSize().background(C.panel), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
                        Mark(36.dp)
                        Text("새 대화 — 아래에서 말하거나 입력하세요", color = C.dim, fontSize = 14.sp)
                    }
                }
            }
            HingeBand(Modifier.fillMaxWidth().height(band))
            Column(Modifier.fillMaxWidth().weight(1f)) {
                TableStatus(st, act)
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { BigMic(st, act) }
                HomeComposer(st, act, primary = true, hPad = 16.dp)
            }
        }
    }
}

/** 힌지 띠 — 이 높이에는 글자를 놓지 않는다(접힌 선이 글자를 가른다) */
@Composable
private fun HingeBand(modifier: Modifier) {
    val line = C.border
    Box(modifier.background(C.panel2).drawBehind {
        val t = 1.dp.toPx()
        drawRect(line, size = Size(size.width, t))
        drawRect(line, topLeft = Offset(0f, size.height - t), size = Size(size.width, t))
    })
}

/** 테이블톱 아래 칸 첫 줄 — 상태 한 줄 + 작업 중이면 큰 ■ 정지(손이 닿는 아래 칸에, §0 불변식 2) */
@Composable
private fun TableStatus(st: AppState, act: AppActions) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp).height(52.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            !st.svcOn -> Ic("alert", C.warn, 16.dp)
            st.confirming -> Box(Modifier.size(8.dp).background(C.glowWait, CircleShape))
            st.busy -> BreathingDot(C.accent)
            else -> Box(Modifier.size(8.dp).background(C.ok, CircleShape))
        }
        Text(when {
                !st.svcOn -> "접근성 서비스가 꺼져 있어요"
                st.confirming -> "확인을 기다려요 — 화면 위 패널에서 실행/취소"
                st.busy && st.hitl == "paused" -> HITL_PAUSED_TEXT
                st.busy -> Plain.of(st.lastStatus.ifBlank { "작업 중" }) + if (st.startedAt > 0) " · ${st.stepCount}단계 · ${Fmt.dur(st.now - st.startedAt)}" else ""
                else -> "준비됨 · ${Prefs.model(ctx)}"
            }, color = C.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (st.busy) HandBtn(st, 48.dp)
        if (st.busy) StopBtn(48.dp, onClick = act.home.stop)
        else IconBtn("settings", "설정") { st.openSettings() }
    }
}

/** 테이블톱 가운데 큰 마이크(72dp) — 책상에 세워 두고 손끝으로 누르기 좋게. 입력줄의 마이크와 같은 동작 */
@Composable
private fun BigMic(st: AppState, act: AppActions) {
    val listening = st.listening && st.voiceVia == "choice"
    val enabled = (st.svcOn && !st.busy) || listening
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(72.dp).background(if (listening) C.accent else C.panel2, CircleShape)
            .border(1.dp, if (listening) C.accent else C.border, CircleShape)
            .clickable(enabled = enabled, onClickLabel = if (listening) "말하기 끝" else "말하기", onClick = act.home.mic),
            contentAlignment = Alignment.Center) {
            if (listening) Box(Modifier.size(22.dp).background(C.onAccent, RoundedCornerShape(4.dp)))
            else Ic("mic", if (enabled) C.text else C.dim, 30.dp)
        }
        Text(if (listening) "다 말했으면 한 번 더" else "눌러서 말하기", color = C.dim, fontSize = 12.sp, fontWeight = FontWeight.Normal)
    }
}
