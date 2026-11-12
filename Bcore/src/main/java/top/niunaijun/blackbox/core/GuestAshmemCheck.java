package top.niunaijun.blackbox.core;

import android.database.CursorWindow;
import android.os.Build;
import android.os.SharedMemory;
import android.util.Log;

/**
 * Runs in the guest after path hooks are installed and before Application.onCreate.
 * A failure means ashmem is still broken, so boot_id substitution is turned off.
 */
public final class GuestAshmemCheck {
    private static final String TAG = "BlackBoxAshmem";

    private GuestAshmemCheck() {}

    public static void verifyOrDisableBootIdSpoof() {
        Throwable failure = probe();
        if (failure == null) {
            Log.i(TAG, "ashmem self-test passed");
            return;
        }
        Log.e(TAG, "ASHMEM SELF-TEST FAILED; disabling boot_id spoof for this process", failure);
        IOCore.get().disableBootIdSpoof();
    }

    static Throwable probe() {
        if (Build.VERSION.SDK_INT >= 27) {
            try {
                SharedMemory memory = SharedMemory.create("blackbox-ashmem-probe", 4096);
                memory.close();
            } catch (Throwable error) {
                return error;
            }
        }
        CursorWindow window = null;
        try {
            window = new CursorWindow("blackbox-cursor-probe");
        } catch (Throwable error) {
            return error;
        } finally {
            if (window != null) window.close();
        }
        return null;
    }
}
