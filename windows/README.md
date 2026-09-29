# 소전2 한글패치 Windows (SnqxKR)

중섭 PC 클라이언트(官服·B服)에 `LangPackageTableCnData.bytes` 를 넣고, 게임 업데이트로 한패가 맞지 않게 되면 번역 메모리로 임시 복구하는 앱. 안드로이드 앱과 같은 한패 저장소·같은 규칙·같은 엔진이다.

## 빌드·배포

```
dotnet build windows/SnqxKR.Windows.sln -c Release
```

- 결과물은 `SnqxKR.Windows/bin/Release/net48/SnqxKR.exe` **한 파일(약 130KB)**. 설치 없이 실행하는 포터블 앱이다.
- 대상은 .NET Framework 4.8. Windows 10(1903+)·11 에 기본으로 들어 있어 런타임을 따로 깔 필요가 없다. (.NET 9 WPF 는 런타임째 넣으면 수십 MB 이고 WPF 는 트리밍이 안 된다)
- 데이터(설정·한패 캐시·공식 원본 보관·번역 메모리)는 exe 옆 `SnqxKR-data\`. 그 폴더에 쓸 수 없으면 `%LOCALAPPDATA%\SnqxKR\`.
- `SnqxKR.EngineCheck` 는 엔진을 실제 파일로 검증하는 콘솔이다 (배포하지 않음). `SnqxKR.EngineCheck.exe <샘플 폴더>`.
- Smart App Control 이 켜진 PC 에서는 서명 없는 로컬 빌드가 막힐 수 있다. 배포본은 코드 서명이 필요하다.

## 화면

1. **한글패치할 게임을 선택하세요**: 찾은 설치 목록. 중섭이 아닌 설치는 흐리게 보이고 선택할 수 없다. 다시 찾기 · 실행 파일 직접 선택 · 관리자 권한으로 전체 스캔.
2. **선택한 게임**: 상태와 주 버튼(한글패치 적용 / 임시 복구 / 정식 한패로 교체), 업데이트 확인, 원본 복원, 게임 버전·한패·번역 메모리·백업 정보.
3. **기록**

## 동작 (`PatchService`, 안드로이드 PatchEngine 과 같은 규칙)

- 게임 폴더에 공식 원본(한글 없음)이 보이면 앱 데이터에 보관하고, 같은 버전 한패가 있으면 번역 메모리에 넣는다.
- 한패와 게임의 버전 지문(Id 집합)이 다르면 옛 한패 적용을 막는다. 대신 보관한 공식 원본을 번역 메모리로 한국어화한 **임시 복구본**을 넣는다.
- 적용 전 설치마다 처음 한 번 게임 폴더 파일을 백업한다. **원본 복원**은 백업이 지금 게임 버전일 때만 한다.
- 크기·수정시각이 그대로면 해시·본문 읽기를 건너뛴다.
- 게임(`GF2_Exilium.exe`)이 그 폴더에서 실행 중이면 쓰지 않는다.

## 관리자 권한

앱은 일반 권한으로 돈다. 필요한 일만 같은 exe 를 관리자 권한으로 한 번 더 띄워서 한다 (`Elevated`, UAC 창).

| 명령 | 언제 | 하는 일 |
|---|---|---|
| `--scan <결과파일>` | "관리자 권한으로 전체 스캔" | NTFS MFT(`FSCTL_ENUM_USN_DATA`)로 모든 고정 NTFS 드라이브에서 `GF2_Exilium.exe`·`PCLauncher.exe` 를 찾는다. 폴더를 하나씩 열지 않아 몇 초면 끝난다. 찾은 폴더도 중섭 확인을 거친다 |
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
| 전에 찾은 경로 (전체 스캔·직접 선택 포함) | 게임 폴더 | 매번 재검증, 없어진 폴더는 버림, 중섭만 기억 |

## 남은 확인 사항

- B服 PC 실제 설치본으로 표식 확인.
- 글로벌·한국 PC판 실물로 "중섭 아님" 판정 확인.
- config.ini 비ASCII 경로 인코딩 (런처가 Qt5 라 `\xXXXX` 로 이스케이프할 수 있음).
