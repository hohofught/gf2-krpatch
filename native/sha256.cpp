#include "sha256.h"

#include <cstring>
#include <stdexcept>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <bcrypt.h>
#pragma comment(lib, "bcrypt.lib")
#endif

#if defined(__aarch64__)
#include <arm_neon.h>
#if defined(__ANDROID__) || defined(__linux__)
#include <sys/auxv.h>
#ifndef HWCAP_SHA2
#define HWCAP_SHA2 (1 << 6)
#endif
#endif
#endif

namespace snqx {

#if defined(_WIN32)

Sha256::Sha256() {
    BCRYPT_ALG_HANDLE alg = nullptr;
    BCRYPT_HASH_HANDLE hash = nullptr;
    if (BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM, nullptr, 0) != 0 ||
        BCryptCreateHash(alg, &hash, nullptr, 0, nullptr, 0, 0) != 0)
        throw std::runtime_error("SHA-256 을 준비하지 못함");
    alg_ = alg;
    hash_ = hash;
}

Sha256::~Sha256() {
    if (hash_) BCryptDestroyHash(static_cast<BCRYPT_HASH_HANDLE>(hash_));
    if (alg_) BCryptCloseAlgorithmProvider(static_cast<BCRYPT_ALG_HANDLE>(alg_), 0);
}

void Sha256::update(const uint8_t* data, size_t len) {
    while (len > 0) {
        ULONG k = len > 0x40000000 ? 0x40000000 : ULONG(len);
        BCryptHashData(static_cast<BCRYPT_HASH_HANDLE>(hash_), const_cast<PUCHAR>(data), k, 0);
        data += k;
        len -= k;
    }
}

std::string Sha256::hex() {
    uint8_t d[32];
    BCryptFinishHash(static_cast<BCRYPT_HASH_HANDLE>(hash_), d, sizeof d, 0);
    static const char* digits = "0123456789abcdef";
    std::string s(64, '0');
    for (int i = 0; i < 32; i++) { s[2 * i] = digits[d[i] >> 4]; s[2 * i + 1] = digits[d[i] & 15]; }
    return s;
}

bool sha256_hardware() { return true; }

#else

namespace {

const uint32_t K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
};

inline uint32_t rotr(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }

void blocks_c(uint32_t h[8], const uint8_t* p, size_t n) {
    while (n--) {
        uint32_t w[64];
        for (int i = 0; i < 16; i++)
            w[i] = uint32_t(p[4 * i]) << 24 | uint32_t(p[4 * i + 1]) << 16 | uint32_t(p[4 * i + 2]) << 8 | p[4 * i + 3];
        for (int i = 16; i < 64; i++) {
            uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
            uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; i++) {
            uint32_t t1 = hh + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i];
            uint32_t t2 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c));
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
        p += 64;
    }
}

#if defined(__aarch64__)
/** ARMv8 SHA2 명령. 메시지 4워드 묶음 16개를 돌리며 다음 묶음을 만든다. */
__attribute__((target("sha2"))) void blocks_arm(uint32_t h[8], const uint8_t* p, size_t n) {
    uint32x4_t s0 = vld1q_u32(h), s1 = vld1q_u32(h + 4);
    while (n--) {
        const uint32x4_t save0 = s0, save1 = s1;
        uint32x4_t m[4];
        for (int i = 0; i < 4; i++) m[i] = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(p + 16 * i)));
        for (int r = 0; r < 16; r++) {
            const uint32x4_t wk = vaddq_u32(m[r & 3], vld1q_u32(K + 4 * r));
            const uint32x4_t prev = s0;
            s0 = vsha256hq_u32(s0, s1, wk);
            s1 = vsha256h2q_u32(s1, prev, wk);
            if (r < 12) m[r & 3] = vsha256su1q_u32(vsha256su0q_u32(m[r & 3], m[(r + 1) & 3]), m[(r + 2) & 3], m[(r + 3) & 3]);
        }
        s0 = vaddq_u32(s0, save0);
        s1 = vaddq_u32(s1, save1);
        p += 64;
    }
    vst1q_u32(h, s0);
    vst1q_u32(h + 4, s1);
}

bool has_arm_sha2() {
    static const bool yes = (getauxval(AT_HWCAP) & HWCAP_SHA2) != 0;
    return yes;
}
#endif

}  // namespace

bool sha256_hardware() {
#if defined(__aarch64__)
    return has_arm_sha2();
#else
    return false;
#endif
}

Sha256::Sha256() {
    const uint32_t init[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                              0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    std::memcpy(h_, init, sizeof h_);
}

Sha256::~Sha256() = default;

void Sha256::blocks(const uint8_t* p, size_t n) {
#if defined(__aarch64__)
    if (has_arm_sha2()) { blocks_arm(h_, p, n); return; }
#endif
    blocks_c(h_, p, n);
}

void Sha256::update(const uint8_t* data, size_t len) {
    total_ += len;
    if (fill_ > 0) {
        size_t k = 64 - fill_ < len ? 64 - fill_ : len;
        std::memcpy(buf_ + fill_, data, k);
        fill_ += k; data += k; len -= k;
        if (fill_ < 64) return;
        blocks(buf_, 1);
        fill_ = 0;
    }
    if (len >= 64) {
        size_t n = len / 64;
        blocks(data, n);
        data += n * 64;
        len -= n * 64;
    }
    std::memcpy(buf_, data, len);
    fill_ = len;
}

std::string Sha256::hex() {
    uint64_t bits = total_ * 8;
    uint8_t tail[128] = {0};
    size_t pad = fill_ < 56 ? 56 - fill_ : 120 - fill_;
    tail[0] = 0x80;
    for (int i = 0; i < 8; i++) tail[pad + i] = uint8_t(bits >> (56 - 8 * i));
    update(tail, pad + 8);
    static const char* digits = "0123456789abcdef";
    std::string s(64, '0');
    for (int i = 0; i < 8; i++)
        for (int j = 0; j < 4; j++) {
            uint8_t b = uint8_t(h_[i] >> (24 - 8 * j));
            s[8 * i + 2 * j] = digits[b >> 4];
            s[8 * i + 2 * j + 1] = digits[b & 15];
        }
    return s;
}

#endif

}  // namespace snqx
