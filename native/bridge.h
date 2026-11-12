#ifndef APPLETIZER_BRIDGE_H
#define APPLETIZER_BRIDGE_H

/*
 * An engine library that runs its engine inside an emulator exports these
 * instead of the ve_* API. engine.c looks for atzb_open and, when present,
 * hands the whole voice to the library.
 */
#include "engine.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct atzb atzb;

atzb *atzb_open(const char *voice_dir, const char *common_dir, atz_voice_info *info,
                atz_log_cb log, char *err, size_t errlen);
int atzb_set_prosody(atzb *b, int rate, int pitch);
/* text is already free of surrogates and non-empty. */
uint32_t atzb_speak(atzb *b, const uint16_t *text, size_t len,
                    atz_pcm_cb on_pcm, atz_word_cb on_word, void *user);
void atzb_stop(atzb *b);
void atzb_close(atzb *b);

#ifdef __cplusplus
}
#endif

#endif
