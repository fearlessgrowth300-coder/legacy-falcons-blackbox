package top.niunaijun.blackbox.core;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Applies a stable, very small per-BlackBox-user calibration transform to raw 3-axis sensor
 * events before guest listeners receive them. This prevents clones from exposing byte-identical
 * accelerometer/gyroscope/magnetometer samples while preserving magnitude and normal app motion
 * behaviour. It does not pretend the underlying physical sensor or its timing is independent.
 */
public final class SensorSignalIsolation {
    private static final String TAG = "SensorSignalIsolation";
    private static volatile boolean sInstalled;
    private static volatile long sSeed;

    private SensorSignalIsolation() {
    }

    public static synchronized boolean install(DeviceProfile profile) {
        if (profile == null || profile.androidId == null) return false;
        sSeed = seed(profile.androidId);
        if (sInstalled) return true;
        try {
            Class<?> queue = Class.forName(
                    "android.hardware.SystemSensorManager$SensorEventQueue");
            Method dispatch = queue.getDeclaredMethod(
                    "dispatchSensorEvent", int.class, float[].class, int.class, long.class);
            dispatch.setAccessible(true);
            Pine.hook(dispatch, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame frame) {
                    if (frame.args == null || frame.args.length < 4
                            || !(frame.args[1] instanceof float[])) {
                        return;
                    }
                    float[] values = (float[]) frame.args[1];
                    if (values.length != 3) return;
                    long timestamp = frame.args[3] instanceof Long
                            ? (Long) frame.args[3] : 0L;
                    transform(values, timestamp, sSeed);
                }
            });
            sInstalled = true;
            Slog.d(TAG, "per-clone 3-axis sensor calibration active");
            return true;
        } catch (Throwable error) {
            Slog.w(TAG, "sensor calibration hook unavailable: "
                    + error.getClass().getSimpleName());
            return false;
        }
    }

    public static boolean isActive() {
        return sInstalled;
    }

    private static void transform(float[] values, long timestamp, long seed) {
        if (!Float.isFinite(values[0]) || !Float.isFinite(values[1])
                || !Float.isFinite(values[2])) {
            return;
        }

        // A sub-degree Z-axis calibration offset is within normal physical sensor mounting
        // tolerance and preserves vector magnitude, orientation behaviour, and game controls.
        double degrees = (((seed >>> 8) & 0xffffL) / 65535.0 - 0.5) * 0.7;
        double angle = Math.toRadians(degrees);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        float x = values[0];
        float y = values[1];
        values[0] = (float) (x * cos - y * sin);
        values[1] = (float) (x * sin + y * cos);

        // Add tiny deterministic per-sample calibration noise. The amplitude is deliberately
        // below normal hardware noise so it does not disturb camera stabilization or UI motion.
        long state = mix64(seed ^ timestamp);
        for (int i = 0; i < 3; i++) {
            state = mix64(state + 0x9e3779b97f4a7c15L + i);
            double unit = ((state >>> 11) * 0x1.0p-53) - 0.5;
            double amplitude = Math.max(1.0, Math.abs(values[i])) * 0.00002;
            values[i] += (float) (unit * amplitude);
        }
    }

    private static long seed(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            long out = 0L;
            for (int i = 0; i < 8; i++) out = (out << 8) | (digest[i] & 0xffL);
            return out;
        } catch (Throwable ignored) {
            return value.hashCode() * 0x9e3779b97f4a7c15L;
        }
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
