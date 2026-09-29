# 소전2 한글패치 (SnqxKR)

소녀전선2: 망명 **중섭** 클라이언트에 커뮤니티 한글패치(`LangPackageTableCnData.bytes`)를
자동으로 받아 넣어주는 Material You 앱. [arca.live 가이드](https://arca.live/b/gilrsfrontline2exili/127344286)의
수동 절차(Shizuku + X-plore로 직접 복사)를 앱 하나로 대체한다.

## 하는 일

1. 설치된 중섭 클라이언트를 모두 감지 (`com.Sunborn.SnqxExilium` / `.bilibili` / `.qq`), 여러 개면 官服·B服·QQ 중 골라서 적용.
   글로벌·한국 서버 클라이언트는 허용 목록에 없으므로 절대 건드리지 않는다.
2. `https://raw.githubusercontent.com/nemasdf/haguel-baefo/.../LangPackageTableCnData.bytes` 에서 최신 한패 다운로드
3. Shizuku 특권 프로세스로 게임 폴더에 복사
   `/sdcard/Android/data/<pkg>/files/LocalCache/Data/Table/LangPackageTableCnData.bytes`
4. 복사 후 SHA-256 재검증, 클라이언트마다 최초 1회 원본 자동 백업

## 중복 다운로드 방지 (3단)

| 단계 | 방법 | 절약 |
|---|---|---|
| 1 | `HEAD` 로 ETag 비교 | 본문 0바이트 |
| 2 | `GET` + `If-None-Match` → `304` | 본문 0바이트 |
| 3 | 받은 내용의 SHA-256 이 캐시와 동일 | 파일 교체 생략 |

실측 결과 raw.githubusercontent.com 은 `If-None-Match` 를 무시하고 200 + 전체 본문을 준다(2026-08 기준).
즉 실제로 다운로드를 막는 건 1단계(HEAD ETag 비교)이고, 2·3단계는 폴백이다.
ETag 값은 콘텐츠 해시라 파일이 갱신될 때만 바뀐다.

추가로 적용 직전 **게임 폴더의 파일 해시**와 캐시 해시를 비교해서, 같으면 복사 자체를 건너뛴다.

## 요구 사항

- Android 9+ (minSdk 28), Shizuku v11+ 실행 중 (무선 디버깅 또는 root, Sui 도 가능)
- 권한은 Shizuku-API 공식 흐름대로 요청한다. "다시 묻지 않음"으로 거부했다면 요청 창이 뜨지 않으므로 Shizuku 앱의 앱 관리에서 허용한다.
- 안드 13+ 에서 `Android/data` 직접 접근이 막혀 있어 Shizuku가 필수다.
  Shizuku가 root로 떠 있으면 uid 0, adb 페어링으로 떠 있으면 uid 2000(shell)으로 동작하며 둘 다 이 경로에 쓸 수 있다.

## 설치

[Releases](../../releases) 에서 APK를 받거나, [Actions](../../actions) 최신 빌드의 `apk` 아티팩트를 받아 설치한다.

```
adb install -r snqx-krpatch-*.apk
```

## 빌드

```bash
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk (약 1.9MB)
```

시크릿(`RELEASE_KEYSTORE` = base64 keystore, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD`)을 넣지 않으면 **디버그 키로 서명**된다. CI 러너는 매번 새 디버그 키를 만들기 때문에
빌드가 바뀌면 덮어쓰기 설치가 막힌다(기존 앱 삭제 후 설치). 고정 키로 받으려면 위 시크릿을 등록하면 된다.

## 구조

| 파일 | 역할 |
|---|---|
| `IFileService.aidl` | 특권 프로세스에 노출하는 파일 API |
| `FileService.kt` | Shizuku가 root/shell 프로세스에서 실행하는 실제 파일 조작 |
| `ShizukuBridge.kt` | 바인더·권한 리스너(onCreate 등록/onDestroy 해제), 권한 요청, UserService 바인딩 |
| `PatchRepository.kt` | 다운로드/ETag 캐시/해시, 클라이언트별 기록·설정 |
| `PatchEngine.kt` | 상태 판정, 공식 원본 보관, 번역 메모리, 버전 불일치 차단, 임시 복구 |
| `PatchCheckWorker.kt` | 백그라운드 확인(3시간)과 알림 |
| `langtable/` | `LangPackageTableCnData.bytes` 파서·재작성·번역 메모리·복구 ([형식](docs/lang-table-format.md)) |
| `PatchViewModel.kt` | 상태 판정, 적용·복원·제거 흐름 |
| `MainActivity.kt` | Compose Material3 (dynamic color) UI |

## 주의

- 적용 전 게임을 완전히 종료해야 한다 (앱이 `/proc` 스캔으로 실행 여부를 확인하고 막는다).
- 게임이 대규모 업데이트로 테이블을 다시 내려받으면 한패가 덮일 수 있다. 그때는 앱에서 다시 적용하면 된다.
- 비공식 패치이므로 사용에 따른 책임은 사용자에게 있다.
