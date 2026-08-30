#ifndef ZOMDROID_HOST_TEST_JNI_H
#define ZOMDROID_HOST_TEST_JNI_H

#include <stdint.h>

#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL

typedef int32_t jint;
typedef void *jclass;

struct JNINativeInterface_;
typedef const struct JNINativeInterface_ *JNIEnv;

struct JNINativeInterface_ {
    jclass (*FindClass)(JNIEnv *env, const char *name);
    jint (*ThrowNew)(JNIEnv *env, jclass type, const char *message);
};

#endif
