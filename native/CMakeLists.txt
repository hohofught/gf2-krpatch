# 번역 엔진 네이티브 판
#  - 윈도우: snqx.dll (x64, 정적 CRT: VC++ 재배포 패키지 없이 돈다) + snqx_cli (점검·측정용)
#  - 안드로이드: libsnqx.so (arm64-v8a, armv8-a). armv9-a 판은 이 폰(SM8850)에서 오히려 느려 넣지 않는다
cmake_minimum_required(VERSION 3.22)
project(snqx LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

set(SNQX_SOURCES engine.cpp sha256.cpp)

if(ANDROID)
  add_library(snqx SHARED ${SNQX_SOURCES} jni.cpp)
  target_compile_options(snqx PRIVATE -march=armv8-a -O3 -fexceptions -ffunction-sections -fdata-sections)
  target_compile_definitions(snqx PRIVATE SNQX_ARCH="armv8-a")
  # 16KB 페이지 기기(안드로이드 15+) 대응, 쓰지 않는 코드 제거, 기호 제거
  target_link_options(snqx PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384 -s)
  # 폰에서 직접 돌리는 점검·측정 프로그램 (앱에는 들어가지 않음): cmake -DSNQX_BUILD_CLI=ON
  if(SNQX_BUILD_CLI)
    add_executable(snqx_cli cli.cpp ${SNQX_SOURCES})
    target_compile_options(snqx_cli PRIVATE -O3 -march=armv8-a)
    target_compile_definitions(snqx_cli PRIVATE SNQX_ARCH="armv8-a cli")
    target_link_options(snqx_cli PRIVATE -static-libstdc++)
  endif()
else()
  set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")
  add_library(snqx SHARED ${SNQX_SOURCES})
  target_compile_definitions(snqx PRIVATE SNQX_ARCH="x64" _CRT_SECURE_NO_WARNINGS)
  add_executable(snqx_cli cli.cpp ${SNQX_SOURCES})
  target_compile_definitions(snqx_cli PRIVATE SNQX_ARCH="x64 cli" _CRT_SECURE_NO_WARNINGS)
  if(MSVC)
    target_compile_options(snqx PRIVATE /O2 /utf-8 /W4)
    target_compile_options(snqx_cli PRIVATE /O2 /utf-8)
  endif()
endif()

# 안드로이드 kotlin 판(엔진 .so 없음)이 고르는 빈 대상
add_custom_target(snqx_none)
