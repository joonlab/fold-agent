plugins {
    // AGP 9 는 Kotlin 지원이 내장이다 — kotlin.android 플러그인을 넣으면 오히려 에러.
    id("com.android.application") version "9.4.1" apply false
    // 앱 화면(MainActivity)만 Compose — 참고 앱(cmux 관제실·기록)과 같은 검증 조합(DESIGN §3-3, refapps §10-2)
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
