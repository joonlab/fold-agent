# 폴드 에이전트 — 이 폰에 대해 아는 것
(맥의 Claude Code 가 수집·실기 검증해 만든 노트. 앱별 상세 요령은 그 앱에 들어갈 때 [앱 요령]으로 따로 온다.)

## 사용자
- 한국어로 말한다. 음성 인식이 고유명사(아파트·가게·방 이름)를 자주 틀린다 — 문맥으로 바로잡아 검색한다.
- (여기에 자기 맥락을 적는다. 예: 「자주 가는 곳: 집=○○아파트 · 회사=○○빌딩」, 「카카오톡 『메모장』 = 나 혼자 쓰는 방」. 공개 저장소에는 넣지 말 것)

## 기기
- Galaxy Z Fold8(SM-F971N) · Android 17 · One UI 9.0. 접힌 상태(커버 화면)로 쓰는 일이 많다 — 화면이 좁아 목록이 잘릴 수 있으니 scroll 로 확인.
- 접힘/펼침과 해상도는 [현재 화면] 머리말에 매번 나온다(커버 1248x1972 · 메인 2448x1848). 좌표·레이아웃은 그 상태 기준
- 설정 앱을 런처로 열면 첫 화면이 아니라 마지막 사용 화면이 복원됨: 덤프에서 디바이스 케어 '배터리' 화면(com.samsung.android.lool BatteryActivity)이 떴고 좌상단 '상위 메뉴로 이동' 버튼([1], 설명=상위 메뉴로 이동)이 있었다.…
- 설정 화면 트리는 풍부함(TextView 라벨·id=title/info·[클릭]·[스크롤] 노출) — 항목을 텍스트로 찾아 tap 가능
- 알림창 열기: global(notifications). 한 번 더 아래로 스크롤하거나 두 번 내리면 퀵 설정 패널 전체가 펼쳐진다(일반 지식)
- 손전등·음량·밝기·소리 모드·방해금지는 전용 도구로. 블루투스·자동 회전·NFC·절전·다크 모드 = quick_toggle. 와이파이·비행기 모드·모바일 데이터는 끄지 않는다(비서 연결이 끊긴다). 설정값 읽기·충전 중 화면 켜짐·화면 꺼짐 시간 = system_setting(설정 화면에 안 보여도 값은 있다) · 자동 회전 타일이 없거나 실패하면 system_setting
- 메인 화면 배경·잠금화면·루틴의 「(메인 화면)」 동작은 접힌 상태에선 회색(「커버 화면에서는 추가할 수 없어요」) — 펼쳐 달라고 말하고 멈춘다. 배경화면 및 스타일도 지금 켜진 화면만 바꾼다
- 커버 화면과 메인 화면의 홈 배치는 따로다 — 한쪽에 둔 앱·폴더는 다른 쪽에 안 생긴다. 어느 쪽인지는 [현재 화면]의 접힘/펼침으로

## 바로가기 (화면을 누르기 전에 먼저 고려)
- 설정 화면 open_settings: 와이파이 설정 열어줘=WIFI_SETTINGS · 와이파이 켜줘(끄기는 안 함)=panel.action.WIFI · 블루투스 설정=BLUETOOTH_SETTINGS · 새 블루투스 기기 연결=BLUETOOTH_PAIRING_SETTINGS · 비행기 모드=AIRPLANE_MODE_SETTINGS · 모바일 데이터 / 데이터 사용량=DATA_USAGE_SETTINGS · 데이터 절약 모드=DATA_SAVER_SETTINGS · 핫스팟 켜줘 / 테더링=TETHER_SETTINGS · NFC 켜줘=NFC_SETTINGS · VPN 설정=VPN_SETTINGS · 통신사 네트워크 / 이동통신망=NETWORK_PROVIDER_SETTINGS · 화면 설정 / 디스플레이=DISPLAY_SETTINGS · 자동 밝기=ADAPTIVE_BRIGHTNESS_SETTINGS · 다크 모드=DARK_THEME_SETTINGS · 화면 꺼짐 시간=SCREEN_TIMEOUT_SETTINGS · 자동 회전=AUTO_ROTATE_SETTINGS · 글자 크기 / 글꼴=TEXT_READING_SETTINGS · 배경화면=WALLPAPER_SETTINGS 쓰지 않음(AOSP 선택기) — 사진은 갤러리 그 사진→옵션 더보기→배경화면으로 설정, 동영상·스타일은 설정 앱→배경화면 및 스타일 · 소리 설정 / 벨소리 / 진동=SOUND_SETTINGS · 볼륨 조절=panel.action.VOLUME · 방해금지 모드=ZEN_MODE_SETTINGS · 알림 설정=NOTIFICATION_SETTINGS · 앱 알림 모아보기 / 앱별 알림 끄기=ALL_APPS_NOTIFICATION_SETTINGS · 알림 기록 보기=NOTIFICATION_HISTORY · 잠금화면 알림=LOCK_SCREEN_NOTIFICATIONS_SETTINGS · 배터리 / 절전 모드=BATTERY_SAVER_SETTINGS · 배터리 화면 / 배터리 사용량=com.samsung.android.sm.ACTION_BATTERY · 앱 목록 / 애플리케이션=APPLICATION_SETTINGS · 기본 앱 설정 / 링크 열기=MANAGE_DOMAIN_URLS · 다른 앱 위에 표시 권한=action.MANAGE_OVERLAY_PERMISSION · 사용 정보 접근 권한=USAGE_ACCESS_SETTINGS · 위치 켜줘 / GPS=LOCATION_SOURCE_SETTINGS · 개인정보 보호=PRIVACY_SETTINGS · 보안 설정=SECURITY_SETTINGS · 잠금화면 설정=LOCK_SCREEN_SETTINGS · 지문 등록=FINGERPRINT_SETTINGS · 접근성=ACCESSIBILITY_SETTINGS · 저장공간 / 용량 확인=INTERNAL_STORAGE_SETTINGS · 날짜와 시간=DATE_SETTINGS · 언어 설정=LOCALE_SETTINGS · 키보드 설정=INPUT_METHOD_SETTINGS · 계정 / 동기화=SYNC_SETTINGS · 휴대전화 정보 / 기기 이름=DEVICE_INFO_SETTINGS · 개발자 옵션=APPLICATION_DEVELOPMENT_SETTINGS · 측면 버튼 설정=com.samsung.android.intent.action.SIDE_KEY_SETTINGS · 커버 화면에서 쓸 앱 / 커버 화면 알림=com.samsung.settings.APPS_ALLOWED_COVER_SCREEN · 설정 앱 열어줘=SETTINGS
- 알람 맞추기 (예: 내일 7시 알람): set_alarm(hour, minute, label?) — 시계 앱 android.intent.action.SET_ALARM
- 알람 목록 보기: open_screen(alarms) · 타이머 목록: open_screen(timers)
- 타이머 (예: 3분 타이머): set_timer(seconds, label?) — android.intent.action.SET_TIMER
- 스톱워치: stopwatch(start | stop | reset | lap | get) — 시계 앱을 알아서 조작
- 전화 걸기 화면(다이얼만): open_link("tel:{번호}") — 다이얼 화면에 번호만 채워짐, 통화 버튼은 사용자 확인 후 tap(irreversible)
- 문자 작성 화면(발송 안 됨): open_link("smsto:{번호}?body={URL인코딩 본문}", "com.samsung.android.messaging") — 작성창만 열림, 보내기는 사용자 확인 후
- 캘린더 일정 추가 화면: open_screen(new_event, title, begin, end) — 제목·시간이 채워진 작성 화면, 저장은 사용자 확인 후
- 카메라: open_screen(photo_camera | video_camera | qr_scan) — 촬영·녹화 버튼은 사용자가 원할 때만
- 웹 검색(구글): open_link("https://www.google.com/search?q={검색어}", "com.android.chrome")
- 웹 검색(삼성 인터넷): open_link("https://www.google.com/search?q={검색어}", "com.sec.android.app.sbrowser")
- 네이버 검색: open_link("https://m.search.naver.com/search.naver?query={검색어}")
- 유튜브 검색: open_link("https://www.youtube.com/results?search_query={검색어}", "com.google.android.youtube")
- 지도에서 장소 검색(구글 지도): open_link("geo:0,0?q={장소}", "com.google.android.apps.maps")
- 네이버 지도 장소 검색: open_link("nmap://search?query={장소}&appname=kr.joonlab.foldagent", "com.nhn.android.nmap")

## 검증된 딥링크 (✅ 폰에서 실제로 열어 검색어 반영까지 확인 — 앱 화면을 누르기 전에 먼저 쓴다. {query} 는 URL 인코딩)
- YouTube 검색 결과: open_link("https://www.youtube.com/results?search_query={query}")
- Chrome 구글 검색 결과: open_link("https://www.google.com/search?q={query}")
- TMAP 장소 검색: open_link("tmap://search?name={query}")
- TMAP 지오 인텐트로 검색: open_link("geo:0,0?q={query}", package="com.skt.tmap.ku")
- 지도 장소 검색: open_link("geo:0,0?q={query}", package="com.google.android.apps.maps")
- 지도 검색 결과(URL): open_link("https://www.google.com/maps/search/?api=1&query={query}")
- Play 스토어 앱 검색 결과: open_link("market://search?q={query}&c=apps", package="com.android.vending")
- YT Music 검색 결과: open_link("https://music.youtube.com/search?q={query}")
- 쿠팡 검색 결과: open_link("coupang://search?q={query}")
- 네이버지도 장소 검색: open_link("nmap://search?query={query}&appname=kr.joonlab.foldagent")
- LinkedIn 통합 검색 결과: open_link("https://www.linkedin.com/search/results/all/?keywords={query}")
- LinkedIn 사람 검색 결과: open_link("https://www.linkedin.com/search/results/people/?keywords={query}")
- LinkedIn 채용공고 검색: open_link("https://www.linkedin.com/jobs/search/?keywords={query}")
- 포토 사진 검색: open_link("https://photos.google.com/search/{query}")
- 카카오맵 장소 검색: open_link("kakaomap://search?q={query}")
- 카카오 T 통합 검색: open_link("kakaot://integrated_search")
- Drive 검색 결과: open_link("https://drive.google.com/drive/search?q={query}")
- Threads 검색 결과: open_link("https://www.threads.com/search?q={query}")
- Threads 검색(앱 스킴): open_link("barcelona://search")
- Perplexity 검색 결과로 바로: open_link("https://www.perplexity.ai/search?q={query}")

## 실측 절차가 있는 앱 (그 앱의 [앱 요령] 맨 위 ★ 절차를 따른다)
- 설정: 설정값은 화면보다 system_setting 이 먼저 — 없는 항목을 찾아 스크롤로 위아래를 오가지 말 것(2026-09-26 개발자 옵션 16번 왕복)
- 네이버지도: 장소 정보 보기·캡처: open_link nmap://search?query={장소}&appname=kr.joonlab.foldagent → 결과 목록 첫 장소의 이름 줄(Button, 「서울숲숲,숲길」처럼 이름+분…
- 노트: 노트에 새로 정리할 땐 write_note 한 번으로, 기존 노트 끝에 덧붙일 땐 write_note mode=append(제목 그대로)로. 아래는 도구가 못 하는 편집(중간 고치기·지우기)용 실측
- com.sec.android.app.launcher: 폴더 만들기·여러 앱 묶기는 드래그 없이 된다(2026-09-28 펼친 화면 실측)
- 음성 녹음: 녹음 → 텍스트 변환: 녹음 열기 → 「텍스트 변환 어시스트」 → 「텍스트 변환」 → 「언어 선택」 창이 한 번 더 뜬다 — 언어를 고르고 확인은 id=select_language_trans_text(앞 메뉴와 이름…
- 갤러리: 이번 작업에서 capture 로 찍은 사진(FoldAgent_…)을 보낼 땐 갤러리를 열지 말고 share_images 로 바로 공유 화면을. 아래는 그 밖의 사진(사용자가 찍은 것 등) 고르기·공유 실측 — 목록의…
- TMAP: 경로 설정 — 2026-09-26 폰에서 실측. 사용자가 말한 출발지·도착지·옵션을 전부 반영한다(출발지를 말했는데 현재 위치로 대신하면 실패다).
- TMAP 경로(출발지 지정·옵션): geocode(출발지)·geocode(도착지) → tmap://route?startname=…&startx=…&starty=…&goalname=…&goalx=…&goaly=… (좌표 없으면 경로 못 잡음)

## 공통 주의
- 금융·인증 앱도 대개 화면을 읽을 수 있다(토스 확인). 화면이 비어 보이면 먼저 wait 로 로딩을 기다리고 다시 본다 — 「이동했어요」·로딩 화면은 보안 차단이 아니다. 기다려도 비어 있고 look 도 검게 나올 때만 보안 화면으로 보고 finish 로 넘긴다. 「원격제어/접근성 앱 실행 중」 경고로 앱이 닫히면 그대로 알린다
- PIN·패턴·비밀번호·지문 확인 화면(잠금 해제, 지문/얼굴 등록, 보안 설정 진입)에서는 절대 입력하지 않는다 — 사용자에게 넘김
- 되돌릴 수 없는 동작은 tap(irreversible=true) 로 표시하고 사용자 확인 후만: 통화 버튼, 문자 '보내기', 일정 '저장', 알람 '삭제', 소프트웨어 '다운로드 및 설치', '초기화'(android.settings.MASTER_CLEAR / RESET_SETTINGS 는 열지도 말 것), 앱 '삭제'·'강제 중지'·'데이터 삭제'
- 앱 첫 실행 시 권한 팝업(허용/거부), 로그인 창, 업데이트·광고 팝업이 먼저 뜰 수 있다 — 목표 화면인지 트리 텍스트로 확인 후 진행. 권한은 사용자 의도에 맞을 때만 '앱 사용 중에만 허용'
- 인벤토리 덤프는 5~12초 뒤 캡처라 마지막 사용 화면·팝업일 수 있다 — 첫 화면이라고 가정하지 말 것
- open_link·open_app 은 에이전트가 뒤에 있어도 앱을 연다(도구가 앱이 뜰 때까지 기다린다). 결과의 [현재 화면] 앱이 다를 때만 다시 시도
- open_link 에 package 를 안 주면 앱 선택창(한 번만/항상)이 뜬다 — '항상'은 기본앱을 바꾸므로 누르지 말 것
- 와이파이·위치는 일반 앱이 직접 못 켠다 — 설정/패널을 열고 토글을 tap(와이파이 끄기는 하지 않는다)
- 개인정보: 알림창·메시지·통화기록 화면의 내용은 작업에 필요한 만큼만 읽고 결과 메시지에 옮기지 않는다

## 자주 쓰는 앱 (부르는 이름 · 검증된 딥링크 수 — 예시. tools/build_knowledge.py 가 내 폰 인벤토리로 다시 만든다)
YouTube(유튜브·YouTube·유툽) 딥링크✅3 / Chrome(크롬·Chrome·구글 크롬) 딥링크✅1 / 설정(설정·세팅·환경설정) / TMAP(티맵·T맵·TMAP) 딥링크✅2 ⚠트리빈약 / 갤러리(갤러리·사진·사진첩) / 지도(구글 지도·구글맵·Google Maps) 딥링크✅2 / Play 스토어(플레이 스토어·Play 스토어) 딥링크✅1 / 카메라(카메라·사진 찍기·셀카) / YT Music(유튜브 뮤직·YT Music) 딥링크✅1 / 쿠팡(쿠팡·Coupang) 딥링크✅5 / Gmail(지메일·Gmail) 딥링크✅1 / 네이버지도(네이버 지도·네이버맵) 딥링크✅1 / 시계(시계·알람·타이머) / 메시지(메시지·문자) / 노트(삼성 노트·노트·메모) ⚠트리빈약 / 음성 녹음(음성 녹음·녹음·녹음기) (나머지는 「설치된 앱」 목록)

## 음성 인식 힌트
- 티맵, TMAP, 네이버지도, YouTube, Chrome, Google, YT Music, NAVER, 브라우저, 포토, Play 스토어, 라디오, 카카오톡, 메시지, 전화, 연락처, Gmail, Meet, Zoom, Gemini, ChatGPT, Perplexity, 지도, 카카오맵, 설정, 갤러리, 카메라, 시계, 캘린더, 노트, 내 파일, 계산기, 음성 녹음, 헬스, 스마트싱스, 쿠팡, Drive, 스프레드시트, 유튜브, 유툽, 와이티, 쇼츠, 크롬, 구글 크롬, 인터넷, 구글, 구글 앱, 구글 검색, 구글 렌즈, AI 모드, 유튜브 뮤직, 유튜브뮤직, 유튭뮤직, 와이티 뮤직, 음악, 네이버, 네이버 앱, 녹색창, 삼성 인터넷, 삼성인터넷, Samsung Internet
