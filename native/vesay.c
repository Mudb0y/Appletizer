/* vesay: speak text with a converted engine into a WAV file, for testing
 * off-device. */
#include "engine.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef struct {
    FILE *f;
    size_t samples;
    double first_audio;
    struct timespec start;
    int words;
    int verbose;
} sink;

static double since(const struct timespec *t0) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double)(t.tv_sec - t0->tv_sec) + (t.tv_nsec - t0->tv_nsec) / 1e9;
}

static int on_pcm(void *user, const int16_t *pcm, size_t n) {
    sink *s = user;
    if (!s->samples) s->first_audio = since(&s->start);
    fwrite(pcm, 2, n, s->f);
    s->samples += n;
    return 0;
}

static void on_word(void *user, uint32_t a, uint32_t b, uint32_t pos) {
    sink *s = user;
    s->words++;
    if (s->verbose) fprintf(stderr, "word [%u,%u) at sample %u\n", a, b, pos);
}

static void put32(unsigned char *p, uint32_t v) { for (int i = 0; i < 4; i++) p[i] = (unsigned char)(v >> (8 * i)); }
static void put16(unsigned char *p, uint16_t v) { p[0] = (unsigned char)v; p[1] = (unsigned char)(v >> 8); }

static void wav_header(FILE *f, int rate, size_t samples) {
    unsigned char h[44];
    memcpy(h, "RIFF", 4); put32(h + 4, (uint32_t)(36 + samples * 2));
    memcpy(h + 8, "WAVEfmt ", 8); put32(h + 16, 16); put16(h + 20, 1); put16(h + 22, 1);
    put32(h + 24, (uint32_t)rate); put32(h + 28, (uint32_t)rate * 2); put16(h + 32, 2); put16(h + 34, 16);
    memcpy(h + 36, "data", 4); put32(h + 40, (uint32_t)(samples * 2));
    fseek(f, 0, SEEK_SET);
    fwrite(h, 1, sizeof h, f);
}

/* UTF-8 to UTF-16, enough for test sentences. */
static size_t utf16(const char *s, uint16_t *out) {
    size_t n = 0;
    const unsigned char *p = (const unsigned char *)s;
    while (*p) {
        uint32_t c = *p++;
        int extra = c >= 0xf0 ? 3 : c >= 0xe0 ? 2 : c >= 0xc0 ? 1 : 0;
        if (extra) c &= 0x3f >> extra;
        while (extra-- && (*p & 0xc0) == 0x80) c = (c << 6) | (*p++ & 0x3f);
        if (c >= 0x10000) {
            c -= 0x10000;
            out[n++] = (uint16_t)(0xd800 | (c >> 10));
            out[n++] = (uint16_t)(0xdc00 | (c & 0x3ff));
        } else {
            out[n++] = (uint16_t)c;
        }
    }
    return n;
}

int main(int argc, char **argv) {
    const char *voice = NULL, *common = NULL, *out = "out.wav", *text = NULL;
    int rate = 100, pitch = 100, verbose = 0;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-v") && i + 1 < argc) voice = argv[++i];
        else if (!strcmp(argv[i], "-c") && i + 1 < argc) common = argv[++i];
        else if (!strcmp(argv[i], "-o") && i + 1 < argc) out = argv[++i];
        else if (!strcmp(argv[i], "-r") && i + 1 < argc) rate = atoi(argv[++i]);
        else if (!strcmp(argv[i], "-p") && i + 1 < argc) pitch = atoi(argv[++i]);
        else if (!strcmp(argv[i], "-V")) verbose = 1;
        else text = argv[i];
    }
    if (!voice || !text) {
        fprintf(stderr, "usage: vesay -v VOICEDIR [-c COMMONDIR] [-o OUT.wav] [-r RATE] [-p PITCH] [-V] TEXT\n");
        return 2;
    }

    char err[512] = "";
    struct timespec t0;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    atz_engine *e = atz_open(voice, common, err, sizeof err);
    if (!e) {
        fprintf(stderr, "vesay: %s\n", err);
        return 1;
    }
    const atz_voice_info *info = atz_info(e);
    fprintf(stderr, "vesay: %s, %s, model %s, %d Hz, opened in %.2fs\n", info->voice,
            info->language, info->model, info->sample_rate, since(&t0));
    if (rate != 100 || pitch != 100) {
        int r = atz_set_prosody(e, rate, pitch);
        if (r) fprintf(stderr, "vesay: prosody: %#x\n", (unsigned)r);
    }

    sink s = { fopen(out, "wb"), 0, 0, {0}, 0, verbose };
    if (!s.f) { perror(out); return 1; }
    wav_header(s.f, info->sample_rate, 0);
    uint16_t *u = malloc((strlen(text) + 1) * 2 * sizeof *u);
    size_t n = utf16(text, u);
    clock_gettime(CLOCK_MONOTONIC, &s.start);
    uint32_t r = atz_speak(e, u, n, on_pcm, on_word, &s);
    double took = since(&s.start);
    wav_header(s.f, info->sample_rate, s.samples);
    fclose(s.f);
    atz_close(e);
    if (r) {
        fprintf(stderr, "vesay: synthesis failed: %#x\n", r);
        return 1;
    }
    fprintf(stderr, "vesay: %zu samples (%.2fs audio) in %.2fs, first audio after %.3fs, %d word marks\n",
            s.samples, (double)s.samples / info->sample_rate, took, s.first_audio, s.words);
    return s.samples ? 0 : 1;
}
