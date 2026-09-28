# 지도 (com.google.android.apps.maps)
부르는 이름: 구글 지도, 구글맵, Google Maps, 지도, 구글 맵스
용도: 장소 검색·해외 지도·주차 위치 확인에도 쓰임
열면: 메인 탐색 화면(MapsActivity), 지도 + 상단 검색창 + 카테고리 칩 + 하단 탭
화면:
상단 검색창: EditText id=search_omnibox_text_box 설명 '주유소, ATM 찾기'(좌상단) · 우측 '음성 검색' 버튼 · 계정 아이콘(id=selected_account_disc)
그 아래 카테고리 칩(id=recycler_view): 집 설정·음식점·커피·라면·주차·편의점
우측 플로팅: 레이어 · 방향(북쪽 정렬) · 나침반 모드 시작 · '경로'(설명='경로')
하단 탭: 탐색(explore_tab_strip_button) · 내 페이지(saved_tab_strip_button) · 참여(contribute_tab_strip_button)
하단 시트에 현재 지역명·날씨 표시
★ 아래 딥링크(✅)가 있는 일은 open_link 한 번으로(open_app 을 먼저 부르지 않는다). 「하는 법」의 화면 절차는 딥링크가 안 될 때만
하는 법:
- 장소 검색: open_link geo:0,0?q={query} (package 지정) → 결과 목록/장소 카드. 화면으로는 search_omnibox_text_box에 type_text(submit)
- 길찾기/내비: open_link google.navigation:q={query} → 바로 운전 안내. 경로 비교만 원하면 https://www.google.com/maps/dir/?api=1&destination={query}
- 주변 카테고리 찾기: 칩 '주차'/'음식점'/'커피'/'편의점' tap
딥링크({query} 자리에 검색어):
- ✅ 장소 검색: `open_link("geo:0,0?q={query}", package="com.google.android.apps.maps")` (package 지정 필요)
- ✅ 검색 결과(URL): `open_link("https://www.google.com/maps/search/?api=1&query={query}")`
- 🟡 경로 보기: `open_link("https://www.google.com/maps/dir/?api=1&destination={query}&travelmode=driving")`
주의:
- google.navigation: 은 즉시 안내가 시작된다 — 경로만 볼 때는 dir URL 사용
- 하단 시트에 현재 위치 지역명이 노출되므로 노트/응답에 옮기지 말 것
