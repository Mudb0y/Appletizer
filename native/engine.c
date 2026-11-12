#define _GNU_SOURCE
#include "engine.h"
#include "bridge.h"
#include "ve.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

/* Samples per engine output buffer. Smaller means the first audio arrives
 * sooner, which is what a screen reader notices. */
#define PCM_SAMPLES 1024
#define MAX_MARKS 32

static void log_stderr(const char *msg) { fprintf(stderr, "appletizer: %s\n", msg); }
static atz_log_cb log_fn = log_stderr;

void atz_set_log(atz_log_cb log) { log_fn = log ? log : log_stderr; }

static void logf_(const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    log_fn(buf);
}

/* ---- data class: where resource names resolve to files -------------------- */

typedef struct {
    const char *dirs[2];
    int ndirs;
} data_class;

/*
 * The engine asks for resources by broker name ("clc/plp/cfg3", "lid") or by
 * file name ("broker.hdr"). Apple's rule, kept exactly: slashes become
 * underscores, a name with no dot gets ".dat", and the first search directory
 * holding the result wins.
 */
static int resolve(const data_class *dc, const char *name, char *out, size_t outlen) {
    char file[512];
    size_t n = 0;
    int dot = 0;
    for (const char *p = name; *p && n < sizeof file - 5; p++) {
        if (*p == '.') dot = 1;
        file[n++] = *p == '/' ? '_' : *p;
    }
    file[n] = 0;
    if (!dot) strcat(file, ".dat");
    for (int i = 0; i < dc->ndirs; i++) {
        struct stat st;
        snprintf(out, outlen, "%s/%s", dc->dirs[i], file);
        if (stat(out, &st) == 0) return 1;
    }
    logf_("no file for resource %s (%s)", name, file);
    return 0;
}

/* ---- platform interfaces ---------------------------------------------------- */

static void *heap_alloc(void *h, uint32_t size) { (void)h; return malloc(size); }
static void *heap_calloc(void *h, uint32_t n, uint32_t size) { (void)h; return calloc(n, size); }
static void *heap_realloc(void *h, void *p, uint32_t size) { (void)h; return realloc(p, size); }
static void heap_free(void *h, void *p) { (void)h; free(p); }

static const ve_heap_if heap_if = { heap_alloc, heap_calloc, heap_realloc, heap_free };

static ve_err cs_open(void *cls, void *heap, void **out) {
    (void)cls; (void)heap;
    if (!out) return VE_E_ARG;
    pthread_mutex_t *m = malloc(sizeof *m);
    if (!m) return VE_E_NOMEM;
    pthread_mutexattr_t a;
    pthread_mutexattr_init(&a);
    pthread_mutexattr_settype(&a, PTHREAD_MUTEX_RECURSIVE);
    int r = pthread_mutex_init(m, &a);
    pthread_mutexattr_destroy(&a);
    if (r) { free(m); return VE_E_SYSTEM; }
    *out = m;
    return VE_OK;
}
static ve_err cs_close(void *cs) {
    if (!cs) return VE_OK;
    if (pthread_mutex_destroy(cs)) return VE_E_SYSTEM;
    free(cs);
    return VE_OK;
}
static ve_err cs_enter(void *cs) { return cs && pthread_mutex_lock(cs) ? VE_E_SYSTEM : VE_OK; }
static ve_err cs_leave(void *cs) { return cs && pthread_mutex_unlock(cs) ? VE_E_SYSTEM : VE_OK; }

static const ve_critsec_if critsec_if = { cs_open, cs_close, cs_enter, cs_leave };

static ve_err st_open(void *dcp, void *heap, const char *name, const char *mode, void **out) {
    (void)heap;
    char path[1024];
    if (!name || !mode || !out) return VE_E_ARG;
    if (!resolve(dcp, name, path, sizeof path)) return VE_E_NOTFOUND;
    FILE *f = fopen(path, mode);
    if (!f) return VE_E_OPEN;
    *out = f;
    return VE_OK;
}
static ve_err st_close(void *s) {
    if (!s) return VE_E_ARG;
    return fclose(s) ? VE_E_CLOSE : VE_OK;
}
static uint32_t st_read(void *buf, uint32_t size, uint32_t count, void *s) {
    return s ? (uint32_t)fread(buf, size, count, s) : 0;
}
static ve_err st_seek(void *s, uint32_t offset, uint32_t origin, uint32_t backwards) {
    if (!s) return VE_E_ARG;
    long off = backwards == (uint32_t)-1 ? -(long)offset : (long)offset;
    int whence = origin == VE_SEEK_CUR ? SEEK_CUR : origin == VE_SEEK_END ? SEEK_END : SEEK_SET;
    return fseek(s, off, whence) ? VE_E_SEEK : VE_OK;
}
static uint32_t st_size(void *s) {
    struct stat st;
    if (!s || fstat(fileno(s), &st)) return 0;
    return (uint32_t)st.st_size;
}
static ve_err st_error(void *s) {
    if (!s) return VE_E_ARG;
    return ferror(s) ? VE_E_STREAM : VE_OK;
}
static uint32_t st_write(const void *buf, uint32_t size, uint32_t count, void *s) {
    return s ? (uint32_t)fwrite(buf, size, count, s) : 0;
}

static const ve_stream_if stream_if = {
    st_open, st_close, st_read, st_seek, st_size, st_error, st_write,
};

typedef struct {
    const uint8_t *base;
    size_t size;
} mapping;

/* Whole-file read-only mappings: the voice files are what the engine reads
 * most, and the page cache shares them between engines for free. */
static ve_err mp_open(void *dcp, void *heap, const char *name, void **out) {
    (void)heap;
    char path[1024];
    if (!name || !out) return VE_E_ARG;
    if (!resolve(dcp, name, path, sizeof path)) return VE_E_NOTFOUND;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return VE_E_OPEN;
    struct stat st;
    if (fstat(fd, &st)) { close(fd); return VE_E_OPEN; }
    mapping *m = calloc(1, sizeof *m);
    if (!m) { close(fd); return VE_E_NOMEM; }
    m->size = (size_t)st.st_size;
    if (m->size) {
        void *p = mmap(NULL, m->size, PROT_READ, MAP_PRIVATE, fd, 0);
        if (p == MAP_FAILED) {
            logf_("mmap %s: %s", path, strerror(errno));
            close(fd);
            free(m);
            return VE_E_SYSTEM;
        }
        m->base = p;
    }
    close(fd);
    *out = m;
    return VE_OK;
}
static ve_err mp_close(void *mp) {
    mapping *m = mp;
    if (!m) return VE_E_ARG;
    if (m->base) munmap((void *)m->base, m->size);
    free(m);
    return VE_OK;
}
static ve_err mp_map(void *mp, uint32_t offset, uint32_t *bytes, const void **data) {
    mapping *m = mp;
    if (!m || !data) return VE_E_ARG;
    if (offset > m->size) {
        *data = NULL;
        return VE_OK;
    }
    size_t avail = m->size - offset;
    *data = m->base + offset;
    if (bytes) *bytes = (*bytes == 0 || *bytes > avail) ? (uint32_t)avail : *bytes;
    return VE_OK;
}
static ve_err mp_unmap(void *mp, const void *data) { (void)data; return mp ? VE_OK : VE_E_ARG; }
static ve_err mp_freeze(void *mp) { (void)mp; return VE_OK; }

static const ve_mapping_if mapping_if = { mp_open, mp_close, mp_map, mp_unmap, mp_freeze };

/* ---- engine libraries --------------------------------------------------------- */

/* Each converted engine is its own library with the same ve_* names, so they
 * are opened locally and called through a table rather than linked. */
typedef struct {
    char *path;
    __typeof__(ve_ttsInitialize) *initialize;
    __typeof__(ve_ttsUnInitialize) *uninitialize;
    __typeof__(ve_ttsOpen) *open;
    __typeof__(ve_ttsClose) *close;
    __typeof__(ve_ttsSetParamList) *set_params;
    __typeof__(ve_ttsSetOutDevice) *set_out_device;
    __typeof__(ve_ttsProcessText2Speech) *speak;
    __typeof__(ve_ttsStop) *stop;
    __typeof__(ve_ttsResourceLoad) *load_resource;
    /* Set instead of the above for an engine run inside an emulator. */
    __typeof__(atzb_open) *bridge_open;
    __typeof__(atzb_set_prosody) *bridge_prosody;
    __typeof__(atzb_speak) *bridge_speak;
    __typeof__(atzb_stop) *bridge_stop;
    __typeof__(atzb_close) *bridge_close;
} ve_api;

#define MAX_LIBS 8
static ve_api libs[MAX_LIBS];
static int nlibs;
static pthread_mutex_t libs_lock = PTHREAD_MUTEX_INITIALIZER;

/* Libraries stay loaded for the life of the process. */
static const ve_api *load_api(const char *path, char *err, size_t errlen) {
    const ve_api *found = NULL;
    pthread_mutex_lock(&libs_lock);
    for (int i = 0; i < nlibs && !found; i++)
        if (!strcmp(libs[i].path, path)) found = &libs[i];
    if (found) goto out;
    if (nlibs == MAX_LIBS) {
        snprintf(err, errlen, "too many engine libraries");
        goto out;
    }
    void *dl = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!dl) {
        snprintf(err, errlen, "dlopen %s: %s", path, dlerror());
        goto out;
    }
    ve_api a = { .path = strdup(path) };
#define SYM(field, name) \
    if (!(*(void **)&a.field = dlsym(dl, name))) { \
        snprintf(err, errlen, "%s lacks %s", path, name); \
        free(a.path); \
        goto out; \
    }
    if ((*(void **)&a.bridge_open = dlsym(dl, "atzb_open"))) {
        SYM(bridge_prosody, "atzb_set_prosody")
        SYM(bridge_speak, "atzb_speak")
        SYM(bridge_stop, "atzb_stop")
        SYM(bridge_close, "atzb_close")
        goto loaded;
    }
    SYM(initialize, "ve_ttsInitialize")
    SYM(uninitialize, "ve_ttsUnInitialize")
    SYM(open, "ve_ttsOpen")
    SYM(close, "ve_ttsClose")
    SYM(set_params, "ve_ttsSetParamList")
    SYM(set_out_device, "ve_ttsSetOutDevice")
    SYM(speak, "ve_ttsProcessText2Speech")
    SYM(stop, "ve_ttsStop")
    SYM(load_resource, "ve_ttsResourceLoad")
#undef SYM
loaded:
    libs[nlibs] = a;
    found = &libs[nlibs++];
out:
    pthread_mutex_unlock(&libs_lock);
    return found;
}

/* ---- engine ------------------------------------------------------------------ */

struct atz_engine {
    const ve_api *ve;
    atzb *bridge;
    data_class dc;
    char *voice_dir, *common_dir;
    char *broker;              /* broker.hdr text handed to the engine */
    void *rules;               /* user_rules.txt, kept for the engine's lifetime */
    void *prompts;             /* apdb.dat */
    size_t prompts_len;
    ve_handle speech, instance;
    atz_voice_info info;

    /* per-utterance state, touched by the notify callback */
    int16_t pcm[PCM_SAMPLES];
    ve_mark marks[MAX_MARKS];
    atz_pcm_cb on_pcm;
    atz_word_cb on_word;
    void *user;
    volatile int stopping;
};

static char *read_file(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *buf = n >= 0 ? malloc((size_t)n + 1) : NULL;
    if (buf && fread(buf, 1, (size_t)n, f) != (size_t)n) { free(buf); buf = NULL; }
    fclose(f);
    if (buf) {
        buf[n] = 0;
        if (len) *len = (size_t)n;
    }
    return buf;
}

static void tag(const char *xml, const char *name, char *out, size_t outlen) {
    char open[64], close_[64];
    snprintf(open, sizeof open, "<%s>", name);
    snprintf(close_, sizeof close_, "</%s>", name);
    out[0] = 0;
    const char *a = strstr(xml, open);
    if (!a) return;
    a += strlen(open);
    const char *b = strstr(a, close_);
    if (!b) return;
    size_t n = (size_t)(b - a) < outlen - 1 ? (size_t)(b - a) : outlen - 1;
    memcpy(out, a, n);
    out[n] = 0;
}

static void set_string(ve_param *p, uint32_t id, const char *s) {
    memset(p, 0, sizeof *p);
    p->id = id;
    snprintf(p->u.string, sizeof p->u.string, "%s", s);
}

static void set_value(ve_param *p, uint32_t id, uint16_t v) {
    memset(p, 0, sizeof *p);
    p->id = id;
    p->u.value = v;
}

static ve_err notify(ve_handle inst, void *user, ve_callback_msg *msg) {
    (void)inst;
    atz_engine *e = user;
    ve_outdata *out = msg->param;
    switch (msg->message) {
    case VE_MSG_OUTBUFREQ:
        if (e->stopping) return VE_E_NOBUFFER;
        out->pcm = e->pcm;
        out->pcm_bytes = sizeof e->pcm;
        out->mark_list = e->marks;
        out->marks = sizeof e->marks;
        return VE_OK;
    case VE_MSG_OUTBUFDONE:
        if (e->on_word) {
            for (uint32_t i = 0; i < out->marks && i < MAX_MARKS; i++) {
                const ve_mark *m = &out->mark_list[i];
                if (m->type == VE_MRK_WORD)
                    e->on_word(e->user, m->src_pos / 2, (m->src_pos + m->src_len) / 2, m->dest_pos);
            }
        }
        /* Refusing the next buffer request is what ends synthesis here;
         * ve_ttsStop is for other threads. */
        if (out->pcm_bytes && e->on_pcm && !e->stopping &&
            e->on_pcm(e->user, out->pcm, out->pcm_bytes / 2))
            e->stopping = 1;
        return VE_OK;
    default:
        return VE_OK;
    }
}

static void load_resource(atz_engine *e, const char *file, const char *mime, void **keep, size_t *keep_len) {
    char path[1024];
    size_t len = 0;
    snprintf(path, sizeof path, "%s/%s", e->voice_dir, file);
    char *data = read_file(path, &len);
    if (!data) return;
    ve_handle h;
    ve_err r = e->ve->load_resource(e->instance, mime, (uint32_t)len, data, &h);
    if (r) {
        logf_("loading %s failed: %#x", file, r);
        free(data);
        return;
    }
    *keep = data;
    if (keep_len) *keep_len = len;
}

atz_engine *atz_open(const char *engine, const char *voice_dir, const char *common_dir,
                     char *err, size_t errlen) {
    const ve_api *ve = load_api(engine, err, errlen);
    if (!ve) return NULL;
    atz_engine *e = calloc(1, sizeof *e);
    if (!e) return NULL;
    e->ve = ve;
    if (ve->bridge_open) {
        e->bridge = ve->bridge_open(voice_dir, common_dir, &e->info, log_fn, err, errlen);
        if (!e->bridge) {
            free(e);
            return NULL;
        }
        return e;
    }
    e->voice_dir = strdup(voice_dir);
    e->common_dir = strdup(common_dir ? common_dir : voice_dir);
    e->dc.dirs[0] = e->voice_dir;
    e->dc.dirs[1] = e->common_dir;
    e->dc.ndirs = 2;

    char path[1024];
    snprintf(path, sizeof path, "%s/broker.hdr", voice_dir);
    char *hdr = read_file(path, NULL);
    if (!hdr) {
        snprintf(err, errlen, "cannot read %s", path);
        goto fail;
    }
    /* Apple prepends "defaultvoice:<name>\n" to the built-in voices' headers
     * and strips it again before the engine sees the XML. */
    char *xml = hdr;
    if (!strncmp(xml, "defaultvoice:", 13)) {
        char *nl = strchr(xml, '\n');
        xml = nl ? nl + 1 : xml + strlen(xml);
    }
    e->broker = strdup(xml);
    free(hdr);

    char hz[32];
    tag(e->broker, "language", e->info.language, sizeof e->info.language);
    tag(e->broker, "voice", e->info.voice, sizeof e->info.voice);
    tag(e->broker, "voicemodel", e->info.model, sizeof e->info.model);
    tag(e->broker, "frequencyhz", hz, sizeof hz);
    e->info.sample_rate = atoi(hz);
    if (!e->info.language[0] || !e->info.voice[0] || !e->info.sample_rate) {
        snprintf(err, errlen, "%s lacks language, voice or frequencyhz", path);
        goto fail;
    }

    ve_install inst = {
        .format_version = 0x0520,
        .broker_info = e->broker,
        .heap_if = &heap_if,
        .critsec_if = &critsec_if,
        .stream_if = &stream_if,
        .mapping_if = &mapping_if,
        .data_class = &e->dc,
    };
    ve_err r = e->ve->initialize(&inst, &e->speech);
    if (r) {
        snprintf(err, errlen, "ve_ttsInitialize: %#x", r);
        goto fail;
    }
    r = e->ve->open(e->speech, NULL, NULL, &e->instance, NULL);
    if (r) {
        snprintf(err, errlen, "ve_ttsOpen: %#x", r);
        e->ve->uninitialize(e->speech);
        e->speech.data = NULL;
        goto fail;
    }

    ve_param p[4];
    set_string(&p[0], VE_PARAM_LANGUAGE, e->info.language);
    set_string(&p[1], VE_PARAM_VOICE, e->info.voice);
    set_value(&p[2], VE_PARAM_FREQUENCY, (uint16_t)(e->info.sample_rate / 1000));
    set_value(&p[3], VE_PARAM_TYPE_OF_CHAR, 1);
    r = e->ve->set_params(e->instance, p, 4);
    if (r) {
        snprintf(err, errlen, "setting language and voice: %#x", r);
        goto fail_open;
    }
    if (e->info.model[0]) {
        set_string(&p[0], VE_PARAM_VOICE_MODEL, e->info.model);
        r = e->ve->set_params(e->instance, p, 1);
        if (r) logf_("setting voice model %s: %#x", e->info.model, r);
    }

    load_resource(e, "user_rules.txt", "application/x-vocalizer-rettt+text", &e->rules, NULL);
    load_resource(e, "apdb.dat", "application/x-vocalizer-activeprompt-db;mode=automatic",
                  &e->prompts, &e->prompts_len);

    ve_outdevice dev = { e, notify };
    r = e->ve->set_out_device(e->instance, &dev);
    if (r) {
        snprintf(err, errlen, "ve_ttsSetOutDevice: %#x", r);
        goto fail_open;
    }
    return e;

fail_open:
    e->ve->close(e->instance);
    e->ve->uninitialize(e->speech);
    e->speech.data = NULL;
fail:
    free(e->broker);
    free(e->voice_dir);
    free(e->common_dir);
    free(e->rules);
    free(e->prompts);
    free(e);
    return NULL;
}

const atz_voice_info *atz_info(const atz_engine *e) { return &e->info; }

static uint16_t clamp(int v, int lo, int hi) { return (uint16_t)(v < lo ? lo : v > hi ? hi : v); }

// Volume stays at the engine default: the voices are levelled for it, 100
// drives them into clipping, and Android scales the stream volume itself.
int atz_set_prosody(atz_engine *e, int rate, int pitch) {
    if (e->bridge) return e->ve->bridge_prosody(e->bridge, rate, pitch);
    ve_param p[2];
    set_value(&p[0], VE_PARAM_SPEECHRATE, clamp(rate, 50, 400));
    set_value(&p[1], VE_PARAM_PITCH, clamp(pitch, 50, 200));
    return (int)e->ve->set_params(e->instance, p, 2);
}

uint32_t atz_speak(atz_engine *e, const uint16_t *text, size_t len,
                   atz_pcm_cb on_pcm, atz_word_cb on_word, void *user) {
    // The engine rejects empty text, and fails the whole utterance on any
    // UTF-16 surrogate, so one emoji would silence the sentence around it.
    // Blanking surrogates keeps the indices that word marks refer to.
    if (!len) return 0;
    uint16_t *clean = malloc(len * sizeof *clean);
    if (!clean) return VE_E_NOMEM;
    for (size_t i = 0; i < len; i++)
        clean[i] = text[i] >= 0xD800 && text[i] <= 0xDFFF ? ' ' : text[i];
    if (e->bridge) {
        uint32_t r = e->ve->bridge_speak(e->bridge, clean, len, on_pcm, on_word, user);
        free(clean);
        return r;
    }
    e->on_pcm = on_pcm;
    e->on_word = on_word;
    e->user = user;
    e->stopping = 0;
    ve_intext in = { 0, (uint32_t)(len * 2), clean };
    ve_err r = e->ve->speak(e->instance, &in);
    free(clean);
    e->on_pcm = NULL;
    e->on_word = NULL;
    if (r == VE_E_STOPPED || (r && e->stopping)) return 0;
    return r;
}

void atz_stop(atz_engine *e) {
    if (e->bridge) {
        e->ve->bridge_stop(e->bridge);
        return;
    }
    e->stopping = 1;
    e->ve->stop(e->instance);
}

void atz_close(atz_engine *e) {
    if (!e) return;
    if (e->bridge) {
        e->ve->bridge_close(e->bridge);
        free(e);
        return;
    }
    e->ve->close(e->instance);
    e->ve->uninitialize(e->speech);
    free(e->broker);
    free(e->voice_dir);
    free(e->common_dir);
    free(e->rules);
    free(e->prompts);
    free(e);
}
