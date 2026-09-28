# 카메라 (com.sec.android.app.camera)
부르는 이름: 카메라, 사진 찍기, 셀카, Camera, 동영상 촬영
용도: 사진·동영상 촬영. QR·문서 스캔 용도도 가능
열면: 후면 카메라 '사진' 모드 촬영 화면(뷰파인더)
화면:
상단 퀵설정(y≈91~186): 플래시 · 해상도 · 모션 포토 · 필터 · 셀피.
중앙 하단 줌 버튼: .6 / 1 / 2 (id=zoom_lens_text_button_main), 우측 '야간 촬영'·'퀵 컨트롤'.
하단: '사진 및 동영상 보기'(좌, id=quick_view_button_layout) · '사진 촬영' 셔터(중앙, id=center_button_container, [504,1491][746,1733]) · '전면 카메라로 전환'(우, id=switch_camera_button).
모드 바(id=shooting_mode_list, y≈1751~1838): 인물 사진 · 사진 · 동영상 · 더보기 (각 버튼이 2개씩 중복 노출됨)
하는 법:
- 사진 찍기: open_app('카메라') → '사진' 모드 확인 → '사진 촬영'(id=center_button_container) 탭
- 셀카: '전면 카메라로 전환'(id=switch_camera_button) 탭 → 셔터 탭
- 동영상 녹화: 모드 바 '동영상' 탭 → 셔터 탭(시작) → 다시 셔터 탭(정지)
- 방금 찍은 사진 보기: 좌하단 '사진 및 동영상 보기' 탭
- QR 코드 스캔: (일반 지식) 사진 모드에서 QR에 카메라를 대면 자동 인식 링크가 뜸 → 링크 탭 (일반 지식)
주의:
- 셔터 탭은 즉시 촬영(저장)됨
- 모드 바 버튼이 id=..._button / ..._button_full 로 중복 노출 — 같은 이름 중 아무거나 하나만 탭
- actions에 QR_SCANNER_MODE·DOCUMENT_SCAN 인텐트가 있으나 URI 형식이 없어 open_link로는 못 씀
