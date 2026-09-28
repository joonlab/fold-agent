# YouTube (com.google.android.youtube)
부르는 이름: 유튜브, YouTube, 유툽, 와이티, 쇼츠
용도: 영상·쇼츠 시청
열면: 첫 화면이 아니라 쇼츠 재생 화면(세로 영상 재생 중, 우측 액션 레일 노출)
화면:
하단 탭(y≈1807~1933): 홈 / Shorts / 만들기 / 구독 / 내 페이지 — 각각 Button 설명=탭명
우상단: Button 설명="검색"(≈[1026,142]) · 설명="추가 작업"
쇼츠 우측 레일(x≈1080~1248): 좋아요 표시 · 댓글 N개 보기 · 저장 · 동영상 공유 · 리믹스
채널 아바타 옆 설명="@채널을(를) 구독합니다." 버튼, 하단 SeekBar(재생 위치)
★ 아래 딥링크(✅)가 있는 일은 open_link 한 번으로(open_app 을 먼저 부르지 않는다). 「하는 법」의 화면 절차는 딥링크가 안 될 때만
하는 법:
- 영상 검색: open_link https://www.youtube.com/results?search_query={query} (package=com.google.android.youtube) → 결과 목록에서 영상 tap. 대안: 우상단 설명="검색" tap → 입력창에 type_text(submit)
- 특정 영상 재생: open_link https://www.youtube.com/watch?v={id} 또는 https://youtu.be/{id}
- 구독 피드 보기: 하단 설명="구독..." 탭 tap
- 쇼츠 보기: 하단 설명="Shorts" tap → 다음 영상은 scroll(down)
딥링크({query} 자리에 검색어):
- ✅ 검색 결과: `open_link("https://www.youtube.com/results?search_query={query}")`
- 📐 영상 재생: `open_link("https://www.youtube.com/watch?v={videoId}")`
- ✅ 쇼츠 탭: `open_link("https://www.youtube.com/shorts")`
- ✅ 구독 피드: `open_link("https://www.youtube.com/feed/subscriptions")`
주의:
- 앱을 열면 직전 쇼츠/영상이 이어서 재생될 수 있음 — 첫 화면이 홈이라고 가정하지 말 것
- 되돌리기 어려운 동작: "좋아요 표시", "@채널을(를) 구독합니다."(구독), 댓글 작성, "동영상 공유", "만들기"(업로드) — irreversible=true로 처리
- 쇼츠 화면의 광고 카드("제품 보기")는 외부 쇼핑으로 이동하므로 탭 금지
