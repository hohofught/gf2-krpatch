# 소전2 한글패치 Windows (SnqxKR)

중섭 PC 클라이언트(官服·B服)에 `LangPackageTableCnData.bytes` 를 넣고, 게임 업데이트로 한패가 맞지 않게 되면 번역 메모리로 임시 복구하는 앱. 안드로이드 앱과 같은 한패 저장소·같은 규칙·같은 엔진이다.

## 빌드·배포

두 가지로 만든다. 둘 다 exe 한 파일이고 설치 없이 실행한다.

```
dotnet build windows/SnqxKR.Windows/SnqxKR.Windows.csproj -c Release                   # SnqxKR.exe (C# 엔진)
native\build-windows.cmd                                                                  # snqx.dll (Visual Studio 2022 C++)
dotnet build windows/SnqxKR.Windows/SnqxKR.Windows.csproj -c Release -p:Engine=Native   # SnqxKR-native.exe
```

- `SnqxKR.Windows/bin/Release/net48/SnqxKR.exe` (약 190KB): C# 엔진만.
- `SnqxKR.Windows/bin/Release/native/SnqxKR-native.exe` (약 430KB): C++ 엔진 `snqx.dll`(x64, 정적 CRT)을 exe 안에 넣었다. 처음 쓸 때 `%LOCALAPPDATA%\SnqxKR\native\<내용해시>\snqx.dll` 로 풀어 올린다. 64비트가 아니거나 DLL 을 못 올리면(Smart App Control 이 서명 없는 DLL 을 막는 경우 등) C# 엔진으로 돈다. 쓰는 엔진은 화면 아래와 기록에 나온다.
- 두 엔진의 결과는 바이트 단위로 같다. PC 에서 임시 복구 약 0.9초(C#) → 0.33초(네이티브).
- 대상은 .NET Framework 4.8. Windows 10(1903+)·11 에 기본으로 들어 있어 런타임을 따로 깔 필요가 없다. (.NET 9 WPF 는 런타임째 넣으면 수십 MB 이고 WPF 는 트리밍이 안 된다)
- 데이터(설정·한패 캐시·공식 원본 보관·번역 메모리)는 `%LOCALAPPDATA%\SnqxKR\`. 수백 MB 가 될 수 있어 로밍(`Roaming`)이 아닌 `Local` 이다. exe 를 어디에 두든 같은 데이터를 쓴다.
  - 0.2 가 exe 옆에 만든 `SnqxKR-data\` 는 처음 실행할 때 자동으로 옮긴다 (`settings.ini` 는 맨 마지막에 옮겨, 중간에 끊기면 다음 실행 때 마저 옮김).
- 아이콘은 안드로이드와 같은 "한" (Noto Sans KR Bold, SIL OFL 1.1, `licenses/NotoSansKR-OFL.txt`).
- `SnqxKR.EngineCheck` 는 엔진을 실제 파일로 검증하는 콘솔이다 (배포하지 않음). `SnqxKR.EngineCheck.exe <샘플 폴더>`.
- Smart App Control 이 켜진 PC 에서는 서명 없는 로컬 빌드가 막힐 수 있다. 배포본은 코드 서명이 필요하다.

## 화면

1. **한글패치할 게임을 선택하세요**: 찾은 설치 목록. 중섭이 아닌 설치는 흐리게 보이고 선택할 수 없다. 다시 찾기 · 실행 파일 직접 선택 · 관리자 권한으로 전체 스캔. 옆의 작은 ▾ (고급 설정)에서 훑을 루트 드라이브를 체크로 고른다 (처음에는 고정 NTFS 드라이브 전부, 바꾸면 바로 기억한다).
2. **선택한 게임**: 상태와 큰 버튼, 업데이트 확인, 원본 복원, 게임 버전·한패·번역 메모리·백업 정보.
   - 큰 버튼은 지금 할 가장 좋은 일 하나다 (`PrimaryAction`, 안드로이드와 같은 규칙): 한글패치 적용 / 임시 복구 / 정식 한패로 교체 /
     새 한패 받아서 적용·교체 (받아 둔 한패가 옛 버전용인데 서버에 새 한패가 있을 때: 받아서 이 버전용이면 넣고, 아니면 임시 복구) /
     새 한패 확인 (임시 복구 중에 기다리거나 번역 메모리가 없을 때).
   - 줄이 밀린 한패는 넣지 않고, "그래도 이 한패 적용 (받은 시각 · 원문과 자리 일치율)" 버튼으로만 넣는다 (경고 창을 거치고, 새로 받지 않고 보인 그 한패를 넣는다).
3. **저장 공간**: 항목별 크기와 삭제, 데이터 폴더 열기.
   - 캐시(받은 한패·임시 복구본): 다시 받거나 만들 수 있어 바로 지운다. 해시·버전 기록은 남아 상태 판정은 그대로다.
   - 번역 메모리 · 공식 원본 보관본 · 원본 백업: 지우면 임시 복구·원본 복원을 못 하게 될 수 있어 한 번 묻는다.
   - 번역 메모리는 최대 500MB. 넘으면 가장 오래전 한패에서만 본 줄부터 뺀다.
4. **기록**

## 동작 (`PatchService`, 안드로이드 PatchEngine 과 같은 규칙)

- 게임 폴더에 공식 원본(한글 없음)이 보이면 앱 데이터에 보관하고, 같은 버전 한패가 있으면 번역 메모리에 넣는다.
- 한패와 게임의 버전 지문(Id 집합)이 다르면 옛 한패를 그대로 넣지 않는다 (Id 가 다시 매겨져 문장이 엉뚱한 자리에 나온다). 대신 **임시 복구본**을 넣는다.
  1. 보관한 새 공식 원본을 번역 메모리로 한국어화한다.
  2. 받아 둔 옛 한패가 번역 메모리보다 새것이면, 그 번역을 새 Id 자리로 옮긴다 (`docs/lang-table-format.md` 의 2단계).
- 적용 전 설치마다 처음 한 번 게임 폴더 파일을 백업한다. **원본 복원**은 백업이 지금 게임 버전일 때만 한다.
- 크기·수정시각이 그대로면 해시·본문 읽기를 건너뛴다.
- 게임(`GF2_Exilium.exe`)이 그 폴더에서 실행 중이면 쓰지 않는다.

## 관리자 권한

앱은 일반 권한으로 돈다. 필요한 일만 같은 exe 를 관리자 권한으로 한 번 더 띄워서 한다 (`Elevated`, UAC 창).

| 명령 | 언제 | 하는 일 |
|---|---|---|
| `--scan <결과파일> [C: D: ...]` | "관리자 권한으로 전체 스캔" (드라이브는 옆 ▾ 고급 설정) | NTFS MFT(`FSCTL_ENUM_USN_DATA`)로 고른 드라이브(없으면 고정 NTFS 드라이브 전부)에서 `GF2_Exilium.exe`·`PCLauncher.exe` 를 찾는다. 폴더를 하나씩 열지 않아 몇 초면 끝난다. 찾은 폴더도 중섭 확인을 거친다. 인자는 드라이브 글자(`X:`)만 받는다. 훑지 못한 드라이브는 이유를 결과에 남겨 기록에 보인다 (예전에는 조용히 빠졌다). 고정·이동식 드라이브 중 NTFS 가 아닌 것(FAT·exFAT)은 MFT 가 없어 고를 수 없다 |
| `--copy <원본> <대상>` | 게임 폴더에 쓸 권한이 없을 때 (B服 기본 경로 `Program Files`) | 대상이 중섭으로 확인된 게임 폴더의 `LangPackageTableCnData.bytes` 일 때만 복사 |

## 중섭 전용 (`CnServerCheck`)

**글로벌·한국 서버 클라이언트에는 절대 쓰지 않는다.** 실행 파일 이름(`GF2_Exilium.exe`)과 `GF2_Exilium_Data` 구조는 글로벌(Steam 포함)도 같고, B服 는 런처 이름까지 `GF2_Exilium.exe` 다. 이름으로는 구분이 안 되므로 찾은 설치마다 아래를 확인하고, 하나라도 어긋나면 "중섭 아님"으로 표시만 하고 손대지 않는다.

공통 (둘 다 필수):

| 확인 | 중섭 값 |
|---|---|
| `GF2_Exilium_Data\app.info` (Unity 회사명/제품명) | `SunBorn` / `少女前线2：追放` |
| 게임 `GF2_Exilium.exe` 코드 서명자 | `上海散爆信息技术有限公司` |

채널 (둘 중 하나):

| | 官服 (2026-09 실측) | B服 (빌리빌리 설치 파일 분석) |
|---|---|---|
| 런처 | `PCLauncher.exe`, 서명 `上海散爆信息技术有限公司` | `GF2_Exilium.exe` (원래 이름 `PCGameClient.exe`, 제품명 `少女前线2：追放 启动器`), 서명 `上海宽娱数码科技有限公司` |
| 게임 폴더 | 런처 `config.ini` `game_install_path` (기본 `GF2 Game`) | 런처 폴더 아래 `Games` |
| 연결 확인 | 게임 `GameConfig.cfg` `ClientPath` → 런처, 런처 `config.ini` → 게임 | `ClientPath` 또는 `Games` 의 상위 폴더에 빌리빌리 런처 |
| 레지스트리 | `HKLM\...\WOW6432Node\...\Uninstall\GF2Exilium` | `SOFTWARE\GF2_Exilium` `GameInstallPath`, 제거 정보 |
| 기본 경로 | 사용자 지정 | `C:\Program Files\bilibili Game\GF2_Exilium\Games` |

- B服 표식은 빌리빌리 게임센터 PC판 설치 파일을 실행하지 않고 풀어서 런처 문자열로 확인했다. 실제 설치본의 게임 서명자와 `ClientPath` 값은 아직 확인하지 못했다. 다르면 확인이 실패하는 쪽(쓰지 않음)으로 동작한다.
- 중섭 PC 클라이언트의 `Table` 폴더에는 다른 언어 파일(`LangPackageTableThthBuiltinData.bytes` 등)도 있다. 언어 파일 유무로 서버를 가르면 안 된다.
- PC 와 안드로이드 중섭은 같은 게임 버전이면 `LangPackageTableCnData.bytes` 가 같다 (버전 지문 일치 확인).

## 게임 찾기 (`GameLocator`)

디스크를 순회하지 않는다. 흔적에서 경로를 계산해 파일 존재만 확인한다. 설치가 여럿일 수 있어 전부 모은다.

| 흔적 | 얻는 것 | 실측 (2026-09) |
|---|---|---|
| 실행 중인 `GF2_Exilium.exe` / `PCLauncher.exe` | 게임 또는 런처 폴더 (B服 는 둘 다 `GF2_Exilium.exe` 라 폴더 구조로 판단) | 관리자 프로세스도 `QueryFullProcessImageName` 으로 읽음 |
| 레지스트리 제거 정보 / B服 `SOFTWARE\GF2_Exilium` | 런처 폴더 | 官服 `InstallLocation` 은 비어 있음, `DisplayIcon`·`UninstallString` 에서 추출 |
| 바탕화면·시작 메뉴 `.lnk` | 런처 폴더 | `C:\GF2Exilium\PCLauncher.exe` |
| 런처 `config.ini` `game_install_path` (官服) / `Games` (B服) | 게임 폴더 | `C:/GF2Exilium/GF2 Game` |
| B服 기본 설치 경로 | 런처 폴더 | `C:\Program Files\bilibili Game\GF2_Exilium` |
| Everything 색인 (Everything 이 떠 있을 때, `EverythingSearch`) | 게임·런처 실행 파일 | IPC(`WM_COPYDATA`, SDK 의 `everything_ipc.h` 규약)로 두 이름을 묻는다. 관리자 권한·디스크 훑기 없이 17 ms. 에픽 글로벌판(`Epic Games\GIRLSFRONTLINE2EXILIUM`)도 찾아 "중섭 아님"으로 보인다. Everything 이 관리자 권한으로 떠 있으면 메시지가 막혀 건너뛴다 |
| 전에 찾은 경로 (전체 스캔·직접 선택 포함) | 게임 폴더 | 매번 재검증, 없어진 폴더는 버림, 중섭만 기억 |

## 남은 확인 사항

- B服 PC 실제 설치본으로 표식 확인.
- 글로벌·한국 PC판 실물로 "중섭 아님" 판정 확인.
- config.ini 비ASCII 경로 인코딩 (런처가 Qt5 라 `\xXXXX` 로 이스케이프할 수 있음).
