package kr.joonlab.foldagent

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 알림 읽기(`notifications` 도구) — 인증번호·누가 연락했나를 알림창을 열지 않고 본다.
 *
 * 권한은 설정의 「알림 접근」(사람 또는 adb `cmd notification allow_listener`). 읽는 곳 두 군데를 합친다:
 *   - 지금 알림창에 남은 것(getActiveNotifications)
 *   - 이 앱이 켜진 뒤 받은 것 메모리 버퍼(최대 [MAX]) — 지워진 알림도 남는다. 앱이 다시 시작되면 비는 게 정상(디스크에 안 쓴다)
 * 빼는 것: 진행 중(ONGOING — 음악·다운로드) · 묶음 머리글(GROUP_SUMMARY) · 우리 앱 · 30초 안 같은 내용(진행률·재게시).
 * (필터 기준은 clipbridge NotifyMirror 실측 — PKM android_notification-sms-mirror-to-mac_260922)
 *
 * 🔒 알림에는 카톡 대화·OTP 가 흐른다 — 본문은 모델에게 돌려줄 결과 글에만. logcat·trace·recipes 에는 건수만(Agent 가 그렇게 기록한다).
 */
class NotifyListener : NotificationListenerService() {

    data class Item(val t: Long, val pkg: String, val title: String, val text: String) {
        val sig get() = "$pkg|$title|$text"
    }

    companion object {
        private const val TAG = "FoldAgent"
        private const val MAX = 200
        private const val DUP_MS = 30_000L
        @Volatile var instance: NotifyListener? = null
            private set
        private val buf = ArrayDeque<Item>()   // 오래된 것 → 새것. LOCK = buf

        fun component(ctx: Context) = ComponentName(ctx, NotifyListener::class.java)

        fun granted(ctx: Context): Boolean =
            runCatching { ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(ctx)) }.getOrDefault(false)

        const val NO_PERMISSION = "실패: 알림 접근 권한이 꺼져 있다 — 설정 > 알림 > 기기 및 앱 알림(또는 특별한 접근 권한 > 알림 접근)에서 폴드 에이전트를 켜야 한다. 사용자에게 그렇게 말할 것(알림창을 열어 대신 찾지 않는다)"

        /** 알림 한 건 → 항목. 뺄 것이면 null */
        private fun itemOf(own: String, sbn: StatusBarNotification): Item? {
            val n = sbn.notification ?: return null
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
            if (sbn.packageName == own) return null
            val ex = n.extras ?: return null
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
            if (title.isEmpty() && text.isEmpty()) return null
            return Item(sbn.postTime, sbn.packageName, title, text)
        }

        /** 같은 내용이 30초 안에 또 오면 버린다(진행률 갱신·재게시). true = 넣음 */
        private fun remember(it: Item): Boolean = synchronized(buf) {
            if (buf.any { b -> b.sig == it.sig && kotlin.math.abs(b.t - it.t) < DUP_MS }) return false
            buf.addLast(it)
            while (buf.size > MAX) buf.removeFirst()
            true
        }

        private val labels = HashMap<String, String>()
        private fun label(ctx: Context, pkg: String): String = synchronized(labels) {
            labels.getOrPut(pkg) {
                runCatching { ctx.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } }
                    .getOrDefault(pkg.substringAfterLast('.'))
            }
        }

        /**
         * 도구 본체. 결과 첫 줄은 건수·조건만(본문 없음) — Agent 가 첫 줄만 기록에 남긴다.
         * @param app 앱 이름 또는 패키지 일부 · sinceMin 최근 몇 분(기본 60) · limit 최대 30 · query 제목·본문에 들어갈 글
         */
        fun query(ctx: Context, app: String, sinceMin: Int, limit: Int, query: String): String {
            if (!granted(ctx)) return NO_PERMISSION
            val svc = instance
            val notes = ArrayList<String>()
            val active = if (svc == null) {
                // 권한은 있는데 연결이 안 됐다(재설치 직후 등) — 다시 붙여 달라고 하고 이번엔 버퍼만
                runCatching { NotificationListenerService.requestRebind(component(ctx)) }
                notes += "알림 연결 준비 중이라 지금 알림창에 남은 것은 못 읽었다(이 앱이 받은 것만)"
                emptyList()
            } else runCatching { svc.activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList()).mapNotNull { itemOf(ctx.packageName, it) }
            val since = System.currentTimeMillis() - sinceMin.coerceIn(1, 7 * 24 * 60) * 60_000L
            val max = limit.coerceIn(1, 30)
            val appKey = app.trim().lowercase().replace(" ", "")
            val q = query.trim().lowercase()
            // 버퍼 + 알림창 → 같은 내용 30초 안이면 하나로(알림창에 남은 것은 버퍼에도 있다)
            val all = ArrayList<Item>()
            (synchronized(buf) { buf.toList() } + active).sortedByDescending { it.t }.forEach { c ->
                if (all.none { it.sig == c.sig && kotlin.math.abs(it.t - c.t) < DUP_MS }) all += c
            }
            val hits = all.asSequence()
                .filter { it.t >= since }
                .filter { appKey.isEmpty() || it.pkg.lowercase().contains(appKey) || label(ctx, it.pkg).lowercase().replace(" ", "").contains(appKey) }
                .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.text.lowercase().contains(q) }
                .take(max).toList()
            val cond = buildList {
                add("최근 ${sinceMin}분"); if (appKey.isNotEmpty()) add("앱 「$app」"); if (q.isNotEmpty()) add("검색 「$query」")
            }.joinToString(" · ")
            val tail = if (notes.isEmpty()) "" else "\n(" + notes.joinToString(" · ") + ")"
            if (hits.isEmpty()) return "알림 0건($cond) — 조건에 맞는 알림이 없다. 알림창에 남은 것과 이 앱이 켜진 뒤 받은 것만 보인다(시간을 넓히거나 앱 이름을 바꿔 볼 수 있다)$tail"
            val f = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA)
            return "알림 ${hits.size}건($cond · 새것부터):\n" + hits.joinToString("\n") { i ->
                "- ${f.format(Date(i.t))} · ${label(ctx, i.pkg)} · ${i.title.take(60).ifEmpty { "(제목 없음)" }} · ${i.text.replace('\n', ' ').take(200)}"
            } + tail
        }
    }

    override fun onListenerConnected() {
        instance = this
        Log.i(TAG, "notify listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        Log.i(TAG, "notify listener disconnected")
        runCatching { NotificationListenerService.requestRebind(component(this)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        itemOf(packageName, sbn)?.let { remember(it) }   // 본문은 로그에 안 남긴다
    }
}
