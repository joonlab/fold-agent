package kr.joonlab.foldagent.jobs

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kr.joonlab.foldagent.MainActivity
import kr.joonlab.foldagent.R

/**
 * 맡긴 작업이 끝났다는 알림 — 채널 「작업 완료」. 누르면 맡긴 방이 열린다(MainActivity 가 [EXTRA_ROOM] 을 읽는다).
 * 알림 권한(Android 13+ POST_NOTIFICATIONS)이 없으면 조용히 건너뛴다 — 결과 턴은 이미 방에 붙어 있다.
 */
object JobNotify {
    const val CHANNEL = "jobs_done"
    /** MainActivity 가 읽는 extra — 열 방(Session.id) */
    const val EXTRA_ROOM = "openRoom"
    /** 「이어서 하기」 — 이 글을 [EXTRA_ROOM] 방에서 화면을 써도 되는 실행으로 다시 시작한다(MainActivity) */
    const val EXTRA_CONTINUE = "continueTask"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "작업 완료", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "홈맥에 맡긴 작업이 끝나면 알려요. 누르면 맡긴 대화방이 열려요"
        })
    }

    fun allowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** 자동 이어가기가 화면이 필요해 멈췄다 — 누르면 그 방에서 [task] 를 이어서 한다(화면 사용 가능) */
    fun postContinue(ctx: Context, sessionId: String, task: String, final: String) {
        if (!allowed(ctx)) { Log.i("FoldAgent", "jobs: 알림 권한 없음 — 이어서 하기 알림 생략"); return }
        runCatching {
            ensureChannel(ctx)
            val open = Intent(ctx, MainActivity::class.java).putExtra(EXTRA_ROOM, sessionId).putExtra(EXTRA_CONTINUE, task)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val id = ("cont:$sessionId").hashCode()
            val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val body = final.replace(Regex("\\s+"), " ").trim().take(300)
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("▶ 이어서 하기 — ${task.replace(Regex("\\s+"), " ").take(50)}")
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .addAction(Notification.Action.Builder(null, "이어서 하기", pi).build())
                .build()
            ctx.getSystemService(NotificationManager::class.java)?.notify(id, n)
        }.onFailure { Log.w("FoldAgent", "jobs: 이어서 하기 알림 실패 $it") }
    }

    fun post(ctx: Context, j: JobInfo, final: String, status: String) {
        if (!allowed(ctx)) { Log.i("FoldAgent", "jobs: 알림 권한 없음 — 알림 생략 ${j.jobId}"); return }
        runCatching {
            ensureChannel(ctx)
            val open = Intent(ctx, MainActivity::class.java).putExtra(EXTRA_ROOM, j.sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, j.jobId.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val head = when (status) { "ok" -> "✅ 맡긴 일 끝남"; "cancel" -> "⏹ 맡긴 일 취소됨"; else -> "⚠️ 맡긴 일 실패" }
            val body = final.replace(Regex("\\s+"), " ").trim().take(300)
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("$head — ${j.title.ifBlank { j.skill.ifBlank { j.tool } }}".take(80))
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            ctx.getSystemService(NotificationManager::class.java)?.notify(j.jobId.hashCode(), n)
        }.onFailure { Log.w("FoldAgent", "jobs: 알림 실패 $it") }
    }
}
