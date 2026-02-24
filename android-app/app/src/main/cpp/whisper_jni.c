/*
 * JNI bridge between the Android app and whisper.cpp.
 *
 * Provides init, transcribe, free, and isLoaded functions via JNI.
 * Follows the same pattern as privatemode_jni.c.
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>
#include "whisper.h"

#define TAG "WhisperJNI"

static struct whisper_context *ctx = NULL;

JNIEXPORT jint JNICALL
Java_ai_privatemode_android_whisper_WhisperNative_nativeInit(
    JNIEnv *env, jobject thiz, jstring modelPath) {

    if (ctx != NULL) {
        whisper_free(ctx);
        ctx = NULL;
    }

    const char *path = (*env)->GetStringUTFChars(env, modelPath, NULL);
    struct whisper_context_params cparams = whisper_context_default_params();
    ctx = whisper_init_from_file_with_params(path, cparams);
    (*env)->ReleaseStringUTFChars(env, modelPath, path);

    if (ctx == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to init whisper context");
        return -1;
    }
    return 0;
}

JNIEXPORT jstring JNICALL
Java_ai_privatemode_android_whisper_WhisperNative_nativeTranscribe(
    JNIEnv *env, jobject thiz, jfloatArray samples, jint n_threads) {

    if (ctx == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "transcribe called but context is NULL");
        return (*env)->NewStringUTF(env, "");
    }

    jsize n_samples = (*env)->GetArrayLength(env, samples);
    jfloat *data = (*env)->GetFloatArrayElements(env, samples, NULL);

    int threads = n_threads > 0 ? n_threads : 2;
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "whisper_full: %d samples (%.1fs audio), %d threads",
        n_samples, (float)n_samples / 16000.0f, threads);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "de";
    params.n_threads = threads;
    params.no_timestamps = true;
    params.print_progress = true;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;

    int ret = whisper_full(ctx, params, data, n_samples);
    (*env)->ReleaseFloatArrayElements(env, samples, data, JNI_ABORT);

    if (ret != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full failed: %d", ret);
        return (*env)->NewStringUTF(env, "");
    }

    int n_segments = whisper_full_n_segments(ctx);
    __android_log_print(ANDROID_LOG_INFO, TAG, "whisper_full done: %d segments", n_segments);
    /* Estimate buffer size: 256 bytes per segment */
    size_t buf_size = (size_t)n_segments * 256 + 1;
    char *buf = (char *)malloc(buf_size);
    buf[0] = '\0';
    size_t offset = 0;

    for (int i = 0; i < n_segments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text == NULL) continue;
        size_t len = strlen(text);
        if (offset + len + 1 >= buf_size) {
            buf_size = offset + len + 256;
            buf = (char *)realloc(buf, buf_size);
        }
        memcpy(buf + offset, text, len);
        offset += len;
    }
    buf[offset] = '\0';

    jstring result = (*env)->NewStringUTF(env, buf);
    free(buf);
    return result;
}

JNIEXPORT void JNICALL
Java_ai_privatemode_android_whisper_WhisperNative_nativeFree(
    JNIEnv *env, jobject thiz) {

    if (ctx != NULL) {
        whisper_free(ctx);
        ctx = NULL;
    }
}

JNIEXPORT jboolean JNICALL
Java_ai_privatemode_android_whisper_WhisperNative_nativeIsLoaded(
    JNIEnv *env, jobject thiz) {

    return ctx != NULL ? JNI_TRUE : JNI_FALSE;
}
