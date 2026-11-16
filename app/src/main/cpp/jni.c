// Brücke zwischen der Tastatur (Kotlin) und whisper.cpp: Spracherkennung direkt auf dem Gerät.
#include <jni.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"

static void quiet_log(enum ggml_log_level level, const char *text, void *user_data) {
    (void) level; (void) text; (void) user_data;   // keine Ausgaben (auch nicht des erkannten Texts)
}

JNIEXPORT jlong JNICALL
Java_de_schranner_tastatur_WhisperNative_init(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    whisper_log_set(quiet_log, NULL);
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_de_schranner_tastatur_WhisperNative_free(JNIEnv *env, jclass clazz, jlong ptr) {
    (void) env; (void) clazz;
    if (ptr != 0) whisper_free((struct whisper_context *) (intptr_t) ptr);
}

// Gibt den erkannten Text als UTF-8-Bytes zurück (null bei Fehler).
JNIEXPORT jbyteArray JNICALL
Java_de_schranner_tastatur_WhisperNative_transcribe(JNIEnv *env, jclass clazz, jlong ptr, jfloatArray audio,
                                                    jint threads, jstring language, jstring prompt, jint audio_ctx) {
    (void) clazz;
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) ptr;
    if (ctx == NULL || audio == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, audio);
    jfloat *data = (*env)->GetFloatArrayElements(env, audio, NULL);
    const char *lang = (*env)->GetStringUTFChars(env, language, NULL);
    const char *hint = prompt != NULL ? (*env)->GetStringUTFChars(env, prompt, NULL) : NULL;

    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.translate = false;
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = false;
    p.suppress_blank = true;
    p.language = lang;
    p.initial_prompt = (hint != NULL && hint[0] != 0) ? hint : NULL;
    if (audio_ctx > 0) p.audio_ctx = audio_ctx;   // kurze Aufnahmen brauchen nicht das volle 30-Sekunden-Fenster

    int rc = whisper_full(ctx, p, data, n);

    jbyteArray result = NULL;
    if (rc == 0) {
        size_t len = 0;
        int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; i++) len += strlen(whisper_full_get_segment_text(ctx, i));
        char *text = (char *) malloc(len + 1);
        if (text != NULL) {
            text[0] = 0;
            for (int i = 0; i < segments; i++) strcat(text, whisper_full_get_segment_text(ctx, i));
            result = (*env)->NewByteArray(env, (jsize) len);
            if (result != NULL) (*env)->SetByteArrayRegion(env, result, 0, (jsize) len, (const jbyte *) text);
            free(text);
        }
    }
    if (hint != NULL) (*env)->ReleaseStringUTFChars(env, prompt, hint);
    (*env)->ReleaseStringUTFChars(env, language, lang);
    (*env)->ReleaseFloatArrayElements(env, audio, data, JNI_ABORT);
    return result;
}
