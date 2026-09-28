# Play 스토어 (com.android.vending)
부르는 이름: 플레이 스토어, 플레이스토어, Play 스토어, 구글 플레이, 앱스토어, 마켓
용도: 앱 설치·업데이트
열면: "앱" 탭 홈(추천/엔터테인먼트/인기 차트 등 칩 + 에디터 추천 배너)
화면:
상단 우측: 알림 및 혜택(설명 있음) · 그 오른쪽 라벨 없는 버튼([1058,120][1185,247], 프로필로 추정)
칩 줄(y≈1082~1208): 추천 / 엔터테인먼트 / 인기 차트 / 키즈 / 카테고리
본문: 추천 앱 목록(설명에 앱 이름·장르·별표 평점)
하단 탭(y≈1765~1933): 게임 / 앱 / 검색 / 도서 / 내 페이지 — 라벨은 TextView, 클릭 가능한 건 부모 View
★ 아래 딥링크(✅)가 있는 일은 open_link 한 번으로(open_app 을 먼저 부르지 않는다). 「하는 법」의 화면 절차는 딥링크가 안 될 때만
하는 법:
- 앱 검색: open_link market://search?q={query}&c=apps → 결과 tap. 대안: 하단 "검색" tap → 입력칸 type_text(submit=true)
- 특정 앱 상세: open_link market://details?id={패키지명} (일반 지식)
- 업데이트 확인: 상단 우측 프로필 버튼 tap → "앱 및 기기 관리" (일반 지식)
딥링크({query} 자리에 검색어):
- ✅ 앱 검색 결과: `open_link("market://search?q={query}&c=apps", package="com.android.vending")` (package 지정 필요)
- 📐 앱 상세: `open_link("market://details?id={packageName}")`
- 📐 앱 상세(웹 URL): `open_link("https://play.google.com/store/apps/details?id={packageName}")`
접근성 트리: partial — 항목이 안 보이면 딥링크를 쓰거나 finish(success=false)로 사용자에게 넘긴다
주의:
- 되돌릴 수 없는 동작: "설치", "구매"/가격 버튼, "구독", "제거" — irreversible=true, 유료는 반드시 사용자 확인
- 프로필 버튼 등 일부 아이콘에 라벨이 없음 — 좌표로만 식별
- 목록 첫 항목에 "스폰서"(광고) 섞임
