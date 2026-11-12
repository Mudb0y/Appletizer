// std::string as the iOS 10 engine expects it, linked into the converted engine.
//
// Apple built the engine against iOS arm64 libc++, whose strings use the
// alternate layout: data pointer first, long flag in the top bit of the last
// byte. macho2elf binds the engine's out-of-line std::string calls to the
// target's libc++, which uses the standard layout: long flag in the lowest bit
// of the first byte, data pointer last. The engine's inlined string code and
// those calls then read each other's strings wrongly; a string longer than 22
// bytes written by the target's libc++ makes the next inlined clear() write
// through its capacity as a pointer.
//
// This is libc++'s own std::string compiled with the alternate layout, plus
// the few library functions that take or return one. Everything here is
// hidden, so the engine's imports of these names bind to it at link time and
// nothing else in the process sees it.
//
// Build: clang++ -std=c++17 -O2 -fPIC -fvisibility=hidden
//        -D_LIBCPP_DISABLE_VISIBILITY_ANNOTATIONS
//        -D_LIBCPP_ABI_ALTERNATE_STRING_LAYOUT -c

#include <cerrno>
#include <climits>
#include <cstdio>
#include <cstdlib>
#include <stdexcept>
#include <string>

#if !defined(_LIBCPP_ABI_ALTERNATE_STRING_LAYOUT) || !defined(_LIBCPP_DISABLE_VISIBILITY_ANNOTATIONS)
#error "build with -D_LIBCPP_ABI_ALTERNATE_STRING_LAYOUT -D_LIBCPP_DISABLE_VISIBILITY_ANNOTATIONS"
#endif

template class std::basic_string<char>;

std::logic_error::logic_error(const std::string &s) : logic_error(s.c_str()) {}
std::runtime_error::runtime_error(const std::string &s) : runtime_error(s.c_str()) {}

_LIBCPP_BEGIN_NAMESPACE_STD

namespace {

template <class T, class F>
T convert(const char *fn, const string &s, size_t *idx, F parse) {
    const char *p = s.c_str();
    char *end;
    int saved = errno;
    errno = 0;
    T r = parse(p, &end);
    int err = errno;
    errno = saved;
    if (end == p) throw invalid_argument(string(fn) + ": no conversion");
    if (err == ERANGE) throw out_of_range(string(fn) + ": out of range");
    if (idx) *idx = static_cast<size_t>(end - p);
    return r;
}

template <class T>
string format(const char *fmt, T v) {
    char buf[64];
    int n = snprintf(buf, sizeof buf, fmt, v);
    return string(buf, n < 0 ? 0 : n < (int)sizeof buf ? n : (int)sizeof buf - 1);
}

} // namespace

int stoi(const string &s, size_t *idx, int base) {
    long r = convert<long>("stoi", s, idx, [base](const char *p, char **e) { return strtol(p, e, base); });
    if (r < INT_MIN || r > INT_MAX) throw out_of_range("stoi: out of range");
    return static_cast<int>(r);
}

unsigned long stoul(const string &s, size_t *idx, int base) {
    return convert<unsigned long>("stoul", s, idx,
                                  [base](const char *p, char **e) { return strtoul(p, e, base); });
}

float stof(const string &s, size_t *idx) {
    return convert<float>("stof", s, idx, [](const char *p, char **e) { return strtof(p, e); });
}

string to_string(int v) { return format("%d", v); }
string to_string(unsigned v) { return format("%u", v); }
string to_string(unsigned long v) { return format("%lu", v); }
string to_string(float v) { return format("%f", static_cast<double>(v)); }

_LIBCPP_END_NAMESPACE_STD
