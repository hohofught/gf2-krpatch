# 릴리스 체크리스트

배포 APK 는 CI 가 아니라 **로컬 빌드를 손으로 올린다**. 저장소에 서명 시크릿이 없어 CI APK 는 빌드마다 새 디버그 키로 서명되고, 그러면 설치된 앱 위로 업데이트가 안 되기 때문이다. CI 의 `release` job 은 이미 올라간 파일을 덮지 않는다(`overwrite_files: false`).

그래서 **손으로 올리는 파일은 CI 검사를 거치지 않는다.** CI 가 통과한 것은 같은 소스로 만든 다른 파일이다. 아래 4·5 단계를 올릴 바로 그 파일로 한다. 검토할 것은 [review-guide.md](review-guide.md).

## 1. 버전

- `app/build.gradle.kts`: `versionCode` +1, `versionName`
- `windows/SnqxKR.Windows/SnqxKR.Windows.csproj`: `<Version>`
- 확인: `python tools/review/check_source.py --release-tag vX.Y` 가 오류 0

## 2. CI

- main 에 올리고 `build` 워크플로의 `check`·`build`·`smoke` 가 모두 통과했는지 본다.
- `smoke` 요약에 native 판이 "건너뜀" 이면 에뮬레이터가 arm64 를 못 돌린 것이다. 그 판은 5 단계의 실기기 검사가 유일한 시작 검사다.

## 3. 로컬 빌드

```
dotnet build windows/SnqxKR.Windows/SnqxKR.Windows.csproj -c Release                   # SnqxKR.exe (C# 엔진)
native\build-windows.cmd                                                                  # snqx.dll
dotnet build windows/SnqxKR.Windows/SnqxKR.Windows.csproj -c Release -p:Engine=Native   # SnqxKR-native.exe
gradlew assembleKotlinRelease assembleNativeRelease
```

경고 0 인지 본다 (윈도우 빌드는 `windows/README.md` 와 같다).

## 4. 서명

```
apksigner verify --print-certs app\build\outputs\apk\kotlin\release\app-kotlin-release.apk
apksigner verify --print-certs app\build\outputs\apk\native\release\app-native-release.apk
```

SHA-256 이 지난 릴리스와 같은 키(`7d2f…3a5c`)인지 본다. 다르면 사용자가 지우고 다시 깔아야 하므로 올리지 않는다.

## 5. 실기기 시작 검사 (올릴 바로 그 파일로)

```
tools/smoke-android.sh -s <시리얼> app/build/outputs/apk/kotlin/release/app-kotlin-release.apk app/build/outputs/apk/native/release/app-native-release.apk
```

- 앱이 없는 기기: 스크립트가 깔고 검사한 뒤 지운다.
- 앱이 있는 기기: 기본은 덮어쓰지 않고 멈춘다. 업데이트 경로(이전 판 데이터로 새 판 시작)를 보려면 이전 릴리스를 깔아 한 번 연 뒤 `--replace` 로 새 판을 덮는다. 서명이 같아야 한다.
- `--recreate` 는 화면 회전 설정을 잠깐 바꿨다 되돌린다. 실기기에서는 필요할 때만 쓴다.
- 보안 폴더 같은 다른 사용자 공간에 같은 앱이 있으면 "이미 깔려 있다" 로 멈춘다 (APK 는 사용자 공간끼리 같이 쓴다).

## 6. 실기기 Shizuku 흐름 (손으로)

시작 검사로는 안 보이는 부분이다. 무선 디버깅으로 띄운 Shizuku(shell)에서 한다.

1. Shizuku 를 끈 채로 연다: 안내가 나오고 죽지 않는다.
2. Shizuku 를 켜고 권한을 준다: 게임 폴더 상태가 보인다.
3. 한패를 받아 적용한다. 게임을 켜서 한글이 나오는지 본다.
4. 원본 복구, 패치 제거를 한 번씩 한다.
5. 작업 중에 앱을 닫았다가 다시 연다. 화면을 돌린다.
6. 이번 판에서 바뀐 흐름을 하나씩 따라간다 (릴리스 노트의 항목마다).

## 7. 윈도우

두 exe 를 실행해 시작, 게임 폴더 찾기, 상태 표시를 본다.

## 8. 올리기

- 태그 `vX.Y` 를 달고 올린다. 릴리스에 붙이는 이름은 CI 와 같게 한다: `snqx-krpatch-vX.Y.apk`, `snqx-krpatch-vX.Y-native-arm64.apk`, `SnqxKR.exe`, `SnqxKR-native.exe`.
- 올린 뒤 `gh release view vX.Y --json assets` 의 sha256 이 로컬 파일과 같은지 본다.
