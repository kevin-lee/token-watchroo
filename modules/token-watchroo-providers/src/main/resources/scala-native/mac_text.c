/*
 * NFC and SHA-256 for the Claude Code keychain service name (#91).
 *
 * Claude Code names the keychain item of a custom config folder "Claude Code-credentials-" plus the first 8 hex
 * characters of sha256 of the folder text in Unicode Normalization Form C (NFC). Scala Native 0.5.12 has no
 * java.text.Normalizer and no java.security.MessageDigest, so these two functions use the macOS libraries:
 * CFStringNormalize from CoreFoundation (linked with -framework CoreFoundation) and CC_SHA256 from CommonCrypto (in
 * libSystem).
 *
 * Both functions are short, call nothing back, and touch only the memory that the caller passes, so the Scala side
 * does not mark them @blocking.
 */
#include <CommonCrypto/CommonDigest.h>
#include <CoreFoundation/CoreFoundation.h>
#include <string.h>

/*
 * Converts the NUL-terminated UTF-8 text `in` to NFC. Returns the number of UTF-8 bytes of the result, without a NUL.
 * When `out` is not NULL and the result fits in `cap` bytes, the bytes are copied to `out` (no NUL is written).
 * Returns -1 when `in` is not valid UTF-8 or a CoreFoundation call fails. Every CoreFoundation object is released.
 */
long tw_nfc_utf8(const char *in, char *out, long cap) {
    CFStringRef source = CFStringCreateWithCString(kCFAllocatorDefault, in, kCFStringEncodingUTF8);
    if (source == NULL) {
        return -1;
    }
    CFMutableStringRef text = CFStringCreateMutableCopy(kCFAllocatorDefault, 0, source);
    CFRelease(source);
    if (text == NULL) {
        return -1;
    }
    CFStringNormalize(text, kCFStringNormalizationFormC);

    CFRange range = CFRangeMake(0, CFStringGetLength(text));
    CFIndex needed = 0;
    CFIndex converted = CFStringGetBytes(text, range, kCFStringEncodingUTF8, 0, false, NULL, 0, &needed);
    long result = -1;
    if (converted == range.length) {
        result = (long)needed;
        if (out != NULL && needed <= cap) {
            CFIndex written = 0;
            CFStringGetBytes(text, range, kCFStringEncodingUTF8, 0, false, (UInt8 *)out, needed, &written);
            if (written != needed) {
                result = -1;
            }
        }
    }
    CFRelease(text);
    return result;
}

/* Writes the 32-byte SHA-256 digest of `len` bytes at `data` to `out32`. */
void tw_sha256(const unsigned char *data, unsigned long len, unsigned char *out32) {
    CC_SHA256(data, (CC_LONG)len, out32);
}
