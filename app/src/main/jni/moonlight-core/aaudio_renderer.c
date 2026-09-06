#include "aaudio_renderer.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/system_properties.h>
#include <time.h>
#include <sys/resource.h>
#include <unistd.h>

#define TAG "MoonlightAAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static pthread_mutex_t lifecycleLock = PTHREAD_MUTEX_INITIALIZER;

static AAudioStream* stream;
static int channels;
static int sampleRate;
static int samplesPerFrame;
static int framesPerBurst;
static int maxPendingMs = 40;

// Single-producer (AudioDec thread) / single-consumer (AAudio callback) ring of interleaved frames.
// Indices grow monotonically; position = index % ringFrames.
static int16_t* ring;
static int ringFrames;
static int highWaterFrames;
static _Atomic uint32_t readIdx;
static _Atomic uint32_t writeIdx;

static _Atomic bool active;
static _Atomic bool started;
static _Atomic bool producing;
static _Atomic bool priorityApplied;
static _Atomic int lastLoggedUnderruns;
static _Atomic int lastLoggedDropped;
static int64_t lastStatsLogNs;
static _Atomic bool needReopen;
static _Atomic int underruns;
static _Atomic int dropped;
static _Atomic int reopens;

static int minInt(int a, int b) { return a < b ? a : b; }
static int maxInt(int a, int b) { return a > b ? a : b; }

static aaudio_data_callback_result_t dataCallback(AAudioStream* s, void* userData, void* audioData, int32_t numFrames) {
    (void)s; (void)userData;
    int16_t* out = (int16_t*)audioData;
    uint32_t r = atomic_load_explicit(&readIdx, memory_order_relaxed);
    uint32_t w = atomic_load_explicit(&writeIdx, memory_order_acquire);
    int avail = (int)(w - r);
    int n = minInt(avail, numFrames);
    if (n > 0) {
        int pos = (int)(r % (uint32_t)ringFrames);
        int first = minInt(n, ringFrames - pos);
        memcpy(out, ring + (size_t)pos * channels, (size_t)first * channels * sizeof(int16_t));
        if (n > first) {
            memcpy(out + (size_t)first * channels, ring, (size_t)(n - first) * channels * sizeof(int16_t));
        }
        atomic_store_explicit(&readIdx, r + (uint32_t)n, memory_order_release);
    }
    if (n < numFrames) {
        memset(out + (size_t)n * channels, 0, (size_t)(numFrames - n) * channels * sizeof(int16_t));
        if (atomic_load(&producing)) {
            atomic_fetch_add(&underruns, 1);
        }
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void errorCallback(AAudioStream* s, void* userData, aaudio_result_t error) {
    (void)s; (void)userData;
    // Typical cause: HDMI/ARC or Bluetooth route change. Never touch the stream from here.
    LOGW("AAudio stream error %d (%s); will reopen on the next write", error, AAudio_convertResultToText(error));
    atomic_store(&needReopen, true);
}

// lifecycleLock must be held
static int openStreamLocked(void) {
    AAudioStreamBuilder* builder = NULL;
    aaudio_result_t res = AAudio_createStreamBuilder(&builder);
    if (res != AAUDIO_OK) {
        LOGW("AAudio_createStreamBuilder failed: %d", res);
        return res;
    }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    switch (channels) {
        case 2: AAudioStreamBuilder_setChannelMask(builder, AAUDIO_CHANNEL_STEREO); break;
        case 4: AAudioStreamBuilder_setChannelMask(builder, AAUDIO_CHANNEL_QUAD); break;
        case 6: AAudioStreamBuilder_setChannelMask(builder, AAUDIO_CHANNEL_5POINT1); break;
        case 8: AAudioStreamBuilder_setChannelMask(builder, AAUDIO_CHANNEL_7POINT1); break;
        default: AAudioStreamBuilder_setChannelCount(builder, channels); break;
    }
    // Exclusive MMAP is not available over HDMI on TVs and would steal the device from everything else
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setDataCallback(builder, dataCallback, NULL);
    AAudioStreamBuilder_setErrorCallback(builder, errorCallback, NULL);

    res = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (res != AAUDIO_OK) {
        LOGW("AAudioStreamBuilder_openStream failed: %d (%s)", res, AAudio_convertResultToText(res));
        stream = NULL;
        return res;
    }
    if (AAudioStream_getChannelCount(stream) != channels || AAudioStream_getSampleRate(stream) != sampleRate ||
        AAudioStream_getFormat(stream) != AAUDIO_FORMAT_PCM_I16) {
        LOGW("AAudio gave ch=%d rate=%d format=%d instead of ch=%d rate=%d I16; not using it",
             AAudioStream_getChannelCount(stream), AAudioStream_getSampleRate(stream), AAudioStream_getFormat(stream),
             channels, sampleRate);
        AAudioStream_close(stream);
        stream = NULL;
        return -2;
    }
    framesPerBurst = maxInt(AAudioStream_getFramesPerBurst(stream), 1);
    // Three bursts (12 ms at 192-frame bursts): two underran audibly on the C8K with the ring alone
    AAudioStream_setBufferSizeInFrames(stream, framesPerBurst * 3);
    LOGI("AAudio open: ch=%d rate=%d burst=%d buffer=%d capacity=%d perf=%d sharing=%d",
         channels, sampleRate, framesPerBurst, AAudioStream_getBufferSizeInFrames(stream),
         AAudioStream_getBufferCapacityInFrames(stream), AAudioStream_getPerformanceMode(stream),
         AAudioStream_getSharingMode(stream));
    return 0;
}

int AAudioRenderer_Setup(int channelCount, int rate, int spf, int maxPending) {
    char prop[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.moonlight.aaudio", prop) > 0 && strcmp(prop, "0") == 0) {
        LOGI("AAudio disabled by debug.moonlight.aaudio=0");
        return -100;
    }
    pthread_mutex_lock(&lifecycleLock);
    channels = channelCount;
    sampleRate = rate;
    samplesPerFrame = spf;
    maxPendingMs = maxPending > 0 ? maxPending : 40;
    atomic_store(&needReopen, false);
    atomic_store(&started, false);
    atomic_store(&producing, false);
    atomic_store(&priorityApplied, false);
    atomic_store(&lastLoggedUnderruns, 0);
    atomic_store(&lastLoggedDropped, 0);
    lastStatsLogNs = 0;
    atomic_store(&underruns, 0);
    atomic_store(&dropped, 0);
    atomic_store(&reopens, 0);
    int res = openStreamLocked();
    if (res != 0) {
        pthread_mutex_unlock(&lifecycleLock);
        return res;
    }
    ringFrames = maxInt(16 * samplesPerFrame, 8 * framesPerBurst);
    // Latency bound for the ring itself (~40 ms at 48 kHz); the common-c queue is bounded separately
    highWaterFrames = 4 * framesPerBurst + 4 * samplesPerFrame;
    ring = (int16_t*)calloc((size_t)ringFrames * channels, sizeof(int16_t));
    if (ring == NULL) {
        AAudioStream_close(stream);
        stream = NULL;
        pthread_mutex_unlock(&lifecycleLock);
        return -3;
    }
    atomic_store(&readIdx, 0);
    atomic_store(&writeIdx, 0);
    atomic_store(&active, true);
    pthread_mutex_unlock(&lifecycleLock);
    return 0;
}

int AAudioRenderer_Start(void) {
    pthread_mutex_lock(&lifecycleLock);
    int res = -1;
    if (stream != NULL) {
        res = AAudioStream_requestStart(stream);
        if (res == AAUDIO_OK) {
            atomic_store(&started, true);
        } else {
            LOGW("AAudioStream_requestStart failed: %d", res);
        }
    }
    pthread_mutex_unlock(&lifecycleLock);
    return res;
}

void AAudioRenderer_Stop(void) {
    pthread_mutex_lock(&lifecycleLock);
    atomic_store(&started, false);
    if (stream != NULL) {
        AAudioStream_requestStop(stream);
    }
    pthread_mutex_unlock(&lifecycleLock);
}

void AAudioRenderer_Cleanup(void) {
    pthread_mutex_lock(&lifecycleLock);
    atomic_store(&active, false);
    atomic_store(&started, false);
    if (stream != NULL) {
        // Synchronous: no callback runs after this returns
        AAudioStream_close(stream);
        stream = NULL;
    }
    free(ring);
    ring = NULL;
    ringFrames = 0;
    pthread_mutex_unlock(&lifecycleLock);
    LOGI("AAudio closed: underruns=%d dropped=%d reopens=%d", atomic_load(&underruns), atomic_load(&dropped), atomic_load(&reopens));
}

bool AAudioRenderer_IsActive(void) {
    return atomic_load(&active);
}

int AAudioRenderer_GetMaxPendingMs(void) {
    return maxPendingMs;
}

static void reopenLocked(void) {
    if (stream != NULL) {
        AAudioStream_close(stream);
        stream = NULL;
    }
    atomic_store(&readIdx, 0);
    atomic_store(&writeIdx, 0);
    atomic_store(&producing, false);
    if (openStreamLocked() == 0 && atomic_load(&started)) {
        AAudioStream_requestStart(stream);
    }
    atomic_fetch_add(&reopens, 1);
    atomic_store(&needReopen, false);
}

static int64_t nowNs(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

void AAudioRenderer_Write(const int16_t* frames, int frameCount) {
    if (!atomic_load(&active) || frameCount <= 0) {
        return;
    }
    if (!atomic_load(&priorityApplied)) {
        // This is moonlight-common-c's AudioDec thread; give it audio priority so 5 ms packets are not
        // decoded late on a loaded TV SoC (Android lets a process raise its own threads this far)
        atomic_store(&priorityApplied, true);
        if (setpriority(PRIO_PROCESS, (id_t)gettid(), -16) != 0) {
            LOGW("Could not raise the audio decode thread priority");
        }
    }
    int64_t t = nowNs();
    if (t - lastStatsLogNs > 10000000000LL) {
        lastStatsLogNs = t;
        int u = atomic_load(&underruns), d = atomic_load(&dropped);
        // Quiet while the stream is clean; one line per 10 s window in which the counters moved
        if (u != atomic_load(&lastLoggedUnderruns) || d != atomic_load(&lastLoggedDropped)) {
            LOGI("AAudio stats: underruns +%d (total %d), dropped +%d (total %d), queued %d frames",
                 u - atomic_load(&lastLoggedUnderruns), u, d - atomic_load(&lastLoggedDropped), d,
                 (int)(atomic_load(&writeIdx) - atomic_load(&readIdx)));
            atomic_store(&lastLoggedUnderruns, u);
            atomic_store(&lastLoggedDropped, d);
        }
    }
    if (atomic_load(&needReopen)) {
        pthread_mutex_lock(&lifecycleLock);
        if (atomic_load(&active) && atomic_load(&needReopen)) {
            reopenLocked();
        }
        pthread_mutex_unlock(&lifecycleLock);
        if (stream == NULL) {
            return;
        }
    }
    uint32_t r = atomic_load_explicit(&readIdx, memory_order_acquire);
    uint32_t w = atomic_load_explicit(&writeIdx, memory_order_relaxed);
    int used = (int)(w - r);
    if (used >= highWaterFrames || used + frameCount > ringFrames) {
        // The HAL is behind (route change, stall): bound the latency by dropping this packet
        atomic_fetch_add(&dropped, 1);
        return;
    }
    int pos = (int)(w % (uint32_t)ringFrames);
    int first = minInt(frameCount, ringFrames - pos);
    memcpy(ring + (size_t)pos * channels, frames, (size_t)first * channels * sizeof(int16_t));
    if (frameCount > first) {
        memcpy(ring, frames + (size_t)first * channels, (size_t)(frameCount - first) * channels * sizeof(int16_t));
    }
    atomic_store_explicit(&writeIdx, w + (uint32_t)frameCount, memory_order_release);
    atomic_store(&producing, true);
}

void AAudioRenderer_CountDroppedPacket(void) {
    atomic_fetch_add(&dropped, 1);
}

void AAudioRenderer_GetStats(int stats[4]) {
    int latencyMs = -1;
    pthread_mutex_lock(&lifecycleLock);
    if (stream != NULL && sampleRate > 0) {
        uint32_t r = atomic_load(&readIdx);
        uint32_t w = atomic_load(&writeIdx);
        int64_t queued = (int64_t)(w - r);
        int64_t framePos = 0, timeNs = 0;
        int64_t pipeline = AAudioStream_getBufferSizeInFrames(stream);
        if (AAudioStream_getTimestamp(stream, CLOCK_MONOTONIC, &framePos, &timeNs) == AAUDIO_OK) {
            struct timespec now;
            clock_gettime(CLOCK_MONOTONIC, &now);
            int64_t nowNs = (int64_t)now.tv_sec * 1000000000LL + now.tv_nsec;
            int64_t elapsedFrames = (nowNs - timeNs) * sampleRate / 1000000000LL;
            int64_t written = AAudioStream_getFramesWritten(stream);
            pipeline = written - (framePos + elapsedFrames);
            if (pipeline < 0) pipeline = 0;
        }
        latencyMs = (int)((queued + pipeline) * 1000 / sampleRate);
    }
    pthread_mutex_unlock(&lifecycleLock);
    stats[0] = latencyMs;
    stats[1] = atomic_load(&underruns);
    stats[2] = atomic_load(&dropped);
    stats[3] = atomic_load(&reopens);
}

// ---- JNI (com.limelight.binding.audio.AAudioRenderer) ----

JNIEXPORT jint JNICALL
Java_com_limelight_binding_audio_AAudioRenderer_nativeSetup(JNIEnv* env, jclass clazz, jint channelCount, jint rate, jint spf, jint maxPending) {
    (void)env; (void)clazz;
    return AAudioRenderer_Setup(channelCount, rate, spf, maxPending);
}

JNIEXPORT jint JNICALL
Java_com_limelight_binding_audio_AAudioRenderer_nativeStart(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return AAudioRenderer_Start();
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_audio_AAudioRenderer_nativeStop(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    AAudioRenderer_Stop();
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_audio_AAudioRenderer_nativeCleanup(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    AAudioRenderer_Cleanup();
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_audio_AAudioRenderer_nativeIsActive(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return AAudioRenderer_IsActive() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_com_limelight_binding_audio_AAudioRenderer_nativeGetStats(JNIEnv* env, jclass clazz) {
    (void)clazz;
    int stats[4];
    AAudioRenderer_GetStats(stats);
    jintArray arr = (*env)->NewIntArray(env, 4);
    if (arr != NULL) {
        (*env)->SetIntArrayRegion(env, arr, 0, 4, stats);
    }
    return arr;
}
