# 검토 가이드

변경을 검토할 때(사람이든 자동 검토든) 이 저장소에서 확인할 것. 릴리스 절차는 [release-checklist.md](release-checklist.md).

## 원칙

1. **고친 코드는 실행해 본 뒤에만 낸다. 검토에서 나온 수정도 같다.** 2.2 는 감사에서 나온 안정성 수정 여러 건을 기기에서 한 번도 켜 보지 않고 냈고, 그중 하나 때문에 켜자마자 죽었다.
2. 검토 결과에는 고친 것마다 **어떻게 확인했는지**(단위 테스트, 에뮬레이터 시작 검사, 실기기의 어느 흐름)를 적는다. 확인하지 못한 것은 못 했다고 적는다.
3. 사고가 나면 같은 유형을 다음에 잡을 장치를 하나 더한다. 기계로 잡을 수 있으면 검토기 규칙으로, 실행해야 보이면 시작 검사 단계로, 둘 다 안 되면 아래 체크리스트로.

## 검사 단계가 잡는 것과 못 잡는 것

| 단계 | 언제 | 잡는 것 | 못 잡는 것 |
|---|---|---|---|
| 검토기 `tools/review/check_source.py` | CI `check`, 로컬 수시 | 필드가 `init` 아래 (init-order), 세 엔진 중 일부만 바뀜 (engine-parity, 경고), 태그와 버전 번호 불일치 (release-version) | 그 밖의 모든 논리 오류 |
| 단위 테스트 `testKotlinDebugUnitTest` | CI `build` | 큰 버튼 규칙, 표 읽기·쓰기 (샘플이 있을 때만: `-PsnqxSamples=폴더`) | 안드로이드 화면·서비스, 실제 게임 표 (러너에 샘플 없음) |
| 시작 검사 `tools/smoke-android.sh` | CI `smoke`(에뮬레이터), 릴리스 전 실기기 | 배포 APK(R8 축소 후) 그대로 첫 실행, 화면 다시 만들기, 홈 갔다 오기, 강제 종료 후 다시 실행에서 죽음·멈춤 | Shizuku 가 필요한 적용·복구, 백그라운드 확인(PatchCheckWorker), 알림, 윈도우판. x86_64 에뮬레이터에 못 까는 arm64 판은 건너뜀 |
| 실기기 손 확인 | 릴리스 전 | Shizuku 흐름 전체 | — |

## 과거 사고

| 언제 | 무엇 | 원인 | 지금 막는 것 |
|---|---|---|---|
| 2.2 (2026-09-30) | 안드로이드 두 판 모두 켜자마자 죽음, Shizuku 와 무관 | `PatchViewModel` 의 `init` 이 `refresh()` 를 생성자 안에서 바로 돌리는데, 새로 넣은 `diskStats` 가 `init` 아래에 선언돼 아직 null | 검토기 init-order, CI 시작 검사 |
| 2026-09 | CI 가 빌드 전에 멈춤 | `setup-android@v3` 의 라이선스 단계가 새 러너에서 exit 1 | 러너에 깔린 sdkmanager 사용 (build.yml 주석) |
| 1.0~ | CI APK 로 업데이트 설치가 안 됨 | 서명 시크릿이 없어 CI 는 매번 새 디버그 키 | 배포 APK 는 로컬 빌드, 서명 확인 (release-checklist) |

## 체크리스트

### Kotlin 객체가 만들어지는 순서
- `init` 이 부르는 함수가 읽는 필드는 모두 `init` 위에 있는가. `viewModelScope` 는 `Dispatchers.Main.immediate` 라서, 메인 스레드에서 만든 ViewModel 의 `launch` 는 첫 suspend 까지 **생성자 안에서** 돈다. 컴파일러는 함수를 거쳐 읽는 것을 경고하지 않는다 (검토기가 본다).
- `by lazy` 같은 위임도 선언 자리에서 만들어진다. `init` 아래에 두면 같은 문제다.
- 화면을 돌리면 Activity 는 새로 만들어지고 ViewModel 은 남는다. `savedInstanceState` 로 처음 만들 때만 할 일을 가르고 있는가.

### 스레드와 잠금
- 메인 스레드에서 디스크나 네트워크를 쓰지 않는가 (`withContext(Dispatchers.IO)`).
- `PatchEngine.LOCK` 은 kotlinx `Mutex` 라서 다시 들어갈 수 없다. LOCK 안에서는 `…Locked()` 함수만 부른다. LOCK 을 잡는 공개 함수를 LOCK 안에서 부르면 영원히 멈춘다.
- 네트워크를 기다리는 동안 LOCK 을 쥐고 있지 않은가 (알려진 남은 문제: bootstrap).

### Shizuku 와 FileService
- 일반 사용자는 **무선 디버깅으로 띄운 Shizuku = shell uid** 다. root 를 전제로 하지 않는다. 재부팅이나 Wi-Fi 변경으로 Shizuku 가 꺼질 수 있다.
- `FileService` 는 shell uid 의 다른 프로세스에서 돈다. 앱 쪽 경로 중 shell 이 쓸 수 있는 곳은 `getExternalFilesDir` 아래뿐이다 (`filesDir` 는 못 쓴다).
- `FileService`·`IFileService` 는 Shizuku 가 리플렉션으로 올린다. 이름을 바꾸거나 proguard keep 을 빼지 않는다.
- 바인딩은 시간 제한이 있고 실패할 수 있다. 실패를 사용자에게 알리고 진행을 멈추는가.
- 게임 폴더를 쓰는 동안에는 `ShizukuBridge.holding { }` 으로 특권 프로세스를 붙잡는가 (도중에 내리면 게임 파일이 반쯤 쓰인다).

### 게임 파일 바꾸기
- 옆 파일(`.tmp`)에 다 쓰고 크기를 확인한 뒤 바꾸는가. 원래 파일은 끝날 때까지 `.old` 로 남기는가.
- `/sdcard`(FUSE) 에서는 rename 이 막힐 수 있다. 복사로 넘어가는 길이 있는가.
- 게임이 실행 중일 때 쓰지 않는가.

### 세 엔진 (Kotlin · C++ · C#)
- 같은 입력에 같은 결과를 내야 한다 ([lang-table-format.md](lang-table-format.md)). 한 엔진을 고치면 세 엔진을 같이 고치고, 결과를 엔진마다 뽑아 비교했는가. 검토기가 일부만 바뀐 변경을 경고한다.
- 번역 메모리는 "깨진 파일만 버리고 새로 만든다". 메모리 부족이나 읽기 오류로 쌓아 둔 메모리를 덮어쓰지 않는가.

### 릴리스에 닿는 변경
- `versionCode` 를 올렸는가 (같으면 업데이트 설치가 안 된다). 윈도우 `Version` 도 같이.
- 배포 APK 는 로컬 빌드 키로 서명됐는가 ([release-checklist.md](release-checklist.md)).

## 검토기에 규칙 더하기

`tools/review/check_source.py` 에 함수 하나와 `main()` 의 호출 하나를 더한다. 규칙은 다음을 지킨다.

- 실제로 났거나 날 뻔한 사고에서 나온 것만 넣는다. 주석에 그 사고를 적는다.
- 오탐이 나면 규칙을 고치거나, 그 줄에 허용 주석(`check:allow <규칙>`)을 다는 방법을 둔다.
- 2.2 소스(`git archive v2.2`)처럼 사고가 난 판에서는 걸리고 지금 트리에서는 통과하는지 확인한다.
