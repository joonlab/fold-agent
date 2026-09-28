# 내 파일 (com.sec.android.app.myfiles)
부르는 이름: 내 파일, 파일, 파일 관리자, My Files, 다운로드 폴더
용도: 다운로드·문서·스크린샷 파일 찾기용
열면: '내 파일' 홈 화면
화면:
상단: 제목 '내 파일', '옵션 더보기'.
카테고리 버튼 2열: 다운로드 · 문서 · 이미지 · 오디오 · 동영상 · 파일 설치(id=main_text).
'최근에 추가된 파일' 썸네일 줄(id=recent_thumbnail, 파일명 표시).
'저장공간': 내장 저장공간(사용량) · 저장공간 공유. '유틸리티': 저장공간 관리 · 휴지통.
하단 중앙 검색바(id=home_search_view_container, 우측 '음성 검색' 버튼)
하는 법:
- 다운로드한 파일 보기: open_app('내 파일') → '다운로드' 탭
- 파일 이름으로 검색: 하단 '검색'(id=home_search_view_container) 탭 → type_text(파일명, submit)
- 저장공간 확인: '내장 저장공간' 항목의 used_text/total_text 읽기
주의:
- 파일 삭제·휴지통 비우기·이동은 irreversible
- 최근 파일 썸네일에 파일명(앱명 포함)이 노출됨 — 그대로 읽어주지 말 것
