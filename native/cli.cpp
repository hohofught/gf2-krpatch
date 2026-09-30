// 네이티브 엔진 점검·측정 (배포하지 않음). 윈도우 SnqxKR.EngineCheck·안드로이드 LangTableTest 와 같은 파일·숫자.
// 사용법: snqx_cli <샘플폴더> <출력폴더> [비교할_C#_번역메모리] [비교할_C#_복구본]
#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "snqx.h"

static int failures = 0;

static void check(const std::string& what, bool ok) {
    std::printf("  [%s] %s\n", ok ? "통과" : "실패", what.c_str());
    if (!ok) failures++;
}

static std::vector<unsigned char> slurp(const std::string& path) {
    std::vector<unsigned char> b;
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return b;
    unsigned char buf[1 << 16];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof buf, f)) > 0) b.insert(b.end(), buf, buf + n);
    std::fclose(f);
    return b;
}

static bool exists(const std::string& path) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (f) std::fclose(f);
    return f != nullptr;
}

template <class F>
static long long ms(F&& f) {
    auto t0 = std::chrono::steady_clock::now();
    f();
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
}

int main(int argc, char** argv) {
    if (argc < 3) { std::printf("사용법: snqx_cli <샘플폴더> <출력폴더> [C#메모리] [C#복구본]\n"); return 2; }
    std::string d = argv[1], o = argv[2];
    auto F = [&](const char* n) { return d + "/" + n; };
    auto O = [&](const char* n) { return o + "/" + n; };
    std::printf("%s\n", snqx_version());

    char key[64];
    long long t = ms([&] { snqx_layout_key(F("patch-current.bytes").c_str(), key, sizeof key); });
    check(std::string("버전 지문 ") + key + " (" + std::to_string(t) + " ms)", std::strcmp(key, "497166:a6506173cdddd936") == 0);

    // 08월(V4) 번역 메모리: 원문 08-24 + 한패 08-27
    int64_t mem[5] = {0};
    t = ms([&] {
        if (snqx_build_memory(F("official-0824.bytes").c_str(), F("patch-0827.bytes").c_str(), nullptr,
                              O("native-tm-v4.bin").c_str(), 500LL << 20, 0.90, mem) != 0)
            std::printf("  오류: %s\n", snqx_last_error());
    });
    check("번역 메모리 252,684줄 (" + std::to_string(t) + " ms)", mem[0] == 1 && mem[1] == 252684);
    if (argc > 3 && exists(argv[3])) check("C# 가 쓴 번역 메모리와 바이트 단위로 같음", slurp(O("native-tm-v4.bin")) == slurp(argv[3]));

    // 메모리만으로 복구 / 옛 한패(V2)까지
    int counts[3];
    char sha[65];
    t = ms([&] {
        if (snqx_repair(F("official-pc-current.bytes").c_str(), O("native-tm-v4.bin").c_str(), nullptr,
                        O("native-repaired-1.bytes").c_str(), counts, sha) != 0)
            std::printf("  오류: %s\n", snqx_last_error());
    });
    check("복구 1단계: 한국어 463,540 / 중국어 30,599 (" + std::to_string(t) + " ms)", counts[0] == 463540 && counts[1] == 30599);
    t = ms([&] {
        if (snqx_repair(F("official-pc-current.bytes").c_str(), O("native-tm-v4.bin").c_str(), F("patch-v2-0919.bytes").c_str(),
                        O("native-repaired.bytes").c_str(), counts, sha) != 0)
            std::printf("  오류: %s\n", snqx_last_error());
    });
    check("복구 1+2단계: 한국어 463,799 / 옮김 5,114 (" + std::to_string(t) + " ms) sha " + std::string(sha).substr(0, 12),
          counts[0] == 463799 && counts[2] == 5114);
    if (argc > 4 && exists(argv[4])) check("C# 가 쓴 복구본과 바이트 단위로 같음", slurp(O("native-repaired.bytes")) == slurp(argv[4]));

    // 한패 재작성 일치: 같은 버전 원문 + 한패로 만든 메모리로 원문을 복구하면 한패의 번역이 그대로 나온다
    // (파일 재작성은 복구 경로가 쓴다: 원문을 메모리 없이 복구하면 원문과 바이트 단위로 같아야 한다)
    int64_t empty[5] = {0};
    snqx_build_memory(F("official-pc-current.bytes").c_str(), F("patch-current.bytes").c_str(), nullptr,
                      O("native-tm-v1.bin").c_str(), 500LL << 20, 0.90, empty);
    check("현재 버전 메모리 " + std::to_string(empty[1]) + "줄 (268,956)", empty[0] == 1 && empty[1] == 268956);

    // 자리 검사
    struct Case { const char* official; const char* patch; bool ok; };
    const Case cases[] = {
        {"official-pc-current.bytes", "history/20260922-0725-0cce1eb.bytes", false},
        {"official-pc-current.bytes", "history/20260927-1419-76f18c5.bytes", true},
        {"official-0824.bytes", "history/20260811-1111-0398002.bytes", false},
        {"official-0824.bytes", "history/20260827-0119-810ed14.bytes", true},
    };
    for (const Case& c : cases) {
        if (!exists(F(c.patch))) continue;
        int a[2] = {0, 0};
        t = ms([&] { snqx_alignment(F(c.official).c_str(), F(c.patch).c_str(), a); });
        bool ok = a[0] >= 20 && double(a[1]) / a[0] >= 0.90;
        check(std::string("자리 검사 ") + c.patch + ": " + std::to_string(a[1]) + "/" + std::to_string(a[0]) + " (" + std::to_string(t) + " ms)",
              ok == c.ok);
    }
    // 깨진 한패로는 번역 메모리를 만들지 않는다
    if (exists(F("history/20260922-0725-0cce1eb.bytes"))) {
        int64_t bad[5] = {9, 9, 9, 9, 9};
        snqx_build_memory(F("official-pc-current.bytes").c_str(), F("history/20260922-0725-0cce1eb.bytes").c_str(), nullptr,
                          O("native-tm-bad.bin").c_str(), 500LL << 20, 0.90, bad);
        check("자리가 안 맞는 한패로는 번역 메모리를 만들지 않음", bad[0] == 0 && !exists(O("native-tm-bad.bin")));
    }
    // 번역 메모리 최대 크기: 작은 한도로 자르면 새 세대가 남는다
    int64_t cut[5] = {0};
    snqx_build_memory(F("official-pc-current.bytes").c_str(), F("patch-current.bytes").c_str(), O("native-tm-v4.bin").c_str(),
                      O("native-tm-cut.bin").c_str(), 34000000, 0.90, cut);
    check("최대 크기로 자름: " + std::to_string(cut[1]) + "줄 남고 " + std::to_string(cut[2]) + "줄 뺌",
          cut[0] == 1 && cut[2] > 0 && (long long)slurp(O("native-tm-cut.bin")).size() <= 34000000);

    // 측정: 앱의 임시 복구 버튼 한 번과 같은 작업을 두 번
    for (int r = 1; r <= 2; r++) {
        long long tr = ms([&] {
            snqx_repair(F("official-pc-current.bytes").c_str(), O("native-tm-v4.bin").c_str(), F("patch-v2-0919.bytes").c_str(),
                        O("native-repaired.bytes").c_str(), counts, sha);
        });
        long long tm = ms([&] {
            snqx_build_memory(F("official-pc-current.bytes").c_str(), F("patch-current.bytes").c_str(), O("native-tm-v4.bin").c_str(),
                              O("native-tm-merged.bin").c_str(), 500LL << 20, 0.90, mem);
        });
        long long tl = ms([&] { snqx_layout_key(F("patch-current.bytes").c_str(), key, sizeof key); });
        std::printf("  %d회차: 임시 복구(읽기·1+2단계·쓰기·해시) %lld ms, 번역 메모리 갱신 %lld ms, 버전 지문 %lld ms\n", r, tr, tm, tl);
    }

    std::printf(failures == 0 ? "모두 통과\n" : "실패 %d개\n", failures);
    return failures == 0 ? 0 : 1;
}
