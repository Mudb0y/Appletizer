// The iOS 5 and 6 Nuance engine (Vocalizer for Automotive, vauto_tts) exists
// only as 32-bit ARM code, which arm64-only phones cannot execute, so it runs
// here inside dynarmic. Its library imports and platform interfaces are
// served from the host through SVC trampolines.
#include "bridge.h"

#include <dynarmic/interface/A32/a32.h>
#include <dynarmic/interface/A32/config.h>

#include <atomic>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <functional>
#include <map>
#include <memory>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

extern "C" const std::uint8_t atzb_image[];
extern "C" const std::uint8_t atzb_image_end[];

namespace {

using u8 = std::uint8_t;
using u16 = std::uint16_t;
using u32 = std::uint32_t;
using u64 = std::uint64_t;

constexpr u32 PAGE = 0x1000;
constexpr u32 STACK = 0x40000000, STACK_SIZE = 0x100000;
constexpr u32 HEAP = 0x50000000, HEAP_SIZE = 0x10000000;
constexpr u32 FILES = 0x60000000, FILES_END = 0x7e000000;
constexpr u32 TRAMP = 0x7f000000, TRAMP_SIZE = 0x10000;
constexpr u32 SVC_STOP = 0xffffff;

constexpr u32 E_NOTFOUND = 0x80000014;
constexpr u32 E_ARG = 0x80000007;
constexpr u32 E_REFUSED = 0x80000200;

constexpr u32 MSG_OUTBUFREQ = 8, MSG_OUTBUFDONE = 16;
constexpr u32 MARK_WORD = 2;
constexpr u32 MARK_SIZE = 40, MAX_MARKS = 32;
constexpr u32 PCM_BYTES = 2048;

u32 align(u32 v, u32 a) { return (v + a - 1) & ~(a - 1); }

class Guest final : public Dynarmic::A32::UserCallbacks {
public:
    atz_log_cb log = nullptr;
    std::string error;

    Guest() {
        mem = static_cast<u8 *>(mmap(nullptr, 1ull << 32, PROT_NONE,
                                     MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0));
        if (mem == MAP_FAILED) {
            mem = nullptr;
            return;
        }
        pages = std::make_unique<std::array<u8 *, Dynarmic::A32::UserConfig::NUM_PAGE_TABLE_ENTRIES>>();
        pages->fill(nullptr);
    }

    ~Guest() override {
        if (mem) munmap(mem, 1ull << 32);
    }

    bool ok() const { return mem != nullptr; }

    void logf(const char *fmt, ...) {
        if (!log) return;
        char buf[512];
        va_list ap;
        va_start(ap, fmt);
        vsnprintf(buf, sizeof buf, fmt, ap);
        va_end(ap);
        log(buf);
    }

    bool commit(u32 at, u32 size, int prot = PROT_READ | PROT_WRITE) {
        if (mprotect(mem + at, size, prot)) return false;
        for (u32 p = at; p < at + size; p += PAGE) (*pages)[p / PAGE] = mem + p;
        return true;
    }

    void start_jit() {
        Dynarmic::A32::UserConfig conf;
        conf.callbacks = this;
        conf.page_table = pages.get();
        conf.arch_version = Dynarmic::A32::ArchVersion::v7;
        jit = std::make_unique<Dynarmic::A32::Jit>(conf);
        jit->SetFpscr(0x03000000);  // default NaN and flush-to-zero, as iOS runs
    }

    u8 *host(u32 g) { return mem + g; }
    u32 rd32(u32 g) { u32 v; std::memcpy(&v, mem + g, 4); return v; }
    void wr32(u32 g, u32 v) { std::memcpy(mem + g, &v, 4); }
    const char *str(u32 g) { return reinterpret_cast<const char *>(mem + g); }

    // ---- heap: size classes over a bump region --------------------------
    u32 heap_top = HEAP;
    std::map<u32, std::vector<u32>> free_lists;

    u32 alloc(u32 n) {
        u32 size = align(n ? n : 1, 16);
        auto &fl = free_lists[size];
        u32 block;
        if (!fl.empty()) {
            block = fl.back();
            fl.pop_back();
        } else {
            if (heap_top + 16 + size > HEAP + HEAP_SIZE) return 0;
            block = heap_top;
            heap_top += 16 + size;
        }
        wr32(block, size);
        return block + 16;
    }
    void release(u32 p) {
        if (p < HEAP + 16 || p >= HEAP + HEAP_SIZE) return;
        free_lists[rd32(p - 16)].push_back(p - 16);
    }
    u32 zalloc(u32 n) {
        u32 p = alloc(n);
        if (p) std::memset(mem + p, 0, n);
        return p;
    }
    u32 resize(u32 p, u32 n) {
        if (!p) return alloc(n);
        u32 old = rd32(p - 16);
        if (n <= old) return p;
        u32 q = alloc(n);
        if (q) {
            std::memcpy(mem + q, mem + p, old);
            release(p);
        }
        return q;
    }

    // ---- trampolines --------------------------------------------------------
    std::vector<std::function<void()>> hle;
    std::vector<std::string> hle_names;
    u32 tramp_next = TRAMP + 8;

    // An ARM-mode "svc #n; bx lr" the guest can call like any function.
    u32 trampoline(const std::string &name, std::function<void()> fn) {
        u32 at = tramp_next;
        tramp_next += 8;
        wr32(at, 0xef000000 | static_cast<u32>(hle.size()));
        wr32(at + 4, 0xe12fff1e);
        hle.push_back(std::move(fn));
        hle_names.push_back(name);
        return at;
    }

    u32 &r(int i) { return jit->Regs()[i]; }
    u32 arg(int i) { return i < 4 ? r(i) : rd32(r(13) + 4 * (i - 4)); }
    void ret(u32 v) { r(0) = v; }
    void ret64(u64 v) { r(0) = static_cast<u32>(v); r(1) = static_cast<u32>(v >> 32); }

    // ---- running guest code ------------------------------------------------
    bool stopped = false;

    u32 call(u32 fn, std::initializer_list<u32> args, std::initializer_list<u32> stack_args = {}) {
        u32 sp = STACK + STACK_SIZE - 0x100 - align(4 * static_cast<u32>(stack_args.size()), 8);
        u32 i = 0;
        for (u32 v : stack_args) wr32(sp + 4 * i++, v);
        i = 0;
        for (u32 v : args) r(i++) = v;
        r(13) = sp;
        r(14) = TRAMP;  // the stop trampoline
        r(15) = fn & ~1u;
        jit->SetCpsr(0x10 | ((fn & 1) ? 0x20 : 0));
        stopped = false;
        while (!stopped && error.empty()) jit->Run();
        return error.empty() ? r(0) : 0xffffffff;
    }

    // ---- UserCallbacks ---------------------------------------------------------
    bool mapped(u32 v, u32 n) {
        return (*pages)[v / PAGE] && (*pages)[(v + n - 1) / PAGE];
    }
    template <typename T>
    T read(u32 v) {
        if (!mapped(v, sizeof(T))) { fault("read", v); return 0; }
        T x;
        std::memcpy(&x, mem + v, sizeof x);
        return x;
    }
    template <typename T>
    void write(u32 v, T x) {
        if (!mapped(v, sizeof(T))) { fault("write", v); return; }
        std::memcpy(mem + v, &x, sizeof x);
    }
    void fault(const char *what, u32 v) {
        if (error.empty()) {
            char buf[96];
            snprintf(buf, sizeof buf, "guest %s at %#x, pc %#x", what, v, jit->Regs()[15]);
            error = buf;
        }
        jit->HaltExecution();
    }

    std::uint8_t MemoryRead8(u32 v) override { return read<u8>(v); }
    std::uint16_t MemoryRead16(u32 v) override { return read<u16>(v); }
    std::uint32_t MemoryRead32(u32 v) override { return read<u32>(v); }
    std::uint64_t MemoryRead64(u32 v) override { return read<u64>(v); }
    void MemoryWrite8(u32 v, std::uint8_t x) override { write(v, x); }
    void MemoryWrite16(u32 v, std::uint16_t x) override { write(v, x); }
    void MemoryWrite32(u32 v, std::uint32_t x) override { write(v, x); }
    void MemoryWrite64(u32 v, std::uint64_t x) override { write(v, x); }

    void InterpreterFallback(u32 pc, size_t) override { fault("instruction", pc); }
    void ExceptionRaised(u32 pc, Dynarmic::A32::Exception) override { fault("exception", pc); }
    void AddTicks(std::uint64_t) override {}
    std::uint64_t GetTicksRemaining() override { return 1ull << 40; }

    void CallSVC(std::uint32_t swi) override {
        if (swi == SVC_STOP) {
            stopped = true;
            jit->HaltExecution();
            return;
        }
        if (swi < hle.size()) hle[swi]();
        else fault("svc", swi);
    }

    std::unique_ptr<std::array<u8 *, Dynarmic::A32::UserConfig::NUM_PAGE_TABLE_ENTRIES>> pages;
    std::unique_ptr<Dynarmic::A32::Jit> jit;

private:
    u8 *mem = nullptr;
};

// ---- printf over guest arguments ---------------------------------------------
// next() yields the following 32-bit argument word.
std::string guest_format(Guest &g, const char *fmt, const std::function<u32()> &next) {
    std::string out;
    for (const char *p = fmt; *p; p++) {
        if (*p != '%') { out += *p; continue; }
        std::string spec = "%";
        p++;
        while (*p && std::strchr("-+ #0", *p)) spec += *p++;
        if (*p == '*') { spec += std::to_string(static_cast<int>(next())); p++; }
        else while (*p >= '0' && *p <= '9') spec += *p++;
        if (*p == '.') {
            spec += *p++;
            if (*p == '*') { spec += std::to_string(static_cast<int>(next())); p++; }
            else while (*p >= '0' && *p <= '9') spec += *p++;
        }
        bool wide = false;
        while (*p && std::strchr("hlqjztL", *p)) {
            if (*p == 'q' || *p == 'j' || (*p == 'l' && p[1] == 'l')) wide = true;
            if (*p == 'l' && p[1] == 'l') p++;
            p++;
        }
        char conv = *p;
        if (!conv) break;
        char buf[512];
        switch (conv) {
        case '%': out += '%'; continue;
        case 's': spec += 's'; snprintf(buf, sizeof buf, spec.c_str(), g.str(next())); break;
        case 'c': spec += 'c'; snprintf(buf, sizeof buf, spec.c_str(), static_cast<int>(next())); break;
        case 'f': case 'F': case 'e': case 'E': case 'g': case 'G': {
            u64 lo = next(), hi = next(), bits = lo | hi << 32;
            double d;
            std::memcpy(&d, &bits, 8);
            spec += conv;
            snprintf(buf, sizeof buf, spec.c_str(), d);
            break;
        }
        case 'p': snprintf(buf, sizeof buf, "%#x", next()); break;
        default: {
            u64 v = next();
            if (wide) v |= static_cast<u64>(next()) << 32;
            spec += "ll";
            spec += conv == 'i' ? 'd' : conv;
            long long sv = wide ? static_cast<long long>(v) : static_cast<int>(static_cast<u32>(v));
            if (conv == 'd' || conv == 'i') snprintf(buf, sizeof buf, spec.c_str(), sv);
            else snprintf(buf, sizeof buf, spec.c_str(), static_cast<unsigned long long>(v));
        }
        }
        out += buf;
    }
    return out;
}

std::string resolve_name(const char *name) {
    std::string f;
    bool dot = false;
    for (const char *p = name; *p; p++) {
        if (*p == '.') dot = true;
        f += *p == '/' ? '_' : *p;
    }
    if (!dot) f += ".dat";
    return f;
}

double as_double(u32 lo, u32 hi) {
    u64 b = lo | static_cast<u64>(hi) << 32;
    double d;
    std::memcpy(&d, &b, 8);
    return d;
}
u64 double_bits(double d) {
    u64 b;
    std::memcpy(&b, &d, 8);
    return b;
}
float as_float(u32 v) { float f; std::memcpy(&f, &v, 4); return f; }
u32 float_bits(float f) { u32 v; std::memcpy(&v, &f, 4); return v; }

}  // namespace

struct atzb {
    Guest g;
    std::string voice_dir, common_dir;
    std::map<std::string, u32> api;
    u32 instance[2] = {0, 0};
    int rate = 100, pitch = 100;
    u32 pcm = 0, marks = 0;
    // file mappings: guest handle -> (guest address, length)
    std::map<u32, std::pair<u32, u32>> maps;
    std::map<u32, FILE *> streams;
    u32 files_top = FILES;

    atz_pcm_cb on_pcm = nullptr;
    atz_word_cb on_word = nullptr;
    void *user = nullptr;
    std::atomic<bool> stopping{false};

    std::string path_for(const char *name) {
        std::string f = resolve_name(name);
        for (const std::string &d : {voice_dir, common_dir}) {
            std::string p = d + "/" + f;
            struct stat st;
            if (stat(p.c_str(), &st) == 0) return p;
        }
        g.logf("no file for resource %s (%s)", name, f.c_str());
        return "";
    }

    void install_libc(const std::string &name, u32 slot);
    bool load_image(char *err, size_t errlen);
    u32 platform_tables();
    u32 params(const std::vector<std::pair<u32, std::string>> &strings,
               const std::vector<std::pair<u32, u16>> &values);
};

void atzb::install_libc(const std::string &name, u32 slot) {
    Guest &G = g;
    std::function<void()> fn;
    auto n = [&](const char *s) { return name == s; };
    if (n("_memcpy") || n("_memmove") || n("___memcpy_chk") || n("___memmove_chk"))
        fn = [&G] { std::memmove(G.host(G.arg(0)), G.host(G.arg(1)), G.arg(2)); };
    else if (n("_memset") || n("___memset_chk"))
        fn = [&G] { std::memset(G.host(G.arg(0)), static_cast<int>(G.arg(1)), G.arg(2)); };
    else if (n("_bzero"))
        fn = [&G] { std::memset(G.host(G.arg(0)), 0, G.arg(1)); };
    else if (n("_memcmp"))
        fn = [&G] { G.ret(static_cast<u32>(std::memcmp(G.host(G.arg(0)), G.host(G.arg(1)), G.arg(2)))); };
    else if (n("_strlen"))
        fn = [&G] { G.ret(static_cast<u32>(std::strlen(G.str(G.arg(0))))); };
    else if (n("_strcmp"))
        fn = [&G] { G.ret(static_cast<u32>(std::strcmp(G.str(G.arg(0)), G.str(G.arg(1))))); };
    else if (n("_strncmp"))
        fn = [&G] { G.ret(static_cast<u32>(std::strncmp(G.str(G.arg(0)), G.str(G.arg(1)), G.arg(2)))); };
    else if (n("_strcpy") || n("___strcpy_chk"))
        fn = [&G] { u32 d = G.arg(0); std::strcpy(reinterpret_cast<char *>(G.host(d)), G.str(G.arg(1))); G.ret(d); };
    else if (n("_strncpy") || n("___strncpy_chk"))
        fn = [&G] { u32 d = G.arg(0); std::strncpy(reinterpret_cast<char *>(G.host(d)), G.str(G.arg(1)), G.arg(2)); G.ret(d); };
    else if (n("_strcat") || n("___strcat_chk"))
        fn = [&G] { u32 d = G.arg(0); std::strcat(reinterpret_cast<char *>(G.host(d)), G.str(G.arg(1))); G.ret(d); };
    else if (n("_strchr") || n("_strrchr"))
        fn = [&G, rev = n("_strrchr")] {
            u32 s = G.arg(0);
            const char *h = G.str(s);
            const char *f = rev ? std::strrchr(h, static_cast<int>(G.arg(1))) : std::strchr(h, static_cast<int>(G.arg(1)));
            G.ret(f ? s + static_cast<u32>(f - h) : 0);
        };
    else if (n("_strstr"))
        fn = [&G] {
            u32 s = G.arg(0);
            const char *f = std::strstr(G.str(s), G.str(G.arg(1)));
            G.ret(f ? s + static_cast<u32>(f - G.str(s)) : 0);
        };
    else if (n("_malloc"))
        fn = [&G] { G.ret(G.alloc(G.arg(0))); };
    else if (n("_calloc"))
        fn = [&G] { G.ret(G.zalloc(G.arg(0) * G.arg(1))); };
    else if (n("_realloc"))
        fn = [&G] { G.ret(G.resize(G.arg(0), G.arg(1))); };
    else if (n("_free"))
        fn = [&G] { G.release(G.arg(0)); };
    else if (n("_sprintf") || n("___sprintf_chk") || n("_snprintf") || n("___snprintf_chk") ||
             n("_vsnprintf") || n("___vsnprintf_chk") || n("_vsprintf") || n("___vsprintf_chk")) {
        // Where each variant keeps its destination, size limit, format and arguments.
        int dst = 0, lim = -1, fmt, first;
        bool va = false;
        if (n("_sprintf")) { fmt = 1; first = 2; }
        else if (n("___sprintf_chk")) { fmt = 3; first = 4; }
        else if (n("_snprintf")) { lim = 1; fmt = 2; first = 3; }
        else if (n("___snprintf_chk")) { lim = 1; fmt = 4; first = 5; }
        else if (n("_vsnprintf")) { lim = 1; fmt = 2; first = 3; va = true; }
        else if (n("___vsnprintf_chk")) { lim = 1; fmt = 4; first = 5; va = true; }
        else if (n("_vsprintf")) { fmt = 1; first = 2; va = true; }
        else { fmt = 3; first = 4; va = true; }
        fn = [&G, dst, lim, fmt, first, va] {
            u32 ap = va ? G.arg(first) : 0;
            int k = first;
            std::string s = guest_format(G, G.str(G.arg(fmt)), [&]() -> u32 {
                if (va) { u32 v = G.rd32(ap); ap += 4; return v; }
                return G.arg(k++);
            });
            u32 d = G.arg(dst);
            if (lim < 0) std::memcpy(G.host(d), s.c_str(), s.size() + 1);
            else if (u32 cap = G.arg(lim)) {
                u32 m = std::min<u32>(cap - 1, static_cast<u32>(s.size()));
                std::memcpy(G.host(d), s.data(), m);
                G.host(d)[m] = 0;
            }
            G.ret(static_cast<u32>(s.size()));
        };
    } else if (n("___divsi3") || n("___modsi3"))
        fn = [&G, mod = n("___modsi3")] {
            int a = static_cast<int>(G.arg(0)), b = static_cast<int>(G.arg(1));
            G.ret(static_cast<u32>(b ? (mod ? a % b : a / b) : 0));
        };
    else if (n("___udivsi3") || n("___umodsi3"))
        fn = [&G, mod = n("___umodsi3")] {
            u32 a = G.arg(0), b = G.arg(1);
            G.ret(b ? (mod ? a % b : a / b) : 0);
        };
    else if (n("___divdi3") || n("___moddi3") || n("___udivdi3") || n("___umoddi3"))
        fn = [&G, mod = n("___moddi3") || n("___umoddi3"), sign = n("___divdi3") || n("___moddi3")] {
            u64 a = G.arg(0) | static_cast<u64>(G.arg(1)) << 32, b = G.arg(2) | static_cast<u64>(G.arg(3)) << 32;
            if (!b) { G.ret64(0); return; }
            if (sign) {
                long long x = static_cast<long long>(a), y = static_cast<long long>(b);
                G.ret64(static_cast<u64>(mod ? x % y : x / y));
            } else {
                G.ret64(mod ? a % b : a / b);
            }
        };
    else if (n("_sqrt") || n("_exp") || n("_log") || n("_log10") || n("_sin") || n("_cos") ||
             n("_floor") || n("_ceil") || n("_fabs") || n("_atan") || n("_tan")) {
        double (*f)(double) = n("_sqrt") ? static_cast<double (*)(double)>(std::sqrt)
                            : n("_exp") ? static_cast<double (*)(double)>(std::exp)
                            : n("_log") ? static_cast<double (*)(double)>(std::log)
                            : n("_log10") ? static_cast<double (*)(double)>(std::log10)
                            : n("_sin") ? static_cast<double (*)(double)>(std::sin)
                            : n("_cos") ? static_cast<double (*)(double)>(std::cos)
                            : n("_floor") ? static_cast<double (*)(double)>(std::floor)
                            : n("_ceil") ? static_cast<double (*)(double)>(std::ceil)
                            : n("_fabs") ? static_cast<double (*)(double)>(std::fabs)
                            : n("_atan") ? static_cast<double (*)(double)>(std::atan)
                            : static_cast<double (*)(double)>(std::tan);
        fn = [&G, f] { G.ret64(double_bits(f(as_double(G.arg(0), G.arg(1))))); };
    } else if (n("_pow"))
        fn = [&G] { G.ret64(double_bits(std::pow(as_double(G.arg(0), G.arg(1)), as_double(G.arg(2), G.arg(3))))); };
    else if (n("_expf") || n("_logf") || n("_sqrtf") || n("_powf")) {
        fn = [&G, which = name] {
            float a = as_float(G.arg(0));
            float v = which == "_expf" ? std::exp(a) : which == "_logf" ? std::log(a)
                    : which == "_sqrtf" ? std::sqrt(a) : std::pow(a, as_float(G.arg(1)));
            G.ret(float_bits(v));
        };
    } else if (n("___stack_chk_fail") || n("_abort"))
        fn = [&G, name] { G.error = name; G.jit->HaltExecution(); };
    else
        fn = [&G, name] {
            G.error = "unconverted import " + name;
            G.jit->HaltExecution();
        };
    g.wr32(slot, g.trampoline(name, std::move(fn)));
}

bool atzb::load_image(char *err, size_t errlen) {
    const u8 *p = atzb_image, *end = atzb_image_end;
    auto take32 = [&]() { u32 v; std::memcpy(&v, p, 4); p += 4; return v; };
    auto take16 = [&]() { u16 v; std::memcpy(&v, p, 2); p += 2; return v; };
    if (end - p < 8 || std::memcmp(p, "ATZG", 4)) {
        snprintf(err, errlen, "engine image is damaged");
        return false;
    }
    p += 8;
    for (u32 n = take32(); n; n--) {
        u32 vaddr = take32(), vmsize = take32(), filesize = take32();
        if (!g.commit(vaddr, align(vmsize, PAGE))) {
            snprintf(err, errlen, "cannot map the engine at %#x", vaddr);
            return false;
        }
        std::memcpy(g.host(vaddr), p, filesize);
        p += filesize;
    }
    // Guard value for the engine's stack canaries.
    u32 guard = 0;
    std::vector<std::pair<u32, std::string>> imports;
    for (u32 n = take32(); n; n--) {
        u32 slot = take32();
        u16 len = take16();
        imports.emplace_back(slot, std::string(reinterpret_cast<const char *>(p), len));
        p += len;
    }
    for (u32 n = take32(); n; n--) {
        u32 addr = take32();
        u16 len = take16();
        api[std::string(reinterpret_cast<const char *>(p), len)] = addr;
        p += len;
    }
    for (auto &[slot, name] : imports) {
        if (name == "___stack_chk_guard") {
            if (!guard) {
                guard = g.alloc(4);
                g.wr32(guard, 0x5ca1ab1e);
            }
            g.wr32(slot, guard);
        } else {
            install_libc(name, slot);
        }
    }
    return true;
}

// Builds the heap, critical section, stream and mapping tables of the install
// structure, returning the structure.
u32 atzb::platform_tables() {
    Guest &G = g;
    auto table = [&](std::vector<std::pair<std::string, std::function<void()>>> fns) {
        u32 t = G.alloc(4 * static_cast<u32>(fns.size()));
        for (size_t i = 0; i < fns.size(); i++)
            G.wr32(t + 4 * static_cast<u32>(i), G.trampoline(fns[i].first, std::move(fns[i].second)));
        return t;
    };
    u32 heap_if = table({
        {"heap.alloc", [&G] { G.ret(G.alloc(G.arg(1))); }},
        {"heap.calloc", [&G] { G.ret(G.zalloc(G.arg(1) * G.arg(2))); }},
        {"heap.realloc", [&G] { G.ret(G.resize(G.arg(1), G.arg(2))); }},
        {"heap.free", [&G] { G.release(G.arg(1)); }},
    });
    // One thread drives each engine, so the engine's locks have nothing to do.
    u32 cs_if = table({
        {"cs.open", [&G] { G.wr32(G.arg(2), G.alloc(4)); G.ret(0); }},
        {"cs.close", [&G] { G.ret(0); }},
        {"cs.enter", [&G] { G.ret(0); }},
        {"cs.leave", [&G] { G.ret(0); }},
    });
    u32 stream_if = table({
        {"st.open", [this, &G] {
             std::string path = path_for(G.str(G.arg(2)));
             FILE *f = path.empty() ? nullptr : fopen(path.c_str(), "rb");
             if (!f) { G.ret(E_NOTFOUND); return; }
             u32 h = G.alloc(4);
             streams[h] = f;
             G.wr32(G.arg(4), h);
             G.ret(0);
         }},
        {"st.close", [this, &G] {
             auto it = streams.find(G.arg(0));
             if (it == streams.end()) { G.ret(E_ARG); return; }
             fclose(it->second);
             streams.erase(it);
             G.ret(0);
         }},
        {"st.read", [this, &G] {
             auto it = streams.find(G.arg(3));
             u32 size = G.arg(1);
             if (it == streams.end() || !size) { G.ret(0); return; }
             G.ret(static_cast<u32>(fread(G.host(G.arg(0)), size, G.arg(2), it->second)));
         }},
        {"st.seek", [this, &G] {
             auto it = streams.find(G.arg(0));
             if (it == streams.end()) { G.ret(E_ARG); return; }
             long off = G.arg(3) == 0xffffffff ? -static_cast<long>(G.arg(1)) : static_cast<long>(G.arg(1));
             int whence = G.arg(2) == 1 ? SEEK_CUR : G.arg(2) == 2 ? SEEK_END : SEEK_SET;
             G.ret(fseek(it->second, off, whence) ? E_ARG : 0);
         }},
        {"st.size", [this, &G] {
             auto it = streams.find(G.arg(0));
             struct stat st;
             G.ret(it != streams.end() && !fstat(fileno(it->second), &st) ? static_cast<u32>(st.st_size) : 0);
         }},
        {"st.error", [&G] { G.ret(0); }},
        {"st.write", [&G] { G.ret(G.arg(2)); }},
    });
    // Voice files go straight into the guest's address space, read-only.
    u32 mapping_if = table({
        {"mp.open", [this, &G] {
             std::string path = path_for(G.str(G.arg(2)));
             int fd = path.empty() ? -1 : open(path.c_str(), O_RDONLY | O_CLOEXEC);
             struct stat st;
             if (fd < 0 || fstat(fd, &st)) { if (fd >= 0) close(fd); G.ret(E_NOTFOUND); return; }
             u32 len = static_cast<u32>(st.st_size);
             u32 span = align(len ? len : 1, PAGE);
             if (files_top + span > FILES_END) { close(fd); G.ret(E_NOTFOUND); return; }
             u32 at = files_top;
             void *m = mmap(G.host(at), span, PROT_READ, MAP_PRIVATE | MAP_FIXED, fd, 0);
             close(fd);
             if (m == MAP_FAILED) { G.ret(E_NOTFOUND); return; }
             for (u32 q = at; q < at + span; q += PAGE) (*G.pages)[q / PAGE] = G.host(q);
             files_top += span + PAGE;
             u32 h = G.alloc(4);
             maps[h] = {at, len};
             G.wr32(G.arg(3), h);
             G.ret(0);
         }},
        {"mp.close", [this, &G] {
             auto it = maps.find(G.arg(0));
             if (it == maps.end()) { G.ret(E_ARG); return; }
             auto [at, len] = it->second;
             u32 span = align(len ? len : 1, PAGE);
             for (u32 q = at; q < at + span; q += PAGE) (*G.pages)[q / PAGE] = nullptr;
             mmap(G.host(at), span, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED | MAP_NORESERVE, -1, 0);
             maps.erase(it);
             G.ret(0);
         }},
        {"mp.map", [this, &G] {
             auto it = maps.find(G.arg(0));
             if (it == maps.end()) { G.ret(E_ARG); return; }
             auto [at, len] = it->second;
             u32 off = G.arg(1), bytes = G.arg(2), out = G.arg(3);
             if (off > len) { G.wr32(out, 0); G.ret(0); return; }
             u32 avail = len - off;
             if (bytes) {
                 u32 want = G.rd32(bytes);
                 G.wr32(bytes, want == 0 || want > avail ? avail : want);
             }
             G.wr32(out, at + off);
             G.ret(0);
         }},
        {"mp.unmap", [&G] { G.ret(0); }},
        {"mp.freeze", [&G] { G.ret(0); }},
    });
    u32 install = G.zalloc(0x30);
    G.host(install)[0] = 0x20;
    G.host(install)[1] = 0x05;
    G.wr32(install + 0x08, heap_if);
    G.wr32(install + 0x10, cs_if);
    G.wr32(install + 0x18, stream_if);
    G.wr32(install + 0x1c, mapping_if);
    return install;
}

u32 atzb::params(const std::vector<std::pair<u32, std::string>> &strings,
                 const std::vector<std::pair<u32, u16>> &values) {
    u32 n = static_cast<u32>(strings.size() + values.size());
    u32 a = g.zalloc(132 * n), at = a;
    for (auto &[id, s] : strings) {
        g.wr32(at, id);
        std::strncpy(reinterpret_cast<char *>(g.host(at + 4)), s.c_str(), 127);
        at += 132;
    }
    for (auto &[id, v] : values) {
        g.wr32(at, id);
        std::memcpy(g.host(at + 4), &v, 2);
        at += 132;
    }
    return a;
}

namespace {
std::string tag(const std::string &xml, const char *name) {
    std::string open = std::string("<") + name + ">", close = std::string("</") + name + ">";
    size_t a = xml.find(open);
    if (a == std::string::npos) return "";
    a += open.size();
    size_t b = xml.find(close, a);
    return b == std::string::npos ? "" : xml.substr(a, b - a);
}
}  // namespace

extern "C" atzb *atzb_open(const char *voice_dir, const char *common_dir, atz_voice_info *info,
                           atz_log_cb log, char *err, size_t errlen) {
    auto b = std::make_unique<atzb>();
    b->g.log = log;
    b->voice_dir = voice_dir;
    b->common_dir = common_dir ? common_dir : voice_dir;
    Guest &G = b->g;
    if (!G.ok() || !G.commit(STACK, STACK_SIZE) || !G.commit(HEAP, HEAP_SIZE) ||
        !G.commit(TRAMP, TRAMP_SIZE)) {
        snprintf(err, errlen, "cannot reserve the engine's address space");
        return nullptr;
    }
    G.start_jit();
    G.wr32(TRAMP, 0xef000000 | SVC_STOP);
    G.wr32(TRAMP + 4, 0xe12fff1e);
    if (!b->load_image(err, errlen)) return nullptr;
    for (const char *f : {"_vauto_ttsInitialize", "_vauto_ttsOpen", "_vauto_ttsSetParamList",
                          "_vauto_ttsSetOutDevice", "_vauto_ttsProcessText2Speech"})
        if (!b->api.count(f)) {
            snprintf(err, errlen, "engine image lacks %s", f);
            return nullptr;
        }

    std::string path = b->voice_dir + "/broker.hdr";
    FILE *f = fopen(path.c_str(), "rb");
    if (!f) {
        snprintf(err, errlen, "cannot read %s", path.c_str());
        return nullptr;
    }
    std::string hdr;
    char chunk[4096];
    for (size_t n; (n = fread(chunk, 1, sizeof chunk, f)) > 0;) hdr.append(chunk, n);
    fclose(f);
    // Apple prepends "defaultvoice:<name>\n" to built-in voices' headers.
    if (hdr.rfind("defaultvoice:", 0) == 0) hdr.erase(0, hdr.find('\n') + 1);
    std::string language = tag(hdr, "language"), voice = tag(hdr, "voice"), model = tag(hdr, "voicemodel");
    int rate = std::atoi(tag(hdr, "frequencyhz").c_str());
    if (language.empty() || voice.empty() || !rate) {
        snprintf(err, errlen, "%s lacks language, voice or frequencyhz", path.c_str());
        return nullptr;
    }
    snprintf(info->language, sizeof info->language, "%s", language.c_str());
    snprintf(info->voice, sizeof info->voice, "%s", voice.c_str());
    snprintf(info->model, sizeof info->model, "%s", model.c_str());
    info->sample_rate = rate;

    u32 install = b->platform_tables();
    u32 broker = G.alloc(static_cast<u32>(hdr.size()) + 1);
    std::memcpy(G.host(broker), hdr.c_str(), hdr.size() + 1);
    G.wr32(install + 4, broker);

    u32 speech = G.zalloc(8), inst = G.zalloc(8);
    u32 rc = G.call(b->api["_vauto_ttsInitialize"], {install, speech});
    if (rc == 0)
        rc = G.call(b->api["_vauto_ttsOpen"], {G.rd32(speech), G.rd32(speech + 4), 0, 0}, {inst, 0});
    if (rc) {
        snprintf(err, errlen, "starting the engine: %s", G.error.empty() ? std::to_string(rc).c_str() : G.error.c_str());
        return nullptr;
    }
    b->instance[0] = G.rd32(inst);
    b->instance[1] = G.rd32(inst + 4);
    u32 list = b->params({{7, language}, {8, voice}}, {{1, static_cast<u16>(rate / 1000)}, {12, 1}});
    rc = G.call(b->api["_vauto_ttsSetParamList"], {b->instance[0], b->instance[1], list, 4});
    if (rc) {
        snprintf(err, errlen, "setting language and voice: %#x %s", rc, G.error.c_str());
        return nullptr;
    }
    if (!model.empty()) {
        rc = G.call(b->api["_vauto_ttsSetParamList"], {b->instance[0], b->instance[1], b->params({{19, model}}, {}), 1});
        if (rc) G.logf("setting voice model %s: %#x", model.c_str(), rc);
    }

    b->pcm = G.alloc(PCM_BYTES);
    b->marks = G.zalloc(MARK_SIZE * MAX_MARKS);
    atzb *self = b.get();
    u32 notify = G.trampoline("notify", [self] {
        Guest &g = self->g;
        u32 msg = g.arg(3);
        u32 kind = g.rd32(msg), out = g.rd32(msg + 8);
        if (kind == MSG_OUTBUFREQ) {
            if (self->stopping) { g.ret(E_REFUSED); return; }
            g.wr32(out + 4, PCM_BYTES);
            g.wr32(out + 8, self->pcm);
            g.wr32(out + 12, MARK_SIZE * MAX_MARKS);
            g.wr32(out + 16, self->marks);
        } else if (kind == MSG_OUTBUFDONE) {
            u32 count = g.rd32(out + 12), list = g.rd32(out + 16);
            for (u32 i = 0; self->on_word && i < count && i < MAX_MARKS; i++) {
                u32 m = list + MARK_SIZE * i;
                if (g.rd32(m + 4) == MARK_WORD) {
                    u32 pos = g.rd32(m + 8), len = g.rd32(m + 12);
                    self->on_word(self->user, pos / 2, (pos + len) / 2, g.rd32(m + 16));
                }
            }
            u32 bytes = g.rd32(out + 4);
            if (bytes && self->on_pcm && !self->stopping &&
                self->on_pcm(self->user, reinterpret_cast<const int16_t *>(g.host(g.rd32(out + 8))), bytes / 2))
                self->stopping = true;
        }
        g.ret(0);
    });
    u32 dev = G.zalloc(8);
    G.wr32(dev + 4, notify);
    rc = G.call(b->api["_vauto_ttsSetOutDevice"], {b->instance[0], b->instance[1], dev});
    if (rc) {
        snprintf(err, errlen, "setting the output: %#x %s", rc, G.error.c_str());
        return nullptr;
    }
    return b.release();
}

extern "C" int atzb_set_prosody(atzb *b, int rate, int pitch) {
    auto clamp = [](int v, int lo, int hi) { return static_cast<u16>(v < lo ? lo : v > hi ? hi : v); };
    // This engine only lowers pitch (50 to 100) and premium voices refuse it
    // altogether, so pitch is best effort; each value is sent only on change.
    u16 r = clamp(rate, 50, 400), p = clamp(pitch, 50, 100);
    u32 rc = 0;
    if (r != b->rate) {
        u32 list = b->params({}, {{3, r}});
        rc = b->g.call(b->api["_vauto_ttsSetParamList"], {b->instance[0], b->instance[1], list, 1});
        b->g.release(list);
        if (!rc) b->rate = r;
    }
    if (p != b->pitch) {
        u32 list = b->params({}, {{4, p}});
        b->g.call(b->api["_vauto_ttsSetParamList"], {b->instance[0], b->instance[1], list, 1});
        b->g.release(list);
        b->pitch = p;
    }
    return static_cast<int>(rc);
}

extern "C" uint32_t atzb_speak(atzb *b, const uint16_t *text, size_t len,
                               atz_pcm_cb on_pcm, atz_word_cb on_word, void *user) {
    Guest &G = b->g;
    u32 bytes = static_cast<u32>(len * 2);
    u32 buf = G.alloc(bytes + 2), in = G.zalloc(12);
    if (!buf || !in) return 0x8000000a;
    std::memcpy(G.host(buf), text, bytes);
    G.host(buf)[bytes] = G.host(buf)[bytes + 1] = 0;
    G.wr32(in + 4, bytes);
    G.wr32(in + 8, buf);
    b->on_pcm = on_pcm;
    b->on_word = on_word;
    b->user = user;
    b->stopping = false;
    u32 rc = G.call(b->api["_vauto_ttsProcessText2Speech"], {b->instance[0], b->instance[1], in});
    b->on_pcm = nullptr;
    b->on_word = nullptr;
    G.release(buf);
    G.release(in);
    if (!G.error.empty()) {
        G.logf("engine stopped: %s", G.error.c_str());
        return 0x80000000;
    }
    if (rc && b->stopping) return 0;
    return rc;
}

// The engine runs on the speaking thread, so a stop only marks the next
// buffer request for refusal, which ends synthesis within one buffer.
extern "C" void atzb_stop(atzb *b) { b->stopping = true; }

extern "C" void atzb_close(atzb *b) {
    for (auto &[h, f] : b->streams) fclose(f);
    delete b;
}
