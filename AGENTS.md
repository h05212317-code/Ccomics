# Ccomics 작업 지침

## 배포 규칙
- Ccomics Android 앱 코드를 수정한 경우, 별도로 로컬 APK만 요청받지 않는 한 **항상 GitHub 정식 릴리스까지 배포**한다.
- 배포 절차:
  1. `app/build.gradle`의 `versionCode`와 `versionName`을 올린다.
  2. 변경 사항과 버전을 커밋한다.
  3. `./publish.ps1 -Notes "변경 요약"`을 실행해 단위 테스트·서명 빌드·`main` 푸시·GitHub 최신 릴리스 공개까지 완료한다.
- 로컬 APK만 빌드한 뒤 배포 여부를 되묻지 않는다.
