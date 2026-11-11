/*
 * The Nuance Vocalizer Expressive 5.2 C API as statically linked into iOS
 * 7.1.2's voiced (VoiceServices-228). Nuance never published these headers;
 * every layout here was read off Apple's call sites in VSVocalizer.c and
 * VSVocalizerPlatform.c, and the field names follow the engine's own log
 * strings where it has them.
 */
#ifndef APPLETIZER_VE_H
#define APPLETIZER_VE_H

#include <stdint.h>

typedef uint32_t ve_err;

#define VE_OK            0u
#define VE_E_ARG         0x80000007u
#define VE_E_NOTFOUND    0x80010014u
#define VE_E_NOMEM       0x80010108u
#define VE_E_SYSTEM      0x8001010eu
#define VE_E_OPEN        0x80000003u
#define VE_E_CLOSE       0x80010103u
#define VE_E_SEEK        0x80010104u
#define VE_E_STREAM      0x80010004u
#define VE_E_NOBUFFER    0x80010200u
/* What ve_ttsProcessText2Speech returns when ve_ttsStop cut it short. */
#define VE_E_STOPPED     0x80010807u

/* Every engine handle is this pair, passed by value in two registers. */
typedef struct {
    void *data;
    uint32_t check;
} ve_handle;

/* ---- platform interfaces the caller supplies to ve_ttsInitialize ---------- */

typedef struct {
    void *(*alloc)(void *heap, uint32_t size);
    void *(*calloc)(void *heap, uint32_t count, uint32_t size);
    void *(*realloc)(void *heap, void *p, uint32_t size);
    void (*free)(void *heap, void *p);
} ve_heap_if;

typedef struct {
    ve_err (*open)(void *cs_class, void *heap, void **out);
    ve_err (*close)(void *cs);
    ve_err (*enter)(void *cs);
    ve_err (*leave)(void *cs);
} ve_critsec_if;

#define VE_SEEK_SET 0
#define VE_SEEK_CUR 1
#define VE_SEEK_END 2

typedef struct {
    ve_err (*open)(void *data_class, void *heap, const char *name,
                   const char *mode, void **out);
    ve_err (*close)(void *stream);
    uint32_t (*read)(void *buf, uint32_t size, uint32_t count, void *stream);
    /* backwards is (uint32_t)-1 to seek towards the start, anything else forwards */
    ve_err (*seek)(void *stream, uint32_t offset, uint32_t origin, uint32_t backwards);
    uint32_t (*size)(void *stream);
    ve_err (*error)(void *stream);
    uint32_t (*write)(const void *buf, uint32_t size, uint32_t count, void *stream);
} ve_stream_if;

typedef struct {
    ve_err (*open)(void *data_class, void *heap, const char *name, void **out);
    ve_err (*close)(void *mapping);
    /* *bytes in: wanted (0 means to the end), out: available from offset */
    ve_err (*map)(void *mapping, uint32_t offset, uint32_t *bytes, const void **data);
    ve_err (*unmap)(void *mapping, const void *data);
    ve_err (*freeze)(void *mapping);
} ve_mapping_if;

typedef struct {
    uint16_t format_version;           /* 0x0520 */
    const char *broker_info;           /* broker.hdr text, minus Apple's defaultvoice: line */
    const ve_heap_if *heap_if;
    void *heap;
    const ve_critsec_if *critsec_if;
    void *critsec_class;
    const ve_stream_if *stream_if;
    const ve_mapping_if *mapping_if;
    void *data_class;
    const void *log_if;
    void *log;
} ve_install;

/* ---- parameters ------------------------------------------------------------ */

enum {
    VE_PARAM_FREQUENCY = 1,     /* kHz: 22 */
    VE_PARAM_VOLUME = 2,        /* 0..100 */
    VE_PARAM_SPEECHRATE = 3,    /* 50..400, 100 normal */
    VE_PARAM_PITCH = 4,         /* 50..200, 100 normal */
    VE_PARAM_LANGUAGE = 7,      /* string, broker <language> */
    VE_PARAM_VOICE = 8,         /* string, broker <voice> */
    VE_PARAM_TYPE_OF_CHAR = 12, /* 1: UTF-16 */
    VE_PARAM_VOICE_MODEL = 19,  /* string, broker <voicemodel>: bet3, full_155mrf22 */
};

typedef struct {
    uint32_t id;
    union {
        uint16_t value;
        char string[128];
    } u;
} ve_param;

/* ---- output ---------------------------------------------------------------- */

enum {
    VE_MSG_END = 2,
    VE_MSG_OUTBUFREQ = 8,
    VE_MSG_OUTBUFDONE = 16,
};

enum {
    VE_MRK_WORD = 2,
    VE_MRK_PHONEME = 4,
};

typedef struct {
    uint32_t reserved;
    uint32_t type;         /* VE_MRK_* */
    uint32_t src_pos;      /* bytes into the input text */
    uint32_t src_len;      /* bytes */
    uint32_t dest_pos;     /* samples from the start of the utterance */
    uint32_t dest_len;
    uint16_t value;        /* the phoneme, for VE_MRK_PHONEME */
    uint8_t pad[22];
} ve_mark;

typedef struct {
    uint32_t reserved;
    uint32_t pcm_bytes;    /* request: capacity; done: bytes written */
    void *pcm;
    uint32_t marks;        /* request: capacity in bytes; done: count */
    ve_mark *mark_list;
} ve_outdata;

typedef struct {
    uint32_t message;      /* VE_MSG_* */
    void *param;           /* ve_outdata * for OUTBUFREQ and OUTBUFDONE */
} ve_callback_msg;

typedef ve_err (*ve_out_notify)(ve_handle instance, void *user, ve_callback_msg *msg);

typedef struct {
    void *user;
    ve_out_notify notify;
} ve_outdevice;

typedef struct {
    uint32_t format;       /* 0: plain text */
    uint32_t bytes;
    const void *text;      /* in VE_PARAM_TYPE_OF_CHAR's encoding */
} ve_intext;

/* ---- entry points ---------------------------------------------------------- */

ve_err ve_ttsInitialize(const ve_install *install, ve_handle *speech);
ve_err ve_ttsUnInitialize(ve_handle speech);
ve_err ve_ttsOpen(ve_handle speech, void *heap, void *log, ve_handle *instance, void *reserved);
ve_err ve_ttsClose(ve_handle instance);
ve_err ve_ttsSetParamList(ve_handle instance, const ve_param *params, uint32_t count);
ve_err ve_ttsSetOutDevice(ve_handle instance, const ve_outdevice *device);
ve_err ve_ttsProcessText2Speech(ve_handle instance, const ve_intext *text);
ve_err ve_ttsStop(ve_handle instance);
ve_err ve_ttsResourceLoad(ve_handle instance, const char *mime, uint32_t size,
                          const void *data, ve_handle *resource);

#endif
