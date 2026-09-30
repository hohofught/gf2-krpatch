/*
 * 소전2 한글패치 번역 엔진 (C++ 네이티브). 안드로이드 langtable 의 Kotlin 엔진, 윈도우 Engine 의 C# 엔진과 같은 결과를 낸다.
 * 파일 경로를 받아 파일 단위로 일한다: 큰 배열을 JNI·P/Invoke 경계로 넘기지 않는다.
 * 경로는 UTF-8. 성공 0, 실패 -1 (이유는 snqx_last_error).
 */
#ifndef SNQX_H
#define SNQX_H

#include <stdint.h>

#if defined(_WIN32)
#define SNQX_API __declspec(dllexport)
#else
#define SNQX_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/** 엔진 판 ("snqx 1 armv8-a" 등) */
SNQX_API const char* snqx_version(void);

/** 이 스레드에서 마지막으로 실패한 이유 */
SNQX_API const char* snqx_last_error(void);

/** 게임 버전 지문 "줄수:해시16자리" (Id 집합). out 은 64바이트 이상 */
SNQX_API int snqx_layout_key(const char* path, char* out, int out_len);

/**
 * 한패가 같은 버전 공식 원문과 같은 자리에 번역을 넣었는지.
 * out[0] = 번역 안 된 줄(한글 없이 한자만), out[1] = 그중 같은 Id 원문과 같은 줄
 */
SNQX_API int snqx_alignment(const char* official, const char* patch, int32_t* out2);

/**
 * 같은 버전 공식 원문 + 한패로 번역 메모리를 만들어 memory_in(없으면 NULL)과 합치고 max_bytes 로 잘라 memory_out 에 쓴다.
 * 자리 검사가 min_alignment 에 못 미치면(판단할 줄이 20줄 이상일 때) 만들지 않는다.
 * out[0] = 1 만듦 / 0 자리가 맞지 않아 안 만듦, out[1] = 줄 수, out[2] = 크기 제한으로 뺀 줄,
 * out[3] = 번역 안 된 줄, out[4] = 그중 원문과 같은 줄
 */
SNQX_API int snqx_build_memory(const char* official, const char* patch, const char* memory_in,
                               const char* memory_out, int64_t max_bytes, double min_alignment, int64_t* out5);

/**
 * 임시 복구: 새 공식 원문을 번역 메모리로 한국어화하고, old_patch(없으면 NULL)의 번역을 새 자리로 옮겨 out_path 에 쓴다.
 * out[0] = 한국어 줄, out[1] = 중국어로 남은 줄, out[2] = 옛 한패에서 옮긴 줄. sha65 = 쓴 파일의 SHA-256 (소문자, NUL 포함 65바이트)
 */
SNQX_API int snqx_repair(const char* official, const char* memory, const char* old_patch, const char* out_path,
                         int32_t* out3, char* sha65);

#ifdef __cplusplus
}
#endif

#endif
