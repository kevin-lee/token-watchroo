/*
 * Test-only feeder for CurlBufferSpec.
 *
 * Drives the real libcurl write callback from curl_write_buffer.c without libcurl. It is C so that no Scala memory
 * crosses while the thread is Unmanaged: the Scala binding of tw_test_curl_feed is @blocking, so the calling thread is
 * Unmanaged for the whole run, as it is inside curl_easy_perform. The pattern byte at each offset is
 * (offset * 31 + 7) & 0xff, which the Scala test recomputes to check every byte.
 */
#include <stdlib.h>
#include <time.h>

size_t (*tw_curl_buffer_callback(void))(char *, size_t, size_t, void *);

static unsigned char tw_test_curl_pattern(size_t index) {
    return (unsigned char)((index * 31 + 7) & 0xff);
}

static size_t tw_test_curl_chunk_length(size_t i, size_t max_chunk, size_t remaining) {
    size_t len = 1 + (i * 7919) % max_chunk;
    return len < remaining ? len : remaining;
}

/* Feeds total bytes in uneven chunks, alternating (size 1, count len) and (size len, count 1), and pauses pause_nanos
 * after each accepted chunk so that forced collections land while the thread is Unmanaged inside the feed. Returns the
 * bytes the callback accepted, which stops short of total when the callback returns fewer bytes than given. */
size_t tw_test_curl_feed(void *ctx, size_t total, size_t max_chunk, size_t pause_nanos) {
    size_t (*write)(char *, size_t, size_t, void *) = tw_curl_buffer_callback();
    char *chunk = malloc(max_chunk);
    if (chunk == NULL) {
        return 0;
    }
    size_t offset = 0;
    size_t i = 0;
    while (offset < total) {
        size_t len = tw_test_curl_chunk_length(i, max_chunk, total - offset);
        for (size_t k = 0; k < len; k++) {
            chunk[k] = (char)tw_test_curl_pattern(offset + k);
        }
        size_t returned = i % 2 == 0 ? write(chunk, 1, len, ctx) : write(chunk, len, 1, ctx);
        offset += returned;
        if (returned < len) {
            break;
        }
        if (pause_nanos > 0) {
            struct timespec pause = {0, (long)pause_nanos};
            nanosleep(&pause, NULL);
        }
        i++;
    }
    free(chunk);
    return offset;
}

/* How many calls tw_test_curl_feed makes for these arguments when every chunk is accepted. */
size_t tw_test_curl_chunks(size_t total, size_t max_chunk) {
    size_t offset = 0;
    size_t i = 0;
    while (offset < total) {
        offset += tw_test_curl_chunk_length(i, max_chunk, total - offset);
        i++;
    }
    return i;
}
