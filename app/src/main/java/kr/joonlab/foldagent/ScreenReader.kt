package kr.joonlab.foldagent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 한 시점의 화면. text 는 모델에게 주는 목록, nodes[i] 는 그 목록의 [i] 번 항목.
 * title·listSpan·scrollMore 는 「본 화면 기억」(SeenScreens) 한 줄 요약용 — 모델에게 주는 text 에는 영향 없다(2026-09-27).
 */
class Snapshot(
    val text: String, val nodes: List<AccessibilityNodeInfo>, val pkg: String,
    /** 창 제목(추정 — 못 찾으면 빈 문자열) */
    val title: String = "",
    /** 가장 큰 스크롤 목록의 첫·끝 항목 글자(14자) — 목록이 없으면 null */
    val listSpan: Pair<String, String>? = null,
    /** 그 목록이 앞(위)·뒤(아래)로 더 스크롤되는지 — 목록이 없으면 null */
    val scrollMore: ScrollMore? = null,
)

class ScrollMore(val back: Boolean, val forward: Boolean)

/**
 * 접근성 트리를 모델이 읽을 텍스트 목록으로 바꾼다.
 * 스크린샷 대신 트리를 쓰는 이유: 토큰이 훨씬 적고 번호로 정확히 가리킬 수 있다.
 * (트리가 빈약한 앱 — 게임·캔버스 — 은 v2 에서 스크린샷으로 보완)
 */
object ScreenReader {
    private const val MAX_NODES = 180

    fun read(svc: AccessibilityService): Snapshot {
        val root = svc.rootInActiveWindow
            ?: svc.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
            ?: return Snapshot("(화면을 읽을 수 없음 — 잠금화면이거나 보안 화면일 수 있음)", emptyList(), "?")
        val pkg = root.packageName?.toString() ?: "?"
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val depths = ArrayList<Int>()
        val sb = StringBuilder("앱: ").append(pkg).append(" · ").append(foldState(svc)).append('\n')
        // 펼친 화면의 삼성 설정·메시지 등은 「왼쪽 목록 | 오른쪽 상세」가 서로 다른 창 2개다(Activity Embedding), 분할 화면도 같다.
        // 활성 창 하나만 읽으면 이미 연 오른쪽 칸을 못 봐서 「안 열렸다」고 뒤로·다시 열기를 반복했다(2026-09-27 디스플레이 설정 11단계).
        val panes = runCatching {
            svc.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null &&
                it.root.packageName?.toString() != svc.packageName }
        }.getOrDefault(emptyList())
        if (panes.size >= 2) {
            val sorted = panes.sortedWith(compareBy({ w -> android.graphics.Rect().also { w.getBoundsInScreen(it) }.left },
                { w -> android.graphics.Rect().also { w.getBoundsInScreen(it) }.top }))
            sb.append("(화면이 ").append(sorted.size).append("칸으로 나뉨 — 칸마다 따로 적는다)\n")
            sorted.forEachIndexed { k, w ->
                val r = android.graphics.Rect().also { w.getBoundsInScreen(it) }
                val where = when { sorted.size == 2 && r.left > 0 -> "오른쪽 칸"; sorted.size == 2 -> "왼쪽 칸"; else -> "칸 ${k + 1}" }
                sb.append("── ").append(where).append(" · ").append(w.root.packageName ?: "?")
                if (w.isActive || w.isFocused) sb.append(" · 활성")
                sb.append('\n')
                walk(w.root, 1, nodes, depths, sb)
            }
        } else walk(root, 0, nodes, depths, sb)
        if (nodes.size >= MAX_NODES) sb.append("… (항목이 많아 일부 생략 — 스크롤하면 더 보일 수 있음)\n")
        if (nodes.isEmpty()) sb.append("(읽을 수 있는 항목 없음)\n")
        val list = runCatching { mainList(nodes, depths) }.getOrNull()
        return Snapshot(sb.toString(), nodes, pkg,
            title = runCatching { titleOf(svc, nodes, depths, panes.size >= 2) }.getOrDefault(""),
            listSpan = list?.first, scrollMore = list?.second)
    }

    private val NAV_UP = setOf("상위 메뉴로 이동", "뒤로", "뒤로 가기", "뒤로 이동", "Navigate up", "Back")

    private fun textOf(n: AccessibilityNodeInfo): String = n.text?.toString()?.trim().orEmpty()

    /**
     * 창 제목 추정(본 화면 기억용) — 순서: ① 「상위 메뉴로 이동」·「뒤로」 버튼 바로 뒤의 누를 수 없는 글자 ② 창 제목(AccessibilityWindowInfo.title)
     * ③ 첫 스크롤 목록보다 앞(깊이 ≤2)의 누를 수 없는 20자 이하 글자. 파이썬 시험(화면 1,291개)에서 ①③만으로 15.7% 가 「?」였다 — ② 는 실기 미확인.
     */
    private fun titleOf(svc: AccessibilityService, nodes: List<AccessibilityNodeInfo>, depths: List<Int>, split: Boolean): String {
        val nav = nodes.indexOfFirst { (it.contentDescription?.toString()?.trim() ?: textOf(it)) in NAV_UP }
        if (nav >= 0) {
            for (j in nav + 1..minOf(nav + 3, nodes.size - 1)) {
                val t = textOf(nodes[j])
                if (!nodes[j].isClickable && t.length in 1..30) return t
            }
        }
        if (!split) {
            val wt = svc.windows.firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.title?.toString()?.trim().orEmpty()
            if (wt.length in 1..30) return wt
        }
        val firstScroll = nodes.indexOfFirst { it.isScrollable }.let { if (it < 0) nodes.size else it }
        for (j in 0 until firstScroll) {
            val t = textOf(nodes[j])
            if (depths[j] <= 2 && !nodes[j].isClickable && t.length in 1..20) return t
        }
        return ""
    }

    /**
     * 화면에서 가장 큰 스크롤 목록(안쪽 항목이 가장 많은 것)의 첫·끝 글자와 앞·뒤로 더 있는지.
     * 끝 여부는 목록 노드의 접근성 동작(SCROLL_FORWARD/BACKWARD)으로 결정론적으로 안다 — 「아래는 아직 안 봤다」를 글자로 보이려고
     * (run 232447-007: 목록이 아래로 이어지는데 두 쪽만 16번 오갔다).
     */
    private fun mainList(nodes: List<AccessibilityNodeInfo>, depths: List<Int>): Pair<Pair<String, String>?, ScrollMore>? {
        var best = -1; var bestEnd = -1
        for (i in nodes.indices) {
            if (!nodes[i].isScrollable) continue
            var e = i + 1
            while (e < nodes.size && depths[e] > depths[i]) e++
            if (best < 0 || e - i > bestEnd - best) { best = i; bestEnd = e }
        }
        if (best < 0) return null
        val labels = (best + 1 until bestEnd).map { nodes[it] }
            .filter { it.viewIdResourceName?.endsWith(":id/summary") != true }
            .mapNotNull { n -> (textOf(n).ifEmpty { n.contentDescription?.toString()?.trim().orEmpty() }).takeIf { it.isNotEmpty() && it !in NAV_UP } }
            .map { one -> one.replace('\n', ' ').let { if (it.length > 14) it.take(14) + "…" else it } }
        val acts = nodes[best].actionList.map { it.id }
        val fwd = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id in acts ||
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id in acts
        val back = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id in acts ||
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id in acts
        return (if (labels.isEmpty()) null else labels.first() to labels.last()) to ScrollMore(back, fwd)
    }

    /**
     * 접힘/펼침 — 폴드8 실측: 커버 1248x1972(비 0.63) · 메인 2448x1848(비 0.75). 가로세로 비로 가른다(회전해도 같다).
     * 좌표·레이아웃이 화면마다 달라 모델이 알아야 한다. 전엔 지식 노트에 「지금 접힌 상태」로 박혀 있어 펼쳐 쓸 때 틀렸다.
     */
    fun foldState(svc: AccessibilityService): String {
        val b = svc.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        val w = b.width(); val h = b.height()
        val open = minOf(w, h).toFloat() / maxOf(w, h) > 0.7f
        return (if (open) "펼침(메인 화면)" else "접힘(커버 화면)") + " ${w}x$h"
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, out: MutableList<AccessibilityNodeInfo>, depths: MutableList<Int>, sb: StringBuilder) {
        if (out.size >= MAX_NODES || !n.isVisibleToUser) return
        val text = n.text?.toString()?.trim().orEmpty()
        val desc = n.contentDescription?.toString()?.trim().orEmpty()
        val hint = n.hintText?.toString()?.trim().orEmpty()
        val useful = text.isNotEmpty() || desc.isNotEmpty() || n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
        var childDepth = depth
        if (useful) {
            val i = out.size
            out.add(n)
            depths.add(depth)
            sb.append("  ".repeat(depth.coerceAtMost(8)))
            sb.append('[').append(i).append("] ").append(n.className?.toString()?.substringAfterLast('.') ?: "View")
            if (text.isNotEmpty()) sb.append(" \"").append(clip(text)).append('"')
            if (desc.isNotEmpty() && desc != text) sb.append(" 설명=\"").append(clip(desc)).append('"')
            if (hint.isNotEmpty() && hint != text) sb.append(" 힌트=\"").append(clip(hint)).append('"')
            n.viewIdResourceName?.substringAfter(":id/")?.let { sb.append(" id=").append(it) }
            val f = ArrayList<String>()
            if (n.isClickable) f.add("클릭")
            if (n.isEditable) f.add("입력")
            if (n.isScrollable) f.add("스크롤")
            if (n.isCheckable) f.add(if (n.isChecked) "켜짐" else "꺼짐")
            if (n.isSelected) f.add("선택됨")
            if (!n.isEnabled) f.add("비활성")
            // 접힌 목록(네이버 지도 결과 시트)은 항목이 트리에 있지만 크기가 0이다 — 누를 수 없다고 알려 준다(2026-09-26 E6)
            val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
            if (r.width() <= 0 || r.height() <= 0) f.add("안보임")
            if (f.isNotEmpty()) sb.append(" [").append(f.joinToString(",")).append(']')
            sb.append('\n')
            childDepth = depth + 1
        }
        for (k in 0 until n.childCount) {
            val c = n.getChild(k) ?: continue
            walk(c, childDepth, out, depths, sb)
        }
    }

    fun labelOf(n: AccessibilityNodeInfo): String =
        n.text?.toString()?.takeIf { it.isNotBlank() }
            ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: n.hintText?.toString()?.takeIf { it.isNotBlank() }
            ?: childText(n, 0)
            ?: n.viewIdResourceName?.substringAfter(":id/")
            ?: (n.className?.toString()?.substringAfterLast('.') ?: "항목")

    /** 라벨 없는 컨테이너(예: 목록 한 줄)는 안쪽 글자로 이름을 붙인다 — 확인 게이트 판정에도 쓰인다. */
    private fun childText(n: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 3) return null
        val parts = ArrayList<String>()
        for (k in 0 until n.childCount) {
            val c = n.getChild(k) ?: continue
            // 안쪽에 따로 누를 수 있는 버튼은 다른 대상이다 — 그 글자는 이 항목의 이름에 넣지 않는다.
            // (토스 계좌 카드 안의 「송금」 버튼 글자가 카드 이름에 섞여, 카드를 여는 탭에 송금 확인 창이 떴다 — 2026-09-26)
            if (c.isClickable) continue
            val t = c.text?.toString()?.takeIf { it.isNotBlank() } ?: c.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?: childText(c, depth + 1)
            if (t != null) parts += t
            if (parts.size >= 3) break
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let { if (it.length > 60) it.take(60) + "…" else it }
    }

    private fun clip(s: String): String {
        val one = s.replace('\n', ' ')
        return if (one.length > 80) one.take(80) + "…" else one
    }
}
