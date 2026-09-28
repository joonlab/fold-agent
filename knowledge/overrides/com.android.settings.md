★ 설정값은 화면보다 system_setting 이 먼저 — 없는 항목을 찾아 스크롤로 위아래를 오가지 말 것(2026-09-26 개발자 옵션 16번 왕복)
- 「충전 중 화면 계속 켜짐(Stay awake)」 = system_setting(set, stay_on_while_plugged_in, on) 한 번. 이 폰(One UI 9) 개발자 옵션 목록엔 안 보인다 — 화면에서 찾지 않는다
- 화면 자동 꺼짐 시간 = system_setting(set, screen_off_timeout, 「5분」). 선택지는 15초·30초·1·2·5·10분뿐 — 다른 값은 가장 가까운 칸으로 맞춰진다. 자동 회전은 quick_toggle(auto_rotate) 먼저, 타일이 없거나 실패하면 system_setting(set, accelerometer_rotation)
- 바꾸기는 사용자 확인을 거친다. 「권한 없음」이 오면 화면에서 찾지 말고 finish(success=false)로 ./dev.sh grant 가 필요하다고 말한다
- 「~가 켜져 있나/몇 분이냐」는 system_setting(get) 으로 값을 읽고 답한다(화면을 열지 않는다). 와이파이·비행기·데이터도 읽기만 된다
- 설정 항목이 어디 있는지 모르면: open_settings(APP_SEARCH_SETTINGS) → type_text(핵심 낱말 하나, submit=true). 결과 줄에 경로(예: 디스플레이 > …)가 나오면 그 줄을 tap. 결과가 없으면 이 폰에 없는 항목 — 바로 finish(success=false)로 말하고 대안을 안내
- 목록을 직접 볼 땐 한 방향으로 끝까지 내리고, 끝에서 못 찾으면 거기서 멈춘다(다시 올라가며 찾지 않는다)
- 모드 및 루틴: 설정 → 모드 및 루틴 → 「루틴」 탭 → 추가(id=menu_add_routine) → 조건(예: 충전 상태) → 동작 → 저장. 끝날 때 「루틴 실행 전 설정으로 돌아감」이 기본 · 조건이 이미 맞는 상태로 저장하면 곧바로 실행된다
- 동영상 배경: 설정 → 배경화면 및 스타일 → 잠금화면 → 배경화면 → 갤러리 「동영상」 탭(동영상 파일을 VIEW 로 열면 비디오 플레이어로 가서 배경 설정이 없다)
