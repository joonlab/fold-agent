#!/bin/bash
# foldagent 개발 헬퍼 — 빌드 · 무선 설치 · 실행을 한 곳에서
set -e
# 🚨 gradlew 가 org.gradle.java.home 을 읽기 «전에» java 를 찾는다. keg-only openjdk 는 PATH 에 없다.
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
P="$(cd "$(dirname "$0")" && pwd)"
PKG=kr.joonlab.foldagent
APK="$P/app/build/outputs/apk/debug/app-debug.apk"

case "${1:-run}" in
  pair)    adb pair "$2" "$3" ;;            # ./dev.sh pair <IP:페어링포트> <6자리>
  connect) adb connect "$2" ;;
  build)   "$P/gradlew" -p "$P" assembleDebug ;;
  install) adb install -r "$APK" && "$0" ensure-bound ;;
  ensure-bound)
    # 재설치 뒤 접근성 연결이 「Binding」에 멈추는 일이 있다(2026-09-26, 채팅 화면이 앞에 있을 때 재현 — 원인 미확정) → 멈췄으면 rebind
    sleep 3
    # 「Bound services」는 여러 줄로 찍혀 한 줄 grep 으로는 못 찾는다 — 「Binding services」에 남아 있으면 멈춘 것
    if adb shell dumpsys accessibility | grep "Binding services" | grep -q "$PKG"; then echo "⚠️ 접근성 연결 멈춤 → rebind"; "$0" rebind
    else echo "✅ 접근성 연결됨"; fi ;;
  run)
    "$P/gradlew" -p "$P" assembleDebug
    adb install -r "$APK"
    "$0" ensure-bound
    # ⚠️ 설치만 하면 Doze 중엔 서비스가 안 뜬다(Android 12+ 백그라운드 FGS 제한) → 한 번 연다
    adb shell am start -n "$PKG/.MainActivity"
    echo "✅ 폰에서 떴습니다" ;;
  grant)
    # ⚠️ 민감 권한 — 화면 읽기·대신 조작(접근성) + 마이크. 기존 접근성 서비스 목록은 보존하고 덧붙인다.
    adb shell pm grant "$PKG" android.permission.RECORD_AUDIO
    # 밝기(WRITE_SETTINGS)·방해금지/무음(알림 정책 접근) — 사용자 허용 2026-09-26
    adb shell appops set "$PKG" WRITE_SETTINGS allow
    adb shell cmd notification allow_dnd "$PKG"
    # 충전 중 화면 켜짐(system_setting) — 허용 목록 키만·확인 게이트 뒤에 쓴다(2026-09-26 결정). 실패해도(구 APK 등) 나머지 grant 는 계속
    adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS || echo "⚠️ WRITE_SECURE_SETTINGS 허용 실패 — 새 APK 설치 뒤 다시 ./dev.sh grant"
    SVC="$PKG/$PKG.AgentService"
    cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
    case "$cur" in *"$SVC"*) ;; ""|null) adb shell settings put secure enabled_accessibility_services "$SVC" ;;
      *) adb shell settings put secure enabled_accessibility_services "$cur:$SVC" ;; esac
    adb shell settings put secure accessibility_enabled 1
    adb shell settings get secure enabled_accessibility_services ;;
  rebind)
    # 설정엔 켜져 있는데 연결이 「Binding」에 멈췄을 때 — 목록에서 뺐다가 다시 넣어 시스템이 새로 붙게 한다
    SVC="$PKG/$PKG.AgentService"
    cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
    rest=$(echo "$cur" | tr ':' '\n' | grep -v "$SVC" | paste -sd: -)
    adb shell settings put secure enabled_accessibility_services "${rest:-null}"; sleep 1
    adb shell settings put secure enabled_accessibility_services "${rest:+$rest:}$SVC"; sleep 2
    adb shell dumpsys accessibility | grep "Binding services" | grep -q "$PKG" && echo "⚠️ 아직 연결 중 — 잠시 뒤 다시 확인" || echo "✅ 재연결됨" ;;
  say)     adb shell am start -n "$PKG/.MainActivity" --es cmd "'$2'" ;;   # ./dev.sh say "알람 7시에 맞춰줘"
  cancel)  adb shell am start -n "$PKG/.MainActivity" --ez cancel true >/dev/null ;;   # 진행 중인 작업 취소(시험용)
  ask)
    # say 후 이번 요청이 끝날 때까지 기다려 단계·결과만 보여 준다. logcat -c 는 쓰지 않는다(사용자 증거) — 시각 문자열로 거른다
    # ./dev.sh ask "요청" [세션이름]  — 세션을 주면 그 대화로(시험마다 새 대화)
    T=$(date '+%m-%d %H:%M:%S')
    adb shell am start -n "$PKG/.MainActivity" --es cmd "'$2'" ${3:+--es session "$3"} >/dev/null 2>&1
    lines() { adb logcat -d -v time -s FoldAgent | awk -v t="$T" 'substr($0,1,14) >= t' | grep -E 'step [0-9]+ →|finish\['; }
    # 앱이 죽으면 finish 가 영영 안 온다(2026-09-26 QA20 — 10분 대기) → 크래시 버퍼도 본다
    crashed() { adb logcat -d -v time -b crash | awk -v t="$T" 'substr($0,1,14) >= t' | grep -q "Process: $PKG"; }
    for i in $(seq 1 240); do sleep 2; lines | grep -q 'finish\[' && break; crashed && { echo "💥 앱이 죽음:"; adb logcat -d -v time -b crash | awk -v t="$T" 'substr($0,1,14) >= t' | head -8; break; }; done
    lines | sed 's/^\([0-9-]* [0-9:]*\).*FoldAgent([0-9]*): /\1 /' ;;
  alog)    adb logcat -s FoldAgent ;;
  pull-logs)
    # 폰의 기록·지식 전부를 맥으로 (분석용 · git 제외 _data/phone)
    mkdir -p "$P/_data"
    adb pull "/sdcard/Android/data/$PKG/files/." "$P/_data/phone" >/dev/null && echo "✅ $P/_data/phone"
    find "$P/_data/phone/runs" -name '*.jsonl' | wc -l | xargs echo "실행 기록 수:" ;;
  push-knowledge)
    # 정본 knowledge/knowledge.md → 폰. 다음 실행부터 시스템 프롬프트에 들어간다
    adb push "$P/knowledge/knowledge.md" "/sdcard/Android/data/$PKG/files/knowledge.md" >/dev/null
    [ -f "$P/knowledge/aliases.json" ] && adb push "$P/knowledge/aliases.json" "/sdcard/Android/data/$PKG/files/aliases.json" >/dev/null
    if [ -d "$P/knowledge/apps" ]; then
      adb shell mkdir -p "/sdcard/Android/data/$PKG/files/apps"
      adb push "$P/knowledge/apps/." "/sdcard/Android/data/$PKG/files/apps/" >/dev/null
    fi
    echo "✅ 폰에 반영 (knowledge.md + apps/ $(ls "$P/knowledge/apps" 2>/dev/null | wc -l | tr -d ' ')개)" ;;
  analyze) "$0" pull-logs && python3 "$P/tools/analyze.py" "$P/_data/phone" ;;
  log)     adb logcat --pid=$(adb shell pidof "$PKG") ;;
  devices) adb devices -l ;;
  *) echo "사용: ./dev.sh [pair|connect|build|install|run|grant|say|alog|pull-logs|push-knowledge|analyze|log|devices]" ;;
esac
