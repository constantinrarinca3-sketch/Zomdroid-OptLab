#include <dlfcn.h>
#include <android/dlext.h>
#include <pthread.h>
#include <string.h>
#include <unistd.h>
#include <wait.h>
#include "errno.h"
#include "zomdroid_globals.h"
#include <android/native_window.h>
#include "android_linker_ns.h"
#include <malloc.h>
#include <stdlib.h>
#include <sys/system_properties.h>
#include <bits/stdatomic.h>
#include <sys/sysinfo.h>
#include <asm-generic/fcntl.h>
#include <stdio.h>
#include <signal.h>
#include <ucontext.h>
#include <fcntl.h>
#include <poll.h>
#include <unwind.h>
#include "logger.h"

#define LOG_TAG "zomdroid-main"

struct android_namespace_t* zomdroid_ns;

JavaVM* g_zomdroid_art_vm;
JavaVM* g_zomdroid_jvm;
__thread JNIEnv* g_zomdroid_jni_env;
jvmtiEnv* g_zomdroid_jvmti_env;
jobject g_zomdroid_main_class_loader;
const char* g_zomdroid_vulkan_driver_name;

ZomdroidSurface g_zomdroid_surface = {.mutex = PTHREAD_MUTEX_INITIALIZER,
                                      .ready_for_destroy_cond = PTHREAD_COND_INITIALIZER};

bool g_zomdroid_surface_gen_ack = false;

Renderer g_zomdroid_renderer;

ZomdroidEventQueue g_zomdroid_event_queue = {.mutex = PTHREAD_MUTEX_INITIALIZER};
bool g_zomdroid_input_mutex_safe = false;
bool g_zomdroid_input_analog_filter = false;
bool g_zomdroid_input_coalesce = false;

static long get_mem_available_mb() {
    FILE* f = fopen("/proc/meminfo", "r");
    if (!f) return -1;

    char line[256];
    long memAvailableKb = -1;

    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "MemAvailable: %ld kB", &memAvailableKb) == 1) {
            break;
        }
    }
    fclose(f);

    return (memAvailableKb > 0) ? (memAvailableKb / 1024) : -1;
}

// Absolute path to a persistent file that mirrors the game's native stdout/stderr
// (box64 SHOWSEGV/SHOWBT reports, NG [NGG] probes, etc). Unlike logcat it survives the
// crash and app restarts, so diagnostic output always reaches the Bug Report zip.
static char g_native_log_path[1024] = {0};
static int g_abort_marker_fd = -1;

__attribute__((noreturn))
static void monitor_stdio_and_memory() {
    int pipefd[2];
    char buffer[8192];
    int native_logfd = -1;

    if (pipe(pipefd) == -1) {
        LOGE("Failed to create pipe for monitoring stdio");
        abort();
    }

    const char* stdio_mode = getenv("ZOMDROID_STDIO_MODE");
    const bool buffered_mode = stdio_mode && strcmp(stdio_mode, "BUFFERED") == 0;
    static char stdout_buffer[64 * 1024];
    static char stderr_buffer[64 * 1024];
    if (buffered_mode) {
        setvbuf(stdout, stdout_buffer, _IOFBF, sizeof(stdout_buffer));
        setvbuf(stderr, stderr_buffer, _IOFBF, sizeof(stderr_buffer));
    } else {
        setvbuf(stdout, NULL, _IONBF, 0);
        setvbuf(stderr, NULL, _IONBF, 0);
    }

    dup2(pipefd[1], STDOUT_FILENO);
    dup2(pipefd[1], STDERR_FILENO);
    close(pipefd[1]);

    fcntl(pipefd[0], F_SETFL, O_NONBLOCK);

    if (g_native_log_path[0])
        native_logfd = open(g_native_log_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);

    time_t last_mem_check = 0;
    time_t last_mem_log = 0;
    time_t last_stdio_summary = 0;
    unsigned long stdio_bytes = 0;
    unsigned long stdio_reads = 0;

    LOGI("[ZD-OPT-LAB] native stdio mode=%s",
         buffered_mode ? "BUFFERED" : "LEGACY");

    while (1) {
        if (buffered_mode) {
            struct pollfd pfd = {.fd = pipefd[0], .events = POLLIN};
            (void)poll(&pfd, 1, 250);
        }
        ssize_t i = read(pipefd[0], buffer, sizeof(buffer) - 1);
        if (i > 0) {
            if (native_logfd >= 0) (void)write(native_logfd, buffer, (size_t)i); // persist raw before strtok
            stdio_bytes += (unsigned long)i;
            stdio_reads++;
            if (!buffered_mode) {
                buffer[i] = '\0';
                // Legacy mode mirrors each line to logcat for diagnostic parity.
                char* saveptr;
                char* line = strtok_r(buffer, "\n", &saveptr);
                while (line) {
                    LOGI("%s", line);
                    line = strtok_r(NULL, "\n", &saveptr);
                }
            }
        }

        time_t now = time(NULL);
        if ((now - last_mem_check >= 1) && (now - last_mem_log >= 30)) {
            last_mem_check = now;

            long free_mb = get_mem_available_mb();
            if (free_mb != -1 && free_mb < 300) {
                last_mem_log = now;
                LOGW("Low memory: only %ld MB available", free_mb);
            }
        }

        if (buffered_mode && now - last_stdio_summary >= 30) {
            last_stdio_summary = now;
            LOGI("[ZD-OPT-LAB] stdio bytes=%lu reads=%lu", stdio_bytes, stdio_reads);
        }

        if (!buffered_mode)
            usleep(10000);
    }
}

void zomdroid_set_art_vm(void* vm) {
    g_zomdroid_art_vm = vm;
}

static void handle_abort(int sig) {
    // Async-signal-safe only: never attach JNI, allocate, lock, show UI, or pause forever here.
    static const char marker[] = "ZomDroid received SIGABRT; inspect native.log and log.txt\n";
    if (g_abort_marker_fd >= 0)
        (void)write(g_abort_marker_fd, marker, sizeof(marker) - 1);

    struct sigaction action = {0};
    action.sa_handler = SIG_DFL;
    sigemptyset(&action.sa_mask);
    sigaction(sig, &action, NULL);
    kill(getpid(), sig);
    _exit(128 + sig);
}

static void create_jvm_and_launch_main(int jvm_argc, const char** jvm_argv, const char* main_class_name, int argc, const char** argv) {
    void* libjvm = linkernsbypass_namespace_dlopen("libjvm.so", RTLD_GLOBAL, zomdroid_ns);
    if (libjvm == NULL) {
        LOGE("%s", dlerror());
        return;
    }

    jint(*JNI_CreateJavaVM)(JavaVM**, void**, void*) = dlsym(libjvm, "JNI_CreateJavaVM");

    JavaVM* jvm;
    JNIEnv* env;
    JavaVMInitArgs vm_args;
    JavaVMOption options[jvm_argc];
    if (jvm_argv != NULL) {
        for (int i = 0; i < jvm_argc; i++) {
            options[i].optionString = (char*) jvm_argv[i];
        }
    }
    vm_args.version = JNI_VERSION_1_6;
    vm_args.options = options;
    vm_args.nOptions = jvm_argc;
    vm_args.ignoreUnrecognized = JNI_FALSE;

    jint res = JNI_CreateJavaVM(&jvm, (void**)&env, &vm_args);
    if (res != JNI_OK) {
        LOGE("Failed to create JVM, error code: %d", res);
        return;
    }

    jvmtiEnv* jvmtiEnv = NULL;
    res = (*jvm)->GetEnv(jvm, (void**)&jvmtiEnv, JVMTI_VERSION_11);
    if (res != JNI_OK) {
        LOGE("Failed to create JVM TI connection, error code: %d", res);
        return;
    }
    g_zomdroid_jvmti_env = jvmtiEnv;

    jvmtiError err;
//    jvmtiCapabilities potentialCaps;
//    err = (*jvmtiEnv)->GetPotentialCapabilities(jvmtiEnv, &potentialCaps);
//    if (err != JVMTI_ERROR_NONE) {
//        LOGE("Failed to get potential capabilities for JVM TI env, error code: %d", err);
//        return;
//    }
//    if (!potentialCaps.can_generate_native_method_bind_events) {
//        LOGE("JVM TI env doesn't have a required potential capability: can_generate_native_method_bind_events");
//        return;
//    }
//
//    jvmtiCapabilities caps = { 0 };
//    caps.can_generate_native_method_bind_events = 1;
//    err = (*jvmtiEnv)->AddCapabilities(jvmtiEnv, &caps);
//    if (err != JVMTI_ERROR_NONE) {
//        LOGE("Failed to add necessary capabilities to JVM TI env, error code: %d", err);
//        return;
//    }
//
//
//    jvmtiEventCallbacks callbacks;
//    callbacks.NativeMethodBind = &onNativeMethodBind;
//    err = (*jvmtiEnv)->SetEventCallbacks(jvmtiEnv, &callbacks, sizeof(callbacks));
//    if (err != JVMTI_ERROR_NONE) {
//        LOGE("Failed to set event callbacks for JVM TI env, error code: %d", err);
//        return;
//    }
//
//    err = (*jvmtiEnv)->SetEventNotificationMode(jvmtiEnv, JVMTI_ENABLE, JVMTI_EVENT_NATIVE_METHOD_BIND, NULL);
//    if (err != JVMTI_ERROR_NONE) {
//        LOGE("Failed to enable NATIVE_METHOD_BIND event for JVM TI env, error code: %d", err);
//        return;
//    }

    g_zomdroid_jvm = jvm;

    jclass main_class = (*env)->FindClass(env, main_class_name);
    if (main_class == NULL) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env); // покажет UnsupportedClassVersionError или ClassNotFoundException
            (*env)->ExceptionClear(env);
        }
        LOGE("Failed to load main class");
        goto FINISH;
    }

    jobject classLoader = NULL;
    if ((err = (*jvmtiEnv)->GetClassLoader(jvmtiEnv, main_class, &classLoader)) != JVMTI_ERROR_NONE) {
        LOGE("GetClassLoader() failed, error code %d", err);
        goto FINISH;
    }
    g_zomdroid_main_class_loader = (*env)->NewGlobalRef(env, classLoader);

    jmethodID main_method = (*env)->GetStaticMethodID(env, main_class, "main", "([Ljava/lang/String;)V");
    if (main_method == NULL) {
        LOGE("Failed to locate main method");
        goto FINISH;
    }

    jobjectArray main_class_args = (*env)->NewObjectArray(env, argc, (*env)->FindClass(env, "java/lang/String"), NULL);

    if (argv != NULL) {
        for (int i = 0; i < argc; i++) {
            jstring arg_string = (*env)->NewStringUTF(env, argv[i]);
            (*env)->SetObjectArrayElement(env, main_class_args, i, arg_string);
        }
    }

    (*env)->CallStaticVoidMethod(env, main_class, main_method, main_class_args);

    FINISH:
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        abort();
    }

    LOGW("JNI: leaving create_jvm_and_launch_main() WITHOUT DestroyJavaVM");

}

static int init_zomdroid_namespace(const char* ld_library_path) {
    if (!linkernsbypass_load_status()) {
        LOGE("linkernsbypass is not loaded");
        return -1;
    }

    zomdroid_ns = android_create_namespace("zomdroid-ns", ld_library_path, ld_library_path,
                                           ANDROID_NAMESPACE_TYPE_SHARED,
                                           NULL, NULL);
    return 0;
}

static int load_linker_hook() {
    void* zomdroid_linker = linkernsbypass_namespace_dlopen("libzomdroidlinker.so", RTLD_LOCAL, zomdroid_ns);
    if (!zomdroid_linker) {
        LOGE("%s", dlerror());
        return -1;
    }
    void (*zomdroid_linker_set_proc_addrs)(void*, void*, void*) =
            dlsym(zomdroid_linker, "zomdroid_linker_set_proc_addrs");
    int (*zomdroid_linker_init)() =
            dlsym(zomdroid_linker, "zomdroid_linker_init");
    void (*zomdroid_linker_set_vulkan_loader_handle)(void*) =
            dlsym(zomdroid_linker, "zomdroid_linker_set_vulkan_loader_handle");
    void (*zomdroid_linker_set_vulkan_driver_handle)(void*) =
            dlsym(zomdroid_linker, "zomdroid_linker_set_vulkan_driver_handle");
    if (!zomdroid_linker_init || !zomdroid_linker_set_proc_addrs ||
            !zomdroid_linker_set_vulkan_loader_handle || !zomdroid_linker_set_vulkan_driver_handle) {
        dlerror();
        LOGE("Failed to locate symbols for libzomdroidlinker.so");
        return -1;
    }

    void* libdl = dlopen("libdl.so", RTLD_LAZY);
    if (!libdl) {
        LOGE("%s", dlerror());
        return -1;
    }
    void* _loader_dlopen_fn = dlsym(libdl, "__loader_dlopen");
    void* _loader_dlsym_fn = dlsym(libdl, "__loader_dlsym");
    void* _loader_android_dlopen_ext_fn = dlsym(libdl, "__loader_android_dlopen_ext");
    if (!_loader_dlopen_fn || !_loader_dlsym_fn || ! _loader_android_dlopen_ext_fn) {
        dlclose(libdl);
        LOGE("Failed to locate symbols for libdl.so");
        return -1;
    }

    zomdroid_linker_set_proc_addrs(_loader_dlopen_fn, _loader_dlsym_fn, _loader_android_dlopen_ext_fn);
    if (zomdroid_linker_init() != 0 ) {
        LOGE("Failed to initialise zomdroid linker");
        return -1;
    }

    if (g_zomdroid_vulkan_driver_name != NULL) {
        void* vulkan_loader = linkernsbypass_namespace_dlopen_unique("/system/lib64/libvulkan.so",
                                                               getenv("ZOMDROID_CACHE_DIR"), RTLD_LOCAL, zomdroid_ns);
        if (!vulkan_loader) {
            LOGE("%s", dlerror());
            return -1;
        }
        zomdroid_linker_set_vulkan_loader_handle(vulkan_loader);

        void* vulkan_driver = linkernsbypass_namespace_dlopen(g_zomdroid_vulkan_driver_name, RTLD_LOCAL, zomdroid_ns);
        if (!vulkan_driver) {
            LOGE("%s", dlerror());
            return -1;
        }
        zomdroid_linker_set_vulkan_driver_handle(vulkan_driver);
    }

    return 0;
}

// ---------------------------------------------------------------------------
// Native crash handler.
// The JVM only reports SIGSEGV; box64 clears its own handlers; SIGBUS/SIGILL/
// SIGFPE otherwise go to SIG_DFL and kill the process silently (no hs_err, no
// backtrace) — which is exactly what we saw on the Dimensity NG_GL4ES crash.
// This handler catches those, resolves the fault PC / address to a .so name +
// offset via dladdr, and writes crash.txt into the game dir (bundled by the
// log export). Then it re-raises so the process still dies as before.
// ---------------------------------------------------------------------------
static char g_crash_path[1024] = {0};
static char g_crash_altstack[64 * 1024];

static void cw_str(int fd, const char* s) {
    if (!s) return;
    size_t n = 0; while (s[n]) n++;
    (void)write(fd, s, n);
}

static void cw_hex(int fd, unsigned long v) {
    char buf[18];
    buf[0] = '0'; buf[1] = 'x';
    for (int i = 0; i < 16; i++) {
        int nyb = (int)((v >> ((15 - i) * 4)) & 0xf);
        buf[2 + i] = (char)(nyb < 10 ? ('0' + nyb) : ('a' + nyb - 10));
    }
    (void)write(fd, buf, 18);
}

static void cw_addr(int fd, const char* label, void* addr) {
    cw_str(fd, label);
    cw_hex(fd, (unsigned long)addr);
    Dl_info info;
    if (addr && dladdr(addr, &info) && info.dli_fname) {
        cw_str(fd, "  ");
        cw_str(fd, info.dli_fname);
        cw_str(fd, "+");
        cw_hex(fd, (unsigned long)addr - (unsigned long)info.dli_fbase);
        if (info.dli_sname) { cw_str(fd, " ("); cw_str(fd, info.dli_sname); cw_str(fd, ")"); }
    }
    cw_str(fd, "\n");
}

typedef struct { int fd; int count; int max; } cw_bt_ctx;

static _Unwind_Reason_Code cw_bt_cb(struct _Unwind_Context* ctx, void* arg) {
    cw_bt_ctx* b = (cw_bt_ctx*)arg;
    if (b->count >= b->max) return _URC_END_OF_STACK;
    void* pc = (void*)_Unwind_GetIP(ctx);
    if (pc) {
        cw_str(b->fd, "  #");
        cw_hex(b->fd, (unsigned long)b->count);
        cw_addr(b->fd, " ", pc);
    }
    b->count++;
    return _URC_NO_REASON;
}

static void zomdroid_crash_handler(int sig, siginfo_t* si, void* uctx) {
    int fd = open(g_crash_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        cw_str(fd, "=== ZOMDROID NATIVE CRASH ===\n");
        cw_str(fd, "signal: ");
        cw_str(fd, sig == SIGSEGV ? "SIGSEGV" :
                   sig == SIGBUS  ? "SIGBUS"  :
                   sig == SIGILL  ? "SIGILL"  :
                   sig == SIGFPE  ? "SIGFPE"  : "OTHER");
        cw_str(fd, "\n");
        cw_addr(fd, "fault_addr: ", si ? si->si_addr : (void*)0);
        if (uctx) {
            ucontext_t* uc = (ucontext_t*)uctx;
            cw_addr(fd, "pc:         ", (void*)uc->uc_mcontext.pc);
        }
        cw_str(fd, "backtrace:\n");
        cw_bt_ctx b = { fd, 0, 32 };
        _Unwind_Backtrace(cw_bt_cb, &b);
        cw_str(fd, "=== END ===\n");
        close(fd);
    }
    // restore default disposition and re-raise so the process dies as before
    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_crash_handler(const char* game_dir_path) {
    snprintf(g_crash_path, sizeof(g_crash_path), "%s/crash.txt", game_dir_path);
    // Drop any stale dump from a previous run so an old crash isn't exported as fresh.
    unlink(g_crash_path);

    stack_t ss = {0};
    ss.ss_sp = g_crash_altstack;
    ss.ss_size = sizeof(g_crash_altstack);
    ss.ss_flags = 0;
    sigaltstack(&ss, NULL);

    struct sigaction sa = {0};
    sa.sa_sigaction = zomdroid_crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    // NOTE: SIGSEGV is deliberately NOT taken here — it is left to Android's debuggerd
    // (see the signal loop above) so a full tombstone is produced. We only back up the
    // signals that would otherwise die silently.
    sigaction(SIGBUS,  &sa, NULL);
    sigaction(SIGILL,  &sa, NULL);
    sigaction(SIGFPE,  &sa, NULL);

    LOGI("crash handler installed (SIGBUS/SIGILL/SIGFPE) -> %s ; SIGSEGV left to debuggerd", g_crash_path);
}

void zomdroid_start_game(const char* game_dir_path, const char* library_dir_path, int jvm_argc,
                         const char** jvm_argv, const char* main_class_name, int argc, const char** argv) {

    char abort_marker_path[1024];
    snprintf(abort_marker_path, sizeof(abort_marker_path), "%s/zomdroid-abort.txt", game_dir_path);
    unlink(abort_marker_path);
    g_abort_marker_fd = open(abort_marker_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);

    struct sigaction abort_action = {0};
    abort_action.sa_handler = handle_abort;
    sigemptyset(&abort_action.sa_mask);
    abort_action.sa_flags = SA_RESETHAND;
    sigaction(SIGABRT, &abort_action, NULL);

    // Persist native stdout/stderr to <game>/native.log so box64/NG diagnostic output
    // survives crashes and restarts and reaches the exported Bug Report.
    snprintf(g_native_log_path, sizeof(g_native_log_path), "%s/native.log", game_dir_path);

    pthread_t logging_thread;
    if (pthread_create(&logging_thread, NULL, (void *(*)(void *)) &monitor_stdio_and_memory, NULL) != 0) {
        LOGW("Failed to create stdout logging thread");
    } else {
        pthread_detach(logging_thread);
    }

    if (init_zomdroid_namespace(library_dir_path) != 0) {
        LOGE("Failed to initialize zomdroid namespace");
        return;
    }

    if (load_linker_hook() != 0) {
        LOGE("Failed to load linker hook");
        return;
    }

    if (chdir(game_dir_path) != 0) {
        LOGE("Failed to change cwd with error: %s", strerror(errno));
        return;
    }

    // We keep our SIGABRT dialog; clear other handlers possibly set by box64.
    // DIAG: leave SIGSEGV at its DEFAULT disposition (do NOT set SIG_IGN). Previously
    // SIG_IGN swallowed every real fault the JVM chained down (UseSignalChaining=true),
    // so driver-thread crashes died silently. With SIG_DFL, Android's debuggerd writes a
    // full tombstone (lib + offset + all-thread backtrace) to logcat -> lastlog.txt.
    struct sigaction sa = { 0 };
    for(int sig = SIGHUP; sig < NSIG; sig++) {
        if(sig == SIGABRT) continue;
        sa.sa_handler = SIG_DFL;   // includes SIGSEGV -> default -> debuggerd tombstone
        sigaction(sig, &sa, NULL);
    }

    // Backup catcher for the signals debuggerd-vs-JVM don't cover here: SIGBUS/SIGILL/SIGFPE
    // -> crash.txt. SIGSEGV is intentionally left to debuggerd (see above).
    install_crash_handler(game_dir_path);

    create_jvm_and_launch_main(jvm_argc, jvm_argv, main_class_name, argc, argv);
}


void zomdroid_deinit() {
    LOGI("[ZD-OPT-LAB-END] input_enqueued=%lu input_dropped=%lu input_coalesced=%lu"
         " surface_generation=%llu surface_ack=%llu",
         atomic_load_explicit(&g_zomdroid_event_queue.enqueued, memory_order_relaxed),
         atomic_load_explicit(&g_zomdroid_event_queue.dropped, memory_order_relaxed),
         atomic_load_explicit(&g_zomdroid_event_queue.coalesced, memory_order_relaxed),
         (unsigned long long)g_zomdroid_surface.generation,
         (unsigned long long)g_zomdroid_surface.acknowledged_generation);
}

int zomdroid_init() {
    const char* surface_mode = getenv("ZOMDROID_SURFACE_MODE");
    g_zomdroid_surface_gen_ack = surface_mode && strcmp(surface_mode, "GEN_ACK") == 0;
    const char* input_mode = getenv("ZOMDROID_INPUT_QUEUE");
    g_zomdroid_input_mutex_safe = input_mode && strcmp(input_mode, "MUTEX_SAFE") == 0;
    g_zomdroid_input_analog_filter = getenv("ZOMDROID_INPUT_ANALOG_FILTER")
            && strcmp(getenv("ZOMDROID_INPUT_ANALOG_FILTER"), "1") == 0;
    g_zomdroid_input_coalesce = getenv("ZOMDROID_INPUT_COALESCE")
            && strcmp(getenv("ZOMDROID_INPUT_COALESCE"), "1") == 0;
    LOGI("[ZD-OPT-LAB] surface=%s input=%s analog=%d coalesce=%d",
         g_zomdroid_surface_gen_ack ? "GEN_ACK" : "LEGACY",
         g_zomdroid_input_mutex_safe ? "MUTEX_SAFE" : "LEGACY",
         g_zomdroid_input_analog_filter, g_zomdroid_input_coalesce);

    const char* renderer_name = getenv("ZOMDROID_RENDERER");
    if (renderer_name == NULL) {
        LOGE("Renderer env var is not set");
        exit(1);
    } else if (strcmp(renderer_name, "ZINK_ZFA") == 0) {
        g_zomdroid_renderer = ZINK_ZFA;
    } else if (strcmp(renderer_name, "ZINK_OSMESA") == 0) {
        g_zomdroid_renderer = ZINK_OSMESA;
    } else if (strcmp(renderer_name, "GL4ES") == 0) {
        g_zomdroid_renderer = GL4ES;
    } else if (strcmp(renderer_name, "NG_GL4ES") == 0) {
        g_zomdroid_renderer = NG_GL4ES;
    } else if (strcmp(renderer_name, "MOBILEGL_PZCOMPAT") == 0) {
        g_zomdroid_renderer = MOBILEGL_PZCOMPAT;
    } else {
        LOGE("Unrecognized renderer %s", renderer_name);
        exit(1);
    }
    g_zomdroid_vulkan_driver_name = getenv("ZOMDROID_VULKAN_DRIVER_NAME");
    return 0;
}

/*void jvm_pause_all_threads() {
    jint thread_count;
    jthread* threads;
    jvmtiEnv* jvmti = g_zomdroid_jvmti_env;
    jvmtiError err;
    if ((err = (*jvmti)->GetAllThreads(jvmti, &thread_count, &threads)) != JVMTI_ERROR_NONE) {
        LOGE("GetAllThreads() failed, error code: %d", err);
        return;
    }
    (*jvmti)->SuspendThreadList(jvmti, thread_count, threads, &err);
    if (err != JVMTI_ERROR_NONE) {
        LOGE("SuspendThreadList() failed, error code: %d", err);
        return;
    }
}
void jvm_resume_all_threads() {
    jint thread_count;
    jthread* threads;
    jvmtiEnv* jvmti = g_zomdroid_jvmti_env;
    jvmtiError err;
    if ((err = (*jvmti)->GetAllThreads(jvmti, &thread_count, &threads)) != JVMTI_ERROR_NONE) {
        LOGE("GetAllThreads() failed, error code: %d", err);
        return;
    }
    (*jvmti)->ResumeThreadList(jvmti, thread_count, threads, &err);
    if (err != JVMTI_ERROR_NONE) {
        LOGE("SuspendThreadList() failed, error code: %d", err);
        return;
    }
}*/

void zomdroid_surface_deinit() {
    ANativeWindow* release_without_render = NULL;
    ANativeWindow* superseded_pending = NULL;
    pthread_mutex_lock(&g_zomdroid_surface.mutex);

    if (g_zomdroid_surface_gen_ack && !g_zomdroid_surface.is_used) {
        release_without_render = g_zomdroid_surface.native_window;
        g_zomdroid_surface.native_window = NULL;
        g_zomdroid_surface.width = 0;
        g_zomdroid_surface.height = 0;
        g_zomdroid_surface.refresh_rate = 0.0f;
        pthread_mutex_unlock(&g_zomdroid_surface.mutex);
        if (release_without_render) ANativeWindow_release(release_without_render);
        return;
    }

    if (g_zomdroid_surface_gen_ack
            && g_zomdroid_surface.is_dirty
            && g_zomdroid_surface.generation > g_zomdroid_surface.acknowledged_generation) {
        // The render thread clears is_dirty while holding this mutex before taking ownership.
        // If it is still set, this reference has not crossed the handoff boundary.
        superseded_pending = g_zomdroid_surface.native_window;
    }
    g_zomdroid_surface.native_window = NULL;
    g_zomdroid_surface.width = 0;
    g_zomdroid_surface.height = 0;
    g_zomdroid_surface.refresh_rate = 0.0f;

    if (g_zomdroid_surface_gen_ack) {
        uint64_t requested_generation = ++g_zomdroid_surface.generation;
        if (g_zomdroid_surface.is_used)
            g_zomdroid_surface.is_dirty = true;

        if (g_zomdroid_surface.is_used) {
            struct timespec deadline;
            clock_gettime(CLOCK_REALTIME, &deadline);
            deadline.tv_nsec += 750L * 1000L * 1000L;
            if (deadline.tv_nsec >= 1000000000L) {
                deadline.tv_sec++;
                deadline.tv_nsec -= 1000000000L;
            }
            int rc = 0;
            while (g_zomdroid_surface.acknowledged_generation < requested_generation
                    && rc != ETIMEDOUT) {
                rc = pthread_cond_timedwait(&g_zomdroid_surface.ready_for_destroy_cond,
                                            &g_zomdroid_surface.mutex, &deadline);
            }
            if (g_zomdroid_surface.acknowledged_generation < requested_generation) {
                LOGW("[ZD-OPT-LAB] surface ACK timeout generation=%llu ack=%llu",
                     (unsigned long long)requested_generation,
                     (unsigned long long)g_zomdroid_surface.acknowledged_generation);
            }
        }
        pthread_mutex_unlock(&g_zomdroid_surface.mutex);
        if (superseded_pending) ANativeWindow_release(superseded_pending);
        return;
    }

    if (g_zomdroid_surface.is_used)
        g_zomdroid_surface.is_dirty = true;

    if (g_zomdroid_surface.is_used)
        pthread_cond_wait(&g_zomdroid_surface.ready_for_destroy_cond, &g_zomdroid_surface.mutex);

    pthread_mutex_unlock(&g_zomdroid_surface.mutex);
}

void zomdroid_surface_init(ANativeWindow* wnd, int width, int height, float refresh_rate) {
    const char* surface_mode = getenv("ZOMDROID_SURFACE_MODE");
    if (surface_mode && strcmp(surface_mode, "GEN_ACK") == 0)
        g_zomdroid_surface_gen_ack = true;

    ANativeWindow* superseded_pending = NULL;
    pthread_mutex_lock(&g_zomdroid_surface.mutex);

    if (g_zomdroid_surface.native_window != NULL && g_zomdroid_surface.native_window != wnd) {
        LOGW("Called init on already initialized surface");
    }
    if (g_zomdroid_surface_gen_ack
            && g_zomdroid_surface.is_dirty
            && g_zomdroid_surface.generation > g_zomdroid_surface.acknowledged_generation) {
        // The render thread clears is_dirty under the same mutex before copying the reference.
        superseded_pending = g_zomdroid_surface.native_window;
    }
    g_zomdroid_surface.native_window = wnd;
    g_zomdroid_surface.width = width;
    g_zomdroid_surface.height = height;
    g_zomdroid_surface.refresh_rate = refresh_rate;

    if (g_zomdroid_surface_gen_ack)
        g_zomdroid_surface.generation++;

    if (g_zomdroid_surface.is_used)
        g_zomdroid_surface.is_dirty = true;

    pthread_mutex_unlock(&g_zomdroid_surface.mutex);
    if (superseded_pending) ANativeWindow_release(superseded_pending);
}

static float g_last_axis_state[16];
static bool g_last_axis_valid[16];

static void note_input_drop(void) {
    unsigned long dropped = atomic_fetch_add_explicit(&g_zomdroid_event_queue.dropped, 1,
                                                       memory_order_relaxed) + 1;
    if ((dropped & 63UL) == 1UL)
        LOGW("[ZD-OPT-LAB] input queue dropped=%lu", dropped);
}

static void enqueue_zomdroid_event(const ZomdroidEvent* event) {
    if (g_zomdroid_input_mutex_safe) {
        pthread_mutex_lock(&g_zomdroid_event_queue.mutex);
        unsigned int head = g_zomdroid_event_queue.safe_head;
        unsigned int tail = g_zomdroid_event_queue.safe_tail;

        if (g_zomdroid_input_coalesce && head != tail) {
            ZomdroidEvent* latest = &g_zomdroid_event_queue.buffer[head];
            bool replace = event->type == CURSOR_POS && latest->type == CURSOR_POS;
            replace = replace || (event->type == JOYSTICK_AXIS
                    && latest->type == JOYSTICK_AXIS
                    && latest->joystickAxis.axis == event->joystickAxis.axis);
            if (replace) {
                *latest = *event;
                atomic_fetch_add_explicit(&g_zomdroid_event_queue.coalesced, 1,
                                          memory_order_relaxed);
                pthread_mutex_unlock(&g_zomdroid_event_queue.mutex);
                return;
            }
        }

        unsigned int next = (head + 1U) & EVENT_QUEUE_MAX;
        if (next == tail) {
            pthread_mutex_unlock(&g_zomdroid_event_queue.mutex);
            note_input_drop();
            return;
        }
        g_zomdroid_event_queue.buffer[next] = *event;
        g_zomdroid_event_queue.safe_head = next; // payload is published while holding the mutex
        atomic_fetch_add_explicit(&g_zomdroid_event_queue.enqueued, 1, memory_order_relaxed);
        pthread_mutex_unlock(&g_zomdroid_event_queue.mutex);
        return;
    }

    // Exact legacy path retained for A/B. It claims a slot before filling the payload;
    // MUTEX_SAFE is the corrected publication protocol.
    u_char head;
    u_char next;
    do {
        head = atomic_load_explicit(&g_zomdroid_event_queue.head, memory_order_relaxed);
        u_char tail = atomic_load_explicit(&g_zomdroid_event_queue.tail, memory_order_acquire);
        next = (head + 1) & EVENT_QUEUE_MAX;
        if (next == tail) {
            note_input_drop();
            return;
        }
    } while (!atomic_compare_exchange_weak_explicit(&g_zomdroid_event_queue.head,
                                                     &head, next,
                                                     memory_order_acquire,
                                                     memory_order_relaxed));
    g_zomdroid_event_queue.buffer[next] = *event;
    atomic_thread_fence(memory_order_release);
    atomic_fetch_add_explicit(&g_zomdroid_event_queue.enqueued, 1, memory_order_relaxed);
}

void zomdroid_event_keyboard(int key, bool isPressed) {
    ZomdroidEvent event = {.type = KEYBOARD};
    event.keyboard.key = key;
    event.keyboard.is_pressed = isPressed;
    enqueue_zomdroid_event(&event);
}

void zomdroid_event_char(unsigned int codepoint) {
    ZomdroidEvent event = {.type = CHAR_INPUT};
    event.charInput.codepoint = codepoint;
    enqueue_zomdroid_event(&event);
}

void zomdroid_event_cursor_pos(double x, double y) {
    ZomdroidEvent event = {.type = CURSOR_POS};
    event.cursorPos.x = x;
    event.cursorPos.y = y;
    enqueue_zomdroid_event(&event);
}

void zomdroid_event_mouse_button(int button, bool isPressed) {
    ZomdroidEvent event = {.type = MOUSE_BUTTON};
    event.mouseButton.button = button;
    event.mouseButton.is_pressed = isPressed;
    enqueue_zomdroid_event(&event);
}

void zomdroid_event_mouse_scroll(double xoffset, double yoffset) {
    ZomdroidEvent event = {.type = MOUSE_SCROLL};
    event.mouseScroll.xoffset = xoffset;
    event.mouseScroll.yoffset = yoffset;
    enqueue_zomdroid_event(&event);
}

void zomdroid_event_joystick_connected() {
    ZomdroidEvent event = {.type = JOYSTICK_CONNECTED};
    event.joystickConnected.joystick_name = "Zomdroid Controller";
    event.joystickConnected.joystick_guid = "00000000000000000000000000000000";
    event.joystickConnected.axis_count = 6;
    event.joystickConnected.button_count = 11;
    event.joystickConnected.hat_count = 1;
    enqueue_zomdroid_event(&event);
}

void zomdroid_event_joystick_axis(int axis, float state) {
    if (g_zomdroid_input_analog_filter && axis >= 0 && axis < 16) {
        pthread_mutex_lock(&g_zomdroid_event_queue.mutex);
        float delta = g_last_axis_state[axis] - state;
        if (delta < 0.0f) delta = -delta;
        bool neutral_transition = state == 0.0f && g_last_axis_state[axis] != 0.0f;
        if (g_last_axis_valid[axis] && delta < 0.0005f && !neutral_transition) {
            atomic_fetch_add_explicit(&g_zomdroid_event_queue.coalesced, 1,
                                      memory_order_relaxed);
            pthread_mutex_unlock(&g_zomdroid_event_queue.mutex);
            return;
        }
        g_last_axis_valid[axis] = true;
        g_last_axis_state[axis] = state;
        pthread_mutex_unlock(&g_zomdroid_event_queue.mutex);
    }
    ZomdroidEvent event = {.type = JOYSTICK_AXIS};
    event.joystickAxis.axis = axis;
    event.joystickAxis.state = state;
    enqueue_zomdroid_event(&event);
}

void zomdroid_event_joystick_dpad(int dpad, char state) {
    ZomdroidEvent event = {.type = JOYSTICK_DPAD};
    event.joystickDpad.dpad = dpad;
    event.joystickDpad.state = state;
    enqueue_zomdroid_event(&event);
}

void zomdroid_event_joystick_button(int button, bool is_pressed) {
    ZomdroidEvent event = {.type = JOYSTICK_BUTTON};
    event.joystickButton.button = button;
    event.joystickButton.is_pressed = is_pressed;
    enqueue_zomdroid_event(&event);
}
