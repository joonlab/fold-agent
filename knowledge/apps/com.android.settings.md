# 설정 (com.android.settings)
★ 설정값은 화면보다 system_setting 이 먼저 — 없는 항목을 찾아 스크롤로 위아래를 오가지 말 것(2026-09-26 개발자 옵션 16번 왕복)
- 「충전 중 화면 계속 켜짐(Stay awake)」 = system_setting(set, stay_on_while_plugged_in, on) 한 번. 이 폰(One UI 9) 개발자 옵션 목록엔 안 보인다 — 화면에서 찾지 않는다
- 화면 자동 꺼짐 시간 = system_setting(set, screen_off_timeout, 「5분」). 선택지는 15초·30초·1·2·5·10분뿐 — 다른 값은 가장 가까운 칸으로 맞춰진다. 자동 회전은 quick_toggle(auto_rotate) 먼저, 타일이 없거나 실패하면 system_setting(set, accelerometer_rotation)
- 바꾸기는 사용자 확인을 거친다. 「권한 없음」이 오면 화면에서 찾지 말고 finish(success=false)로 ./dev.sh grant 가 필요하다고 말한다
- 「~가 켜져 있나/몇 분이냐」는 system_setting(get) 으로 값을 읽고 답한다(화면을 열지 않는다). 와이파이·비행기·데이터도 읽기만 된다
- 설정 항목이 어디 있는지 모르면: open_settings(APP_SEARCH_SETTINGS) → type_text(핵심 낱말 하나, submit=true). 결과 줄에 경로(예: 디스플레이 > …)가 나오면 그 줄을 tap. 결과가 없으면 이 폰에 없는 항목 — 바로 finish(success=false)로 말하고 대안을 안내
- 목록을 직접 볼 땐 한 방향으로 끝까지 내리고, 끝에서 못 찾으면 거기서 멈춘다(다시 올라가며 찾지 않는다)
- 모드 및 루틴: 설정 → 모드 및 루틴 → 「루틴」 탭 → 추가(id=menu_add_routine) → 조건(예: 충전 상태) → 동작 → 저장. 끝날 때 「루틴 실행 전 설정으로 돌아감」이 기본 · 조건이 이미 맞는 상태로 저장하면 곧바로 실행된다
- 동영상 배경: 설정 → 배경화면 및 스타일 → 잠금화면 → 배경화면 → 갤러리 「동영상」 탭(동영상 파일을 VIEW 로 열면 비디오 플레이어로 가서 배경 설정이 없다)
부르는 이름: 설정, 세팅, 환경설정, Settings, 폰 설정
용도: 배터리·와이파이·블루투스·디스플레이 등 기기 설정 변경
열면: 설정 메인이 아니라 마지막으로 쓴 '배터리' 화면(실제 top activity는 com.samsung.android.lool 디바이스 케어 BatteryActivity)
화면:
상단 좌측 '상위 메뉴로 이동' 뒤로가기 ImageButton, 제목 '배터리'.
본문: 잔량 %(id=title_text), 남은 시간, '일일 사용량' Spinner(id=graph_type_spinner), 사용량 그래프, '문제 확인' 버튼(id=nudge_text), 앱별 배터리 사용 목록(id=title/info).
설정 메인 화면 구조는 이번 덤프에 없음. (일반 지식) 메인 상단에 검색 돋보기가 있고 항목 목록이 이어짐
하는 법:
- 특정 설정 페이지 열기(와이파이·블루투스·배터리·디스플레이 등): open_settings(page) 우선 → 실패 시 open_app('설정') → 상단 검색 → type_text(설정 이름, submit) (일반 지식)
- 배터리 상태 확인: open_settings('battery') → 잔량(id=title_text)·남은 시간(id=title_description) 읽기
- 설정 앱이 서브 페이지에서 열렸을 때 메인으로: '상위 메뉴로 이동' 버튼 탭 또는 global(back) 반복
주의:
- 런처로 열면 메인이 아닌 마지막 서브 페이지(예: 배터리/디바이스 케어)가 뜰 수 있음 — 제목을 확인할 것
- 초기화·앱 삭제·강제 중지·계정 삭제 등 되돌릴 수 없는 설정 버튼은 irreversible로 취급하고 사용자 확인 필요
- 배터리 화면의 앱별 목록에 사용 앱 이름이 노출됨 — 그대로 읽어주지 말 것
