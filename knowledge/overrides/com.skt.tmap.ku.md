★ 경로 설정 — 2026-09-26 폰에서 실측. 사용자가 말한 출발지·도착지·옵션을 전부 반영한다(출발지를 말했는데 현재 위치로 대신하면 실패다).
A. 권장(경로 옵션까지 고를 수 있음):
1. geocode(출발지), geocode(도착지) — 주소가 맞는 후보의 좌표를 쓴다. 좌표를 기억으로 지어내지 말 것
2. open_link("tmap://route?startname={출발지}&startx={출발 경도}&starty={출발 위도}&goalname={도착지}&goalx={도착 경도}&goaly={도착 위도}")  (x=경도 127…, y=위도 37…, 이름은 URL 인코딩)
3. 경로 미리보기: 위쪽에 출발·도착 이름, 아래 옵션 목록(id=tmap_route_option_name_text: 티맵추천·시간우선 등, 각 줄에 분·도착시각·km). 출발·도착 이름이 맞는지 확인
4. 요청한 옵션 줄 tap(「최단 시간」=「시간우선」) → 「안내시작」(id=route_preview_drive_button) tap
⚠️ 미리보기는 약 20초 뒤 **자동으로 안내가 시작된다** — 옵션은 미리보기에 들어온 바로 다음 단계에서 고른다(wait 금지)
B. 좌표를 못 구할 때(옵션은 못 고름):
open_link("tmap://search?name={도착지}") → 도착지 줄 「길찾기」 → 022007 창이면 「확인」(현재 위치가 출발지라 나는 오류 — 계속 진행) → id=route_preview_header_points_list tap → 「출발지」 칸 id=route_preview_header_point_item_0 tap → id=search_edit_text 에 type_text(출발지) → 자동완성 첫 후보 tap → 약 2초 뒤 자동 안내 시작
- 이름만 넣은 tmap://route 링크(좌표 없음)는 경로를 못 잡는다 — 쓰지 말 것
- 출발지를 말하지 않으면 현재 위치가 출발지. 022007 이 뜨면 출발지를 묻는 finish(success=false)
- 안내 종료: global(back) → 「안내를 종료하시겠습니까?」 창, 「안내 종료」가 3초 뒤 자동 선택된다
- 장소 상세(웹뷰)의 「출발·경유·도착」 버튼은 접근성 트리에 안 보인다 — 쓰지 말 것
