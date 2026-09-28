package kr.joonlab.foldagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.joonlab.foldagent.Plain
import kr.joonlab.foldagent.records.Attachment
import kr.joonlab.foldagent.records.Attachments
import kr.joonlab.foldagent.records.TurnCard

class RoomActions(
    val stop: () -> Unit,
    /** 현재 방에 보내기(MainActivity.run) — 방 전환은 AppState.selectThen 이 먼저 한다 */
    val send: (String) -> Unit,
    val mic: () -> Unit,
    val micCancel: () -> Unit,
)

/**
 * 대화방(§1-3) — 세션 하나. 요청은 오른쪽 말풍선, 단계는 한 줄 묶음(펼치기), 확인 기록은 점선 칩, 결과는 왼쪽 카드.
 * 진행 중이면 listeners 의 step 이 실시간으로 붙고 끝에 ■ 정지.
 */
@Composable
fun RoomPane(st: AppState, id: String, listState: LazyListState, showBack: Boolean, act: RoomActions, modifier: Modifier = Modifier,
             showComposer: Boolean = true, tight: Boolean = false) {
    // tight = 펼침 돌림(704dp 폭에 2칸) — 좌우 여백을 줄여 말풍선 폭을 번다
    val turns = if (st.turnsFor == id) st.turns.orEmpty() else emptyList()
    val sum = st.rows.find { it.id == id }
    val live = st.isRunning(id)
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val open = remember(id) { mutableStateMapOf<String, Boolean>() }   // 펼친 단계 묶음(run)
    val title = sum?.title?.takeIf { it.isNotBlank() } ?: turns.firstOrNull()?.request?.take(24)
        ?: if (live && st.liveRequest.isNotBlank()) st.liveRequest.take(24) else "새 대화"

    Column(modifier.fillMaxSize().background(C.bg)) {
        // ── 머리: 뒤로 · 제목 2줄(탭 = 이름 바꾸기) · 메타 · ⋮
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = if (showBack) 2.dp else 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconBtn("back", "뒤로") { st.goHome() }
            Column(Modifier.weight(1f).clickable(enabled = turns.isNotEmpty(), onClickLabel = "이름 바꾸기") { renaming = true }
                .padding(vertical = 4.dp)) {
                Text(title, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 21.sp)
                val meta = buildList {
                    turns.firstOrNull()?.let { add(Fmt.whenLong(it.t.takeIf { t -> t > 0 } ?: sum?.created ?: 0L)) }
                    turns.firstOrNull()?.source?.let { Fmt.source(it) }?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    if (turns.isNotEmpty()) add("${turns.size}턴")
                    turns.sumOf { it.ms }.takeIf { it > 0 }?.let { add(Fmt.dur(it)) }
                    if (id == st.currentId) add("이어 가는 중")
                }
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), color = C.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconBtn("more", "메뉴", enabled = turns.isNotEmpty()) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = C.raised) {
                    @Composable fun item(label: String, icon: String, enabled: Boolean = true, color: Color = C.text, f: () -> Unit) =
                        DropdownMenuItem(text = { Text(label, color = if (enabled) color else C.dim.copy(alpha = .5f), fontSize = 15.sp) },
                            leadingIcon = { Ic(icon, if (enabled) color else C.dim.copy(alpha = .5f), 18.dp) },
                            enabled = enabled, onClick = { menu = false; f() })
                    item("이름 바꾸기", "pencil") { renaming = true }
                    val pinned = sum?.pinned == true
                    item(if (pinned) "고정 풀기" else "고정", "pin") { st.pin(id, !pinned) }
                    val archived = sum?.archived == true
                    item(if (archived) "보관 풀기" else "보관", "archive", enabled = !live) { st.archive(id, !archived) }
                    item("휴지통으로", "trash", enabled = !live, color = C.accentText) { st.trash(id) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.panel2))
        // 이 방에서 홈맥에 맡겨 두고 결과를 기다리는 작업 — 끝나면 여기 결과 턴이 붙고 알림이 온다(목록 밖 줄이라 스크롤 식과 무관)
        val remote = st.remoteJobsIn(id)
        if (remote > 0) Row(Modifier.fillMaxWidth().background(C.accentTint).padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ic("clock", C.accentText, 14.dp)
            Text("원격 작업 ${remote}개 진행 중 — 끝나면 알림, 결과는 이 방에", color = C.text, fontSize = 12.5.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }

        // ── 본문
        // 열면 맨 아래. 자세가 바뀌어 다시 그려져도(같은 내용이면) 다시 내리지 않는다 — 읽던 자리 유지
        val itemCount = (if (turns.isEmpty() && !live) 1 else 0) +
            turns.sumOf { 2 + (if (it.steps.isNotEmpty()) 1 else 0) + (if (it.confirms.isNotEmpty()) 1 else 0) + (if (it.attachments.isNotEmpty()) 1 else 0) } +
            (if (live) 2 + (if (st.liveRequest.isNotBlank()) 1 else 0) else 0)
        val sig = "$itemCount/${st.liveSteps.size}/${turns.lastOrNull()?.run}"
        LaunchedEffect(id, sig) {
            if (itemCount > 0 && st.autoScrolled[id] != sig) {
                st.autoScrolled[id] = sig
                listState.scrollToItem(itemCount - 1, 100_000)   // 넘친 오프셋은 목록 끝에서 잘린다 = 마지막 항목 아래끝
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (tight) 8.dp else 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (turns.isEmpty() && !live) item(key = "empty") {
                Text(if (st.turnsFor == id) "무엇을 할까요? 아래에 말하거나 입력하세요.\n이 대화에 이어서 기억해요." else "불러오는 중…",
                    color = C.dim, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp))
            }
            turns.forEachIndexed { i, t ->
                val k = "$i-${t.run}"
                item(key = "q-$k") { RequestBubble(t.request, t.source, t.t, t.userFiles, st.thumbs) }
                if (t.steps.isNotEmpty()) item(key = "s-$k") {
                    StepsGroup(t.steps.map { it.tool }, t.steps.map { "${Fmt.tool(it.tool)} · ${it.summary}" }, open[k] == true) { open[k] = open[k] != true }
                }
                if (t.confirms.isNotEmpty()) item(key = "c-$k") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { t.confirms.forEach { ConfirmChip(it.question, it.result, it.t) } }
                }
                item(key = "r-$k") { ResultCard(st, t) }
                // 첨부(이미지 썸네일·파일·링크) — 결과 카드 바로 아래 한 항목. 위 itemCount 식에 같이 센다
                if (t.attachments.isNotEmpty()) item(key = "a-$k") { AttachList(st, t.attachments) }
            }
            if (live) {
                if (st.liveRequest.isNotBlank()) item(key = "live-q") { RequestBubble(st.liveRequest, "", st.startedAt, st.liveFiles, st.thumbs) }
                item(key = "live-s") {
                    val tools = st.liveSteps.map { it.substringBefore(": ") }
                    if (tools.isNotEmpty()) StepsGroup(tools, st.liveSteps.map { l ->
                        val tl = l.substringBefore(": "); "${Fmt.tool(tl)} · ${l.substringAfter(": ", "")}" }, true) {}
                }
                item(key = "live-now") { LiveRow(st, act.stop) }
            }
        }

        // 테이블톱에선 입력줄이 아래 칸(힌지 아래)에 따로 있다
        if (showComposer) {
        val canSend = st.svcOn && !st.busy
        Composer(st, hint = "이어서 시키기…", enabled = canSend, primary = true,
            value = st.roomDraft, onValue = { st.roomDraft = it }, voiceHere = st.voiceVia == "room", hPad = if (tight) 8.dp else 12.dp, attach = "room",
            disabledWhy = when { !st.svcOn -> "접근성 서비스가 꺼져 있어요"; st.busy -> "작업 중 — 끝나거나 정지한 뒤에 보낼 수 있어요"; else -> "" },
            onSend = { text -> st.selectThen(id) { act.send(text) } },
            onMic = { if (st.listening) act.mic() else st.selectThen(id) { act.mic() } }, onMicCancel = act.micCancel)
        }
    }
    if (renaming) RenameDialog(title, onDismiss = { renaming = false }) { st.rename(id, it) }
}

// ───────────── 턴 부품 ─────────────

@Composable
private fun RequestBubble(text: String, source: String, t: Long, files: List<kr.joonlab.foldagent.records.UserFile> = emptyList(),
                          thumbs: Map<String, androidx.compose.ui.graphics.ImageBitmap> = emptyMap()) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        SelectionContainer {
            Text(Plain.of(text), color = C.onAccent, fontSize = 15.sp, lineHeight = 21.sp,
                modifier = Modifier.widthIn(max = 520.dp).padding(start = 48.dp)
                    .background(C.accent, RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)).padding(horizontal = 14.dp, vertical = 10.dp))
        }
        // 함께 보낸 이미지·영상 — 앱 입력줄에서 보낸 것은 올릴 때 만든 작은 썸네일이 이 화면이 살아 있는 동안만 있다(원본 uri 권한은 사라진다)
        val shots = files.mapNotNull { f -> f.fileId?.let { thumbs[it] } }
        if (shots.isNotEmpty()) Row(Modifier.padding(start = 48.dp, top = 4.dp, end = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            shots.forEach { b ->
                Image(b, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
            }
        }
        // 함께 보낸 파일(계약 INPUTS §1) — 이름·크기 한 줄씩. 원본은 폰에 없다(홈맥에 올린 것) — 열기 버튼 없음
        files.forEach { f ->
            Text("📄 ${f.title}" + (f.size?.let { " · ${sizeLabel(it)}" } ?: ""), color = C.dim, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 520.dp).padding(start = 48.dp, top = 3.dp, end = 2.dp))
        }
        Row(Modifier.padding(top = 3.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (source.isNotEmpty()) Ic(Fmt.sourceIcon(source), C.dim, 12.dp)
            if (t > 0) Text(Fmt.clock(t), color = C.dim, fontSize = 11.sp)
        }
    }
}

/** 「단계 6개 · 앱 열기 · 탭 ×3 · 입력」 한 줄 — 누르면 단계 줄이 시간순으로 펼쳐진다 */
@Composable
private fun StepsGroup(tools: List<String>, lines: List<String>, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(end = 32.dp)) {
        Row(Modifier.heightIn(min = 36.dp).clickable(onClick = onToggle).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Ic(if (expanded) "down" else "right", C.dim, 14.dp)
            Text(Fmt.stepsLine(tools), color = C.dim, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (expanded) Column(Modifier.padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            lines.forEach { l ->
                var full by remember { mutableStateOf(false) }
                Text(l, color = C.dim, fontSize = 12.sp, lineHeight = 17.sp, maxLines = if (full) 40 else 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { full = !full })   // 잘린 단계 줄은 눌러서 펼친다
            }
        }
    }
}

/** 확인 게이트 기록 — 점선 칩. 여기서 다시 실행하는 버튼은 없다(기록일 뿐) */
@Composable
private fun ConfirmChip(question: String, result: String, t: Long) {
    val line = C.glowWait.copy(alpha = .7f)
    val res = when (result) { "ok" -> "실행함"; "timeout" -> "거부(40초 무응답)"; else -> "거부" }
    Text("확인 · ${Plain.of(question)} → $res" + if (t > 0) " (${Fmt.clock(t)})" else "", color = C.dim, fontSize = 12.sp, lineHeight = 17.sp,
        modifier = Modifier.padding(end = 32.dp).drawBehind {
            drawRoundRect(line, style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
                cornerRadius = CornerRadius(FaTokens.R.small.dp.toPx()))
        }.padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
private fun ResultCard(st: AppState, t: TurnCard) {
    Column(Modifier.fillMaxWidth().padding(end = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.widthIn(max = 560.dp).background(C.raised, RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp))
            .border(1.dp, C.panel2, RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (Fmt.failed(t.status)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Ic("alert", C.warn, 14.dp); Pill(Fmt.statusLabel(t.status), C.warn, C.warnTint)
            }
            if (t.status == "cancel") Pill("⏹ 멈춤", C.stop, C.panel2)
            // 가벼운 마크다운(목록·굵게·코드·링크) — 링크를 누르면 브라우저
            MdText(t.final.ifBlank { "(답 없음)" }, C.text, onOpenFail = { st.toast("링크를 열 앱이 없어요") })
        }
        // 잘했어/틀렸어 — 평가 전만. 평가 후엔 작은 표시
        val r = t.rating ?: st.rated[t.run]
        // 맡긴 작업의 결과 턴(source=job)은 에이전트 실행이 아니라 평가하지 않는다
        if (t.status != "cancel" && t.run.isNotBlank() && t.source != "job") {
            if (r == null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextBtn("잘했어") { st.rate(t.run, true) }
                TextBtn("틀렸어") { st.rate(t.run, false) }
            } else Text(if (r) "👍 잘했어요" else "👎 틀렸어요", color = C.dim, fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun LiveRow(st: AppState, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (st.confirming) C.waitTint else C.accentTint, RoundedCornerShape(FaTokens.R.card.dp))
        .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (st.confirming) Box(Modifier.size(8.dp).background(C.glowWait, androidx.compose.foundation.shape.CircleShape)) else BreathingDot(C.accent)
        Column(Modifier.weight(1f)) {
            Text(if (st.confirming) "확인을 기다려요 — 화면 위 패널에서 실행/취소" else if (st.hitl == "paused") HITL_PAUSED_TEXT else Plain.of(st.lastStatus.ifBlank { "작업 중" }),
                color = C.text, fontSize = 13.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${st.stepCount}단계" + if (st.startedAt > 0) " · ${Fmt.dur(st.now - st.startedAt)}" else "", color = C.dim, fontSize = 11.5.sp)
        }
        HandBtn(st)
        StopBtn(onClick = onStop)
    }
    Spacer(Modifier.height(4.dp))
}

// ───────────── 첨부(계약 cc-capabilities §1-3) ─────────────

/**
 * 썸네일 캐시 — 방을 오가거나 목록을 굴려도 다시 디코딩하지 않게(키 = uri@px).
 * 개수가 아니라 **바이트**로 묶는다(32MB) — 개수(48)로 묶으면 큰 이미지 몇 장으로 수백 MB 가 쌓인다(리뷰 지적 · OOM).
 * 크게 보기(THUMB_CACHE_MAX_PX 초과)는 캐시에 넣지 않는다 — 한 장이 수 MB~수십 MB 다.
 */
private const val THUMB_CACHE_MAX_PX = 1024
private val thumbCache = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap) = (value.width * value.height * 4).coerceAtLeast(1)
}

/** 줄여 읽은 이미지 — 디코딩은 IO 스레드(메인 금지), 결과만 상태로. 못 읽으면 null(자리표시) */
@Composable
private fun rememberBitmap(a: Attachment, maxPx: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val ctx = LocalContext.current.applicationContext
    val key = "${a.uri}@$maxPx"
    val cacheable = maxPx <= THUMB_CACHE_MAX_PX
    val st = produceState(if (cacheable) thumbCache.get(key) else null, key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            Attachments.decode(ctx, a, maxPx)?.asImageBitmap()?.also { if (cacheable) thumbCache.put(key, it) }
        }
    }
    return st.value
}

/** 다른 앱으로 — 없으면 알림 줄. 인텐트가 없으면(파일이 지워짐) 그렇게 말한다 */
private fun launch(ctx: android.content.Context, st: AppState, i: android.content.Intent?, what: String) {
    if (i == null) { st.toast("$what 없어요 — 파일이 지워졌을 수 있어요"); return }
    runCatching { ctx.startActivity(i) }.onFailure { st.toast("열 수 있는 앱이 없어요") }
}

@Composable
private fun AttachList(st: AppState, list: List<Attachment>) {
    val ctx = LocalContext.current
    var big by remember { mutableStateOf<Attachment?>(null) }
    val imgs = list.filter { it.isImage }
    val others = list.filter { !it.isImage }
    Column(Modifier.fillMaxWidth().padding(end = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (imgs.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            imgs.forEach { a -> Thumb(a) { big = a } }
        }
        others.forEach { a ->
            val sub = if (a.isLink) Uri.parse(a.url).host.orEmpty() else listOfNotNull(a.mime, a.size?.let { sizeLabel(it) }).joinToString(" · ")
            Row(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = 48.dp).background(C.panel, RoundedCornerShape(10.dp))
                .border(1.dp, C.panel2, RoundedCornerShape(10.dp))
                .clickable(onClickLabel = if (a.isLink) "브라우저로 열기" else "열기") {
                    launch(ctx, st, Attachments.viewIntent(ctx, a), if (a.isLink) "링크가" else "파일이")
                }.padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Ic(if (a.isLink) "search" else "note", C.accentText, 16.dp)
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(a.title, color = C.text, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (sub.isNotBlank()) Text(sub, color = C.dim, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconBtn("send", "공유") { launch(ctx, st, Attachments.shareIntent(ctx, a), "공유할 파일이") }
            }
        }
    }
    big?.let { a -> ImageDialog(st, a) { big = null } }
}

@Composable
private fun Thumb(a: Attachment, onClick: () -> Unit) {
    val px = with(LocalDensity.current) { 132.dp.roundToPx() }
    val bmp = rememberBitmap(a, px)
    // 크기를 먼저 고정한다 — 디코딩이 늦게 끝나도 목록 높이가 안 바뀌어 맨 아래 자동 스크롤이 어긋나지 않는다
    Box(Modifier.size(132.dp).clip(RoundedCornerShape(10.dp)).background(C.panel2).clickable(onClickLabel = "크게 보기", onClick = onClick),
        contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, a.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(8.dp)) {
            Ic("camera", C.dim, 20.dp)
            Text(a.title, color = C.dim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 크게 보기 — 화면 긴 변 정도로 줄여 읽는다. 갤러리에서 열기·공유 */
@Composable
private fun ImageDialog(st: AppState, a: Attachment, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cfg = LocalConfiguration.current
    val px = with(LocalDensity.current) { maxOf(cfg.screenWidthDp, cfg.screenHeightDp).dp.roundToPx() }.coerceAtMost(2048)
    val bmp = rememberBitmap(a, px)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .92f)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.title, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconBtn("x", "닫기", tint = Color.White, onClick = onDismiss)
            }
            Box(Modifier.weight(1f).fillMaxWidth().clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                if (bmp != null) Image(bmp, a.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Text("불러오는 중… (계속 안 보이면 지워졌거나 읽을 수 없는 이미지예요)", color = Color.White.copy(alpha = .7f), fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextBtn("갤러리에서 열기", color = Color.White, border = Color.White.copy(alpha = .5f)) {
                    launch(ctx, st, Attachments.viewIntent(ctx, a), "이미지가")
                }
                TextBtn("공유", color = Color.White, border = Color.White.copy(alpha = .5f)) {
                    launch(ctx, st, Attachments.shareIntent(ctx, a), "공유할 이미지가")
                }
            }
        }
    }
}

private fun sizeLabel(b: Long): String = when {
    b >= 1_048_576 -> String.format(java.util.Locale.US, "%.1fMB", b / 1_048_576.0)
    b >= 1024 -> "${b / 1024}KB"
    else -> "${b}B"
}
