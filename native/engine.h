#ifndef APPLETIZER_ENGINE_H
#define APPLETIZER_ENGINE_H

#include <stddef.h>
#include <stdint.h>

typedef struct atz_engine atz_engine;

typedef struct {
    char language[128];   /* broker <language>, e.g. "American English" */
    char voice[128];      /* broker <voice>, e.g. "Samantha" */
    char model[64];       /* broker <voicemodel>, e.g. "bet3" */
    int sample_rate;      /* broker <frequencyhz> */
} atz_voice_info;

/* Return nonzero to stop synthesis. */
typedef int (*atz_pcm_cb)(void *user, const int16_t *pcm, size_t samples);
/* A word starts at sample_pos; [start, end) are UTF-16 indices into the text. */
typedef void (*atz_word_cb)(void *user, uint32_t start, uint32_t end, uint32_t sample_pos);
typedef void (*atz_log_cb)(const char *msg);

void atz_set_log(atz_log_cb log);

/*
 * voice_dir holds broker.hdr and the voice's .dat files; common_dir holds the
 * language-independent ones (lid.dat). Either may be shared between engines.
 */
atz_engine *atz_open(const char *voice_dir, const char *common_dir, char *err, size_t errlen);
const atz_voice_info *atz_info(const atz_engine *e);
/* rate 50..400, pitch 50..200, volume 0..100; 100/100/100 is neutral. */
int atz_set_prosody(atz_engine *e, int rate, int pitch, int volume);
/* Blocks until the text is spoken or atz_stop is called. 0, or the engine error. */
uint32_t atz_speak(atz_engine *e, const uint16_t *text, size_t len,
                   atz_pcm_cb on_pcm, atz_word_cb on_word, void *user);
/* Safe from any thread while atz_speak runs. */
void atz_stop(atz_engine *e);
void atz_close(atz_engine *e);

#endif
