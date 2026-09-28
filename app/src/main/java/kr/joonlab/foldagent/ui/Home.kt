package kr.joonlab.foldagent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.joonlab.foldagent.BuildConfig
import kr.joonlab.foldagent.Plain
import kr.joonlab.foldagent.Prefs
import kr.joonlab.foldagent.records.RecordSummary
import kotlin.math.roundToInt

/** 홈 바깥 동작 — MainActivity 가 채운다(서비스·음성·설정 화면 열기) */
class HomeActions(
    val stop: () -> Unit,
    /** 홈 입력줄·상태 카드 마이크 — 말이 끝나면 대화방 칩대로 보낸다 */
    val mic: () -> Unit,
    val openA11y: () -> Unit,
    /** 홈 입력줄 보내기 — 고른 칩대로 방을 바꾼 뒤 실행(RoomChoice.apply → startTask) */
    val send: (String) -> Unit,
)

// ───────────── 머리: 원형 표식 + 「폴드 에이전트」 + 검색 · 새 대화 · 설정 ─────────────

@Composable
fun HomeHeader(st: AppState) {
    val focus = remember { FocusRequester() }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (st.searchOpen) {
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Row(Modifier.weight(1f).height(44.dp).background(C.panel2, RoundedCornerShape(FaTokens.R.small.dp)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ic("search", C.dim, 17.dp)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (st.query.isEmpty()) Text("제목·요청에서 찾기", color = C.dim, fontSize = 16.sp, maxLines = 1)
                    BasicTextField(st.query, { st.query = it; st.reload() }, singleLine = true, cursorBrush = SolidColor(C.accent),
                        textStyle = TextStyle(color = C.text, fontSize = 16.sp, fontFamily = Pretendard),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus))
                }
            }
            IconBtn("x", "검색 닫기") { st.searchOpen = false; if (st.query.isNotEmpty()) { st.query = ""; st.reload() } }
        } else {
            Mark(30.dp)
            Spacer(Modifier.width(6.dp))
            Text("Fold Agent", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, letterSpacing = (-0.2).sp)
            IconBtn("search", "검색") { st.searchOpen = true }
            IconBtn("new", "새 대화", enabled = !st.busy) { st.startNew() }
            IconBtn("settings", "설정") { st.openSettings() }
        }
    }
}

// ───────────── 상태 카드(§1-1) — 에이전트 상태를 글로 ─────────────

@Composable
fun StatusCard(st: AppState, act: HomeActions) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(FaTokens.R.card.dp)
    val base = Modifier.padding(horizontal = 12.dp).fillMaxWidth()
    when {
        !st.svcOn -> Column(base.background(C.warnTint, shape).border(1.dp, C.warn.copy(alpha = .4f), shape).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ic("alert", C.warn, 18.dp)
                Text("접근성 서비스가 꺼져 있어요", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("「폴드 에이전트」를 켜야 화면을 읽고 대신 조작할 수 있어요.", color = C.dim, fontSize = 13.sp)
            TextBtn("접근성 설정 열기", color = C.onAccent, fill = C.accent, border = C.accent, onClick = act.openA11y)
        }
        // 확인 대기 — 호박 물듦. 실행 버튼은 여기 두지 않는다(확인은 화면 위 패널 한 곳에서만, §2-5)
        st.confirming -> Row(base.background(C.waitTint, shape).border(1.dp, C.glowWait.copy(alpha = .45f), shape).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(8.dp).background(C.glowWait, CircleShape))
            Column(Modifier.weight(1f)) {
                Text("확인을 기다려요", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("화면 위 패널에서 실행 또는 취소를 눌러 주세요", color = C.dim, fontSize = 13.sp)
            }
        }
        st.busy -> Row(base.background(C.accentTint, shape).padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BreathingDot(C.accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (st.hitl == "paused") HITL_PAUSED_TEXT else Plain.of(st.lastStatus.ifBlank { "작업 중" }), color = C.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val el = if (st.startedAt > 0) Fmt.dur(st.now - st.startedAt) else ""
                Text(listOf("${st.stepCount}단계", el).filter { it.isNotEmpty() }.joinToString(" · "), color = C.dim, fontSize = 12.sp)
            }
            HandBtn(st)
            StopBtn(onClick = act.stop)
        }
        // 유휴 — 입력은 아래 입력줄 하나로(여기 입력 알약을 두면 같은 「무엇을 해 드릴까요?」가 두 번 보였다, 2026-09-26 실기)
        else -> Row(base.background(C.panel, shape).padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(7.dp).background(C.ok, CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("준비됨 · ${Prefs.model(ctx)}", color = C.text, fontSize = 14.sp, maxLines = 1)
                Text("어디서든 손가락을 오므리면(핀치 인) 불러요", color = C.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconBtn("mic", "말하기", onClick = act.mic)
        }
    }
}

// ───────────── 원격 작업 칩 — 홈맥에 맡겨 두고 결과를 기다리는 작업(busy 와 별개) ─────────────

/**
 * 「⏳ 원격 작업 N개」 — 누르면 가장 최근에 맡긴 작업의 방을 연다. 끝나면 그 방에 결과 턴이 붙고 알림이 온다.
 * compact = 커버 가로 머리 줄 안의 작은 알약
 */
@Composable
fun RemoteJobsChip(st: AppState, compact: Boolean = false) {
    val jobs = st.remoteJobs
    if (jobs.isEmpty()) return
    val last = jobs.last()
    val label = "원격 작업 ${jobs.size}개"
    val open = { st.openRoom(last.sessionId) }
    if (compact) {
        Row(Modifier.height(32.dp).background(C.accentTint, RoundedCornerShape(16.dp)).clickable(onClickLabel = "맡긴 방 열기", onClick = open)
            .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Ic("clock", C.accentText, 14.dp)
            Text("${jobs.size}", color = C.accentText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        return
    }
    Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp).fillMaxWidth().heightIn(min = 44.dp)
        .background(C.panel, RoundedCornerShape(FaTokens.R.card.dp)).border(1.dp, C.border, RoundedCornerShape(FaTokens.R.card.dp))
        .clickable(onClickLabel = "맡긴 방 열기", onClick = open).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Ic("clock", C.accentText, 16.dp)
        Column(Modifier.weight(1f)) {
            Text(label, color = C.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val sub = last.title.ifBlank { last.skill.ifBlank { last.tool } } +
                (if (last.progress.isNotBlank()) " · ${last.progress}" else "") + " — 끝나면 알림"
            Text(sub, color = C.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Ic("right", C.dim, 14.dp)
    }
}

// ───────────── 필터 칩 ─────────────

@Composable
fun FilterChips(st: AppState) {
    val chips = buildList {
        add("all" to "전체"); add("today" to "오늘"); add("pinned" to "고정"); add("failed" to "실패")
        add("archived" to "보관함"); add("trash" to "휴지통")
        if (st.showTests) add("tests" to "시험")
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { (k, l) -> Chip(l, on = st.chip == k) { st.pickChip(k) } }
    }
}

// ───────────── 목록 ─────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordList(st: AppState, listState: LazyListState, act: HomeActions, modifier: Modifier = Modifier, descLines: Int = 2) {
    val ctx = LocalContext.current
    val trash = st.chip == "trash"
    var purgeAsk by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<RecordSummary?>(null) }
    val rows = st.rows
    // 맨 위를 보고 있었으면 목록이 다시 읽혀도 맨 위에 둔다 — 키 기준 위치 유지 때문에 새로 읽힌 뒤 중간 항목(오후 2:33)부터 열렸다(2026-09-26 실기)
    var atTop by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        // 사람이 굴릴 때만 갱신한다 — 다시 읽혀 위치가 밀린 것까지 「맨 위 아님」으로 치면 되돌리지 못한다
        androidx.compose.runtime.snapshotFlow { Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { (moving, i, off) -> if (moving) atTop = i == 0 && off == 0 }
    }
    LaunchedEffect(rows) { if (atTop && rows.isNotEmpty()) listState.scrollToItem(0) }
    LazyColumn(modifier.fillMaxSize(), state = listState) {
        if (trash && rows.isNotEmpty()) item(key = "trash-head") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${Prefs.trashDays(ctx)}일이 지나면 영구 삭제돼요", color = C.dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextBtn("휴지통 비우기 ${rows.size}", color = C.accentText) { purgeAsk = true }
            }
        }
        if (st.listLoaded && rows.isEmpty()) item(key = "empty") { EmptyState(st) }
        // 고정 묶음은 맨 위(전체·오늘·시험 보기에서만 — 보관함·휴지통은 고정이 의미 없다)
        val groupPinned = st.chip in setOf("all", "today", "tests")
        val pinned = if (groupPinned) rows.filter { it.pinned } else emptyList()
        // 날짜 머리는 이어진 묶음이어야 한다(같은 머리 키가 두 번 나오면 LazyColumn 이 죽는다) — 고정 먼저 정렬을 풀고 시각순으로
        val rest = (if (groupPinned) rows.filter { !it.pinned } else rows).sortedByDescending { it.updated }
        if (pinned.isNotEmpty()) {
            stickyHeader(key = "h-pin") { DayHead("고정") }
            pinned.forEach { r -> item(key = "p-" + r.id) { RecordRow(st, r, act, trash, descLines) { renaming = it } } }
        }
        // 머리 키는 표시 글자가 아니라 그날 0시(dayStart)로 — 「9월 20일」은 해가 바뀌면 또 나온다(키 중복 = 여는 즉시 죽음)
        var last = Long.MIN_VALUE
        rest.forEach { r ->
            val day = if (trash) -1L else Fmt.dayStart(r.updated)
            if (day != last) {
                last = day
                val h = if (trash) "휴지통" else Fmt.dayHead(r.updated)
                stickyHeader(key = "h-$day") { DayHead(h) }
            }
            item(key = r.id) { RecordRow(st, r, act, trash, descLines) { renaming = it } }
        }
        item(key = "tail") { Spacer(Modifier.height(24.dp)) }
    }
    if (purgeAsk) PurgeDialog(rows.size, rows.sumOf { it.turns }, onDismiss = { purgeAsk = false }) { st.purge(rows.map { it.id }) }
    renaming?.let { r -> RenameDialog(r.title, onDismiss = { renaming = null }) { st.rename(r.id, it) } }
}

@Composable
private fun DayHead(t: String) {
    Text(t, color = C.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().background(C.bg).padding(horizontal = 16.dp, vertical = 7.dp))
}

@Composable
private fun EmptyState(st: AppState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(52.dp).background(C.panel2, RoundedCornerShape(FaTokens.R.card.dp)), contentAlignment = Alignment.Center) {
            Ic(if (st.chip == "trash") "trash" else "chat", C.dim, 24.dp)
        }
        val msg = when {
            st.query.isNotBlank() -> "「${st.query.trim()}」에 맞는 대화가 없어요"
            st.chip == "trash" -> "휴지통이 비었어요"
            st.chip == "archived" -> "보관한 대화가 없어요"
            st.chip == "pinned" -> "고정한 대화가 없어요 — 대화를 길게 눌러 고정하세요"
            st.chip == "failed" -> "실패한 대화가 없어요"
            st.chip == "today" -> "오늘 시킨 일이 없어요"
            else -> "아직 시킨 일이 없어요. 손가락을 오므려(핀치 인) 불러 보세요"
        }
        Text(msg, color = C.dim, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        Text("build ${BuildConfig.BUILD_TIME}", color = C.dim.copy(alpha = .6f), fontSize = 11.sp)
    }
}

/**
 * 목록 줄 한 개(= 세션). 탭 = 대화방 · 길게 = 메뉴 · 왼쪽으로 밀기 = 휴지통(스낵바 실행 취소 5초).
 * 작업 중인 방은 물듦 + 원형 ■, 보관·휴지통은 막는다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordRow(st: AppState, r: RecordSummary, act: HomeActions, trash: Boolean, descLines: Int, onRename: (RecordSummary) -> Unit) {
    val ctx = LocalContext.current
    val running = st.isRunning(r.id)
    val selected = st.screen == "room" && st.roomId == r.id
    var menu by remember { mutableStateOf(false) }
    val accent = C.accent; val hair = C.panel2
    SwipeToTrash(enabled = !running && !trash, onSwipe = { st.trash(r.id) }) {
        Box {
            Row(Modifier.fillMaxWidth().background(when { running -> C.accentTint; selected -> C.panel; else -> C.bg })
                .drawBehind {
                    if (selected) drawRect(accent, size = Size(3.dp.toPx(), size.height))
                    drawRect(hair, topLeft = Offset(16.dp.toPx(), size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
                }
                .combinedClickable(onClick = { if (!trash) st.openRoom(r.id) else menu = true }, onLongClick = { menu = true })
                .padding(start = 16.dp, end = 12.dp, top = if (descLines < 2) 9.dp else 12.dp, bottom = if (descLines < 2) 9.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).background(if (running) C.accent.copy(alpha = .18f) else C.panel2, RoundedCornerShape(FaTokens.R.small.dp)),
                    contentAlignment = Alignment.Center) {
                    Ic(Fmt.toolIcon(r.lastTool), if (running) C.accentText else C.dim, 18.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(r.title.ifBlank { r.lastRequest.ifBlank { "(빈 대화)" } }, color = C.text, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(Fmt.rowTime(r.updated), color = C.dim, fontSize = 12.sp, maxLines = 1)
                    }
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val desc = if (running) Plain.of(st.lastStatus) else r.summary.ifBlank { r.lastRequest }
                        Text(desc, color = C.dim, fontSize = 13.sp, lineHeight = 18.sp, maxLines = descLines, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (!running && Fmt.failed(r.lastStatus)) Pill(Fmt.statusLabel(r.lastStatus), C.warn, C.warnTint)
                            if (!running && r.lastStatus == "cancel") Pill("멈춤", C.stop, C.panel2)
                            if (r.turns >= 2) Pill("${r.turns}턴", C.dim, C.panel2)
                            if (r.pinned) Ic("pin", C.accentText, 14.dp)
                        }
                    }
                    if (trash && r.trashedAt > 0) {
                        val left = Prefs.trashDays(ctx) - ((System.currentTimeMillis() - r.trashedAt) / 86_400_000L).toInt()
                        Text("남은 날 ${left.coerceAtLeast(0)}일", color = C.warn, fontSize = 12.sp)
                    }
                }
                if (running) StopBtn(onClick = act.stop)
                if (trash) Box(Modifier.size(44.dp).clickable(onClickLabel = "복원") { st.restore(r.id) }, contentAlignment = Alignment.Center) {
                    Ic("restore", C.text, 20.dp)
                }
            }
            RowMenu(st, r, running, trash, menu, onDismiss = { menu = false }, onRename = { onRename(r) })
        }
    }
}

@Composable
private fun RowMenu(st: AppState, r: RecordSummary, running: Boolean, trash: Boolean, open: Boolean,
                    onDismiss: () -> Unit, onRename: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss, containerColor = C.raised) {
        @Composable fun item(label: String, icon: String, enabled: Boolean = true, color: Color = C.text, f: () -> Unit) =
            DropdownMenuItem(text = { Text(label, color = if (enabled) color else C.dim.copy(alpha = .5f), fontSize = 15.sp) },
                leadingIcon = { Ic(icon, if (enabled) color else C.dim.copy(alpha = .5f), 18.dp) },
                enabled = enabled, onClick = { onDismiss(); f() })
        if (trash) item("복원", "restore") { st.restore(r.id) }
        else {
            item("이름 바꾸기", "pencil", f = onRename)
            item(if (r.pinned) "고정 풀기" else "고정", "pin") { st.pin(r.id, !r.pinned) }
            item(if (r.archived) "보관 풀기" else "보관", "archive", enabled = !running) { st.archive(r.id, !r.archived) }
            item("휴지통으로", "trash", enabled = !running, color = C.accentText) { st.trash(r.id) }
        }
    }
}

/** 왼쪽으로 35% 넘게 밀면 onSwipe — 줄은 제자리로 돌아온다(지워지면 목록을 다시 읽을 때 빠진다) */
@Composable
private fun SwipeToTrash(enabled: Boolean, onSwipe: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val off = remember { Animatable(0f) }
    var w by remember { mutableIntStateOf(1) }
    Box(Modifier.fillMaxWidth().onSizeChanged { w = it.width.coerceAtLeast(1) }) {
        if (off.value < -1f) Row(Modifier.matchParentSize().background(C.accentTint).padding(end = 20.dp),
            horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Ic("trash", C.accentText, 20.dp)
            Spacer(Modifier.width(6.dp))
            Text("휴지통", color = C.accentText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.offset { IntOffset(off.value.roundToInt(), 0) }.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectHorizontalDragGestures(
                onDragEnd = {
                    scope.launch {
                        if (off.value < -w * .35f) {
                            off.animateTo(-w.toFloat()); onSwipe(); delay(700); off.snapTo(0f)
                        } else off.animateTo(0f)
                    }
                },
                onDragCancel = { scope.launch { off.animateTo(0f) } },
            ) { ch, dx ->
                ch.consume()
                scope.launch { off.snapTo((off.value + dx).coerceIn(-w.toFloat(), 0f)) }
            }
        }) { content() }
    }
}

/**
 * 홈 화면 전체(목록 칸) — 머리 · 상태 카드 · 칩 · 목록. Composer 는 바깥.
 * compact = 커버 가로(751×475dp): 높이가 모자라 상태 카드를 머리 한 줄로 접고, 필터 칩 줄은 머리 ≡ 메뉴로, 목록 설명은 1줄.
 * (그대로 두면 머리 56 + 상태 카드 70 + 칩 54 + 입력줄 110 으로 목록이 두 줄밖에 안 보인다)
 */
@Composable
fun HomePane(st: AppState, listState: LazyListState, act: HomeActions, modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(modifier.fillMaxSize().background(C.bg)) {
        if (compact) CompactHeader(st, act)
        else {
            HomeHeader(st)
            StatusCard(st, act)
            RemoteJobsChip(st)
            FilterChips(st)
        }
        RecordList(st, listState, act, Modifier.weight(1f), descLines = if (compact) 1 else 2)
    }
}

private val chipNames = listOf("all" to "전체", "today" to "오늘", "pinned" to "고정", "failed" to "실패", "archived" to "보관함", "trash" to "휴지통")

/** 커버 가로 머리 — 표식 · 상태 한 줄(작업 중이면 ■ 정지) · ≡(필터) · 검색 · 새 대화 · 설정 */
@Composable
private fun CompactHeader(st: AppState, act: HomeActions) {
    // 검색 중에도 작업 중이면 ■ 정지를 머리 끝에 남긴다 — 상태 한 줄이 검색 칸으로 바뀌어 정지가 사라졌다(리뷰 지적, §0 불변식 2)
    if (st.searchOpen) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { HomeHeader(st) }
            if (st.busy) { StopBtn(36.dp, onClick = act.stop); Spacer(Modifier.width(8.dp)) }
        }
        return
    }
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Mark(26.dp)
        // 상태 한 줄 — 상태 카드와 같은 네 갈래(꺼짐·확인 대기·작업 중·유휴)
        Row(Modifier.weight(1f).height(40.dp).background(when {
                !st.svcOn -> C.warnTint; st.confirming -> C.waitTint; st.busy -> C.accentTint; else -> C.panel }, RoundedCornerShape(20.dp))
            .clickable(enabled = !st.svcOn, onClick = act.openA11y).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                !st.svcOn -> Ic("alert", C.warn, 15.dp)
                st.confirming -> Box(Modifier.size(7.dp).background(C.glowWait, CircleShape))
                st.busy -> BreathingDot(C.accent, 7.dp)
                else -> Box(Modifier.size(7.dp).background(C.ok, CircleShape))
            }
            val el = if (st.busy && st.startedAt > 0) " · ${st.stepCount}단계 · ${Fmt.dur(st.now - st.startedAt)}" else ""
            Text(when {
                    !st.svcOn -> "접근성 서비스가 꺼져 있어요 — 눌러서 켜기"
                    st.confirming -> "확인을 기다려요 — 화면 위 패널에서 실행/취소"
                    st.busy && st.hitl == "paused" -> HITL_PAUSED_TEXT
                    st.busy -> Plain.of(st.lastStatus.ifBlank { "작업 중" }) + el
                    else -> "준비됨 · ${Prefs.model(ctx)}"
                }, color = C.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (st.busy) HandBtn(st, 32.dp)
            if (st.busy) StopBtn(32.dp, onClick = act.stop)   // 정지는 어느 자세에서도 닿아야 한다(§0 불변식 2)
        }
        if (st.remoteJobs.isNotEmpty()) RemoteJobsChip(st, compact = true)
        Box {
            IconBtn("menu", "목록 거르기 — ${chipNames.firstOrNull { it.first == st.chip }?.second ?: "시험"}",
                tint = if (st.chip != "all") C.accentText else null) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = C.raised) {
                val names = if (st.showTests) chipNames + ("tests" to "시험") else chipNames
                names.forEach { (k, l) ->
                    DropdownMenuItem(text = { Text((if (st.chip == k) "✓ " else "") + l, color = if (st.chip == k) C.accentText else C.text, fontSize = 15.sp,
                        fontWeight = if (st.chip == k) FontWeight.SemiBold else FontWeight.Normal) },
                        onClick = { menu = false; st.pickChip(k) })
                }
            }
        }
        IconBtn("search", "검색") { st.searchOpen = true }
        IconBtn("new", "새 대화", enabled = !st.busy) { st.startNew() }
        IconBtn("settings", "설정") { st.openSettings() }
    }
}
