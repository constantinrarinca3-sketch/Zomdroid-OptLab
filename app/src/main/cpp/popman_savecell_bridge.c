#define _GNU_SOURCE

#include <elf.h>
#include <jni.h>
#include <link.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

/*
 * Build 42.20 and 42.20.3 ship the same official ARM64 PopMan payload. The
 * payload contains every ZombiePopulationManager JNI entry except n_saveCell.
 *
 * This bridge reconstructs only ManagerWorker::saveCell(int,int). Function
 * offsets and object layouts below belong exclusively to payload SHA-256:
 *
 *   eec5ac6f8fb60964cbd9b49c1525ac521f52191484439651b48278f5b8b684af
 *
 * The build-id check is deliberately fail-closed. Never make these offsets a
 * generic compatibility table: a new payload must be audited independently.
 */

#define MANAGER_WORKER_INSTANCE_OFFSET       ((uintptr_t)0x261770)
#define MANAGER_MAIN_INSTANCE_OFFSET         ((uintptr_t)0x261b60)
#define GET_CELL_FROM_CELL_POS_OFFSET        ((uintptr_t)0x079b38)
#define GET_CELL_FROM_WORLD_POS_OFFSET       ((uintptr_t)0x07968c)
#define ARRAY_LIST_ZOMBIE_ADD_OFFSET         ((uintptr_t)0x0687ec)
#define CELL_SAVE_OFFSET                     ((uintptr_t)0x064ed8)
#define OBJECT_POOL_ZOMBIE_RELEASE_OFFSET    ((uintptr_t)0x07d87c)

/* ManagerWorker DWARF layout (byte size 416). */
#define WORKER_MIN_X_OFFSET                  ((size_t)0)
#define WORKER_MIN_Y_OFFSET                  ((size_t)4)
#define WORKER_WIDTH_OFFSET                  ((size_t)8)
#define WORKER_HEIGHT_OFFSET                 ((size_t)12)

/* ManagerMain DWARF layout (byte size 328). */
#define MAIN_ZOMBIE_POOL_OFFSET              ((size_t)200)
#define MAIN_SAVE_REAL_ZOMBIE_HACK_OFFSET    ((size_t)256)

/* Cell DWARF layout (byte size 264). */
#define CELL_LOADED_OFFSET                   ((size_t)84)
#define CELL_SAVE_REAL_ZOMBIE_HACK_OFFSET    ((size_t)96)

/* Zombie DWARF layout (byte size 32). */
#define ZOMBIE_X_OFFSET                      ((size_t)0)
#define ZOMBIE_Y_OFFSET                      ((size_t)4)

/* ArrayList<T> inherits the 24-byte libc++ vector representation. */
typedef struct PointerList {
    void **begin;
    void **end;
    void **capacity;
} PointerList;

typedef void *(*get_cell_fn)(void *, int32_t, int32_t);
typedef void (*array_list_add_fn)(void *, void *const *);
typedef void (*cell_save_fn)(void *);
typedef void (*object_pool_release_fn)(void *, void *);

static const unsigned char EXPECTED_BUILD_ID[20] = {
        0x5d, 0x14, 0xd7, 0x7d, 0xb7, 0x65, 0x62, 0x24, 0xcc, 0x78,
        0x11, 0x4e, 0xc5, 0x53, 0xcb, 0x9d, 0x6c, 0x36, 0x33, 0x23
};

static pthread_once_t resolve_once = PTHREAD_ONCE_INIT;
static uintptr_t popman_base;
static const char *resolve_error = "POPMAN_MODULE_NOT_LOADED";

static uintptr_t align4(uintptr_t value) {
    return (value + 3u) & ~(uintptr_t)3u;
}

static int has_expected_build_id(const struct dl_phdr_info *info) {
    for (ElfW(Half) index = 0; index < info->dlpi_phnum; ++index) {
        const ElfW(Phdr) *header = &info->dlpi_phdr[index];
        if (header->p_type != PT_NOTE) continue;

        const unsigned char *cursor = (const unsigned char *)
                (info->dlpi_addr + header->p_vaddr);
        const unsigned char *end = cursor + header->p_memsz;
        while ((size_t)(end - cursor) >= sizeof(ElfW(Nhdr))) {
            const ElfW(Nhdr) *note = (const ElfW(Nhdr) *)cursor;
            cursor += sizeof(ElfW(Nhdr));
            uintptr_t name_size = align4(note->n_namesz);
            uintptr_t desc_size = align4(note->n_descsz);
            if (name_size > (uintptr_t)(end - cursor)) break;
            const unsigned char *name = cursor;
            cursor += name_size;
            if (desc_size > (uintptr_t)(end - cursor)) break;
            const unsigned char *description = cursor;
            cursor += desc_size;

            if (note->n_type == NT_GNU_BUILD_ID && note->n_namesz == 4
                    && note->n_descsz == sizeof(EXPECTED_BUILD_ID)
                    && memcmp(name, "GNU", 4) == 0
                    && memcmp(description, EXPECTED_BUILD_ID,
                            sizeof(EXPECTED_BUILD_ID)) == 0) {
                return 1;
            }
        }
    }
    return 0;
}

static int find_popman(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    (void)data;
    const char *name = info->dlpi_name;
    if (name == NULL) return 0;
    const char *base_name = strrchr(name, '/');
    base_name = base_name == NULL ? name : base_name + 1;
    if (strcmp(base_name, "libPZPopMan64.so") != 0) return 0;
    if (!has_expected_build_id(info)) {
        resolve_error = "POPMAN_BUILD_ID_MISMATCH";
        return 1;
    }
    popman_base = (uintptr_t)info->dlpi_addr;
    resolve_error = NULL;
    return 1;
}

static void resolve_payload(void) {
    dl_iterate_phdr(find_popman, NULL);
}

static void throw_java(JNIEnv *env, const char *class_name, const char *message) {
    jclass error_class = (*env)->FindClass(env, class_name);
    if (error_class != NULL) (*env)->ThrowNew(env, error_class, message);
}

static int32_t read_i32(const void *object, size_t offset) {
    int32_t value;
    memcpy(&value, (const unsigned char *)object + offset, sizeof(value));
    return value;
}

static float read_float(const void *object, size_t offset) {
    float value;
    memcpy(&value, (const unsigned char *)object + offset, sizeof(value));
    return value;
}

/* Matches the floor conversion emitted by the official x86_64 saveCell. */
static int32_t floor_to_i32(float value) {
    int32_t truncated = (int32_t)value;
    return (float)truncated > value ? truncated - 1 : truncated;
}

static int pointer_list_valid(const PointerList *list) {
    uintptr_t begin = (uintptr_t)list->begin;
    uintptr_t end = (uintptr_t)list->end;
    uintptr_t capacity = (uintptr_t)list->capacity;
    if (begin == 0) return end == 0 && capacity == 0;
    return end >= begin && capacity >= end
            && (end - begin) % sizeof(void *) == 0
            && (capacity - begin) % sizeof(void *) == 0;
}

static void clear_pointer_list(PointerList *list) {
    list->end = list->begin;
}

/*
 * Official saveCell always releases ManagerMain::saveRealZombieHack, including
 * for invalid, missing, or unloaded cells. ObjectPool::release is called here
 * through the audited ARM64 implementation instead of cloning libc++ growth
 * internals into this bridge.
 */
static int recycle_pending_zombies(void *manager_main,
                                   object_pool_release_fn release_zombie) {
    PointerList *pending = (PointerList *)((unsigned char *)manager_main
            + MAIN_SAVE_REAL_ZOMBIE_HACK_OFFSET);
    if (!pointer_list_valid(pending)) return 0;

    void **cursor = pending->begin;
    void **end = pending->end;
    void *pool = (unsigned char *)manager_main + MAIN_ZOMBIE_POOL_OFFSET;
    while (cursor != end) {
        release_zombie(pool, *cursor);
        ++cursor;
    }
    clear_pointer_list(pending);
    return 1;
}

JNIEXPORT void JNICALL
Java_zombie_popman_ZombiePopulationManager_n_1saveCell(
        JNIEnv *env, jclass owner, jint cell_x, jint cell_y) {
    (void)owner;
    pthread_once(&resolve_once, resolve_payload);
    if (popman_base == 0 || resolve_error != NULL) {
        throw_java(env, "java/lang/UnsatisfiedLinkError",
                resolve_error == NULL ? "POPMAN_RESOLVE_FAILED" : resolve_error);
        return;
    }

    void *manager_worker = (void *)(popman_base + MANAGER_WORKER_INSTANCE_OFFSET);
    void *manager_main = (void *)(popman_base + MANAGER_MAIN_INSTANCE_OFFSET);
    get_cell_fn get_cell_from_cell =
            (get_cell_fn)(popman_base + GET_CELL_FROM_CELL_POS_OFFSET);
    get_cell_fn get_cell_from_world =
            (get_cell_fn)(popman_base + GET_CELL_FROM_WORLD_POS_OFFSET);
    array_list_add_fn add_zombie =
            (array_list_add_fn)(popman_base + ARRAY_LIST_ZOMBIE_ADD_OFFSET);
    cell_save_fn save_cell = (cell_save_fn)(popman_base + CELL_SAVE_OFFSET);
    object_pool_release_fn release_zombie =
            (object_pool_release_fn)(popman_base + OBJECT_POOL_ZOMBIE_RELEASE_OFFSET);

    PointerList *pending = (PointerList *)((unsigned char *)manager_main
            + MAIN_SAVE_REAL_ZOMBIE_HACK_OFFSET);
    if (!pointer_list_valid(pending)) {
        throw_java(env, "java/lang/IllegalStateException",
                "POPMAN_PENDING_ZOMBIE_LIST_INVALID");
        return;
    }

    int32_t relative_x = (int32_t)cell_x
            - read_i32(manager_worker, WORKER_MIN_X_OFFSET);
    int32_t relative_y = (int32_t)cell_y
            - read_i32(manager_worker, WORKER_MIN_Y_OFFSET);
    int valid_coordinates = relative_x >= 0 && relative_y >= 0
            && relative_x < read_i32(manager_worker, WORKER_WIDTH_OFFSET)
            && relative_y < read_i32(manager_worker, WORKER_HEIGHT_OFFSET);

    void *cell = NULL;
    if (valid_coordinates) {
        cell = get_cell_from_cell(manager_worker, (int32_t)cell_x, (int32_t)cell_y);
    }

    if (cell != NULL
            && *((const unsigned char *)cell + CELL_LOADED_OFFSET) != 0) {
        PointerList *cell_zombies = (PointerList *)((unsigned char *)cell
                + CELL_SAVE_REAL_ZOMBIE_HACK_OFFSET);
        if (!pointer_list_valid(cell_zombies)) {
            throw_java(env, "java/lang/IllegalStateException",
                    "POPMAN_CELL_ZOMBIE_LIST_INVALID");
            return;
        }

        void **cursor = pending->begin;
        void **end = pending->end;
        while (cursor != end) {
            void *zombie = *cursor;
            int32_t world_x = floor_to_i32(read_float(zombie, ZOMBIE_X_OFFSET));
            int32_t world_y = floor_to_i32(read_float(zombie, ZOMBIE_Y_OFFSET));
            if (get_cell_from_world(manager_worker, world_x, world_y) == cell) {
                add_zombie(cell_zombies, &zombie);
            }
            ++cursor;
        }

        save_cell(cell);
        clear_pointer_list(cell_zombies);
    }

    if (!recycle_pending_zombies(manager_main, release_zombie)) {
        throw_java(env, "java/lang/IllegalStateException",
                "POPMAN_PENDING_ZOMBIE_RECYCLE_FAILED");
    }
}
