#define _GNU_SOURCE 1

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <limits.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

#define LOG_TAG "ZD-LIGHT-SHIM"
#define LEGACY_NAME "libLightingLegacy64.so"

// 0 = unopened, 1 = one thread is opening, 2 = completed (success or recorded failure).
static atomic_int g_legacy_state;
static void *g_legacy_handle;
static char g_legacy_error[512];
static char g_legacy_path[PATH_MAX];
static atomic_uint_fast64_t g_transmission_calls;

JNIEXPORT jint JNICALL ZomDroidLightingShim_ABI_1(void) {
    return 1;
}

static void log_line(int priority, const char *message) {
    __android_log_print(priority, LOG_TAG, "%s", message);
    static const char prefix[] = "[" LOG_TAG "] ";
    (void) write(STDERR_FILENO, prefix, sizeof(prefix) - 1);
    (void) write(STDERR_FILENO, message, strlen(message));
    (void) write(STDERR_FILENO, "\n", 1);
}

static void open_legacy(void) {
    Dl_info self;
    if (dladdr((void *) &ZomDroidLightingShim_ABI_1, &self) == 0 || self.dli_fname == NULL) {
        snprintf(g_legacy_error, sizeof(g_legacy_error), "dladdr(shim) failed");
        log_line(ANDROID_LOG_ERROR, g_legacy_error);
        return;
    }

    const char *slash = strrchr(self.dli_fname, '/');
    int written;
    if (slash == NULL) {
        written = snprintf(g_legacy_path, sizeof(g_legacy_path), "%s", LEGACY_NAME);
    } else {
        size_t directory_length = (size_t) (slash - self.dli_fname);
        written = snprintf(g_legacy_path, sizeof(g_legacy_path), "%.*s/%s",
                           (int) directory_length, self.dli_fname, LEGACY_NAME);
    }
    if (written < 0 || (size_t) written >= sizeof(g_legacy_path)) {
        snprintf(g_legacy_error, sizeof(g_legacy_error), "legacy library path is too long");
        log_line(ANDROID_LOG_ERROR, g_legacy_error);
        return;
    }

    dlerror();
    g_legacy_handle = dlopen(g_legacy_path, RTLD_NOW | RTLD_LOCAL);
    if (g_legacy_handle == NULL) {
        const char *error = dlerror();
        snprintf(g_legacy_error, sizeof(g_legacy_error), "dlopen(%.200s) failed: %.260s",
                 g_legacy_path, error == NULL ? "unknown error" : error);
        log_line(ANDROID_LOG_ERROR, g_legacy_error);
        return;
    }

    log_line(ANDROID_LOG_WARN,
             "ACTIVE mode=ARM64_LEGACY_NON_PARITY abi=1; RGB torch fields and 42.20 "
             "transmission semantics are unavailable");
}

static void ensure_legacy_open(void) {
    int state = atomic_load_explicit(&g_legacy_state, memory_order_acquire);
    if (state == 2) return;

    int unopened = 0;
    if (atomic_compare_exchange_strong_explicit(&g_legacy_state, &unopened, 1,
                                                 memory_order_acq_rel,
                                                 memory_order_acquire)) {
        open_legacy();
        atomic_store_explicit(&g_legacy_state, 2, memory_order_release);
        return;
    }

    // Initialization is tiny and happens only on the first JNI call. Avoid a platform-specific
    // pthread_once_t layout in this shim; every later call takes the state==2 fast path.
    while (atomic_load_explicit(&g_legacy_state, memory_order_acquire) != 2) {
        atomic_signal_fence(memory_order_acquire);
    }
}

static void throw_link_error(JNIEnv *env, const char *detail) {
    if (env == NULL || (*env)->ExceptionCheck(env)) return;
    jclass error_class = (*env)->FindClass(env, "java/lang/UnsatisfiedLinkError");
    if (error_class != NULL) (*env)->ThrowNew(env, error_class, detail);
}

static void *resolve_cached(_Atomic(void *) *slot, const char *symbol, JNIEnv *env) {
    void *function = atomic_load_explicit(slot, memory_order_acquire);
    if (function != NULL) return function;

    ensure_legacy_open();
    if (g_legacy_handle == NULL) {
        throw_link_error(env, g_legacy_error[0] == '\0'
                             ? "ZomDroid Lighting shim could not load the legacy engine"
                             : g_legacy_error);
        return NULL;
    }

    dlerror();
    function = dlsym(g_legacy_handle, symbol);
    const char *error = dlerror();
    if (function == NULL || error != NULL) {
        char detail[640];
        snprintf(detail, sizeof(detail), "legacy Lighting symbol %s is unavailable: %s",
                 symbol, error == NULL ? "unknown error" : error);
        log_line(ANDROID_LOG_ERROR, detail);
        throw_link_error(env, detail);
        return NULL;
    }

    void *expected = NULL;
    if (!atomic_compare_exchange_strong_explicit(slot, &expected, function,
                                                  memory_order_release,
                                                  memory_order_acquire)) {
        function = expected;
    }
    return function;
}

#define JNI_SYMBOL(name) "Java_zombie_iso_LightingJNI_" #name

#define FORWARD_VOID(name, params, call_args)                                      \
    JNIEXPORT void JNICALL Java_zombie_iso_LightingJNI_##name params {             \
        typedef void (JNICALL *function_type) params;                              \
        static _Atomic(void *) cached;                                             \
        function_type function = (function_type) resolve_cached(                   \
                &cached, JNI_SYMBOL(name), env);                                   \
        if (function != NULL) function call_args;                                  \
    }

#define FORWARD_VALUE(result_type, zero, name, params, call_args)                  \
    JNIEXPORT result_type JNICALL Java_zombie_iso_LightingJNI_##name params {      \
        typedef result_type (JNICALL *function_type) params;                       \
        static _Atomic(void *) cached;                                             \
        function_type function = (function_type) resolve_cached(                   \
                &cached, JNI_SYMBOL(name), env);                                   \
        return function == NULL ? (zero) : function call_args;                     \
    }

FORWARD_VOID(configure,
             (JNIEnv *env, jclass cls, jfloat dark_step),
             (env, cls, dark_step))
FORWARD_VOID(scrollLeft,
             (JNIEnv *env, jclass cls, jint player_index),
             (env, cls, player_index))
FORWARD_VOID(scrollRight,
             (JNIEnv *env, jclass cls, jint player_index),
             (env, cls, player_index))
FORWARD_VOID(scrollUp,
             (JNIEnv *env, jclass cls, jint player_index),
             (env, cls, player_index))
FORWARD_VOID(scrollDown,
             (JNIEnv *env, jclass cls, jint player_index),
             (env, cls, player_index))
FORWARD_VOID(stateBeginUpdate,
             (JNIEnv *env, jclass cls, jint player_index, jint min_x, jint min_y,
              jint width, jint height),
             (env, cls, player_index, min_x, min_y, width, height))
FORWARD_VOID(stateEndFrame,
             (JNIEnv *env, jclass cls, jfloat rmod, jfloat gmod, jfloat bmod,
              jfloat ambient, jfloat night, jfloat view_dist, jfloat view_dist_max,
              jboolean cache_cleared, jfloat light_source_update,
              jint time_of_day_light),
             (env, cls, rmod, gmod, bmod, ambient, night, view_dist, view_dist_max,
              cache_cleared, light_source_update, time_of_day_light))
FORWARD_VOID(stateEndUpdate,
             (JNIEnv *env, jclass cls),
             (env, cls))
FORWARD_VALUE(jint, 0, stateUpdateCounter,
              (JNIEnv *env, jclass cls, jint player_index),
              (env, cls, player_index))
FORWARD_VOID(teleport,
             (JNIEnv *env, jclass cls, jint player_index, jint wx, jint wy),
             (env, cls, player_index, wx, wy))
FORWARD_VOID(DoLightingUpdateNew,
             (JNIEnv *env, jclass cls, jlong nano_time,
              jboolean dirty_global_lighting_tick),
             (env, cls, nano_time, dirty_global_lighting_tick))
FORWARD_VALUE(jboolean, JNI_FALSE, WaitingForMain,
              (JNIEnv *env, jclass cls),
              (env, cls))
FORWARD_VOID(playerSet,
             (JNIEnv *env, jclass cls, jfloat x, jfloat y, jfloat z,
              jfloat angle_x, jfloat angle_y, jboolean is_dead,
              jboolean reanimated_corpse, jboolean ghost_mode,
              jboolean short_sighted, jfloat tired_delta, jfloat n_dist,
              jfloat cone),
             (env, cls, x, y, z, angle_x, angle_y, is_dead, reanimated_corpse,
              ghost_mode, short_sighted, tired_delta, n_dist, cone))
FORWARD_VALUE(jboolean, JNI_FALSE, chunkLightingDone,
              (JNIEnv *env, jclass cls, jint wx, jint wy),
              (env, cls, wx, wy))
FORWARD_VALUE(jboolean, JNI_FALSE, getChunkDirty,
              (JNIEnv *env, jclass cls, jint player_index, jint wx, jint wy,
               jint level),
              (env, cls, player_index, wx, wy, level))
FORWARD_VOID(chunkBeginUpdate,
             (JNIEnv *env, jclass cls, jint wx, jint wy, jint min_level,
              jint max_level),
             (env, cls, wx, wy, min_level, max_level))
FORWARD_VOID(chunkEndUpdate,
             (JNIEnv *env, jclass cls),
             (env, cls))
FORWARD_VOID(chunkLevelBeginUpdate,
             (JNIEnv *env, jclass cls, jint level),
             (env, cls, level))
FORWARD_VOID(chunkLevelEndUpdate,
             (JNIEnv *env, jclass cls),
             (env, cls))
FORWARD_VOID(squareSetNull,
             (JNIEnv *env, jclass cls, jint x, jint y, jint z),
             (env, cls, x, y, z))
FORWARD_VOID(squareBeginUpdate,
             (JNIEnv *env, jclass cls, jint x, jint y, jint z),
             (env, cls, x, y, z))
FORWARD_VOID(squareSet,
             (JNIEnv *env, jclass cls, jint vision_unblocked, jboolean has_up,
              jboolean has_down, jboolean has_elevated_floor, jint vision_matrix,
              jlong building_id, jlong room_id, jint light_level,
              jboolean is_open_air),
             (env, cls, vision_unblocked, has_up, has_down, has_elevated_floor,
              vision_matrix, building_id, room_id, light_level, is_open_air))
FORWARD_VOID(squareAddCurtain,
             (JNIEnv *env, jclass cls, jint wnes, jboolean open),
             (env, cls, wnes, open))
FORWARD_VOID(squareAddDoor,
             (JNIEnv *env, jclass cls, jboolean north, jboolean open,
              jboolean transparent),
             (env, cls, north, open, transparent))
FORWARD_VOID(squareAddThumpable,
             (JNIEnv *env, jclass cls, jboolean north, jboolean open,
              jboolean is_door, jboolean can_pass_through),
             (env, cls, north, open, is_door, can_pass_through))
FORWARD_VOID(squareAddWindow,
             (JNIEnv *env, jclass cls, jboolean north, jboolean open,
              jboolean opaque),
             (env, cls, north, open, opaque))
FORWARD_VOID(squareEndUpdate,
             (JNIEnv *env, jclass cls),
             (env, cls))
FORWARD_VALUE(jint, 0, getVertLight,
              (JNIEnv *env, jclass cls, jint player_index, jint x, jint y,
               jint z, jint index),
              (env, cls, player_index, x, y, z, index))
FORWARD_VALUE(jfloat, 0.0f, getLightInfo,
              (JNIEnv *env, jclass cls, jint player_index, jint x, jint y,
               jint z, jint rgb),
              (env, cls, player_index, x, y, z, rgb))
FORWARD_VALUE(jfloat, 0.0f, getDarkMulti,
              (JNIEnv *env, jclass cls, jint player_index, jint x, jint y,
               jint z),
              (env, cls, player_index, x, y, z))
FORWARD_VALUE(jfloat, 0.0f, getTargetDarkMulti,
              (JNIEnv *env, jclass cls, jint player_index, jint x, jint y,
               jint z),
              (env, cls, player_index, x, y, z))
FORWARD_VALUE(jboolean, JNI_FALSE, getSeen,
              (JNIEnv *env, jclass cls, jint player_index, jint x, jint y,
               jint z),
              (env, cls, player_index, x, y, z))
FORWARD_VALUE(jboolean, JNI_FALSE, getCanSee,
              (JNIEnv *env, jclass cls, jint player_index, jint x, jint y,
               jint z),
              (env, cls, player_index, x, y, z))
FORWARD_VALUE(jboolean, JNI_FALSE, getCouldSee,
              (JNIEnv *env, jclass cls, jint player_index, jint x, jint y,
               jint z),
              (env, cls, player_index, x, y, z))
FORWARD_VALUE(jboolean, JNI_FALSE, getSquareLighting,
              (JNIEnv *env, jclass cls, jint player_index, jint x, jint y,
               jint z, jintArray data),
              (env, cls, player_index, x, y, z, data))
FORWARD_VALUE(jboolean, JNI_FALSE, getSquareDirty,
              (JNIEnv *env, jclass cls, jint player_index, jint x, jint y,
               jint z),
              (env, cls, player_index, x, y, z))
FORWARD_VOID(addLight,
             (JNIEnv *env, jclass cls, jint id, jint x, jint y, jint z,
              jint radius, jfloat r, jfloat g, jfloat b, jint building_id,
              jboolean active),
             (env, cls, id, x, y, z, radius, r, g, b, building_id, active))
FORWARD_VOID(addTempLight,
             (JNIEnv *env, jclass cls, jint id, jint x, jint y, jint z,
              jint radius, jfloat r, jfloat g, jfloat b, jint life),
             (env, cls, id, x, y, z, radius, r, g, b, life))
FORWARD_VOID(removeLight,
             (JNIEnv *env, jclass cls, jint id),
             (env, cls, id))
FORWARD_VOID(setLightActive,
             (JNIEnv *env, jclass cls, jint id, jboolean active),
             (env, cls, id, active))
FORWARD_VOID(setLightColor,
             (JNIEnv *env, jclass cls, jint id, jfloat r, jfloat g, jfloat b),
             (env, cls, id, r, g, b))
FORWARD_VOID(addRoomLight,
             (JNIEnv *env, jclass cls, jint id, jlong building_id, jlong room_id,
              jint x, jint y, jint z, jint width, jint height, jboolean active),
             (env, cls, id, building_id, room_id, x, y, z, width, height, active))
FORWARD_VOID(removeRoomLight,
             (JNIEnv *env, jclass cls, jint id),
             (env, cls, id))
FORWARD_VOID(setRoomLightActive,
             (JNIEnv *env, jclass cls, jint id, jboolean active),
             (env, cls, id, active))

/**
 * Current 42.20 Java ABI:
 *   id, x, y, z, r, g, b, angleX, angleY, dist, strength, cone, dot, focusing
 * Legacy ARM64 ABI:
 *   id, x, y, z,          angleX, angleY, dist, strength, cone, dot, focusing
 *
 * RGB has no representation in the old engine. Dropping it restores the positional ABI and the
 * direction/cone fields, but deliberately marks this B path as a non-parity performance probe.
 */
JNIEXPORT void JNICALL Java_zombie_iso_LightingJNI_updateTorch(
        JNIEnv *env, jclass cls, jint id, jfloat x, jfloat y, jfloat z,
        jfloat r, jfloat g, jfloat b, jfloat angle_x, jfloat angle_y,
        jfloat distance, jfloat strength, jboolean cone, jfloat dot,
        jint focusing) {
    (void) r;
    (void) g;
    (void) b;
    typedef void (JNICALL *function_type)(
            JNIEnv *, jclass, jint, jfloat, jfloat, jfloat, jfloat, jfloat,
            jfloat, jfloat, jboolean, jfloat, jint);
    static _Atomic(void *) cached;
    static atomic_flag logged = ATOMIC_FLAG_INIT;
    function_type function = (function_type) resolve_cached(
            &cached, JNI_SYMBOL(updateTorch), env);
    if (function == NULL) return;
    if (!atomic_flag_test_and_set_explicit(&logged, memory_order_relaxed)) {
        log_line(ANDROID_LOG_WARN,
                 "updateTorch adapter active: dropping current RGB, forwarding legacy "
                 "angle/dist/strength/cone ABI");
    }
    function(env, cls, id, x, y, z, angle_x, angle_y, distance, strength,
             cone, dot, focusing);
}

/** Build 42.20's twenty-float transmission block does not exist in the legacy engine. */
JNIEXPORT void JNICALL Java_zombie_iso_LightingJNI_squareSetLightTransmission(
        JNIEnv *env, jclass cls,
        jfloat f00, jfloat f01, jfloat f02, jfloat f03, jfloat f04,
        jfloat f05, jfloat f06, jfloat f07, jfloat f08, jfloat f09,
        jfloat f10, jfloat f11, jfloat f12, jfloat f13, jfloat f14,
        jfloat f15, jfloat f16, jfloat f17, jfloat f18, jfloat f19) {
    (void) env;
    (void) cls;
    (void) f00; (void) f01; (void) f02; (void) f03; (void) f04;
    (void) f05; (void) f06; (void) f07; (void) f08; (void) f09;
    (void) f10; (void) f11; (void) f12; (void) f13; (void) f14;
    (void) f15; (void) f16; (void) f17; (void) f18; (void) f19;

    uint_fast64_t count = atomic_fetch_add_explicit(
            &g_transmission_calls, 1, memory_order_relaxed) + 1;
    if ((count & (count - 1)) == 0) {
        char detail[192];
        snprintf(detail, sizeof(detail),
                 "NON_PARITY legacy transmission no-op; calls=%llu",
                 (unsigned long long) count);
        log_line(ANDROID_LOG_WARN, detail);
    }
}

FORWARD_VOID(removeTorch,
             (JNIEnv *env, jclass cls, jint id),
             (env, cls, id))
FORWARD_VALUE(jint, 0, getVisibleRoomCount,
              (JNIEnv *env, jclass cls, jint player_index),
              (env, cls, player_index))
FORWARD_VALUE(jint, 0, getVisibleRooms,
              (JNIEnv *env, jclass cls, jint player_index, jlongArray room_ids),
              (env, cls, player_index, room_ids))
FORWARD_VOID(destroy,
             (JNIEnv *env, jclass cls),
             (env, cls))
