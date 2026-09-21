/* Copied from Scala Native 0.5.12, nativelib/src/main/resources/scala-native/gc/immix_commix/RegistersCapture.h, with
 * the change of scala-native/scala-native#5048 applied.
 * Copyright (c) 2013-2018 EPFL, licensed under the Apache License, Version 2.0:
 * http://www.apache.org/licenses/LICENSE-2.0 (a copy is in licenses/scala-native-LICENSE.md).
 *
 * Modified for token-watchroo issue #63: upstream, RegistersCapture takes RegistersBuffer by value on x86 and x86_64,
 * so the captured registers never reach MutatorThread.registersBuffer, the buffer the GC scans. On x86_64 a reference
 * that a Scala frame holds only in rbx, rbp or r12 to r15 is then invisible to the marker while the thread is
 * Unmanaged (a @blocking call, GcState.set, a stop at a conditional yieldpoint), and a live object is swept. Here
 * RegistersBuffer is a one-element array, as jmp_buf is, so the parameter decays to a pointer. The setjmp path that
 * arm64 takes is unchanged.
 *
 * build.sbt puts this directory on the C include path ahead of nativelib's own directories, so every GC file that
 * includes "immix_commix/RegistersCapture.h" compiles this copy, and tw_registers_capture_override below lets
 * scripts/check-registers-capture.sh prove that from a binary. Scala Native recompiles a C file only when that file or
 * the build configuration changes, so after editing this copy remove the native and native-test folders under
 * target/out/native0.5/scala-3.8.4/<module>/ before relinking. Pinned to 0.5.12: build.sbt refuses another Scala
 * Native version until this copy is compared with that version's header. Delete it, with the -I option, the checks and
 * the CI steps, once a Scala Native release includes scala-native#5048.
 */
#ifndef REGISTERS_CAPTURE_H
#define REGISTERS_CAPTURE_H

/* token-watchroo #63: present in every object file compiled with this copy. */
__attribute__((used)) static const char tw_registers_capture_override[] =
    "token-watchroo #63: patched RegistersCapture.h";

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#endif

#if defined(__i386__) || defined(__x86__)
#define CAPTURE_X86
typedef struct {
    void *ebx;
    void *edi;
    void *esi;
} RegistersBuffer[1];

#elif defined(__x86_64__)
#define CAPTURE_X86_64
typedef struct {
    void *rbx;
    void *rbp;
    void *rdi;
    void *r12;
    void *r13;
    void *r14;
    void *r15;
    void *xmm[16 * 2];
} RegistersBuffer[1];

#else
#define CAPTURE_SETJMP
#include <setjmp.h>
typedef jmp_buf RegistersBuffer;
#endif

#ifdef CAPTURE_SETJMP
#define RegistersCapture(out) (void)setjmp(out);
#else
INLINE static void RegistersCapture(RegistersBuffer out) {
#ifdef CAPTURE_X86
    void *regEsi;
    void *regEdi;
    void *regEbx;
#ifdef __GNUC__
    asm("mov %%esi, %0\n\t" : "=r"(regEsi));
    asm("mov %%edi, %0\n\t" : "=r"(regEdi));
    asm("mov %%ebx, %0\n\t" : "=r"(regEbx));
#else // _WIN
    __asm {
      mov regEsi, esi
      mov regEdi, edi
      mov regEbx, ebx
    }
#endif
    out->esi = regEsi;
    out->edi = regEdi;
    out->ebx = regEbx;

#elif defined(CAPTURE_X86_64)
#ifdef _WIN32
    CONTEXT context;

    context.ContextFlags = CONTEXT_INTEGER;
    RtlCaptureContext(&context);

    out->rbx = (void *)context.Rbx;
    out->rbp = (void *)context.Rbp;
    out->rdi = (void *)context.Rdi;
    out->r12 = (void *)context.R12;
    out->r13 = (void *)context.R13;
    out->r14 = (void *)context.R14;
    out->r15 = (void *)context.R15;
    memcpy(out->xmm, &context.Xmm0, sizeof(out->xmm));
#else
    void *regBx;
    void *regBp;
    void *regDi;
    void *reg12;
    void *reg13;
    void *reg14;
    void *reg15;
    asm("movq %%rbx, %0\n\t" : "=r"(regBx));
    asm("movq %%rbp, %0\n\t" : "=r"(regBp));
    asm("movq %%rdi, %0\n\t" : "=r"(regDi));
    asm("movq %%r12, %0\n\t" : "=r"(reg12));
    asm("movq %%r13, %0\n\t" : "=r"(reg13));
    asm("movq %%r14, %0\n\t" : "=r"(reg14));
    asm("movq %%r15, %0\n\t" : "=r"(reg15));
    out->rbx = regBx;
    out->rbp = regBp;
    out->r12 = reg12;
    out->r13 = reg13;
    out->r14 = reg14;
    out->r15 = reg15;
#endif // GNU_C

#else
#error "Unable to capture registers state"
#endif // CaptureRegisters
}
#endif // RegistersCapture

#endif // REGISTERS_CAPTURE_H
