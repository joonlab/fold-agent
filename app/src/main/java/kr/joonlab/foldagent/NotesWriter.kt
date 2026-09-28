package kr.joonlab.foldagent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 삼성 노트에 제목 + (글·이미지) 블록을 순서대로 쓴다 — 조작은 코드가 고정 절차로 하고 단계마다 확인한다.
 *
 * 왜 도구로 뺐나(2026-09-26 QA2~QA8): 모델이 노트 편집기를 한 단계씩 조작하면 매번 다르게 틀렸다 —
 * 본문 2~3중 입력, 제목 비움, 본문을 눌러 커서가 글 중간으로 가 이미지가 엉뚱한 데 박힘, 되돌리기로 이미지 삭제,
 * 이미지 든 본문을 type_text 로 덮어써 이미지 0장인데 「2장 정리」라고 보고(거짓 성공).
 * 원칙: 입력은 한 번만(커서 앞 글자로 확인) · 커서는 누르지 않고 SET_SELECTION 으로 끝에 · 이미지는 선택 칸의 파일 이름으로 확인 · 실패하면 멈추고 보고.
 */
class NotesWriter(private val svc: AgentService, private val trace: TraceLog) {

    companion object {
        const val PKG = "com.samsung.android.app.notes"
        private const val GALLERY = "com.sec.android.gallery3d"
    }

    private fun all(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            out += n
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        svc.windows.forEach { walk(it.root) }
        if (out.isEmpty()) walk(svc.rootInActiveWindow)
        return out
    }

    private fun find(pred: (AccessibilityNodeInfo) -> Boolean) = all().firstOrNull(pred)
    private fun id(n: AccessibilityNodeInfo, suffix: String) = n.viewIdResourceName?.endsWith(suffix) == true
    private fun desc(n: AccessibilityNodeInfo) = n.contentDescription?.toString().orEmpty()
    private fun txt(n: AccessibilityNodeInfo) = n.text?.toString().orEmpty()

    private fun waitFor(ms: Long = 4000, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            find(pred)?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        val r = Rect(); n.getBoundsInScreen(r)
        return svc.tapAt(r.exactCenterX(), r.exactCenterY())
    }

    private fun norm(x: String) = x.replace(Regex("\\s+"), "")

    private fun imageCount() = all().count { it.packageName == PKG && txt(it).contains("@files_") && txt(it).endsWith(".jpg") }

    private fun body() = find { it.packageName == PKG && it.isEditable && !id(it, "comp_title_text") && it.className?.contains("EditText") == true }

    /** 본문 칸의 실제 글에 그 문장이 생겼나(최대 4초) — 입력기는 「받았다」고 해도 이미지가 선택된 상태면 노트에 안 들어갔다(2026-09-26 QA12) */
    private fun bodyHas(text: String): Boolean {
        val tail = norm(text).takeLast(15)
        if (tail.isEmpty()) return true
        repeat(16) {
            if (all().any { it.packageName == PKG && it.isEditable && !id(it, "comp_title_text") && norm(txt(it)).contains(tail) }) return true
            Thread.sleep(250)
        }
        return false
    }

    /** 이미지를 넣으면 그 이미지가 선택된 채(테두리·잘라내기 메뉴) 남는다 — 본문 아래 빈 곳을 눌러 선택을 풀고 커서를 끝으로 */
    private fun deselectImage() {
        val b = body() ?: return
        val r = Rect(); b.getBoundsInScreen(r)
        svc.tapAt(r.exactCenterX(), (r.bottom - 30).toFloat())
        Thread.sleep(600)
        svc.imeCtrlEnd()
        Thread.sleep(250)
    }

    /** 한 번만 넣고 커서 앞 글자로 확인. 다시 넣지 않는다 */
    private fun commitOnce(text: String): String {
        val tail = norm(text).takeLast(15)
        if (!svc.commitTextViaIme(text)) return "nocommit"
        if (tail.isEmpty()) return "landed"
        var readable = false
        repeat(8) {
            Thread.sleep(250)
            val b = svc.textBeforeCursor(text.length + 60)
            if (b != null) { readable = true; if (norm(b).endsWith(tail)) return "landed" }
        }
        return if (readable) "missing" else "unknown"
    }

    // 검색 결과에서 연 노트는 「노트 안 검색」 보기(floating_navigate_up, 제목 툴바 없음)로 뜬다 — 이것도 편집기로 친다
    private fun editorOpen() = find { it.packageName == PKG && (id(it, "composer_toolbar_title") || id(it, "comp_title_text") || id(it, "floating_navigate_up")) } != null

    // 목록 제목 앞에 보이지 않는 방향 표시(U+200E)가 붙어 있다
    private fun cleanTitle(s: String) = s.replace(Regex("[\\u200E\\u200F\\u202A-\\u202E]"), "").trim()

    /** 노트 앱을 앞으로 가져와 목록 화면까지 나간다(검색창도 닫는다) */
    private fun toList(): String? {
        val launch = svc.packageManager.getLaunchIntentForPackage(PKG) ?: return "노트 앱이 없음"
        svc.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor(5000) { it.packageName == PKG } ?: return "노트 앱이 안 열림"
        Thread.sleep(600)
        // 편집기는 목록 위에 겹쳐 떠서, 열려 있어도 뒤쪽 목록의 「노트 작성」이 트리에 잡힌다 — 그걸 눌러 놓고 열린 옛 노트(QA8)에 썼다(2026-09-26 QA9).
        // 편집기가 사라진 걸 확인한 뒤에 누른다
        repeat(4) {
            val search = find { it.packageName == PKG && id(it, "search_src_text") } != null
            if (!editorOpen() && !search) return@repeat
            val up = find { it.packageName == PKG && (id(it, "composer_toolbar_navigate") || id(it, "composer_title_navigate") || id(it, "floating_navigate_up")) }
            if (up != null) click(up) else svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(1000)
        }
        if (editorOpen()) return "열려 있던 노트에서 목록으로 못 나감"
        // 목록에 「길게 누르면 새 노트의 스타일을 선택할 수 있어요」 말풍선이 뜨면 그 창이 포커스를 가져가 뒤쪽 「검색」이 안 잡힌다(2026-09-26 A4b·c)
        // → 말풍선을 눌러 닫는다(안내일 뿐 누르면 사라진다)
        find { it.packageName == PKG && txt(it).contains("길게 누르면") }?.let { tip ->
            val r = Rect(); tip.getBoundsInScreen(r)
            if (!tip.performAction(AccessibilityNodeInfo.ACTION_CLICK)) svc.tapAt(r.exactCenterX(), r.exactCenterY())
            trace.event("note_tip_closed", JSONObject().put("text", txt(tip).take(30)))
            Thread.sleep(600)
        }
        return null
    }

    /**
     * 제목이 정확히 같은 노트를 찾아 편집기로 연다. 같은 제목이 없거나 둘 이상이면 고르지 않고 멈춘다(엉뚱한 노트에 쓰는 것보다 낫다).
     * 실측 2026-09-26: 목록 스크롤 없이 찾으려고 검색을 쓴다 — 검색은 본문 일치도 보여 주므로 제목(id=title)으로 거른다.
     * 결과를 누르면 「노트 안 검색」 보기(편집 불가)로 열리고, 뒤로 한 번이면 같은 노트의 편집기(composer_toolbar_title)가 된다.
     */
    private var openedTitle = ""

    private fun openExisting(title: String): String? {
        toList()?.let { return it }
        // 편집기에서 목록으로 돌아오는 전환 중엔 한 번 찾아서는 못 본다(2026-09-26 A4b) — 기다리며 찾는다
        click(waitFor(3000) { it.packageName == PKG && (id(it, "action_search") || desc(it) == "검색") } ?: return "노트 목록의 「검색」을 못 찾음")
        val box = waitFor(3000) { it.packageName == PKG && id(it, "search_src_text") } ?: return "검색창이 안 열림"
        box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, title)
        })
        Thread.sleep(1500)
        val want = norm(cleanTitle(title))
        // id 가 정확히 「title」인 것만 — endsWith 로 보면 folder_title(「폴더」)·sub_header_title 까지 잡힌다
        val titles = all().filter { it.packageName == PKG && it.viewIdResourceName?.endsWith(":id/title") == true && txt(it).isNotBlank() }
        var hits = titles.filter { norm(cleanTitle(txt(it))) == want }
        // 모델이 「[QA25] 설정 화면」을 「설정 화면」으로 줄여 부른다(A4b) — 정확히 같은 게 없을 때만, 그 제목을 포함하는 노트가 딱 하나면 그것
        if (hits.isEmpty() && want.length >= 2) hits = titles.filter { norm(cleanTitle(txt(it))).contains(want) }.takeIf { it.size == 1 } ?: emptyList()
        trace.event("note_find", JSONObject().put("title", title).put("hits", hits.size)
            .put("seen", JSONArray(titles.map { cleanTitle(txt(it)) })))
        if (hits.isEmpty()) return "제목이 「$title」인 노트를 못 찾음(검색 결과의 제목과 정확히 같아야 한다)"
        if (hits.size > 1) return "제목이 「$title」인 노트가 ${hits.size}개 — 어느 것인지 모르니 쓰지 않음"
        click(hits[0])
        waitFor(4000) { it.packageName == PKG && (id(it, "floating_navigate_up") || id(it, "composer_toolbar_title")) } ?: return "노트가 안 열림"
        Thread.sleep(500)
        if (find { it.packageName == PKG && id(it, "composer_toolbar_title") } == null) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)   // 「노트 안 검색」 보기 → 편집기
            waitFor(3000) { it.packageName == PKG && id(it, "composer_toolbar_title") } ?: return "노트를 편집 화면으로 못 바꿈"
        }
        val now = cleanTitle(find { it.packageName == PKG && id(it, "composer_toolbar_title") }?.let { txt(it) }.orEmpty())
        if (norm(now) != want && !norm(now).contains(want)) return "열린 노트 제목이 다름(「$now」)"
        openedTitle = now
        return null
    }

    /**
     * 기존 노트 본문 끝에 커서 — 누르지 않는다(누르면 커서가 누른 자리로 가거나 빈 줄이 채워진다).
     * 입력기가 본문에 안 붙어 있으면 본문 칸에 접근성 포커스·클릭(좌표 탭 아님)으로 붙이고, 끝은 입력기 Ctrl+End.
     * 커서 뒤에 글이 남아 있으면 끝이 아니다 → 멈춘다
     */
    private fun focusExistingEnd(): String? {
        repeat(2) { attempt ->
            if (!imeOnBody()) {
                val b = body() ?: return "본문 칸을 못 찾음"
                b.performAction(AccessibilityNodeInfo.ACTION_FOCUS); b.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(700)
            }
            val info = svc.imeEditorInfo()
            trace.event("note_focus", JSONObject().put("mode", "append").put("attempt", attempt).put("connected", svc.imeConnected())
                .put("pkg", info?.packageName).put("hint", info?.hintText?.toString()))
            if (imeOnBody()) {
                svc.imeCtrlEnd(); Thread.sleep(300)
                val after = svc.textAfterCursor(40)
                trace.event("note_end", JSONObject().put("after", after))
                if (after == null || after.isBlank()) return null
                return "커서를 본문 끝으로 못 옮김(뒤에 「${after.take(15)}」가 남음)"
            }
        }
        return "본문에 입력 포커스를 못 잡음(누르면 커서가 엉뚱한 데 가서 멈춤)"
    }

    private fun openNewNote(): String? {
        toList()?.let { return it }
        val add = find { it.packageName == PKG && desc(it) == "노트 작성" } ?: return "노트 목록의 「노트 작성」을 못 찾음"
        click(add)
        val t = waitFor(4000) { it.packageName == PKG && id(it, "composer_toolbar_title") } ?: return "새 노트 화면이 안 열림"
        // 새 노트인지 확인 — 제목이 비어 있어야 한다(자리표시 「제목」)
        val tt = txt(t)
        if (tt.isNotEmpty() && tt != "제목") return "새 노트가 아니라 기존 노트(「$tt」)가 열림"
        return null
    }

    /** 입력기가 본문에 붙어 있나 — 제목칸은 자리표시가 「제목」. 접근성 포커스(isFocused·findFocus)는 본문에서 안 잡혀(2026-09-26 QA10·11) 입력기 쪽 정보로 본다 */
    private fun imeOnBody(): Boolean {
        if (!svc.imeConnected()) return false
        val info = svc.imeEditorInfo() ?: return false
        return info.packageName == PKG && info.hintText?.toString() != "제목"
    }

    /** 본문에 입력을 붙이고 커서를 문서 끝에 둔다. 제목칸에 남은 채로 쓰면 본문 글이 제목에 붙었다(2026-09-26 QA9) */
    private fun focusBodyEnd(): Boolean {
        repeat(3) { attempt ->
            if (!imeOnBody()) {
                val b = body() ?: return false
                if (attempt == 0) { b.performAction(AccessibilityNodeInfo.ACTION_FOCUS); b.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                else { val r = Rect(); b.getBoundsInScreen(r); svc.tapAt(r.exactCenterX(), (r.bottom - 20).toFloat()) }
                Thread.sleep(700)
            }
            val info = svc.imeEditorInfo()
            trace.event("note_focus", JSONObject().put("attempt", attempt).put("connected", svc.imeConnected())
                .put("pkg", info?.packageName).put("hint", info?.hintText?.toString()).put("fieldId", info?.fieldId))
            if (imeOnBody()) {
                svc.imeCtrlEnd()
                Thread.sleep(250)
                return true
            }
        }
        return false
    }

    private fun setTitle(title: String): String? {
        val t = find { it.packageName == PKG && id(it, "composer_toolbar_title") } ?: return "제목칸을 못 찾음"
        click(t)
        val edit = waitFor(3000) { it.packageName == PKG && id(it, "comp_title_text") && it.isEditable } ?: return "제목 입력칸이 안 열림"
        val ok = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, title)
        })
        if (!ok) { edit.performAction(AccessibilityNodeInfo.ACTION_CLICK); Thread.sleep(400); commitOnce(title) }
        Thread.sleep(400)
        val now = find { it.packageName == PKG && id(it, "comp_title_text") }?.let { txt(it) }
        return if (now == title) null else "제목이 확인 안 됨(지금: ${now ?: "?"})"
    }

    /**
     * 갤러리 선택 화면에서 파일 이름이 맞는 사진을 고른다 — 목록은 설명이 「버튼」뿐이라, 눌러 보고 선택 칸의 이름으로 확인.
     * 실측 2026-09-26 A4: ①선택 화면은 갤러리가 마지막에 본 탭으로 열린다 — 「앨범」 탭이면 사진 대신 앨범 카드를 누르고 다녔다 → 먼저 「사진」 탭
     * ②선택 칸은 0.6초 안에 안 바뀔 때가 있다 — 빈 칸으로 읽고 「아님」 판정 → 해제하려 다시 누른 게 엇갈려 엉뚱한 사진이 선택된 채 남았다
     *   → 칸이 바뀔 때까지 기다리고, 새로 들어온 이름으로 판정 · 해제도 원래대로 돌아올 때까지 확인
     * ③시작할 때 이미 선택된 사진이 있으면 「완료」 때 함께 들어간다 → 취소하고 다시 열고, 그래도 있으면 멈춘다
     */
    private fun tray(): List<String> = all().filter { n -> n.packageName == GALLERY && id(n, "clipboard_list_item_thumbnail") }.map { n -> desc(n) }

    private fun waitTray(ms: Long, pred: (List<String>) -> Boolean): List<String>? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { val t = tray(); if (pred(t)) return t; Thread.sleep(200) }
        return null
    }

    private fun openPicker(): String? {
        click(find { it.packageName == PKG && id(it, "action_add") } ?: return "「삽입」 버튼을 못 찾음")
        click(waitFor(3000) { it.packageName == PKG && txt(it) == "이미지" } ?: return "삽입 메뉴에 「이미지」가 없음")
        click(waitFor(3000) { txt(it) == "갤러리" || desc(it) == "갤러리" } ?: return "「갤러리」가 없음")
        waitFor(5000) { it.packageName == GALLERY && (id(it, "recycler_view_item") || id(it, "action_done")) } ?: return "갤러리 선택 화면이 안 열림"
        Thread.sleep(500)
        find { it.packageName == GALLERY && (txt(it) == "사진" || desc(it) == "사진") }?.let { click(it); Thread.sleep(900) }
        return null
    }

    private fun insertImage(name: String): String? {
        val key = name.substringAfterLast('/').removeSuffix(".jpg")
        val before = imageCount()
        openPicker()?.let { return it }
        if (tray().isNotEmpty()) {   // ③ 남은 선택 — 취소하고 한 번 다시
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); Thread.sleep(1000)
            openPicker()?.let { return it }
            if (tray().isNotEmpty()) { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); return "갤러리 선택 칸에 이미 고른 사진이 있어 멈춤(다른 사진이 함께 들어갈 수 있음)" }
        }
        val items = all().filter { it.packageName == GALLERY && id(it, "recycler_view_item") }
            .sortedWith(compareBy({ Rect().also { r -> it.getBoundsInScreen(r) }.top }, { Rect().also { r -> it.getBoundsInScreen(r) }.left }))
        var picked = false
        val seen = JSONArray()
        for (it in items.take(12)) {
            val base = tray()
            click(it)
            val now = waitTray(2000) { t -> t != base }
            val added = now?.filter { d -> d !in base }.orEmpty()
            seen.put(added.joinToString("|").ifEmpty { "(변화 없음)" }.take(60))
            if (added.any { d -> d.contains(key) }) { picked = true; break }
            if (now != null) {   // 다른 사진이 들어갔으면 빼고, 빠진 것까지 확인
                click(it)
                if (waitTray(2000) { t -> t == base } == null) {
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    trace.event("note_pick", JSONObject().put("key", key).put("items", items.size).put("picked", false).put("tray", seen).put("why", "해제 확인 안 됨"))
                    return "잘못 고른 사진을 해제했는지 확인이 안 돼 멈춤"
                }
            }
        }
        trace.event("note_pick", JSONObject().put("key", key).put("items", items.size).put("picked", picked).put("tray", seen))
        if (!picked) { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); return "갤러리 앞쪽 12장에서 「$key」를 못 찾음" }
        if (tray().size != 1) { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); return "선택 칸에 사진이 ${tray().size}장 — 한 장만이어야 해서 멈춤" }
        click(find { it.packageName == GALLERY && id(it, "action_done") } ?: return "「완료」를 못 찾음")
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end) { if (imageCount() > before) return null; Thread.sleep(300) }
        return "이미지를 골랐지만 노트에 들어간 게 확인 안 됨"
    }

    /** 본문의 pos 자리에 커서 — 화면을 누르지 않고 글자 위치로 옮긴다(누르면 빈 줄이 채워지거나 이미지가 선택된다) */
    private fun cursorAt(pos: Int): Boolean {
        val b = body() ?: return false
        b.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val ok = b.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
        })
        Thread.sleep(400)
        return ok
    }

    /**
     * 커서를 옮기고 커서 앞 글이 key 로 끝나는지 확인. 기존 노트에 이미지가 있으면 입력기 글(이미지 없음)과 본문 칸 위치가
     * 이미지 수만큼 어긋날 수 있어 한 칸씩 뒤로 밀며 찾는다. 읽을 수 없으면(갤러리에서 돌아와 입력기가 끊긴 경우) 옮긴 자리를 믿는다.
     * @return 실제로 쓴 위치, 맞는 자리가 없으면 null
     */
    private fun cursorAtChecked(pos: Int, key: String?, maxShift: Int): Int? {
        if (key.isNullOrEmpty()) return if (cursorAt(pos)) pos else null
        val want = norm(key)
        for (k in 0..maxShift) {
            if (!cursorAt(pos + k)) return null
            // 갤러리에서 돌아오면 입력기가 이미지에 붙어 빈 글로 읽힌다(2026-09-26 A3) — 빈 값도 「읽기 불가」로 보고 옮긴 자리를 믿는다
            val before = svc.textBeforeCursor(key.length + 20)?.takeIf { it.isNotEmpty() } ?: return pos + k
            if (norm(before).endsWith(want)) return pos + k
            if (k == 0) trace.event("note_cursor_shift", JSONObject().put("pos", pos).put("before", before.takeLast(20)).put("key", key))
        }
        return null
    }

    /**
     * 글을 먼저 전부 한 번에 쓰고, 이미지는 「그 이미지가 따라갈 글의 끝 위치」에 커서를 옮겨 넣는다.
     * 이미지 뒤에 글을 쓰면 안 들어가거나(이미지가 선택된 채라 입력이 버려짐), 빈 곳을 눌러 커서를 만들면 빈 줄이 채워졌다(2026-09-26 QA12·13 실측).
     */
    private fun countObj(t: String) = t.count { it == '\uFFFC' }

    /**
     * 마지막 이미지가 선택된 채(테두리·잘라내기 메뉴) 남는다. 뒤로 한 번은 키보드 닫기에 쓰일 때가 있어 선택이 그대로 남았다(2026-09-26 QA24)
     * → 선택 메뉴(「잘라내기」, 노트의 PopupWindow)가 보이는 동안만 뒤로를 누른다(없는데 누르면 편집기를 나간다)
     */
    private fun clearImageSelection(): Int {
        var backs = 0
        Thread.sleep(400)
        repeat(3) {
            if (find { it.packageName == PKG && txt(it) == "잘라내기" } == null) return backs
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); backs++
            Thread.sleep(500)
        }
        return backs
    }

    fun write(title: String, blocks: JSONArray, append: Boolean = false): String {
        if (append) return append(title, blocks)
        val log = JSONObject().put("title", title).put("blocks", blocks.length())
        val done = ArrayList<String>()
        fun stop(why: String): String {
            trace.event("write_note", log.put("ok", false).put("why", why).put("done", JSONArray(done)))
            return "노트 쓰기 중단: $why · 여기까지 한 것: ${done.joinToString(" → ").ifEmpty { "없음" }} (같은 노트에 다시 쓰지 말고 사용자에게 알릴 것)"
        }
        // 블록 정리: 글은 이어 붙이고, 이미지는 바로 앞 글(anchor) 뒤에 붙는다
        val texts = ArrayList<String>()
        val images = ArrayList<Pair<String, String?>>()   // (파일 이름, 앞 글 — 없으면 맨 앞)
        var lastText: String? = null
        for (k in 0 until blocks.length()) {
            val b = blocks.optJSONObject(k) ?: continue
            b.optString("text").takeIf { it.isNotBlank() }?.let { t -> texts += t.trimEnd(); lastText = t.trimEnd() }
            b.optString("image").takeIf { it.isNotBlank() }?.let { images += it to lastText }
        }
        openNewNote()?.let { return stop(it) }
        done += "새 노트"
        if (title.isNotBlank()) { setTitle(title)?.let { return stop(it) }; done += "제목" }
        if (texts.isNotEmpty()) {
            if (!focusBodyEnd()) return stop("본문에 입력 포커스를 못 잡음(제목칸 등 다른 칸에 쓰지 않으려고 멈춤)")
            val all = texts.joinToString("\n\n") + "\n"
            val r = commitOnce(all)
            if (r == "missing" || r == "nocommit") return stop("글이 안 들어감($r)")
            // 확인은 편집기 글 전체(입력기)로 — 긴 노트는 트리에 본문이 안 나오고, 「•」「1.」 줄은 목록 블록으로 바뀐다
            val full = svc.imeFullText()?.first
            val missing = texts.filter { t -> full == null || !norm(full).contains(norm(t.lines().last()).takeLast(12)) }
            if (full != null && missing.isNotEmpty()) return stop("글 일부가 본문에 없음: ${missing.first().take(20)}…(다시 넣지 않음)")
            done += "글 ${texts.size}개" + if (full == null) "(읽기 불가 — 입력기 확인 $r)" else ""
        }
        // 이미지 자리는 첫 이미지를 넣기 전에 입력기로 읽은 본문(글만 — 긴 노트도 전부 읽힌다)에서 한꺼번에 계산하고, 뒤쪽부터 넣는다
        // (뒤에 넣은 이미지는 앞 자리를 밀지 않는다). 실측 2026-09-26:
        //  - 갤러리에서 돌아오면 입력기가 끊기거나 이미지에 붙어 다음 자리를 못 읽었다(QA17~19)
        //  - 입력기 setSelection 은 성공을 돌려주고도 커서를 안 옮겨 이미지 3장이 맨 위에 몰렸다(QA21) → 커서는 접근성 SET_SELECTION 으로(QA14 에서 동작)
        //  - 입력기로 읽은 글에는 이미지가 안 들어 있다 — 개수 확인에 쓸 수 없다
        val base = svc.imeFullText()?.takeIf { it.first.isNotEmpty() } ?: return stop("이미지 자리를 계산할 본문을 못 읽음")
        val baseText = base.first
        val slots = ArrayList<Triple<Int, Int, String>>()   // (본문 안 위치, 원래 순서, 파일 이름)
        images.forEachIndexed { idx, (name, anchor) ->
            val pos = if (anchor == null) 0 else {
                val key = anchor.lines().last().trim().takeLast(12)
                val i = baseText.lastIndexOf(key)
                if (i < 0) return stop("이미지 「$name」을 붙일 글 「$key」을 본문에서 못 찾음") else i + key.length
            }
            slots += Triple(base.second + pos, idx, name)
        }
        trace.event("note_slots", JSONObject().put("len", baseText.length).put("slots", JSONArray(slots.map { "${it.first}:${it.third.takeLast(20)}" })))
        var inserted = 0
        for ((pos, _, name) in slots.sortedWith(compareByDescending<Triple<Int, Int, String>> { it.first }.thenByDescending { it.second })) {
            if (!cursorAt(pos)) return stop("커서를 이미지 자리로 못 옮김")
            insertImage(name)?.let { err ->
                // 긴 노트는 트리에 이미지가 안 보일 수 있다 — 선택 칸에서 파일 이름은 확인했으니 그 경우만 넘어간다
                if (!err.startsWith("이미지를 골랐지만")) return stop("이미지 「$name」: $err")
            }
            inserted++
            done += "이미지(${name.substringAfterLast('_').removeSuffix(".jpg")})"
        }
        if (inserted > 0) log.put("deselectBacks", clearImageSelection())
        trace.event("write_note", log.put("ok", true).put("images", inserted).put("done", JSONArray(done)))
        return "노트 저장: 「$title」 · ${done.joinToString(" → ")} · 이미지 ${inserted}장(갤러리 선택 칸의 파일 이름으로 확인) · 뒤로 나가면 자동 저장"
    }

    /**
     * 기존 노트(제목이 정확히 같은 것 하나)의 본문 끝에 글·이미지를 이어 쓴다. 새 노트 쓰기와 같은 원칙:
     * 누르지 않고 글자 위치로 커서 · 들어갔는지는 편집기 값(입력기)으로 · 이미지는 뒤쪽부터 · 확인 안 되면 다시 넣지 않고 멈춤.
     * 추가로 원래 본문이 그대로인지(앞부분 일치) 확인한다 — 이어쓰기는 남의 글을 건드린다.
     */
    private fun append(title: String, blocks: JSONArray): String {
        val log = JSONObject().put("mode", "append").put("title", title).put("blocks", blocks.length())
        val done = ArrayList<String>()
        fun stop(why: String): String {
            trace.event("write_note", log.put("ok", false).put("why", why).put("done", JSONArray(done)))
            return "노트 쓰기 중단(이어쓰기): $why · 여기까지 한 것: ${done.joinToString(" → ").ifEmpty { "없음" }} (같은 노트에 다시 쓰지 말고 사용자에게 알릴 것)"
        }
        val texts = ArrayList<String>()
        val images = ArrayList<Pair<String, String?>>()
        var lastText: String? = null
        for (k in 0 until blocks.length()) {
            val b = blocks.optJSONObject(k) ?: continue
            b.optString("text").takeIf { it.isNotBlank() }?.let { t -> texts += t.trimEnd(); lastText = t.trimEnd() }
            b.optString("image").takeIf { it.isNotBlank() }?.let { images += it to lastText }
        }
        if (title.isBlank()) return stop("이어 쓸 노트의 제목이 없음")
        if (texts.isEmpty() && images.isEmpty()) return stop("쓸 내용이 없음")
        openExisting(title)?.let { return stop(it) }
        done += "노트 열기"
        focusExistingEnd()?.let { return stop(it) }
        val orig = svc.imeFullText() ?: return stop("원래 본문을 못 읽음")
        val origText = orig.first
        // 기존 이미지 수 — 입력기 글에는 이미지가 없어 본문 칸 위치가 그만큼 뒤로 밀린다(트리는 긴 노트면 첫 쪽만 보여 하한값)
        val existing = maxOf(body()?.let { countObj(txt(it)) } ?: 0, imageCount())
        trace.event("note_append_base", JSONObject().put("len", origText.length).put("offset", orig.second).put("existingImages", existing)
            .put("tail", origText.takeLast(30)))

        if (texts.isNotEmpty()) {
            val lead = if (origText.isEmpty() || origText.endsWith("\n")) "" else "\n"
            val r = commitOnce(lead + texts.joinToString("\n\n") + "\n")
            if (r == "missing" || r == "nocommit") return stop("글이 안 들어감($r)")
            val full = svc.imeFullText()?.first
            if (full != null) {
                if (!norm(full).startsWith(norm(origText))) return stop("원래 본문 앞부분이 달라짐 — 더 쓰지 않음")
                val added = norm(full.substring(minOf(origText.length, full.length)))
                val missing = texts.filter { t -> !added.contains(norm(t.lines().last()).takeLast(12)) }
                if (missing.isNotEmpty()) return stop("글 일부가 본문 끝에 없음: ${missing.first().take(20)}…(다시 넣지 않음)")
            }
            done += "글 ${texts.size}개" + if (full == null) "(읽기 불가 — 입력기 확인 $r)" else ""
        }

        var inserted = 0
        if (texts.isEmpty()) {
            // 이미지만 추가: 매번 본문 끝(커서 뒤가 빈 것 확인)에 앞 순서대로 넣는다
            for ((name, _) in images) {
                if (inserted > 0) {
                    clearImageSelection()   // 넣은 이미지 선택 풀기
                    focusExistingEnd()?.let { return stop("다음 이미지 자리: $it") }
                }
                insertImage(name)?.let { err -> if (!err.startsWith("이미지를 골랐지만")) return stop("이미지 「$name」: $err") }
                inserted++
                done += "이미지(${name.substringAfterLast('_').removeSuffix(".jpg")})"
            }
        } else if (images.isNotEmpty()) {
            val base = svc.imeFullText()?.takeIf { it.first.isNotEmpty() } ?: return stop("이미지 자리를 계산할 본문을 못 읽음")
            val baseText = base.first
            val slots = ArrayList<Triple<Int, Int, Pair<String, String?>>>()   // (입력기 위치, 원래 순서, (파일, 커서 앞에 있어야 할 글))
            images.forEachIndexed { idx, (name, anchor) ->
                val key = (anchor ?: origText.trimEnd()).lines().last().trim().takeLast(12)
                val i = baseText.lastIndexOf(key)
                // 새로 붙인 글 뒤에만 넣는다(앞 글이 없으면 원래 본문 끝)
                if (anchor != null && i < origText.length) return stop("이미지 「$name」을 붙일 글 「$key」을 새로 쓴 부분에서 못 찾음")
                if (i < 0) return stop("이미지 「$name」 자리(원래 본문 끝)를 못 찾음")
                slots += Triple(base.second + i + key.length, idx, name to key)
            }
            trace.event("note_slots", JSONObject().put("mode", "append").put("len", baseText.length)
                .put("slots", JSONArray(slots.map { "${it.first}:${it.third.first.takeLast(20)}" })))
            for ((pos, _, nk) in slots.sortedWith(compareByDescending<Triple<Int, Int, Pair<String, String?>>> { it.first }.thenByDescending { it.second })) {
                val at = cursorAtChecked(pos, nk.second, existing + 2) ?: return stop("이미지 「${nk.first}」 자리로 커서를 못 옮김(앞 글 확인 실패)")
                trace.event("note_cursor", JSONObject().put("want", pos).put("at", at))
                insertImage(nk.first)?.let { err -> if (!err.startsWith("이미지를 골랐지만")) return stop("이미지 「${nk.first}」: $err") }
                inserted++
                done += "이미지(${nk.first.substringAfterLast('_').removeSuffix(".jpg")})"
            }
        }
        if (inserted > 0) log.put("deselectBacks", clearImageSelection())
        trace.event("write_note", log.put("ok", true).put("images", inserted).put("done", JSONArray(done)))
        return "노트 이어쓰기: 「${openedTitle.ifEmpty { title }}」 끝에 · ${done.joinToString(" → ")} · 이미지 ${inserted}장 · 원래 본문은 그대로(앞부분 일치 확인) · 뒤로 나가면 자동 저장"
    }
}
