package top.niunaijun.blackbox.utils.compat;

/**
 * Whether a task icon may be given to {@code TaskDescription.writeToParcel}.
 * From API 33 that parcel path calls {@code Bitmap.asShared}. A hardware,
 * recycled, or null-backed bitmap makes that native call SIGSEGV, and the
 * abort is not a Java exception, so the guest process dies during launch.
 */
public final class TaskIconPolicy {
    /** {@code Icon.writeToParcel} shares the task bitmap from this SDK on. */
    public static final int ASHMEM_ICON_SDK = 33;

    public enum BitmapState {
        ABSENT,
        RECYCLED,
        HARDWARE,
        NULL_NATIVE,
        SOFTWARE
    }

    private TaskIconPolicy() {}

    public static boolean parcelUsesAshmem(int sdkInt) {
        return sdkInt >= ASHMEM_ICON_SDK;
    }

    /** A copy is only worth attempting for a live software bitmap with pixels. */
    public static boolean canCopySafely(BitmapState state) {
        return state == BitmapState.SOFTWARE;
    }

    /**
     * The object passed to the system must not carry a bitmap on API 33+, or
     * when the bitmap is recycled, hardware, or missing its native buffer.
     * Below that, a software bitmap may be copied to ARGB_8888 first.
     */
    public static boolean mustStripIcon(int sdkInt, BitmapState state) {
        if (parcelUsesAshmem(sdkInt)) return true;
        return !canCopySafely(state);
    }
}
