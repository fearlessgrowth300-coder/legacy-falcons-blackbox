package top.niunaijun.blackbox.core;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorAdditionalInfo;
import android.hardware.SensorEvent;
import android.hardware.SensorEventCallback;
import android.hardware.SensorEventListener;
import android.hardware.SensorEventListener2;
import android.os.Build;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.jnihook.jni.JniHook;

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
    private static volatile String sReason = "not installed";
    private static volatile Map<String, Object> sFetchers;
    private static volatile Method sGetService;

    private SensorSignalIsolation() {
    }

    public static void noteInactive(String reason) {
        sReason = reason == null ? "unknown" : reason;
        sInstalled = false;
    }

    public static String inactiveReason() {
        return sInstalled ? "active" : sReason;
    }

    public static synchronized boolean install(DeviceProfile profile) {
        if (profile == null || profile.androidId == null || profile.androidId.isEmpty()) {
            noteInactive("profile android id is missing");
            return false;
        }
        sSeed = seed(profile.androidId);
        if (sInstalled) return true;
        if (PineRuntime.hooksCrashThisRuntime(Build.VERSION.SDK_INT, pageSize())) {
            return installServiceHook();
        }
        return installPine();
    }

    public static boolean isActive() {
        return sInstalled;
    }

    private static boolean installPine() {
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
            sReason = "active via dispatchSensorEvent";
            Slog.d(TAG, "per-clone 3-axis sensor calibration active");
            return true;
        } catch (Throwable error) {
            noteInactive("dispatchSensorEvent hook failed: " + error.getClass().getSimpleName());
            Slog.w(TAG, "sensor calibration hook unavailable: " + sReason);
            return false;
        }
    }

    /**
     * Android 16 cannot use Pine. Every sensor manager is created by
     * SystemServiceRegistry, so that lookup arms the manager: listeners registered
     * later are wrapped and their 3-axis samples are calibrated before delivery.
     */
    private static boolean installServiceHook() {
        try {
            Class<?> registry = Class.forName("android.app.SystemServiceRegistry");
            Field fetchersField = registry.getDeclaredField("SYSTEM_SERVICE_FETCHERS");
            fetchersField.setAccessible(true);
            Object raw = fetchersField.get(null);
            if (!(raw instanceof Map)) {
                noteInactive("sensor service registry is missing");
                return false;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> fetchers = (Map<String, Object>) raw;
            if (fetchers.get(Context.SENSOR_SERVICE) == null) {
                noteInactive("sensor service fetcher is missing");
                return false;
            }
            sFetchers = fetchers;
            serviceGetter(fetchers.get(Context.SENSOR_SERVICE));
            if (!artMethodSizeOk()) return false;
            Method probe = SensorSignalIsolation.class.getDeclaredMethod("probeTarget");
            Method probeBody = SensorSignalIsolation.class.getDeclaredMethod("probeReplacement");
            if (!redirect(probe, probeBody)) return false;
            if (probeTarget() != 2) {
                noteInactive("method redirect did not take effect");
                return false;
            }
            Method target = registry.getDeclaredMethod("getSystemService",
                    Class.forName("android.app.ContextImpl"), String.class);
            Method replacement = SensorSignalIsolation.class.getDeclaredMethod(
                    "serviceForContext", Object.class, String.class);
            target.setAccessible(true);
            replacement.setAccessible(true);
            if (!redirect(target, replacement)) {
                noteInactive("SystemServiceRegistry redirect failed");
                return false;
            }
            Context context = BlackBoxCore.getContext();
            if (context == null) {
                noteInactive("guest context is missing");
                return false;
            }
            Object manager = context.getSystemService(Context.SENSOR_SERVICE);
            if (!(manager instanceof android.hardware.SensorManager) || !sInstalled) {
                if (!sInstalled) noteInactive(sReason == null ? "sensor manager was not armed" : sReason);
                return false;
            }
            sReason = "active via SystemSensorManager listener wrap";
            Slog.w(TAG, "per-clone 3-axis sensor calibration active without Pine");
            return true;
        } catch (Throwable error) {
            noteInactive("SystemSensorManager path failed: " + error.getClass().getSimpleName());
            Slog.w(TAG, "sensor calibration inactive: " + sReason);
            return false;
        }
    }

    /** Replaces SystemServiceRegistry.getSystemService. Must not call that method. */
    @SuppressWarnings("unused")
    public static Object serviceForContext(Object context, String name) {
        try {
            Map<String, Object> fetchers = sFetchers;
            if (fetchers == null || name == null) return null;
            Object fetcher = fetchers.get(name);
            if (fetcher == null) return null;
            Method getService = serviceGetter(fetcher);
            Object service;
            try {
                service = getService.invoke(fetcher, context);
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                return null;
            }
            if (Context.SENSOR_SERVICE.equals(name)) arm(service);
            return service;
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            Slog.w(TAG, "sensor service lookup failed: " + error.getClass().getSimpleName());
            return null;
        }
    }

    private static Method serviceGetter(Object fetcher) throws NoSuchMethodException {
        Method cached = sGetService;
        if (cached != null && cached.getDeclaringClass().isInstance(fetcher)) return cached;
        for (Method method : fetcher.getClass().getMethods()) {
            if ("getService".equals(method.getName()) && method.getParameterTypes().length == 1) {
                method.setAccessible(true);
                sGetService = method;
                return method;
            }
        }
        throw new NoSuchMethodException("getService");
    }

    private static void arm(Object manager) {
        if (manager == null) {
            noteInactive("sensor manager was not created");
            return;
        }
        try {
            Field listeners = findField(manager.getClass(), "mSensorListeners");
            if (listeners == null) {
                noteInactive("SystemSensorManager listener map is missing");
                return;
            }
            makeWritable(listeners);
            Object current = listeners.get(manager);
            if (current instanceof CalibratedListeners) {
                sInstalled = true;
                return;
            }
            CalibratedListeners wrapped = new CalibratedListeners();
            if (current instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) current).entrySet()) {
                    wrapped.putRaw(entry.getKey(), entry.getValue());
                }
            }
            listeners.set(manager, wrapped);
            sInstalled = true;
        } catch (Throwable error) {
            noteInactive("could not arm SystemSensorManager: " + error.getClass().getSimpleName());
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> cursor = type; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static void makeWritable(Field field) {
        try {
            JniHook.setAccessible(field.getDeclaringClass(), field);
        } catch (Throwable ignored) {
        }
        field.setAccessible(true);
    }

    private static void rewriteListener(Object queue, Object key) {
        if (!(key instanceof SensorEventListener) || queue == null) return;
        Field listener = findField(queue.getClass(), "mListener");
        if (listener == null) return;
        try {
            makeWritable(listener);
            Object current = listener.get(queue);
            if (current instanceof CalibratedListener) return;
            if (current instanceof SensorEventListener) {
                listener.set(queue, new CalibratedListener((SensorEventListener) current));
            }
        } catch (Throwable error) {
            noteInactive("sensor listener was not wrapped: " + error.getClass().getSimpleName());
        }
    }

    public static int probeTarget() {
        return 1;
    }

    public static int probeReplacement() {
        return 2;
    }

    private static boolean artMethodSizeOk() {
        try {
            Method first = JniHook.class.getDeclaredMethod("nativeOffset");
            Method second = JniHook.class.getDeclaredMethod("nativeOffset2");
            long size = Math.abs(artAddress(second) - artAddress(first));
            if (size == 32L) return true;
            noteInactive("unexpected ArtMethod size " + size);
            return false;
        } catch (Throwable error) {
            noteInactive("ArtMethod size unavailable: " + error.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean redirect(Method target, Method replacement) {
        try {
            long from = artAddress(replacement);
            long to = artAddress(target);
            Object unsafe = unsafe();
            Method getInt = unsafe.getClass().getMethod("getInt", long.class);
            Method putInt = unsafe.getClass().getMethod("putInt", long.class, int.class);
            Method copy = unsafe.getClass().getMethod("copyMemory", long.class, long.class, long.class);
            putInt.invoke(unsafe, to, getInt.invoke(unsafe, from));
            putInt.invoke(unsafe, to + 8L, getInt.invoke(unsafe, from + 8L));
            copy.invoke(unsafe, from + 16L, to + 16L, 16L);
            return true;
        } catch (Throwable error) {
            noteInactive("method redirect failed: " + error.getClass().getSimpleName());
            return false;
        }
    }

    private static long artAddress(Method method) throws Exception {
        Field art = java.lang.reflect.Executable.class.getDeclaredField("artMethod");
        art.setAccessible(true);
        long address = art.getLong(method);
        if (address == 0L) throw new IllegalStateException("artMethod");
        return address;
    }

    private static Object unsafe() throws Exception {
        Class<?> type = Class.forName("sun.misc.Unsafe");
        Field field = type.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return field.get(null);
    }

    private static long pageSize() {
        try {
            return android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
        } catch (Throwable ignored) {
            return 4096L;
        }
    }

    private static final class CalibratedListeners extends HashMap<Object, Object> {
        @Override
        public Object put(Object key, Object value) {
            rewriteListener(value, key);
            return super.put(key, value);
        }

        void putRaw(Object key, Object value) {
            super.put(key, value);
        }
    }

    private static final class CalibratedListener extends SensorEventCallback {
        private final SensorEventListener delegate;

        CalibratedListener(SensorEventListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event != null && event.values != null && event.values.length == 3) {
                transform(event.values, event.timestamp, sSeed);
            }
            delegate.onSensorChanged(event);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            delegate.onAccuracyChanged(sensor, accuracy);
        }

        @Override
        public void onFlushCompleted(Sensor sensor) {
            if (delegate instanceof SensorEventListener2) {
                ((SensorEventListener2) delegate).onFlushCompleted(sensor);
            }
        }

        @Override
        public void onSensorAdditionalInfo(SensorAdditionalInfo info) {
            if (delegate instanceof SensorEventCallback) {
                ((SensorEventCallback) delegate).onSensorAdditionalInfo(info);
            }
        }
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
