// 번역 엔진 C++ 판. 규칙은 app/.../langtable/*.kt 와 windows/.../Engine/*.cs 를 그대로 따르고, 결과가 바이트 단위로 같아야 한다.
// 형식·알고리즘 설명은 docs/lang-table-format.md.
#include "snqx.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "sha256.h"

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

#ifndef SNQX_ARCH
#define SNQX_ARCH "generic"
#endif

namespace snqx {
namespace {

thread_local std::string g_error;

struct Error : std::runtime_error {
    using std::runtime_error::runtime_error;
};

/** [0, n) 을 최대 4개 스레드로 나눠 f(시작, 끝). 작으면 한 스레드. f 는 예외를 던지지 않아야 한다. */
template <class F>
void parallel_for(size_t n, F&& f) {
    unsigned hw = std::thread::hardware_concurrency();
    unsigned t = hw == 0 ? 1 : (hw < 4 ? hw : 4);
    if (t <= 1 || n < 65536) { f(size_t(0), n); return; }
    size_t chunk = (n + t - 1) / t;
    std::vector<std::thread> threads;
    for (unsigned i = 1; i < t; i++) {
        size_t b = i * chunk, e = std::min(n, b + chunk);
        if (b < e) threads.emplace_back([&f, b, e] { f(b, e); });
    }
    f(size_t(0), std::min(n, chunk));
    for (auto& th : threads) th.join();
}

// ---------------- 파일 ----------------

struct FileCloser {
    void operator()(FILE* f) const { if (f) fclose(f); }
};
using File = std::unique_ptr<FILE, FileCloser>;

#if defined(_WIN32)
// 윈도우 파일 API 는 UTF-8 경로를 못 받는다 (사용자 폴더가 한글일 수 있음)
std::wstring wide(const char* s) {
    int n = MultiByteToWideChar(CP_UTF8, 0, s, -1, nullptr, 0);
    std::wstring w(n > 0 ? n : 1, L'\0');
    if (n > 0) MultiByteToWideChar(CP_UTF8, 0, s, -1, &w[0], n);
    return w;
}
#endif

File open(const char* path, const char* mode) {
#if defined(_WIN32)
    return File(_wfopen(wide(path).c_str(), wide(mode).c_str()));
#else
    return File(fopen(path, mode));
#endif
}

std::vector<uint8_t> read_file(const char* path) {
    File f = open(path, "rb");
    if (!f) throw Error(std::string("파일을 열 수 없음: ") + path);
#if defined(_WIN32)
    _fseeki64(f.get(), 0, SEEK_END);
    long long size = _ftelli64(f.get());
    _fseeki64(f.get(), 0, SEEK_SET);
#else
    fseeko(f.get(), 0, SEEK_END);
    long long size = ftello(f.get());
    fseeko(f.get(), 0, SEEK_SET);
#endif
    if (size < 0 || size > (1LL << 31)) throw Error(std::string("파일 크기가 이상함: ") + path);
    std::vector<uint8_t> b(static_cast<size_t>(size));
    if (size > 0 && fread(b.data(), 1, b.size(), f.get()) != b.size()) throw Error(std::string("파일을 다 읽지 못함: ") + path);
    return b;
}

/**
 * 읽기 전용으로 매핑한 파일. 문장은 복사하지 않고 여기를 가리킨다.
 * 매핑한 쪽은 복사본과 달리 메모리가 모자라면 OS 가 내렸다가 다시 읽는다 (안드로이드에서 앱이 덜 죽는다).
 * 없어질 때(작업 하나가 끝날 때) 바로 푼다. 매핑이 안 되면 통째로 읽는다.
 *
 * 매핑한 파일이 읽는 동안 잘리면 리눅스·안드로이드는 SIGBUS 로 죽는다. 앱은 엔진이 읽는 파일을 모두
 * 임시 파일에 쓴 뒤 이름을 바꾸고, 출력이 입력과 같은 파일이면 [output_path] 가 임시 파일로 돌린다.
 */
class Blob {
public:
    Blob() = default;
    Blob(const Blob&) = delete;
    Blob& operator=(const Blob&) = delete;
    ~Blob() {
        if (!mapped_) return;
#if defined(_WIN32)
        UnmapViewOfFile(p_);
#else
        munmap(const_cast<uint8_t*>(p_), n_);
#endif
    }

    const uint8_t* data() const { return p_; }
    size_t size() const { return n_; }

    static std::unique_ptr<Blob> open(const char* path) {
        static const uint8_t kEmpty[1] = {0};
        std::unique_ptr<Blob> b(new Blob());
        b->p_ = kEmpty;
        long long size = -1;
        void* view = nullptr;
#if defined(_WIN32)
        // 다른 프로그램(게임 업데이트)이 읽기·쓰기·지우기를 할 수 있게 연다 (fopen 과 같게, 지우기만 더)
        HANDLE f = CreateFileW(wide(path).c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                               nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (f == INVALID_HANDLE_VALUE) throw Error(std::string("파일을 열 수 없음: ") + path);
        LARGE_INTEGER li;
        if (GetFileSizeEx(f, &li)) size = li.QuadPart;
        if (size > 0 && size <= (1LL << 31)) {
            HANDLE m = CreateFileMappingW(f, nullptr, PAGE_READONLY, 0, 0, nullptr);
            if (m) {
                view = MapViewOfFile(m, FILE_MAP_READ, 0, 0, 0);
                CloseHandle(m);  // 보기(view)가 남아 있는 동안 매핑은 유지된다
            }
        }
        CloseHandle(f);
#else
        int fd = ::open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) throw Error(std::string("파일을 열 수 없음: ") + path);
        struct stat st;
        if (fstat(fd, &st) == 0) size = st.st_size;
        if (size > 0 && size <= (1LL << 31)) {
            void* m = mmap(nullptr, size_t(size), PROT_READ, MAP_PRIVATE, fd, 0);
            if (m != MAP_FAILED) view = m;
        }
        ::close(fd);  // 매핑은 파일을 닫아도 남는다
#endif
        if (size < 0 || size > (1LL << 31)) {
            if (view) b->unmap_now(view, size_t(size));
            throw Error(std::string("파일 크기가 이상함: ") + path);
        }
        if (view) {
            b->p_ = static_cast<const uint8_t*>(view);
            b->n_ = size_t(size);
            b->mapped_ = true;
        } else if (size > 0) {
            b->owned_ = read_file(path);
            b->p_ = b->owned_.data();
            b->n_ = b->owned_.size();
        }
        return b;
    }

private:
    static void unmap_now(void* view, size_t n) {
#if defined(_WIN32)
        (void)n;
        UnmapViewOfFile(view);
#else
        munmap(view, n);
#endif
    }

    const uint8_t* p_ = nullptr;
    size_t n_ = 0;
    bool mapped_ = false;
    std::vector<uint8_t> owned_;  // 매핑을 못 했을 때만
};

/** 두 경로가 같은 파일인지 (출력이 없으면 false) */
bool same_file(const char* a, const char* b) {
    if (!a || !b || !*a || !*b) return false;
#if defined(_WIN32)
    auto id = [](const char* p, BY_HANDLE_FILE_INFORMATION& info) {
        HANDLE h = CreateFileW(wide(p).c_str(), FILE_READ_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                               nullptr, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, nullptr);
        if (h == INVALID_HANDLE_VALUE) return false;
        bool ok = GetFileInformationByHandle(h, &info) != 0;
        CloseHandle(h);
        return ok;
    };
    BY_HANDLE_FILE_INFORMATION x, y;
    return id(a, x) && id(b, y) && x.dwVolumeSerialNumber == y.dwVolumeSerialNumber &&
           x.nFileIndexHigh == y.nFileIndexHigh && x.nFileIndexLow == y.nFileIndexLow;
#else
    struct stat x, y;
    return stat(a, &x) == 0 && stat(b, &y) == 0 && x.st_dev == y.st_dev && x.st_ino == y.st_ino;
#endif
}

/**
 * 출력이 입력(매핑 중) 중 하나와 같은 파일이면 옆 임시 파일에 쓰게 한다. 입력을 다 푼 뒤 [finish_output] 으로 바꾼다.
 * 앱은 늘 다른 파일로 쓰지만, 같은 파일을 주면 쓰는 도중 입력이 잘려 죽으므로 막아 둔다.
 */
std::string output_path(const char* out, std::initializer_list<const char*> inputs) {
    for (const char* in : inputs) if (same_file(out, in)) return std::string(out) + ".tmp";
    return out;
}

void finish_output(const std::string& written, const char* out) {
    if (written == out) return;
#if defined(_WIN32)
    if (!MoveFileExW(wide(written.c_str()).c_str(), wide(out).c_str(), MOVEFILE_REPLACE_EXISTING)) {
#else
    if (std::rename(written.c_str(), out) != 0) {
#endif
        std::remove(written.c_str());
        throw Error("출력 파일을 바꾸지 못함");
    }
}

/** 64KB 블록으로 모아 쓰고 블록마다 SHA-256 을 갱신한다 */
class BlockWriter {
public:
    BlockWriter(const char* path, bool hash) : f_(open(path, "wb")), hash_(hash) {
        if (!f_) throw Error(std::string("파일을 쓸 수 없음: ") + path);
    }
    void byte(int b) {
        if (n_ == sizeof buf_) flush_block();
        buf_[n_++] = uint8_t(b);
    }
    void varint(uint64_t v) {
        while (v >= 0x80) { byte(int((v & 0x7f) | 0x80)); v >>= 7; }
        byte(int(v));
    }
    void u32le(uint32_t v) { byte(v & 0xff); byte((v >> 8) & 0xff); byte((v >> 16) & 0xff); byte((v >> 24) & 0xff); }
    void u32be(uint32_t v) { byte((v >> 24) & 0xff); byte((v >> 16) & 0xff); byte((v >> 8) & 0xff); byte(v & 0xff); }
    void u64be(uint64_t v) { u32be(uint32_t(v >> 32)); u32be(uint32_t(v)); }
    void bytes(const uint8_t* p, size_t len) {
        while (len > 0) {
            if (n_ == sizeof buf_) flush_block();
            size_t k = std::min(len, sizeof buf_ - n_);
            std::memcpy(buf_ + n_, p, k);
            n_ += k; p += k; len -= k;
        }
    }
    /** 다 쓰고 닫는다. 해시를 켰으면 SHA-256 16진수 */
    std::string finish() {
        flush_block();
        if (fflush(f_.get()) != 0 || ferror(f_.get())) throw Error("파일 쓰기 실패");
        f_.reset();
        return hash_ ? sha_.hex() : std::string();
    }

private:
    void flush_block() {
        if (n_ == 0) return;
        if (fwrite(buf_, 1, n_, f_.get()) != n_) throw Error("파일 쓰기 실패 (저장공간?)");
        if (hash_) sha_.update(buf_, n_);
        n_ = 0;
    }
    File f_;
    bool hash_;
    Sha256 sha_;
    uint8_t buf_[1 << 16];
    size_t n_ = 0;
};

// ---------------- protobuf ----------------

struct Reader {
    const uint8_t* b;
    size_t end;
    size_t p;
    uint64_t varint() {
        uint64_t r = 0;
        int s = 0;
        while (true) {
            if (p >= end) throw Error("파일이 중간에 끊김");
            uint8_t x = b[p++];
            r |= uint64_t(x & 0x7f) << s;
            if (x < 0x80) return r;
            s += 7;
            if (s > 63) throw Error("varint 가 너무 김");
        }
    }
};

int varint_size(uint64_t v) {
    int n = 1;
    while (v >= 0x80) { n++; v >>= 7; }
    return n;
}

// ---------------- 표 ----------------

struct Text {
    const uint8_t* p;
    uint32_t n;
};

inline bool same(const Text& a, const Text& b) { return a.n == b.n && (a.n == 0 || std::memcmp(a.p, b.p, a.n) == 0); }

/** 표 안 문장 자리 (파일 안 시작·길이). 포인터(16바이트) 대신 8바이트로 들고, 쓸 때 Text 로 편다 */
struct Ref {
    uint32_t off;
    uint32_t n;
};

struct Table {
    std::unique_ptr<Blob> data;  // 매핑한 파일 (문장은 여기를 가리킨다)
    std::vector<int64_t> ids;
    std::vector<Ref> refs;
    int64_t chunk_size = 10;
    int64_t flag = 1;

    size_t size() const { return ids.size(); }
    Text text(size_t i) const { return Text{data->data() + refs[i].off, refs[i].n}; }

    const std::vector<int32_t>& order() const {
        if (order_.size() != ids.size()) order_ = sorted_order(ids);
        return order_;
    }

    /** Id 오름차순 위치 (같은 Id 는 원래 순서): 이미 정렬돼 있으면 그대로, 아니면 (Id, 위치) 정렬 */
    static std::vector<int32_t> sorted_order(const std::vector<int64_t>& ids) {
        size_t n = ids.size();
        std::vector<int32_t> r(n);
        bool sorted = true;
        for (size_t i = 1; i < n && sorted; i++) if (ids[i] < ids[i - 1]) sorted = false;
        for (size_t i = 0; i < n; i++) r[i] = int32_t(i);
        if (!sorted) std::stable_sort(r.begin(), r.end(), [&](int32_t a, int32_t b) { return ids[a] < ids[b]; });
        return r;
    }

private:
    mutable std::vector<int32_t> order_;
};

Table parse(std::unique_ptr<Blob> blob) {
    Table t;
    t.data = std::move(blob);
    const uint8_t* b = t.data->data();
    size_t size = t.data->size();
    if (size < 4) throw Error("파일이 너무 작음");
    uint64_t header_len = uint64_t(b[0]) | uint64_t(b[1]) << 8 | uint64_t(b[2]) << 16 | uint64_t(b[3]) << 24;
    if (4 + header_len > size) throw Error("색인 길이가 파일보다 큼");
    size_t body_start = size_t(4 + header_len);

    // 색인 앞의 상수 두 개만 읽는다 (청크 목록은 쓸 때 다시 계산한다)
    Reader h{b, body_start, 4};
    while (h.p < body_start) {
        uint64_t tag = h.varint();
        switch (tag & 7) {
            case 0: {
                uint64_t v = h.varint();
                if ((tag >> 3) == 1) t.chunk_size = int64_t(v);
                else if ((tag >> 3) == 2) t.flag = int64_t(v);
                break;
            }
            case 2: {
                uint64_t len = h.varint();
                if (len > body_start - h.p) throw Error("색인이 끊김");
                h.p += size_t(len);
                break;
            }
            default: throw Error("색인 wire type " + std::to_string(tag & 7));
        }
    }
    // 청크 크기 0 은 쓸 때 오류로 낸다 (Kotlin·C# 과 같은 때)

    // 줄 수를 먼저 세어 딱 맞게 잡는다 (늘리며 옮기지 않게). 틀이 깨진 곳에서 멈추고, 오류는 아래 본 읽기가 낸다
    size_t count = 0;
    try {
        Reader c{b, size, body_start};
        while (c.p < size) {
            if (c.varint() != 0x0a) break;
            uint64_t len = c.varint();
            if (len > size - c.p) break;
            c.p += size_t(len);
            count++;
        }
    } catch (const Error&) {
    }
    t.ids.reserve(count);
    t.refs.reserve(count);

    Reader r{b, size, body_start};
    while (r.p < size) {
        uint64_t tag = r.varint();
        if (tag != 0x0a) throw Error("본문 태그 " + std::to_string(tag));
        uint64_t len = r.varint();
        if (len > size - r.p) throw Error("본문이 끊김");
        size_t end = r.p + size_t(len);
        Reader e{b, end, r.p};
        int64_t id = 0;
        Ref ref{0, 0};
        while (e.p < end) {
            uint64_t t2 = e.varint();
            switch (t2 & 7) {
                case 0: {
                    uint64_t v = e.varint();
                    if ((t2 >> 3) == 1) id = int64_t(v);
                    break;
                }
                case 2: {
                    uint64_t l = e.varint();
                    if (l > end - e.p) throw Error("문장이 끊김");
                    if ((t2 >> 3) == 2) ref = Ref{uint32_t(e.p), uint32_t(l)};  // 파일은 2GB 이하 (Blob::open)
                    e.p += size_t(l);
                    break;
                }
                default: throw Error("본문 wire type " + std::to_string(t2 & 7));
            }
        }
        t.ids.push_back(id);
        t.refs.push_back(ref);
        r.p = end;
    }
    return t;
}

Table read_table(const char* path) { return parse(Blob::open(path)); }

/** 본문의 Id 만 (버전 지문용). 색인은 건너뛴다 — Kotlin·C# 의 readIds 와 같은 규칙 */
std::vector<int64_t> read_ids(const char* path) {
    std::unique_ptr<Blob> data = Blob::open(path);
    const uint8_t* b = data->data();
    size_t size = data->size();
    if (size < 4) throw Error("파일이 너무 작음");
    uint64_t header_len = uint64_t(b[0]) | uint64_t(b[1]) << 8 | uint64_t(b[2]) << 16 | uint64_t(b[3]) << 24;
    if (header_len > size - 4) throw Error("색인 길이가 파일보다 큼");
    std::vector<int64_t> ids;
    ids.reserve(1 << 19);
    Reader r{b, size, size_t(4 + header_len)};
    while (r.p < size) {
        uint64_t tag = r.varint();
        if (tag != 0x0a) throw Error("본문 태그 " + std::to_string(tag));
        uint64_t len = r.varint();
        if (len > size - r.p) throw Error("본문이 끊김");
        size_t end = r.p + size_t(len);
        Reader e{b, end, r.p};
        int64_t id = 0;
        while (e.p < end) {
            uint64_t t = e.varint();
            switch (t & 7) {
                case 0: { uint64_t v = e.varint(); if ((t >> 3) == 1) id = int64_t(v); break; }
                case 2: {
                    uint64_t l = e.varint();
                    if (l > end - e.p) throw Error("문장이 끊김");
                    e.p += size_t(l);
                    break;
                }
                default: throw Error("본문 wire type " + std::to_string(t & 7));
            }
        }
        ids.push_back(id);
        r.p = end;
    }
    return ids;
}

bool same_ids(const Table& a, const Table& b) {
    if (a.ids.size() != b.ids.size()) return false;
    const auto& oa = a.order();
    const auto& ob = b.order();
    for (size_t k = 0; k < oa.size(); k++) if (a.ids[oa[k]] != b.ids[ob[k]]) return false;
    return true;
}

std::string layout_key(const std::vector<int64_t>& ids) {
    std::vector<int64_t> s = ids;
    std::sort(s.begin(), s.end());
    Sha256 sha;
    std::vector<uint8_t> buf;
    buf.reserve(8 << 13);
    for (int64_t v : s) {
        for (int i = 0; i < 8; i++) buf.push_back(uint8_t(uint64_t(v) >> (8 * i)));
        if (buf.size() == (8u << 13)) { sha.update(buf.data(), buf.size()); buf.clear(); }
    }
    sha.update(buf.data(), buf.size());
    return std::to_string(s.size()) + ":" + sha.hex().substr(0, 16);
}

/** 한패 모양으로 쓴다: Id 오름차순 본문 + 청크 색인(청크 번호 내림차순). SHA-256 을 돌려준다. */
std::string write_table(const char* path, const std::vector<int64_t>& ids, const std::vector<Text>& texts,
                        const std::vector<int32_t>& order, int64_t chunk_size, int64_t flag) {
    if (chunk_size == 0) throw Error("청크 크기 0");
    auto inner_size = [&](int32_t i) {
        int64_t id = ids[i];
        const Text& t = texts[i];
        return (id != 0 ? 1 + varint_size(uint64_t(id)) : 0) + (t.n > 0 ? 1 + varint_size(t.n) + int(t.n) : 0);
    };
    // 1) 크기만 계산해 청크(Id/청크크기)마다 offset·size
    std::vector<int64_t> cids, offs, lens;
    int64_t pos = 0, last = INT64_MIN, start = 0;
    for (int32_t i : order) {
        int inner = inner_size(i);
        int64_t cid = ids[i] / chunk_size;
        if (cid != last) {
            if (last != INT64_MIN) lens.push_back(pos - start);
            cids.push_back(cid); offs.push_back(pos);
            last = cid; start = pos;
        }
        pos += 1 + varint_size(uint64_t(inner)) + inner;
    }
    if (last != INT64_MIN) lens.push_back(pos - start);

    // 2) 색인
    std::vector<uint8_t> header;
    auto put = [&](uint64_t v) { while (v >= 0x80) { header.push_back(uint8_t((v & 0x7f) | 0x80)); v >>= 7; } header.push_back(uint8_t(v)); };
    header.push_back(0x08); put(uint64_t(chunk_size));
    header.push_back(0x10); put(uint64_t(flag));
    for (size_t k = cids.size(); k-- > 0;) {
        int64_t cid = cids[k], off = offs[k], len = lens[k];
        int loc = (off != 0 ? 1 + varint_size(uint64_t(off)) : 0) + (len != 0 ? 1 + varint_size(uint64_t(len)) : 0);
        int ent = (cid != 0 ? 1 + varint_size(uint64_t(cid)) : 0) + 1 + varint_size(uint64_t(loc)) + loc;
        header.push_back(0x1a); put(uint64_t(ent));
        if (cid != 0) { header.push_back(0x08); put(uint64_t(cid)); }
        header.push_back(0x12); put(uint64_t(loc));
        if (off != 0) { header.push_back(0x08); put(uint64_t(off)); }
        if (len != 0) { header.push_back(0x10); put(uint64_t(len)); }
    }

    // 3) 한 번에 쓴다
    BlockWriter w(path, true);
    w.u32le(uint32_t(header.size()));
    w.bytes(header.data(), header.size());
    for (int32_t i : order) {
        int64_t id = ids[i];
        const Text& t = texts[i];
        w.byte(0x0a); w.varint(uint64_t(inner_size(i)));
        if (id != 0) { w.byte(0x08); w.varint(uint64_t(id)); }
        if (t.n > 0) { w.byte(0x12); w.varint(t.n); w.bytes(t.p, t.n); }
    }
    return w.finish();
}

// ---------------- 문자 판별 (Kotlin·C# 과 같은 방식) ----------------

bool has_han(const Text& t) {
    const uint8_t* u = t.p;
    for (uint32_t i = 0; i + 2 < t.n;) {
        int b0 = u[i];
        if (b0 >= 0xE4 && b0 <= 0xE9) {
            int cp = ((b0 & 0x0F) << 12) | ((u[i + 1] & 0x3F) << 6) | (u[i + 2] & 0x3F);
            if (cp >= 0x4E00 && cp <= 0x9FFF) return true;
            i += 3;
        } else {
            i++;
        }
    }
    return false;
}

bool has_hangul(const Text& t) {
    const uint8_t* u = t.p;
    for (uint32_t i = 0; i + 2 < t.n;) {
        int b0 = u[i];
        if (b0 >= 0xEA && b0 <= 0xED) {
            int cp = ((b0 & 0x0F) << 12) | ((u[i + 1] & 0x3F) << 6) | (u[i + 2] & 0x3F);
            if (cp >= 0xAC00 && cp <= 0xD7A3) return true;
            i += 3;
        } else {
            i++;
        }
    }
    return false;
}

/** FNV-1a 64 + murmur3 fmix64 (번역 메모리 파일 형식의 일부라 바꾸면 안 된다) */
int64_t hash64(const Text& t) {
    uint64_t h = 0xcbf29ce484222325ULL;
    for (uint32_t i = 0; i < t.n; i++) { h ^= t.p[i]; h *= 0x100000001b3ULL; }
    h ^= h >> 33; h *= 0xff51afd7ed558ccdULL;
    h ^= h >> 33; h *= 0xc4ceb9fe1a85ec53ULL;
    h ^= h >> 33;
    return int64_t(h);
}

/**
 * 번역해도 그대로 남아야 하는 것들 (정규식 \{[0-9]+\}|%[0-9.]*[sdf]|<[^<>]{1,40}>|\\n|\n|[0-9]+(?:\.[0-9]+)? 과 같은 결과).
 * Kotlin·C# 은 UTF-16 으로 세므로 여기서도 UTF-16 코드 단위로 바꿔 훑는다. 순서는 상관없어 정렬해서 비교한다.
 */
std::vector<std::u16string> shape(const Text& t) {
    // 깨진 바이트는 한 바이트마다 U+FFFD (Kotlin·C# 의 decodeUtf8 과 같은 규칙)
    std::u16string s;
    s.reserve(t.n);
    auto cont = [&](uint32_t k) { return k < t.n && (t.p[k] & 0xC0) == 0x80; };
    for (uint32_t i = 0; i < t.n;) {
        uint32_t c = t.p[i];
        uint32_t cp = 0xFFFD;
        int len = 1;
        if (c < 0x80) { cp = c; }
        else if (c >= 0xC2 && c <= 0xDF && cont(i + 1)) { cp = (c & 0x1F) << 6 | (t.p[i + 1] & 0x3F); len = 2; }
        else if (c >= 0xE0 && c <= 0xEF && cont(i + 1) && cont(i + 2)) {
            cp = (c & 0x0F) << 12 | (t.p[i + 1] & 0x3F) << 6 | (t.p[i + 2] & 0x3F); len = 3;
        } else if (c >= 0xF0 && c <= 0xF4 && cont(i + 1) && cont(i + 2) && cont(i + 3)) {
            uint32_t v = (c & 0x07) << 18 | (t.p[i + 1] & 0x3F) << 12 | (t.p[i + 2] & 0x3F) << 6 | (t.p[i + 3] & 0x3F);
            if (v <= 0x10FFFF) { cp = v; len = 4; }
        }
        if (cp >= 0x10000) { cp -= 0x10000; s.push_back(char16_t(0xD800 + (cp >> 10))); s.push_back(char16_t(0xDC00 + (cp & 0x3FF))); }
        else s.push_back(char16_t(cp));
        i += len;
    }
    auto digit = [](char16_t c) { return c >= u'0' && c <= u'9'; };
    std::vector<std::u16string> tokens;
    size_t n = s.size();
    for (size_t i = 0; i < n;) {
        char16_t c = s[i];
        size_t end = 0;  // 0 = 여기서 맞는 것 없음
        if (c == u'{') {
            size_t j = i + 1;
            while (j < n && digit(s[j])) j++;
            if (j > i + 1 && j < n && s[j] == u'}') end = j + 1;
        } else if (c == u'%') {
            size_t j = i + 1;
            while (j < n && (digit(s[j]) || s[j] == u'.')) j++;
            if (j < n && (s[j] == u's' || s[j] == u'd' || s[j] == u'f')) end = j + 1;
        } else if (c == u'<') {
            size_t j = i + 1;
            while (j < n && s[j] != u'<' && s[j] != u'>') j++;
            size_t run = j - (i + 1);
            if (run >= 1 && run <= 40 && j < n && s[j] == u'>') end = j + 1;
        } else if (c == u'\\') {
            if (i + 1 < n && s[i + 1] == u'n') end = i + 2;
        } else if (c == u'\n') {
            end = i + 1;
        } else if (digit(c)) {
            size_t j = i + 1;
            while (j < n && digit(s[j])) j++;
            if (j + 1 < n && s[j] == u'.' && digit(s[j + 1])) {
                j += 2;
                while (j < n && digit(s[j])) j++;
            }
            end = j;
        }
        if (end == 0) { i++; continue; }
        tokens.emplace_back(s, i, end - i);
        i = end;
    }
    std::sort(tokens.begin(), tokens.end());
    return tokens;
}

// ---------------- 번역 메모리 ----------------

const uint32_t MAGIC_V1 = 0x534e514d;  // "SNQM": 세대 없음 (윈도우 0.2 는 리틀엔디안으로 썼다)
const uint32_t MAGIC_V2 = 0x534e5132;  // "SNQ2"

// 칸 색인은 해시 상위 16비트로 나눈다 (65,537칸, 256KB)
const int BUCKET_BITS = 16;

/** v 가 들어갈 칸. 부호 비트를 뒤집어 부호 있는 순서를 그대로 따른다 */
inline size_t bucket_of(int64_t v) { return size_t((uint64_t(v) ^ 0x8000000000000000ULL) >> (64 - BUCKET_BITS)); }

/**
 * 오름차순 배열의 칸 색인: 칸 d 의 값은 a[r[d], r[d+1]) 에 있다. 해시는 고르게 퍼져 한 칸에 몇 개뿐이라
 * 이진 탐색이 전체(27만 줄이면 18단계) 대신 칸 안(2~3단계)에서 끝나고, 찾은 자리는 전체에서 찾은 것과 같다.
 * 엄격히 오름차순이 아니면(깨진 메모리 파일) 빈 것: 전체에서 [lower_bound_at] 으로 찾는다.
 */
std::vector<int32_t> bucket_index(const std::vector<int64_t>& a) {
    std::vector<int32_t> r((size_t(1) << BUCKET_BITS) + 1, 0);
    for (size_t i = 0; i < a.size(); i++) {
        if (i > 0 && a[i] <= a[i - 1]) return {};
        r[bucket_of(a[i]) + 1]++;
    }
    for (size_t d = 0; d + 1 < r.size(); d++) r[d + 1] += r[d];
    return r;
}

/**
 * a[from, to) 에서 v 이상인 첫 자리 (없으면 to). Kotlin·C# 와 같은 순서로 반씩 나눠, 정렬이 깨진 배열에서도
 * 세 엔진이 같은 자리를 낸다 (std::lower_bound 는 표준 라이브러리마다 나누는 방식이 다를 수 있다).
 */
size_t lower_bound_at(const std::vector<int64_t>& a, size_t from, size_t to, int64_t v) {
    size_t lo = from, hi = to;
    while (lo < hi) {
        size_t mid = (lo + hi) >> 1;
        if (a[mid] < v) lo = mid + 1;
        else hi = mid;
    }
    return lo;
}

/** a 에서 v 이상인 첫 자리. 칸 색인이 있으면 v 의 칸 안에서만 찾는다 */
size_t lower_bound_in(const std::vector<int64_t>& a, const std::vector<int32_t>& buckets, int64_t v) {
    if (buckets.empty()) return lower_bound_at(a, 0, a.size(), v);
    size_t d = bucket_of(v);
    return lower_bound_at(a, size_t(buckets[d]), size_t(buckets[d + 1]), v);
}

struct Memory {
    std::vector<int64_t> keys;  // 오름차순 (부호 있는 비교)
    std::vector<Text> values;
    std::vector<int32_t> gens;

    /** 원문 해시로 찾은 위치, 없으면 -1. buckets 는 keys 의 칸 색인 ([bucket_index]) */
    int64_t find(int64_t h, const std::vector<int32_t>& buckets) const {
        size_t i = lower_bound_in(keys, buckets, h);
        if (i == keys.size() || keys[i] != h) return -1;
        return int64_t(i);
    }

    int64_t byte_size() const {
        int64_t s = 8;
        for (const Text& v : values) s += 16 + v.n;
        return s;
    }
};

/** 번역은 복사하지 않고 blob(매핑한 파일) 안을 가리킨다. blob 은 메모리를 다 쓸 때까지 살아 있어야 한다. */
Memory read_memory(const Blob& blob) {
    const uint8_t* b = blob.data();
    const size_t size = blob.size();
    if (size < 8) throw Error("번역 메모리 파일이 아닙니다");
    uint32_t be = uint32_t(b[0]) << 24 | uint32_t(b[1]) << 16 | uint32_t(b[2]) << 8 | b[3];
    uint32_t le = uint32_t(b[0]) | uint32_t(b[1]) << 8 | uint32_t(b[2]) << 16 | uint32_t(b[3]) << 24;
    bool big = be == MAGIC_V1 || be == MAGIC_V2;
    uint32_t magic = big ? be : le;
    if (magic != MAGIC_V1 && magic != MAGIC_V2) throw Error("번역 메모리 파일이 아닙니다");
    size_t p = 4;
    auto need = [&](size_t k) { if (k > size - p) throw Error("번역 메모리 파일이 끊김"); };
    auto u32 = [&]() -> uint32_t {
        need(4);
        uint32_t v = big ? (uint32_t(b[p]) << 24 | uint32_t(b[p + 1]) << 16 | uint32_t(b[p + 2]) << 8 | b[p + 3])
                         : (uint32_t(b[p]) | uint32_t(b[p + 1]) << 8 | uint32_t(b[p + 2]) << 16 | uint32_t(b[p + 3]) << 24);
        p += 4;
        return v;
    };
    auto u64 = [&]() -> uint64_t {
        uint64_t a = u32(), c = u32();
        return big ? (a << 32 | c) : (c << 32 | a);
    };
    Memory m;
    uint32_t n = u32();
    if (n > size / 12) throw Error("번역 메모리 줄 수가 이상함");
    m.keys.resize(n); m.values.resize(n); m.gens.resize(n);
    for (uint32_t i = 0; i < n; i++) {
        m.keys[i] = int64_t(u64());
        m.gens[i] = magic == MAGIC_V2 ? int32_t(u32()) : 0;
        uint32_t len = u32();
        need(len);
        m.values[i] = Text{b + p, len};
        p += len;
    }
    return m;
}

void write_memory(const char* path, const Memory& m) {
    BlockWriter w(path, false);
    w.u32be(MAGIC_V2);
    w.u32be(uint32_t(m.keys.size()));
    for (size_t i = 0; i < m.keys.size(); i++) {
        w.u64be(uint64_t(m.keys[i]));
        w.u32be(uint32_t(m.gens[i]));
        w.u32be(m.values[i].n);
        w.bytes(m.values[i].p, m.values[i].n);
    }
    w.finish();
}

/** 같은 게임 버전 공식 원문과 한패를 같은 Id 끼리 짝지어 만든다. 같은 원문이면 공식 원문에서 먼저 나온 줄의 번역. */
Memory build_memory(const Table& official, const Table& patch) {
    if (!same_ids(official, patch)) throw Error("공식 원문과 한패의 게임 버전이 다릅니다");
    size_t n = official.ids.size();
    const auto& oo = official.order();
    const auto& po = patch.order();
    std::vector<int32_t> patch_at(n);
    for (size_t k = 0; k < n; k++) patch_at[oo[k]] = po[k];
    struct Row { int64_t h; int32_t src; };
    std::vector<Row> rows;
    rows.reserve(n);
    for (size_t i = 0; i < n; i++) {
        Text ko = patch.text(size_t(patch_at[i]));
        Text zh = official.text(i);
        if (same(ko, zh)) continue;
        rows.push_back(Row{hash64(zh), patch_at[i]});
    }
    std::stable_sort(rows.begin(), rows.end(), [](const Row& a, const Row& b) { return a.h < b.h; });
    Memory m;
    for (const Row& r : rows) {
        if (!m.keys.empty() && m.keys.back() == r.h) continue;
        m.keys.push_back(r.h);
        m.values.push_back(patch.text(size_t(r.src)));
        m.gens.push_back(0);
    }
    return m;
}

/** 같은 원문이면 newer 가 이기고, newer 의 줄은 새 세대가 된다 */
Memory merge(const Memory& old, const Memory& newer) {
    // 최대 세대 + 1. 깨진 파일의 INT32_MAX 에서 Kotlin·C# 처럼 돌아가게 부호 없는 덧셈으로 (부호 있는 넘침은 정의되지 않은 동작)
    int32_t gen = old.gens.empty() ? 0 : int32_t(uint32_t(*std::max_element(old.gens.begin(), old.gens.end())) + 1u);
    Memory m;
    size_t i = 0, j = 0;
    m.keys.reserve(old.keys.size() + newer.keys.size());
    while (i < old.keys.size() || j < newer.keys.size()) {
        bool take_new = i >= old.keys.size() || (j < newer.keys.size() && newer.keys[j] <= old.keys[i]);
        if (take_new) {
            if (i < old.keys.size() && old.keys[i] == newer.keys[j]) i++;
            m.keys.push_back(newer.keys[j]); m.values.push_back(newer.values[j]); m.gens.push_back(gen); j++;
        } else {
            m.keys.push_back(old.keys[i]); m.values.push_back(old.values[i]); m.gens.push_back(old.gens[i]); i++;
        }
    }
    return m;
}

/** 파일 크기가 max_bytes 이하가 되도록 오래된 세대부터 (세대 안에서는 해시 순) 뺀다 */
Memory capped(const Memory& m, int64_t max_bytes) {
    int64_t total = m.byte_size();
    if (total <= max_bytes) return m;
    std::vector<int32_t> gens = m.gens;
    std::sort(gens.begin(), gens.end());
    gens.erase(std::unique(gens.begin(), gens.end()), gens.end());
    std::vector<uint8_t> drop(m.keys.size(), 0);
    for (int32_t g : gens) {
        for (size_t i = 0; i < m.keys.size() && total > max_bytes; i++) {
            if (m.gens[i] != g) continue;
            drop[i] = 1;
            total -= 16 + m.values[i].n;
        }
        if (total <= max_bytes) break;
    }
    Memory r;
    for (size_t i = 0; i < m.keys.size(); i++) {
        if (drop[i]) continue;
        r.keys.push_back(m.keys[i]); r.values.push_back(m.values[i]); r.gens.push_back(m.gens[i]);
    }
    return r;
}

// ---------------- 자리 검사 ----------------

struct Alignment {
    int32_t untranslated = 0;
    int32_t matching = 0;
};

Alignment alignment(const Table& official, const Table& patch) {
    if (!same_ids(official, patch)) throw Error("공식 원문과 한패의 게임 버전이 다릅니다");
    const auto& oo = official.order();
    const auto& po = patch.order();
    Alignment a;
    for (size_t k = 0; k < oo.size(); k++) {
        Text ko = patch.text(size_t(po[k]));
        if (has_hangul(ko) || !has_han(ko)) continue;
        a.untranslated++;
        if (same(ko, official.text(size_t(oo[k])))) a.matching++;
    }
    return a;
}

// ---------------- 임시 복구 ----------------

/** 빈 문장은 0 (어떤 문장과도 맞지 않는 것으로 친다) */
inline int64_t key(const Text& t) {
    if (t.n == 0) return 0;
    int64_t h = hash64(t);
    return h == 0 ? 1 : h;
}

/** Id 가 1 차이인지 (부호 있는 넘침 없이, Kotlin·C# 처럼 64비트에서 돌아간다) */
inline bool consecutive(int64_t a, int64_t b) { return uint64_t(a) + 1 == uint64_t(b); }

/**
 * 2단계: 옛 한패의 번역을 새 Id 자리로 옮긴다 (PatchRepair.overlay 와 같은 규칙). 바꾼 줄 수.
 * nh[b] = Id 순 b 번째 줄의 1단계 결과 키. 1단계에서 Id 순으로 돌며 바로 구해 받는다 (따로 옮겨 담지 않는다).
 * Id 는 복사하지 않고 "Id 순 다음 줄과 1 차이인지" 만 1바이트로 든다.
 */
int overlay(const Table& official, std::vector<Text>& texts, std::vector<uint8_t>& changed, const Table& old,
            const std::vector<int64_t>& nh) {
    const int MIN_RUN = 3, MAX_RUN = 64, MAX_CANDIDATES = 16;
    const auto& no = official.order();
    const auto& oo = old.order();
    size_t N = no.size(), O = oo.size();
    std::vector<uint8_t> n_next(N, 0), o_next(O, 0);
    for (size_t b = 0; b + 1 < N; b++) n_next[b] = consecutive(official.ids[no[b]], official.ids[no[b + 1]]);
    for (size_t a = 0; a + 1 < O; a++) o_next[a] = consecutive(old.ids[oo[a]], old.ids[oo[a + 1]]);
    auto n_id = [&](size_t b) { return official.ids[no[b]]; };
    auto o_id = [&](size_t a) { return old.ids[oo[a]]; };
    std::vector<int64_t> oh(O);
    parallel_for(O, [&](size_t s, size_t e) { for (size_t a = s; a < e; a++) oh[a] = key(old.text(size_t(oo[a]))); });

    int pos_bits = 1;
    while ((int64_t(1) << pos_bits) <= int64_t(N)) pos_bits++;
    const int64_t low = (int64_t(1) << pos_bits) - 1;
    std::vector<int64_t> packed(N);
    for (size_t i = 0; i < N; i++) packed[i] = (nh[i] & ~low) | int64_t(i);
    std::sort(packed.begin(), packed.end());
    const std::vector<int32_t> packed_buckets = bucket_index(packed);  // 위치가 붙어 값이 모두 달라 늘 만들어진다

    auto next_ok = [&](size_t a, size_t b) {
        return a + 1 < O && b + 1 < N && o_next[a] && n_next[b] && oh[a + 1] != 0 && oh[a + 1] == nh[b + 1];
    };
    auto prev_ok = [&](size_t a, size_t b) {
        return a > 0 && b > 0 && o_next[a - 1] && n_next[b - 1] && oh[a - 1] != 0 && oh[a - 1] == nh[b - 1];
    };

    std::vector<int32_t> match_to(O, -1);
    for (size_t a = 0; a < O; a++) {
        int64_t h = oh[a];
        if (h == 0) continue;
        int64_t hi = h & ~low;
        size_t first = lower_bound_in(packed, packed_buckets, hi);
        size_t k = first;
        while (k < N && (packed[k] & ~low) == hi) k++;
        if (k - first > size_t(MAX_CANDIDATES)) continue;
        int best = 0, second = 0;
        int32_t best_b = -1;
        for (size_t c = first; c < k; c++) {
            size_t b = size_t(packed[c] & low);
            if (nh[b] != h) continue;
            int run = 1;
            size_t x = a, y = b;
            while (run < MAX_RUN && prev_ok(x, y)) { x--; y--; run++; }
            x = a; y = b;
            while (run < 2 * MAX_RUN && next_ok(x, y)) { x++; y++; run++; }
            if (run > best) { second = best; best = run; best_b = int32_t(b); }
            else if (run > second) second = run;
        }
        if (best >= MIN_RUN && best > second) match_to[a] = best_b;
    }

    std::vector<int32_t> target(N, -1);
    auto put = [&](size_t a, size_t b) { target[b] = target[b] == -1 ? int32_t(a) : -2; };
    long long last = -1;
    for (size_t a = 0; a < O; a++) {
        int32_t b = match_to[a];
        if (b < 0) continue;
        put(a, size_t(b));
        if (last >= 0) {
            int32_t lb = match_to[size_t(last)];
            long long gap = (long long)a - last;
            if (gap > 1 && (long long)b - lb == gap && int64_t(uint64_t(o_id(a)) - uint64_t(o_id(size_t(last)))) == gap &&
                int64_t(uint64_t(n_id(size_t(b))) - uint64_t(n_id(size_t(lb)))) == gap)
                for (long long t = 1; t < gap; t++) put(size_t(last + t), size_t(lb + t));
        }
        last = (long long)a;
    }

    int moved = 0;
    for (size_t b = 0; b < N; b++) {
        int32_t a = target[b];
        if (a < 0) continue;
        Text ko = old.text(size_t(oo[size_t(a)]));
        size_t k = size_t(no[b]);
        if (!has_hangul(ko) || same(ko, texts[k])) continue;
        if (shape(ko) != shape(official.text(k))) continue;
        texts[k] = ko;
        changed[k] = 1;
        moved++;
    }
    return moved;
}

struct RepairResult {
    int32_t translated = 0, left_chinese = 0, from_patch = 0;
    std::string sha;
};

RepairResult repair(const Table& official, const Memory& tm, const Table* old, const char* out_path) {
    size_t n = official.size();
    const auto& no = official.order();
    std::vector<Text> texts(n);
    std::vector<uint8_t> changed(n, 0);
    // 2단계용: Id 순 k 번째 줄의 1단계 결과 키. 찾은 번역의 키는 번역 메모리 줄마다 미리 한 번씩 구한다
    std::vector<int64_t> nh, value_key;
    if (old) {
        nh.assign(n, 0);
        value_key.assign(tm.keys.size(), 0);
        parallel_for(value_key.size(), [&](size_t s, size_t e) { for (size_t j = s; j < e; j++) value_key[j] = key(tm.values[j]); });
    }
    // 1단계: 원문 해시로 번역 메모리를 찾는다 (여러 스레드, Id 순으로 돌아 2단계 키를 바로 그 자리에 쓴다).
    // 칸 색인은 스레드를 나누기 전에 만든다 (스레드들은 읽기만)
    const std::vector<int32_t> tm_buckets = bucket_index(tm.keys);
    parallel_for(n, [&](size_t s, size_t e) {
        for (size_t k = s; k < e; k++) {
            size_t i = size_t(no[k]);
            Text zh = official.text(i);
            int64_t h = hash64(zh);  // 빈 문장도 해시로 찾는다 (Kotlin·C# 과 같음)
            int64_t at = tm.find(h, tm_buckets);
            if (at >= 0) {
                texts[i] = tm.values[size_t(at)];
                changed[i] = 1;
                if (old) nh[k] = value_key[size_t(at)];
            } else {
                texts[i] = zh;
                if (old) nh[k] = zh.n == 0 ? 0 : (h == 0 ? 1 : h);
            }
        }
    });
    value_key = std::vector<int64_t>();
    RepairResult r;
    if (old) r.from_patch = overlay(official, texts, changed, *old, nh);
    nh = std::vector<int64_t>();
    for (size_t i = 0; i < n; i++) {
        if (changed[i]) r.translated++;
        else if (has_han(official.text(i))) r.left_chinese++;
    }
    r.sha = write_table(out_path, official.ids, texts, official.order(), official.chunk_size, official.flag);
    return r;
}

// C API 공통: 예외를 오류 문자열로 바꾼다
template <class F>
int guarded(F&& f) {
    try {
        f();
        g_error.clear();
        return 0;
    } catch (const std::bad_alloc&) {
        g_error = "메모리 부족";
    } catch (const std::exception& e) {
        g_error = e.what();
    } catch (...) {
        g_error = "알 수 없는 오류";
    }
    return -1;
}

}  // namespace
}  // namespace snqx

using namespace snqx;

extern "C" {

SNQX_API const char* snqx_version(void) { return "snqx 1 " SNQX_ARCH; }

SNQX_API const char* snqx_last_error(void) { return g_error.c_str(); }

SNQX_API int snqx_layout_key(const char* path, char* out, int out_len) {
    return guarded([&] {
        std::string k = layout_key(read_ids(path));
        if (int(k.size()) + 1 > out_len) throw Error("버퍼가 작음");
        std::memcpy(out, k.c_str(), k.size() + 1);
    });
}

SNQX_API int snqx_alignment(const char* official, const char* patch, int32_t* out2) {
    return guarded([&] {
        Table o = read_table(official);
        Table p = read_table(patch);
        Alignment a = alignment(o, p);
        out2[0] = a.untranslated;
        out2[1] = a.matching;
    });
}

SNQX_API int snqx_build_memory(const char* official, const char* patch, const char* memory_in,
                               const char* memory_out, int64_t max_bytes, double min_alignment, int64_t* out5) {
    return guarded([&] {
        Table o = read_table(official);
        Table p = read_table(patch);
        Alignment a = alignment(o, p);
        out5[3] = a.untranslated;
        out5[4] = a.matching;
        // 판단할 줄이 20줄 이상인데 자리가 안 맞으면 메모리를 더럽히지 않는다
        if (a.untranslated >= 20 && double(a.matching) / a.untranslated < min_alignment) {
            out5[0] = 0; out5[1] = 0; out5[2] = 0;
            return;
        }
        std::string written = output_path(memory_out, {official, patch, memory_in});
        {
            Memory fresh = build_memory(o, p);
            std::unique_ptr<Blob> old_blob;
            Memory merged;
            bool have_old = false;
            if (memory_in && *memory_in) {
                try {
                    old_blob = Blob::open(memory_in);
                    Memory old = read_memory(*old_blob);
                    merged = merge(old, fresh);
                    have_old = true;
                } catch (const Error&) {
                    // 읽을 수 없는 옛 메모리는 버리고 새로 만든다 (Kotlin·C# 과 같음)
                }
            }
            if (!have_old) merged = std::move(fresh);
            Memory cut = capped(merged, max_bytes);
            write_memory(written.c_str(), cut);
            out5[0] = 1;
            out5[1] = int64_t(cut.keys.size());
            out5[2] = int64_t(merged.keys.size() - cut.keys.size());
        }
        // 윈도우는 매핑한 파일을 바꿀 수 없어 입력을 모두 푼 뒤 바꾼다
        o = Table();
        p = Table();
        finish_output(written, memory_out);
    });
}

SNQX_API int snqx_repair(const char* official, const char* memory, const char* old_patch, const char* out_path,
                         int32_t* out3, char* sha65) {
    return guarded([&] {
        std::string written = output_path(out_path, {official, memory, old_patch});
        RepairResult r;
        {
            Table o = read_table(official);
            std::unique_ptr<Blob> tm_blob = Blob::open(memory);
            Memory tm = read_memory(*tm_blob);
            std::unique_ptr<Table> old;
            if (old_patch && *old_patch) old.reset(new Table(read_table(old_patch)));
            r = repair(o, tm, old.get(), written.c_str());
        }  // 입력 매핑을 여기서 푼다
        finish_output(written, out_path);
        out3[0] = r.translated;
        out3[1] = r.left_chinese;
        out3[2] = r.from_patch;
        std::memcpy(sha65, r.sha.c_str(), 65);
    });
}

}  // extern "C"
