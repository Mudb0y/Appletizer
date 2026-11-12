#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "engine.h"

static void log_android(const char *msg) {
    __android_log_write(ANDROID_LOG_WARN, "Appletizer", msg);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    atz_set_log(log_android);
    return JNI_VERSION_1_6;
}

/* info[0..3] receive voice, language, model and sample rate. */
JNIEXPORT jlong JNICALL
Java_com_mudb0y_appletizer_Engine_open(JNIEnv *env, jclass cls, jstring library, jstring voiceDir,
                                        jstring commonDir, jobjectArray info) {
    (void)cls;
    const char *l = (*env)->GetStringUTFChars(env, library, NULL);
    const char *v = (*env)->GetStringUTFChars(env, voiceDir, NULL);
    const char *c = (*env)->GetStringUTFChars(env, commonDir, NULL);
    char err[512] = "";
    atz_engine *e = atz_open(l, v, c, err, sizeof err);
    (*env)->ReleaseStringUTFChars(env, library, l);
    (*env)->ReleaseStringUTFChars(env, voiceDir, v);
    (*env)->ReleaseStringUTFChars(env, commonDir, c);
    if (!e) {
        jclass ex = (*env)->FindClass(env, "java/io/IOException");
        (*env)->ThrowNew(env, ex, err[0] ? err : "engine failed to open");
        return 0;
    }
    const atz_voice_info *vi = atz_info(e);
    char rate[16];
    snprintf(rate, sizeof rate, "%d", vi->sample_rate);
    const char *fields[4] = { vi->voice, vi->language, vi->model, rate };
    for (int i = 0; i < 4; i++)
        (*env)->SetObjectArrayElement(env, info, i, (*env)->NewStringUTF(env, fields[i]));
    return (jlong)(intptr_t)e;
}

typedef struct {
    JNIEnv *env;
    jobject sink;
    jmethodID audio, word;
    jbyteArray buf;
    jsize cap;
} speak_ctx;

static int on_pcm(void *user, const int16_t *pcm, size_t samples) {
    speak_ctx *s = user;
    JNIEnv *env = s->env;
    jsize bytes = (jsize)(samples * 2);
    if (bytes > s->cap) {
        if (s->buf) (*env)->DeleteLocalRef(env, s->buf);
        s->buf = (*env)->NewByteArray(env, bytes);
        s->cap = bytes;
    }
    /* Little-endian in memory already, which is what AudioFormat wants. */
    (*env)->SetByteArrayRegion(env, s->buf, 0, bytes, (const jbyte *)pcm);
    jboolean go = (*env)->CallBooleanMethod(env, s->sink, s->audio, s->buf, bytes);
    if ((*env)->ExceptionCheck(env)) return 1;
    return !go;
}

static void on_word(void *user, uint32_t start, uint32_t end, uint32_t sample) {
    speak_ctx *s = user;
    if ((*s->env)->ExceptionCheck(s->env)) return;
    (*s->env)->CallVoidMethod(s->env, s->sink, s->word, (jint)start, (jint)end, (jint)sample);
}

JNIEXPORT jint JNICALL
Java_com_mudb0y_appletizer_Engine_speak(JNIEnv *env, jclass cls, jlong handle, jstring text,
                                         jint rate, jint pitch, jobject sink) {
    (void)cls;
    atz_engine *e = (atz_engine *)(intptr_t)handle;
    int r = atz_set_prosody(e, rate, pitch);
    if (r) log_android("setting rate or pitch failed");

    jclass sc = (*env)->GetObjectClass(env, sink);
    speak_ctx s = {
        env, sink,
        (*env)->GetMethodID(env, sc, "audio", "([BI)Z"),
        (*env)->GetMethodID(env, sc, "word", "(III)V"),
        NULL, 0,
    };
    jsize len = (*env)->GetStringLength(env, text);
    const jchar *chars = (*env)->GetStringChars(env, text, NULL);
    uint32_t err = atz_speak(e, chars, (size_t)len, on_pcm, on_word, &s);
    (*env)->ReleaseStringChars(env, text, chars);
    return (jint)err;
}

JNIEXPORT void JNICALL
Java_com_mudb0y_appletizer_Engine_stop(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    atz_stop((atz_engine *)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_com_mudb0y_appletizer_Engine_close(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    atz_close((atz_engine *)(intptr_t)handle);
}
