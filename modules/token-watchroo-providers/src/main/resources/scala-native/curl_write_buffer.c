/*
 * The libcurl write callback for CurlHttp.
 *
 * libcurl calls the write function inside curl_easy_perform, which is @blocking on the Scala side, so the calling
 * thread is in the Unmanaged GC state. Nothing running here may touch the Scala heap. A Scala function passed to C as a
 * CFuncPtr cannot promise that while the optimiser is off: its generated forwarder boxes every Ptr and CSize argument
 * and its body materialises Tag objects on the Scala heap (#41). So the callback is C.
 *
 * Ownership: the buffer is malloc memory owned by the tw_curl_buffer struct. libcurl calls the write function on the
 * thread that runs curl_easy_perform, and every read (length, copy) happens after it returns, on the same thread, so
 * there is no lock.
 *
 * Growth: when the incoming bytes do not fit, the capacity becomes the larger of double the current capacity and the
 * needed size. When realloc fails, the failed flag is set and 0 is returned, which makes libcurl abort the transfer
 * with CURLE_WRITE_ERROR. The old data stays owned by the struct and is released by tw_curl_buffer_free.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define TW_CURL_BUFFER_INITIAL_CAPACITY 8192

typedef struct {
    char *data;
    size_t length;
    size_t capacity;
    int failed;
} tw_curl_buffer;

void *tw_curl_buffer_new(void) {
    tw_curl_buffer *buffer = calloc(1, sizeof(tw_curl_buffer));
    if (buffer == NULL) {
        return NULL;
    }
    buffer->data = malloc(TW_CURL_BUFFER_INITIAL_CAPACITY);
    if (buffer->data == NULL) {
        free(buffer);
        return NULL;
    }
    buffer->capacity = TW_CURL_BUFFER_INITIAL_CAPACITY;
    return buffer;
}

void tw_curl_buffer_free(void *ctx) {
    tw_curl_buffer *buffer = ctx;
    if (buffer == NULL) {
        return;
    }
    free(buffer->data);
    free(buffer);
}

static size_t tw_curl_buffer_write(char *data, size_t size, size_t count, void *userdata) {
    tw_curl_buffer *buffer = userdata;
    if (size != 0 && count > SIZE_MAX / size) {
        buffer->failed = 1;
        return 0;
    }
    size_t incoming = size * count;
    size_t needed = buffer->length + incoming;
    if (needed < buffer->length) {
        buffer->failed = 1;
        return 0;
    }
    if (needed > buffer->capacity) {
        size_t new_capacity = buffer->capacity * 2;
        if (new_capacity < needed) {
            new_capacity = needed;
        }
        char *grown = realloc(buffer->data, new_capacity);
        if (grown == NULL) {
            buffer->failed = 1;
            return 0;
        }
        buffer->data = grown;
        buffer->capacity = new_capacity;
    }
    memcpy(buffer->data + buffer->length, data, incoming);
    buffer->length = needed;
    return incoming;
}

size_t (*tw_curl_buffer_callback(void))(char *, size_t, size_t, void *) {
    return &tw_curl_buffer_write;
}

size_t tw_curl_buffer_length(void *ctx) {
    tw_curl_buffer *buffer = ctx;
    return buffer->length;
}

int tw_curl_buffer_failed(void *ctx) {
    tw_curl_buffer *buffer = ctx;
    return buffer->failed;
}

size_t tw_curl_buffer_copy(void *ctx, char *dest, size_t capacity) {
    tw_curl_buffer *buffer = ctx;
    size_t size = buffer->length < capacity ? buffer->length : capacity;
    memcpy(dest, buffer->data, size);
    return size;
}
