# 번역 엔진 C++ 판 (snqx)

안드로이드 Kotlin 엔진(`app/.../langtable`)·윈도우 C# 엔진(`windows/.../Engine`)과 같은 규칙으로, 결과가 바이트 단위로 같다.
형식·알고리즘은 `docs/lang-table-format.md`.

| 파일 | 내용 |
|---|---|
| `snqx.h` | C API. 파일 경로(UTF-8)를 받아 파일 단위로 일한다: `snqx_layout_key`, `snqx_alignment`, `snqx_build_memory`, `snqx_repair` |
| `engine.cpp` | 파싱(파일을 매핑하고 문장은 그 안의 자리만 든다, 작업이 끝나면 바로 푼다), 번역 메모리, 자리 검사, 임시 복구 1·2단계, 한 번에 쓰기 |
| `sha256.cpp` | 윈도우는 BCrypt, ARM64 는 CPU SHA2 명령(없으면 C 구현) |
| `jni.cpp` | 안드로이드 `com.hoho.snqxkr.langtable.SnqxJni` |
| `cli.cpp` | 점검·측정 (배포하지 않음): `snqx_cli <샘플폴더> <출력폴더> [C#메모리] [C#복구본]` |

## 빌드

- 윈도우: `build-windows.cmd` → `build/windows-x64/Release/snqx.dll`, `snqx_cli.exe` (Visual Studio 2022 C++, 정적 CRT)
- 안드로이드: 앱의 native 판을 빌드하면 Gradle 이 `libsnqx.so` (arm64-v8a, armv8-a) 를 만든다. 폰에서 돌리는 점검 프로그램은
  ```
  cmake -S native -B native/build/android-arm64 -G Ninja -DCMAKE_TOOLCHAIN_FILE=<NDK>/build/cmake/android.toolchain.cmake \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DSNQX_BUILD_CLI=ON
  cmake --build native/build/android-arm64 --target snqx_cli
  ```

armv9-a 로 따로 빌드한 판은 이 폰(SM8850)에서 armv8-a 판보다 느리거나 같아 넣지 않는다.

## 안전

- 깨진 파일은 오류로 끝낸다: varint·길이·줄 수를 남은 크기로 확인한다. 예외는 C API 에서 잡아 -1 과 `snqx_last_error` 로 바꾼다.
- 전역 상태 없음 (오류 문자열만 스레드마다). 여러 스레드에서 동시에 불러도 된다.
- 해시·찾기는 최대 4개 스레드로 나눈다.
