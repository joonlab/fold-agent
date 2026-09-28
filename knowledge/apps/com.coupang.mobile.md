# 쿠팡 (com.coupang.mobile)
부르는 이름: 쿠팡, Coupang, 쿠팡앱, 로켓배송
용도: 온라인 쇼핑. 상품 검색·장바구니·주문 확인용
열면: 홈 첫 화면(정상 로드)
화면:
상단: 쿠팡 로고(id=coupang_logo), 알림(설명="알림", id=button_noti) 우상단
검색창: EditText "쿠팡에서 검색하세요!" id=edit_text [클릭,입력] 상단(y≈236~351)
중단: 카테고리 그리드 id=recycler_category(자주산상품·쿠팡플레이·로켓프레시·쿠팡이츠·골드박스·반짝세일·패션/잡화·로켓배송 등)
하단 탭(id=gnb_tab_layout, 설명으로 구분): 쿠팡 홈 · 전체 카테고리 · 검색 · 마이쿠팡 · 장바구니
★ 아래 딥링크(✅)가 있는 일은 open_link 한 번으로(open_app 을 먼저 부르지 않는다). 「하는 법」의 화면 절차는 딥링크가 안 될 때만
하는 법:
- 상품 검색: open_app(쿠팡) → 검색창(id=edit_text) tap → type_text(검색어, submit=true) → 결과 목록 스크롤
- 장바구니 보기: 하단 탭 설명="장바구니" tap (또는 딥링크 coupang://cart)
- 주문/배송 조회: 하단 탭 설명="마이쿠팡" tap → 주문목록(일반 지식)
딥링크({query} 자리에 검색어):
- ✅ 검색 결과: `open_link("coupang://search?q={query}")`
- ✅ 장바구니: `open_link("coupang://cart")`
- ✅ 홈: `open_link("coupang://home")`
- ✅ 마이쿠팡: `open_link("coupang://my")`
- ✅ 골드박스: `open_link("coupang://goldbox")`
주의:
- 되돌릴 수 없는 동작: 「구매하기」「바로구매」「결제하기」, 장바구니 담기 후 주문 — 사용자 명시 확인 없이 tap 금지(irreversible=true)
- 홈 배너(ViewPager·banner_image)는 텍스트 없는 이미지 — 광고일 수 있음
