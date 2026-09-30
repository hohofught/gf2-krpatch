// 안드로이드 JNI: com.hoho.snqxkr.langtable.SnqxJni 의 external 함수들. 파일 경로만 오가고 결과는 작은 배열·문자열.
#include <jni.h>

#include <string>

#include "snqx.h"

namespace {

struct Utf {
    Utf(JNIEnv* env, jstring s) : env_(env), s_(s), c_(s ? env->GetStringUTFChars(s, nullptr) : nullptr) {}
    ~Utf() { if (c_) env_->ReleaseStringUTFChars(s_, c_); }
    const char* get() const { return c_; }
    /** 문자열을 못 꺼냄 (메모리 부족, 자바 쪽에 OutOfMemoryError 가 걸려 있다). 그러면 null 을 돌려 그 예외가 나가게 한다 */
    bool failed() const { return s_ && !c_; }
    JNIEnv* env_;
    jstring s_;
    const char* c_;
};

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_com_hoho_snqxkr_langtable_SnqxJni_version(JNIEnv* env, jobject) {
    return env->NewStringUTF(snqx_version());
}

JNIEXPORT jstring JNICALL Java_com_hoho_snqxkr_langtable_SnqxJni_lastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(snqx_last_error());
}

JNIEXPORT jstring JNICALL Java_com_hoho_snqxkr_langtable_SnqxJni_layoutKey(JNIEnv* env, jobject, jstring path) {
    Utf p(env, path);
    if (p.failed()) return nullptr;
    char out[64];
    if (snqx_layout_key(p.get(), out, sizeof out) != 0) return nullptr;
    return env->NewStringUTF(out);
}

JNIEXPORT jintArray JNICALL Java_com_hoho_snqxkr_langtable_SnqxJni_alignment(JNIEnv* env, jobject, jstring official, jstring patch) {
    Utf o(env, official), p(env, patch);
    if (o.failed() || p.failed()) return nullptr;
    int32_t out[2];
    if (snqx_alignment(o.get(), p.get(), out) != 0) return nullptr;
    jintArray r = env->NewIntArray(2);
    if (!r) return nullptr;
    env->SetIntArrayRegion(r, 0, 2, reinterpret_cast<const jint*>(out));
    return r;
}

JNIEXPORT jlongArray JNICALL Java_com_hoho_snqxkr_langtable_SnqxJni_buildMemory(
    JNIEnv* env, jobject, jstring official, jstring patch, jstring memoryIn, jstring memoryOut, jlong maxBytes, jdouble minAlignment) {
    Utf o(env, official), p(env, patch), in(env, memoryIn), out(env, memoryOut);
    if (o.failed() || p.failed() || in.failed() || out.failed()) return nullptr;
    int64_t res[5];
    if (snqx_build_memory(o.get(), p.get(), in.get(), out.get(), maxBytes, minAlignment, res) != 0) return nullptr;
    jlongArray r = env->NewLongArray(5);
    if (!r) return nullptr;
    env->SetLongArrayRegion(r, 0, 5, reinterpret_cast<const jlong*>(res));
    return r;
}

/** "한국어줄 중국어줄 옮긴줄 sha256" (실패하면 null) */
JNIEXPORT jstring JNICALL Java_com_hoho_snqxkr_langtable_SnqxJni_repair(
    JNIEnv* env, jobject, jstring official, jstring memory, jstring oldPatch, jstring outPath) {
    Utf o(env, official), m(env, memory), old(env, oldPatch), out(env, outPath);
    if (o.failed() || m.failed() || old.failed() || out.failed()) return nullptr;
    int32_t counts[3];
    char sha[65];
    if (snqx_repair(o.get(), m.get(), old.get(), out.get(), counts, sha) != 0) return nullptr;
    std::string s = std::to_string(counts[0]) + " " + std::to_string(counts[1]) + " " + std::to_string(counts[2]) + " " + sha;
    return env->NewStringUTF(s.c_str());
}

}  // extern "C"
