#!/bin/bash
# 같은 요청 묶음을 돌려 단계 수·시간·결과를 한 줄씩 — 지식 반영 전후 비교용
# 사용: tools/bench.sh "요청1" "요청2" …
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
P="$(cd "$(dirname "$0")/.." && pwd)"
for q in "$@"; do
  adb shell input keyevent KEYCODE_HOME; sleep 1
  : # (logcat 을 비우지 않는다 — 사용자 실행의 증거가 지워진다)
  t0=$(date +%s)
  since=$(adb shell 'date "+%m-%d %H:%M:%S.000"' | tr -d '\r')
  adb shell am start -n kr.joonlab.foldagent/.MainActivity --es cmd "'$q'" >/dev/null 2>&1
  for i in $(seq 1 150); do adb logcat -d -s FoldAgent -T "$since" | grep -qE "finish\[" && break; sleep 1; done
  t1=$(date +%s)
  log=$(adb logcat -d -s FoldAgent -T "$since")
  steps=$(echo "$log" | grep -c " → ")
  path=$(echo "$log" | grep -oE "→ [a-z_]+ .{0,50}" | sed 's/→ //' | tr '\n' '|' | cut -c1-160)
  fin=$(echo "$log" | grep -oE "finish\[[a-z]+\]: .*" | cut -c1-90)
  printf "%-26s %2s단계 %3ss  %s\n    경로: %s\n" "$q" "$steps" "$((t1-t0))" "$fin" "$path"
  sleep 6   # 👍/👎 패널이 사라지고 다음 실행과 겹치지 않게
done
adb shell input keyevent KEYCODE_HOME
