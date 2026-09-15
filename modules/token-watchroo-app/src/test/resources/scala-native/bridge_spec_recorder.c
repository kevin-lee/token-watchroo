/*
 * Test-only envelope recorder for BridgeSpec.
 *
 * Bridge.send calls tw_test_recorder_record while the worker thread is in the Unmanaged GC state, so the recorder is
 * written in C: it copies each JSON string into malloc memory reached through ctx and touches no Scala memory.
 *
 * It also keeps the FNV-1a 64 hash of each string as it arrived and again after the pause, so the test can tell
 * whether a string was corrupted before the callback, during it, or after it was recorded.
 */
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define TW_TEST_RECORDER_INITIAL_CAPACITY 65536
#define TW_TEST_RECORDER_INITIAL_HASHES 256

typedef struct {
    pthread_mutex_t lock;
    char *data;
    size_t length;
    size_t capacity;
    uint64_t *hashes;
    uint64_t *exit_hashes;
    size_t hash_capacity;
    size_t count;
    int failed;
} tw_test_recorder;

void *tw_test_recorder_new(void) {
    tw_test_recorder *recorder = calloc(1, sizeof(tw_test_recorder));
    if (recorder == NULL) {
        return NULL;
    }
    recorder->data = malloc(TW_TEST_RECORDER_INITIAL_CAPACITY);
    recorder->hashes = malloc(TW_TEST_RECORDER_INITIAL_HASHES * sizeof(uint64_t));
    recorder->exit_hashes = malloc(TW_TEST_RECORDER_INITIAL_HASHES * sizeof(uint64_t));
    if (recorder->data == NULL || recorder->hashes == NULL || recorder->exit_hashes == NULL ||
        pthread_mutex_init(&recorder->lock, NULL) != 0) {
        free(recorder->data);
        free(recorder->hashes);
        free(recorder->exit_hashes);
        free(recorder);
        return NULL;
    }
    recorder->capacity = TW_TEST_RECORDER_INITIAL_CAPACITY;
    recorder->hash_capacity = TW_TEST_RECORDER_INITIAL_HASHES;
    return recorder;
}

void tw_test_recorder_free(void *ctx) {
    tw_test_recorder *recorder = ctx;
    if (recorder == NULL) {
        return;
    }
    pthread_mutex_destroy(&recorder->lock);
    free(recorder->data);
    free(recorder->hashes);
    free(recorder->exit_hashes);
    free(recorder);
}

static uint64_t tw_test_recorder_fnv1a(const char *bytes, size_t size) {
    uint64_t hash = 14695981039346656037ULL;
    for (size_t i = 0; i < size; i++) {
        hash ^= (unsigned char)bytes[i];
        hash *= 1099511628211ULL;
    }
    return hash;
}

/* The callback given to Bridge.make. The pause after unlocking keeps the thread Unmanaged in the callback long enough
 * for the forced collections to land while callbacks run. The string is hashed again after the pause, so the test can
 * tell whether it changed while the callback ran. */
// TODO: REVIEWME: It should be reviewed by Kevin.
static void tw_test_recorder_record(const char *json, void *ctx) {
    tw_test_recorder *recorder = ctx;
    pthread_mutex_lock(&recorder->lock);
    size_t size = strlen(json);
    uint64_t hash = tw_test_recorder_fnv1a(json, size);
    size_t needed = recorder->length + size + 1;
    size_t capacity = recorder->capacity;
    while (capacity < needed) {
        capacity *= 2;
    }
    char *data = capacity == recorder->capacity ? recorder->data : realloc(recorder->data, capacity);
    if (data != NULL) {
        recorder->data = data;
        recorder->capacity = capacity;
    }
    size_t hash_capacity =
        recorder->count < recorder->hash_capacity ? recorder->hash_capacity : recorder->hash_capacity * 2;
    uint64_t *hashes = hash_capacity == recorder->hash_capacity
                           ? recorder->hashes
                           : realloc(recorder->hashes, hash_capacity * sizeof(uint64_t));
    uint64_t *exit_hashes = hash_capacity == recorder->hash_capacity
                                ? recorder->exit_hashes
                                : realloc(recorder->exit_hashes, hash_capacity * sizeof(uint64_t));
    if (hashes != NULL) {
        recorder->hashes = hashes;
    }
    if (exit_hashes != NULL) {
        recorder->exit_hashes = exit_hashes;
    }
    if (hashes != NULL && exit_hashes != NULL) {
        recorder->hash_capacity = hash_capacity;
    }
    size_t index = recorder->count;
    int recorded = 0;
    if (data == NULL || hashes == NULL || exit_hashes == NULL) {
        recorder->failed = 1;
    } else {
        memcpy(data + recorder->length, json, size);
        data[recorder->length + size] = '\n';
        recorder->length = needed;
        hashes[index] = hash;
        exit_hashes[index] = hash;
        recorder->count += 1;
        recorded = 1;
    }
    pthread_mutex_unlock(&recorder->lock);
    struct timespec pause = {0, 1000000L};
    nanosleep(&pause, NULL);
    if (recorded) {
        uint64_t exit_hash = tw_test_recorder_fnv1a(json, strlen(json));
        pthread_mutex_lock(&recorder->lock);
        recorder->exit_hashes[index] = exit_hash;
        pthread_mutex_unlock(&recorder->lock);
    }
}

void (*tw_test_recorder_callback(void))(const char *, void *) {
    return &tw_test_recorder_record;
}

size_t tw_test_recorder_count(void *ctx) {
    tw_test_recorder *recorder = ctx;
    pthread_mutex_lock(&recorder->lock);
    size_t count = recorder->count;
    pthread_mutex_unlock(&recorder->lock);
    return count;
}

size_t tw_test_recorder_length(void *ctx) {
    tw_test_recorder *recorder = ctx;
    pthread_mutex_lock(&recorder->lock);
    size_t length = recorder->length;
    pthread_mutex_unlock(&recorder->lock);
    return length;
}

int tw_test_recorder_failed(void *ctx) {
    tw_test_recorder *recorder = ctx;
    pthread_mutex_lock(&recorder->lock);
    int failed = recorder->failed;
    pthread_mutex_unlock(&recorder->lock);
    return failed;
}

size_t tw_test_recorder_copy(void *ctx, char *dest, size_t capacity) {
    tw_test_recorder *recorder = ctx;
    pthread_mutex_lock(&recorder->lock);
    size_t size = recorder->length < capacity ? recorder->length : capacity;
    memcpy(dest, recorder->data, size);
    pthread_mutex_unlock(&recorder->lock);
    return size;
}

size_t tw_test_recorder_copy_hashes(void *ctx, uint64_t *dest, size_t capacity) {
    tw_test_recorder *recorder = ctx;
    pthread_mutex_lock(&recorder->lock);
    size_t count = recorder->count < capacity ? recorder->count : capacity;
    memcpy(dest, recorder->hashes, count * sizeof(uint64_t));
    pthread_mutex_unlock(&recorder->lock);
    return count;
}

size_t tw_test_recorder_copy_exit_hashes(void *ctx, uint64_t *dest, size_t capacity) {
    tw_test_recorder *recorder = ctx;
    pthread_mutex_lock(&recorder->lock);
    size_t count = recorder->count < capacity ? recorder->count : capacity;
    memcpy(dest, recorder->exit_hashes, count * sizeof(uint64_t));
    pthread_mutex_unlock(&recorder->lock);
    return count;
}
