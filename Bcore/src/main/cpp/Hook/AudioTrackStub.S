#if defined(__aarch64__)
    .text
    .global audio_setup_trampoline
    .type audio_setup_trampoline, %function
    .balign 16
audio_setup_trampoline:
    sub sp, sp, #128
    stp x0, x1, [sp, #0]
    stp x2, x3, [sp, #16]
    stp x4, x5, [sp, #32]
    stp x6, x7, [sp, #48]
    str x30, [sp, #64]
    mov x0, sp
    add x1, sp, #128
    ldr x2, [sp, #64]
    bl audio_prepare
    mov x16, x0
    ldp x0, x1, [sp, #0]
    ldp x2, x3, [sp, #16]
    ldp x4, x5, [sp, #32]
    ldp x6, x7, [sp, #48]
    add sp, sp, #128
    adr x30, audio_setup_return
    blr x16
audio_setup_return:
    bl audio_finish
    mov x30, x0
    ret
#endif
