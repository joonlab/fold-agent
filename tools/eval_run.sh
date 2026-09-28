#!/bin/bash
# tools/eval_run.sh <id> "<문장>" [세션] — HOME 에서 시작해 ask, 끝나면 화면 캡처(NOHOME=1 이면 지금 화면에서)
# 캡처할 디스플레이를 고르려면 EVAL_DISPLAY_ID 에 `adb shell dumpsys SurfaceFlinger --display-id` 로 본 id 를 넣는다(비우면 기본 화면)
export PATH=$PATH:${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools
R="$(cd "$(dirname "$0")/.." && pwd)"; D=$R/_eval_$(date +%Y%m%d); mkdir -p "$D/shots"
id=$1; s=$2; sess=${3:-eval-$1-$(date +%H%M%S)}
[ -z "$NOHOME" ] && adb shell input keyevent HOME && sleep 1
echo "▶ $id [$sess] $(date +%H:%M:%S)"
"$R/dev.sh" ask "$s" "$sess"
adb shell screencap ${EVAL_DISPLAY_ID:+-d $EVAL_DISPLAY_ID} -p /data/local/tmp/eval-$id.png && adb pull -q /data/local/tmp/eval-$id.png "$D/shots/eval-$id.png"
echo "■ $(date +%H:%M:%S) last run: $(adb shell ls -t /sdcard/Android/data/kr.joonlab.foldagent/files/runs/$(date +%Y%m%d)/ | head -1)"
