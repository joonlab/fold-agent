# Chrome (com.android.chrome)
부르는 이름: 크롬, Chrome, 구글 크롬, 인터넷, 브라우저
용도: 기본 웹브라우저 — 기사·웹검색
열면: 마지막으로 보던 뉴스 기사 페이지(탭 27개 열린 상태)
화면:
상단 툴바(y≈110~257): 설명="홈페이지 열기"(id=home_button) · 주소창 EditText id=url_bar [클릭,입력] · 설명="새 탭"(id=optional_toolbar_button) · 설명="N개 탭 보기"(id=tab_switcher_button) · 설명="Chrome 맞춤설정 및 제어"(id=menu_button)
본문: WebView — 웹페이지 요소가 트리에 풍부하게 노출되지만 화면 밖 요소 좌표가 [..,1933]/[0,0]으로 뭉개짐 → 보이는 것만 tap
하단 탭바 없음(주소창 상단)
★ 아래 딥링크(✅)가 있는 일은 open_link 한 번으로(open_app 을 먼저 부르지 않는다). 「하는 법」의 화면 절차는 딥링크가 안 될 때만
하는 법:
- 웹 검색: open_link https://www.google.com/search?q={query} (package=com.android.chrome). 대안: id=url_bar tap → type_text(검색어, submit=true)
- URL 열기: open_link {url} (package=com.android.chrome) (일반 지식)
- 새 탭: id=optional_toolbar_button(설명="새 탭") tap
- 탭 목록: id=tab_switcher_button tap
딥링크({query} 자리에 검색어):
- ✅ 구글 검색 결과: `open_link("https://www.google.com/search?q={query}")`
주의:
- 열면 마지막 페이지가 그대로 뜸(탭 27개 누적) — 새 작업은 open_link로 시작
- WebView 요소 좌표 중 화면 밖 것이 y=1933 또는 [0,0]으로 표시됨 — scroll 후 다시 읽고 tap
- 웹페이지 안의 "구독하기", 로그인, 결제 버튼은 irreversible
