package top.niunaijun.blackbox.core;

import android.hardware.Sensor;
import android.hardware.SensorAdditionalInfo;
import android.hardware.SensorEvent;
import android.hardware.SensorEventCallback;
import android.hardware.SensorEventListener;
import android.hardware.SensorEventListener2;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.os.Build;
import android.os.Handler;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.jnihook.jni.JniHook;

/**
 * Applies a stable, very small per-BlackBox-user calibration transform to raw 3-axis sensor
 * events before guest listeners receive them. This prevents clones from exposing byte-identical
 * accelerometer/gyroscope/magnetometer samples while preserving magnitude and normal app motion
 * behaviour. It does not pretend the underlying physical sensor or its timing is independent.
 */
@androidx.annotation.Keep
public final class SensorSignalIsolation {
    private static final String TAG = "SensorSignalIsolation";
    private static volatile boolean sInstalled;
    private static volatile long sSeed;
    private static volatile String sReason = "not installed";
    private static final Map<String, Method> BACKUPS = new HashMap<>();
    private static final Map<SensorEventListener, SensorEventListener> EVENT_WRAPS =
            new IdentityHashMap<>();
    private static final Map<TriggerEventListener, TriggerEventListener> TRIGGER_WRAPS =
            new IdentityHashMap<>();

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
     * Android 16 cannot use Pine. Every SensorManager the guest gets from
     * getSystemService still dispatches through these public methods, so wrapping
     * the listener there calibrates samples without a private signature.
     */
    private static boolean installServiceHook() {
        try {
            if (!artMethodSizeOk()) return false;
            Method[] probes = artProbes();
            if (probes == null) {
                noteInactive("art probe methods missing");
                Slog.w(TAG, "sensor calibration inactive: " + sReason);
                return false;
            }
            if (!redirect(probes[0], probes[1])) return false;
            int redirected = invokeInt(probes[0]);
            if (redirected != 2) {
                noteInactive("method redirect did not take effect, value=" + redirected);
                return false;
            }
            List<Method> found = new ArrayList<>();
            collectListenerMethods(SensorManager.class, found);
            try {
                collectListenerMethods(Class.forName("android.hardware.SystemSensorManager"), found);
            } catch (Throwable error) {
                Slog.w(TAG, "SystemSensorManager members unavailable: "
                        + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
            int hooked = 0;
            String first = null;
            for (Method method : found) {
                Slog.w(TAG, "found member " + method.toGenericString());
                Method hook = match(Hooks.class, method);
                Method backup = match(Originals.class, method);
                if (hook == null || backup == null) {
                    noteInactive("no wrapper for " + method.toGenericString());
                    Slog.w(TAG, "sensor calibration inactive: " + sReason);
                    return false;
                }
                hook.setAccessible(true);
                backup.setAccessible(true);
                method.setAccessible(true);
                if (!redirect(backup, method) || !redirect(method, hook)) {
                    noteInactive("could not hook " + method.toGenericString()
                            + " (" + sReason + ")");
                    return false;
                }
                BACKUPS.put(signature(method), backup);
                hooked++;
                if (first == null) first = method.toGenericString();
                Slog.w(TAG, "hooked member " + method.toGenericString());
            }
            if (hooked == 0) {
                noteInactive("no registerListener member hooked; saw " + describe(found));
                return false;
            }
            sInstalled = true;
            sReason = "active via " + first;
            Slog.w(TAG, "per-clone sensor calibration active via " + hooked + " members, first=" + first);
            return true;
        } catch (Throwable error) {
            noteInactive(error.getClass().getSimpleName() + ": " + error.getMessage());
            Slog.w(TAG, "sensor calibration inactive: " + sReason);
            return false;
        }
    }

    private static void collectListenerMethods(Class<?> type, List<Method> into) {
        if (type == null) return;
        for (Method method : type.getDeclaredMethods()) {
            if (method.isBridge() || method.isSynthetic() || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (!Modifier.isPublic(method.getModifiers())) continue;
            if (!takesListener(method)) continue;
            if (!containsSignature(into, method)) into.add(method);
        }
    }

    private static boolean takesListener(Method method) {
        String name = method.getName();
        if (!"registerListener".equals(name) && !"unregisterListener".equals(name)
                && !"flush".equals(name) && !"requestTriggerSensor".equals(name)
                && !"cancelTriggerSensor".equals(name)) {
            return false;
        }
        for (Class<?> parameter : method.getParameterTypes()) {
            if (parameter == SensorEventListener.class || parameter == TriggerEventListener.class) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSignature(List<Method> methods, Method candidate) {
        for (Method method : methods) {
            if (method.getName().equals(candidate.getName())
                    && java.util.Arrays.equals(method.getParameterTypes(), candidate.getParameterTypes())) {
                return true;
            }
        }
        return false;
    }

    private static Method match(Class<?> type, Method original) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(original.getName())
                    && java.util.Arrays.equals(method.getParameterTypes(), original.getParameterTypes())) {
                return method;
            }
        }
        return null;
    }

    private static String signature(Method method) {
        StringBuilder builder = new StringBuilder(method.getName());
        for (Class<?> parameter : method.getParameterTypes()) {
            builder.append('#').append(parameter.getName());
        }
        return builder.toString();
    }

    private static String describe(List<Method> methods) {
        if (methods.isEmpty()) return "(none)";
        StringBuilder builder = new StringBuilder();
        for (Method method : methods) {
            if (builder.length() > 0) builder.append(" | ");
            builder.append(method.toGenericString());
        }
        return builder.toString();
    }

    private static Object callOriginal(Object self, String name, Class<?>[] types, Object[] args) {
        Method backup = BACKUPS.get(signature(name, types));
        if (backup == null) {
            throw new IllegalStateException("backup missing for " + name);
        }
        try {
            return backup.invoke(self, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String signature(String name, Class<?>[] types) {
        StringBuilder builder = new StringBuilder(name);
        for (Class<?> type : types) builder.append('#').append(type.getName());
        return builder.toString();
    }

    private static SensorEventListener wrap(SensorEventListener listener) {
        if (listener == null || listener instanceof CalibratedListener) return listener;
        synchronized (EVENT_WRAPS) {
            SensorEventListener existing = EVENT_WRAPS.get(listener);
            if (existing != null) return existing;
            CalibratedListener created = new CalibratedListener(listener);
            EVENT_WRAPS.put(listener, created);
            return created;
        }
    }

    private static TriggerEventListener wrap(TriggerEventListener listener) {
        if (listener == null || listener instanceof CalibratedTrigger) return listener;
        synchronized (TRIGGER_WRAPS) {
            TriggerEventListener existing = TRIGGER_WRAPS.get(listener);
            if (existing != null) return existing;
            CalibratedTrigger created = new CalibratedTrigger(listener);
            TRIGGER_WRAPS.put(listener, created);
            return created;
        }
    }

    @androidx.annotation.Keep
    public static int probeTarget() {
        return 1;
    }

    @androidx.annotation.Keep
    public static int probeReplacement() {
        return 2;
    }

    /** Find the two probe methods without a reflective name lookup. */
    private static Method[] artProbes() {
        Method returnsOne = null;
        Method returnsTwo = null;
        for (Method method : SensorSignalIsolation.class.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0 || method.getReturnType() != int.class) continue;
            if (!Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) continue;
            method.setAccessible(true);
            int value;
            try {
                value = invokeInt(method);
            } catch (Throwable error) {
                Slog.w(TAG, "skipping probe " + method.getName() + ": " + error.getClass().getSimpleName());
                continue;
            }
            if (value == 1 && returnsOne == null) returnsOne = method;
            else if (value == 2 && returnsTwo == null) returnsTwo = method;
        }
        if (returnsOne == null || returnsTwo == null) return null;
        Slog.w(TAG, "found member " + returnsOne.getName() + "()I and " + returnsTwo.getName() + "()I");
        return new Method[]{returnsOne, returnsTwo};
    }

    private static int invokeInt(Method method) throws Exception {
        Object value = method.invoke(null);
        return value instanceof Integer ? (Integer) value : Integer.MIN_VALUE;
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

    /** Bodies installed in place of SensorManager's public registration methods. */
    @androidx.annotation.Keep
    public static class Hooks {
        private static final Class<?>[] EVENT = {SensorEventListener.class};
        private static final Class<?>[] EVENT_SENSOR = {SensorEventListener.class, Sensor.class};
        private static final Class<?>[] EVENT_SENSOR_INT = {
                SensorEventListener.class, Sensor.class, int.class};
        private static final Class<?>[] EVENT_SENSOR_INT_INT = {
                SensorEventListener.class, Sensor.class, int.class, int.class};
        private static final Class<?>[] EVENT_SENSOR_INT_HANDLER = {
                SensorEventListener.class, Sensor.class, int.class, Handler.class};
        private static final Class<?>[] EVENT_SENSOR_INT_INT_HANDLER = {
                SensorEventListener.class, Sensor.class, int.class, int.class, Handler.class};
        private static final Class<?>[] TRIGGER = {TriggerEventListener.class, Sensor.class};

        public boolean registerListener(SensorEventListener listener, Sensor sensor, int samplingPeriodUs) {
            return (Boolean) callOriginal(this, "registerListener", EVENT_SENSOR_INT,
                    new Object[]{wrap(listener), sensor, samplingPeriodUs});
        }

        public boolean registerListener(SensorEventListener listener, Sensor sensor,
                                        int samplingPeriodUs, int maxReportLatencyUs) {
            return (Boolean) callOriginal(this, "registerListener", EVENT_SENSOR_INT_INT,
                    new Object[]{wrap(listener), sensor, samplingPeriodUs, maxReportLatencyUs});
        }

        public boolean registerListener(SensorEventListener listener, Sensor sensor,
                                        int samplingPeriodUs, Handler handler) {
            return (Boolean) callOriginal(this, "registerListener", EVENT_SENSOR_INT_HANDLER,
                    new Object[]{wrap(listener), sensor, samplingPeriodUs, handler});
        }

        public boolean registerListener(SensorEventListener listener, Sensor sensor,
                                        int samplingPeriodUs, int maxReportLatencyUs, Handler handler) {
            return (Boolean) callOriginal(this, "registerListener", EVENT_SENSOR_INT_INT_HANDLER,
                    new Object[]{wrap(listener), sensor, samplingPeriodUs, maxReportLatencyUs, handler});
        }

        public void unregisterListener(SensorEventListener listener) {
            callOriginal(this, "unregisterListener", EVENT, new Object[]{wrap(listener)});
        }

        public void unregisterListener(SensorEventListener listener, Sensor sensor) {
            callOriginal(this, "unregisterListener", EVENT_SENSOR, new Object[]{wrap(listener), sensor});
        }

        public boolean flush(SensorEventListener listener) {
            return (Boolean) callOriginal(this, "flush", EVENT, new Object[]{wrap(listener)});
        }

        public boolean requestTriggerSensor(TriggerEventListener listener, Sensor sensor) {
            return (Boolean) callOriginal(this, "requestTriggerSensor", TRIGGER,
                    new Object[]{wrap(listener), sensor});
        }

        public boolean cancelTriggerSensor(TriggerEventListener listener, Sensor sensor) {
            return (Boolean) callOriginal(this, "cancelTriggerSensor", TRIGGER,
                    new Object[]{wrap(listener), sensor});
        }
    }

    /** Receives the original ArtMethod bytes. The Java bodies are never executed. */
    @androidx.annotation.Keep
    public static class Originals {
        public boolean registerListener(SensorEventListener listener, Sensor sensor, int samplingPeriodUs) {
            return false;
        }

        public boolean registerListener(SensorEventListener listener, Sensor sensor,
                                        int samplingPeriodUs, int maxReportLatencyUs) {
            return false;
        }

        public boolean registerListener(SensorEventListener listener, Sensor sensor,
                                        int samplingPeriodUs, Handler handler) {
            return false;
        }

        public boolean registerListener(SensorEventListener listener, Sensor sensor,
                                        int samplingPeriodUs, int maxReportLatencyUs, Handler handler) {
            return false;
        }

        public void unregisterListener(SensorEventListener listener) {
        }

        public void unregisterListener(SensorEventListener listener, Sensor sensor) {
        }

        public boolean flush(SensorEventListener listener) {
            return false;
        }

        public boolean requestTriggerSensor(TriggerEventListener listener, Sensor sensor) {
            return false;
        }

        public boolean cancelTriggerSensor(TriggerEventListener listener, Sensor sensor) {
            return false;
        }
    }

    private static final class CalibratedTrigger extends TriggerEventListener {
        private final TriggerEventListener delegate;

        CalibratedTrigger(TriggerEventListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onTrigger(TriggerEvent event) {
            if (event != null && event.values != null && event.values.length == 3) {
                transform(event.values, event.timestamp, sSeed);
            }
            delegate.onTrigger(event);
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
