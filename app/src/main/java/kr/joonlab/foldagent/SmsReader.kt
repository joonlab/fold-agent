package kr.joonlab.foldagent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.Telephony
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 문자 읽기(`sms_read` 도구) — 읽기 전용. READ_SMS(adb `pm grant … android.permission.READ_SMS` 또는 설정 > 앱 > 권한 > SMS).
 * 받은 문자·보낸 문자(inbox·sent)만. MMS·채팅+(RCS)는 이 표에 없다.
 * 번호는 숫자만 뽑아 뒤 8자리로 느슨하게 맞춘다(010-XXXX-XXXX · +8210XXXXXXXX · 010XXXXXXXX 이 섞여 저장된다).
 * 이름은 연락처(READ_CONTACTS)에서 번호를 찾아 맞춘다 — 「김민지팀장님」처럼 호칭이 붙어도 떼고 찾는다(사용자 2026-09-28: 이름으로 못 찾아 실패).
 *   연락처에 없으면 주소 칸 부분일치(발신 이름이 주소로 오는 발신자 — 카드사·통신사 등). 결과의 번호 옆엔 저장된 이름을 붙인다.
 * 최신 N건을 가져와 오래된 순으로 보여 준다(대화 흐름으로 읽힌다). (PKM android_notification-sms-mirror-to-mac_260922)
 *
 * 🔒 본문은 결과 글에만 — trace·logcat·recipes 에는 건수만(Agent 가 첫 줄만 기록한다).
 */
object SmsReader {

    const val NO_PERMISSION = "실패: 문자 읽기 권한이 없다 — 설정 > 애플리케이션 > 폴드 에이전트 > 권한 > SMS 에서 허용해야 한다. 사용자에게 그렇게 말할 것(메시지 앱을 열어 대신 찾지 않는다)"

    fun read(ctx: Context, with: String, query: String, limit: Int, sinceDays: Int): String {
        if (ctx.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return NO_PERMISSION
        val max = limit.coerceIn(1, 30)
        val where = StringBuilder("${Telephony.Sms.TYPE} IN (${Telephony.Sms.MESSAGE_TYPE_INBOX}, ${Telephony.Sms.MESSAGE_TYPE_SENT})")
        val args = ArrayList<String>()
        val w = with.trim()
        val digits = w.filter { it.isDigit() }
        val norm = "REPLACE(REPLACE(REPLACE(${Telephony.Sms.ADDRESS},'-',''),' ',''),'+','')"
        var contact: Pair<String, List<String>>? = null   // (연락처 이름, 번호 뒤 8자리들)
        if (w.isNotEmpty()) {
            if (digits.length >= 4) {
                where.append(" AND $norm LIKE ?")
                args += "%" + digits.takeLast(8)
            } else {
                contact = findContact(ctx, w)
                if (contact != null) {
                    where.append(" AND (" + contact.second.joinToString(" OR ") { "$norm LIKE ?" } + ")")
                    contact.second.forEach { args += "%$it" }
                } else {
                    where.append(" AND ${Telephony.Sms.ADDRESS} LIKE ?")
                    args += "%$w%"
                }
            }
        }
        val q = query.trim()
        if (q.isNotEmpty()) { where.append(" AND ${Telephony.Sms.BODY} LIKE ?"); args += "%$q%" }
        if (sinceDays > 0) {
            where.append(" AND ${Telephony.Sms.DATE} >= ?")
            args += (System.currentTimeMillis() - sinceDays.coerceAtMost(3650) * 86_400_000L).toString()
        }
        // LIMIT 을 정렬 글에 붙이지 않는다(공급자마다 거부) — 최신순으로 읽다 max 건에서 멈춘다
        val rows = ArrayList<String>()
        val names = HashMap<String, String?>()
        val f = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA)
        ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
            where.toString(), args.toTypedArray(), "${Telephony.Sms.DATE} DESC")?.use { c ->
            while (rows.size < max && c.moveToNext()) {
                val sent = c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT
                val dir = if (sent) "보냄 →" else "받음 ←" + if (c.getInt(4) == 0) "(안 읽음)" else ""
                val addr = c.getString(0) ?: "?"
                val who = nameOf(ctx, addr, names)?.let { "$it($addr)" } ?: addr
                rows += "- ${f.format(Date(c.getLong(2)))} $dir $who · ${(c.getString(1) ?: "").replace('\n', ' ').take(300)}"
            }
        } ?: return "실패: 문자 저장소를 읽지 못했다"
        val cond = buildList {
            if (w.isNotEmpty()) add("상대 「$w」" + (contact?.let { " = 연락처 ${it.first}" } ?: "")); if (q.isNotEmpty()) add("검색 「$q」"); if (sinceDays > 0) add("최근 ${sinceDays}일")
        }.joinToString(" · ").ifEmpty { "전체" }
        // 0건이면 다음 길을 알려 준다 — 도구 하나가 비었다고 포기하지 않게(2026-09-28 실패: 「번호를 알려 주면」으로 끝냄)
        if (rows.isEmpty()) return "문자 0건($cond) — 맞는 SMS 가 없다. " + when {
            contact != null -> "연락처 「${contact.first}」는 있다. 채팅+(RCS)·MMS 는 이 도구에 안 보이니 메시지 앱을 열어 그 사람 대화를 찾아볼 것"
            w.isNotEmpty() && digits.length < 4 && !hasContacts(ctx) -> "연락처 권한이 없어 이름으로 번호를 못 찾았다 — 연락처 앱에서 번호를 확인하거나 메시지 앱에서 이름으로 검색할 것"
            w.isNotEmpty() && digits.length < 4 -> "연락처에서 「$w」를 못 찾았다 — 메시지 앱에서 이름으로 검색하거나 연락처 앱에서 다른 표기를 찾아볼 것"
            else -> "메시지 앱에서 직접 찾아볼 수 있다(채팅+·MMS 는 이 도구에 안 보인다)"
        }
        return "문자 ${rows.size}건($cond · 최신 ${rows.size}건을 오래된 순):\n" + rows.asReversed().joinToString("\n")
    }

    // ── 연락처(READ_CONTACTS — 없으면 이름 연결 없이 예전처럼) ──
    private val HONORIFIC = Regex("(님|씨|팀장|부장|차장|과장|대리|실장|이사|상무|전무|대표|사장|원장|선생|교수|박사|매니저|프로)+$")
    private fun hasContacts(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** 이름 → (연락처 이름, 번호 뒤 8자리들). 호칭을 떼고 부분일치 — 여러 명이면 첫 사람(이름이 가장 짧은 = 가장 정확한) */
    private fun findContact(ctx: Context, name: String): Pair<String, List<String>>? {
        if (!hasContacts(ctx)) return null
        val key = name.replace(" ", "").replace(HONORIFIC, "")
        if (key.isBlank()) return null
        val P = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val found = LinkedHashMap<String, MutableSet<String>>()
        runCatching {
            ctx.contentResolver.query(P, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "REPLACE(${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME},' ','') LIKE ?", arrayOf("%$key%"), null)?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    val d = (c.getString(1) ?: "").filter { it.isDigit() }
                    if (d.length >= 4) found.getOrPut(n) { LinkedHashSet() } += d.takeLast(8)
                }
            }
        }
        val best = found.keys.minByOrNull { it.length } ?: return null
        return best to found.getValue(best).toList()
    }

    /** 번호 → 저장된 이름(없으면 null). 같은 번호는 한 번만 찾는다 */
    private fun nameOf(ctx: Context, addr: String, cache: HashMap<String, String?>): String? {
        if (!hasContacts(ctx) || addr.count { it.isDigit() } < 4) return null
        return cache.getOrPut(addr) {
            runCatching {
                ctx.contentResolver.query(android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(addr)),
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
        }
    }
}
