package kr.joonlab.foldagent.records

import android.content.Context
import android.util.Log
import kr.joonlab.foldagent.Prefs

/**
 * 기록 저장소 싱글턴 접근자. Application 클래스가 없으므로 AgentService·MainActivity 가 처음 쓸 때 init 한다.
 * 한 프로세스에 FileRecordStore 하나 — 서비스와 화면이 같은 인스턴스·같은 락을 쓴다.
 */
object Records {
    @Volatile lateinit var store: RecordStore
    @Volatile private var inited = false

    /** 여러 번 불려도 한 번만(applicationContext 로 — 액티비티를 붙잡지 않게) */
    fun init(ctx: Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            val app = ctx.applicationContext
            val s = FileRecordStore(app)
            store = s
            inited = true
            // 인덱스 적재(없거나 깨졌으면 재구성) + 휴지통 기한 지난 것 영구 삭제 — 프로세스 시작 때 한 번, 메인 스레드 밖에서
            Thread({
                runCatching {
                    s.warmUp()
                    s.purge(null, Prefs.trashDays(app))
                }.onFailure { Log.w("FoldAgent", "records: 시작 정리 실패 $it") }
            }, "records-init").start()
        }
    }
}
