// SHA-256. 버전 지문과 쓴 파일 확인에 쓴다.
//  - 윈도우: OS 의 BCrypt (CPU SHA 명령을 쓴다)
//  - ARM64: CPU 에 SHA2 명령이 있으면 그것으로, 없으면 C 구현
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace snqx {

class Sha256 {
public:
    Sha256();
    ~Sha256();
    Sha256(const Sha256&) = delete;
    Sha256& operator=(const Sha256&) = delete;

    void update(const uint8_t* data, size_t len);
    /** 소문자 16진수 64자 (한 번만 부른다) */
    std::string hex();

private:
#if defined(_WIN32)
    void* alg_ = nullptr;
    void* hash_ = nullptr;
#else
    void blocks(const uint8_t* p, size_t n);
    uint32_t h_[8];
    uint8_t buf_[64];
    size_t fill_ = 0;
    uint64_t total_ = 0;
#endif
};

/** 이 기기에서 SHA-256 을 CPU 명령으로 하는지 (측정·로그용) */
bool sha256_hardware();

}  // namespace snqx
