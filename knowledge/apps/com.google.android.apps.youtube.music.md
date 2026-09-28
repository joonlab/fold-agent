# YT Music (com.google.android.apps.youtube.music)
부르는 이름: 유튜브 뮤직, 유튜브뮤직, YT Music, 유튭뮤직, 와이티 뮤직, 음악
용도: 음악 재생
열면: 첫 화면이 아니라 "빠른 선곡" 하위 화면 + 하단 미니 플레이어(곡 일시정지 상태)
화면:
상단: 설명="뒤로" · 제목 TextView "빠른 선곡" · id=avatar_menu_button(계정)
본문 id=section_list_content: 곡/앨범 카드 ViewGroup — 라벨 없음
미니 플레이어 id=player_page: id=mini_player_title/subtitle, id=mini_player_play_pause_replay_button(재생/일시정지), id=mini_player_media_route_button(전송/캐스트)
하단 탭(y≈1807~1933, id=text1): 홈 / 샘플 / 검색 / 보관함
★ 아래 딥링크(✅)가 있는 일은 open_link 한 번으로(open_app 을 먼저 부르지 않는다). 「하는 법」의 화면 절차는 딥링크가 안 될 때만
하는 법:
- 노래 검색 후 재생: open_link https://music.youtube.com/search?q={query} (package=com.google.android.apps.youtube.music) → 첫 결과 tap. 대안: 하단 "검색" tap → type_text(submit=true)
- 재생/일시정지: id=mini_player_play_pause_replay_button tap
- 보관함(내 재생목록): 하단 "보관함" tap
딥링크({query} 자리에 검색어):
- ✅ 검색 결과: `open_link("https://music.youtube.com/search?q={query}")`
- 📐 곡 재생: `open_link("https://music.youtube.com/watch?v={videoId}")`
접근성 트리: partial — 항목이 안 보이면 딥링크를 쓰거나 finish(success=false)로 사용자에게 넘긴다
주의:
- 본문 카드(ViewGroup [4]~[9])에 텍스트·설명이 없어 어떤 곡인지 트리로 구분 불가 — 검색 딥링크 우선
- 열면 마지막 하위 화면(빠른 선곡 등)이 뜸 — 필요하면 global(back) 또는 하단 "홈"
- 좋아요/싫어요·재생목록 추가는 기록에 남음
