# 목업 소스

`docs/images/*.png` 는 실제 화면 캡처가 아니라 이 폴더의 HTML 목업을 렌더한 것입니다(데이터는 전부 가상).
HTML 은 공용 목업 킷을 `https://mockup-kit.invalid/` 라는 가짜 주소로 불러옵니다. 렌더러가 이 주소를 킷 폴더로 바꿔 줍니다.

```bash
git clone https://github.com/joonlab/android-mac-lab
node android-mac-lab/mockup-kit/shot.mjs --batch docs/mockups   # → docs/images/
```

킷 사용법: https://github.com/joonlab/android-mac-lab/tree/main/mockup-kit
