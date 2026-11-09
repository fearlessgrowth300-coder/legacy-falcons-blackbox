package top.niunaijun.blackbox.core;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.Sensor;
import android.hardware.SensorAdditionalInfo;
import android.hardware.SensorEvent;
import android.hardware.SensorEventCallback;
import android.hardware.SensorEventListener;
import android.hardware.SensorEventListener2;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
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
 *
 * <p>API 37 does not use ART method patching. The guest's SensorManager keeps its normal
 * instance; the HashMap that owns its listener queues is replaced, and each queue's listener
 * is wrapped when it is registered.
 */
@androidx.annotation.Keep
public final class SensorSignalIsolation {
    private static final String TAG = "SensorSignalIsolation";
    private static volatile boolean sInstalled;
    private static volatile long sSeed;
    private static volatile String sReason = "not installed";
    private static final Map<SensorEventListener, SensorEventListener> EVENT_WRAPS =
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
            return installJava();
        }
        return installPine();
    }

    public static boolean isActive() {
        return sInstalled;
    }

    /** Guest contexts go through this so every later sensor lookup is armed. */
    public static Context wrapContext(Context base) {
        if (base == null || base instanceof SensorServiceContext) return base;
        return new SensorServiceContext(base);
    }

    public static void wrapBase(Object wrapper) {
        if (!(wrapper instanceof ContextWrapper)) return;
        try {
            Field base = ContextWrapper.class.getDeclaredField("mBase");
            base.setAccessible(true);
            Object current = base.get(wrapper);
            if (current instanceof Context && !(current instanceof SensorServiceContext)) {
                base.set(wrapper, new SensorServiceContext((Context) current));
            }
        } catch (Throwable error) {
            Slog.w(TAG, "could not wrap context: " + error.getClass().getSimpleName()
                    + ": " + error.getMessage());
        }
    }

    private static boolean installPine() {
        try {
            Class<?> queue = Class.forName(
                    "android.hardware.SystemSensorManager$SensorEventQueue");
            java.lang.reflect.Method dispatch = queue.getDeclaredMethod(
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
                    long timestamp = frame.args[3] instanceof Long ? (Long) frame.args[3] : 0L;
                    transform(values, timestamp, sSeed);
                }
            });
            sInstalled = true;
            sReason = "strategy=dispatchSensorEvent";
            Slog.d(TAG, "per-clone 3-axis sensor calibration active");
            return true;
        } catch (Throwable error) {
            noteInactive("dispatchSensorEvent hook failed: " + error.getClass().getSimpleName()
                    + ": " + error.getMessage());
            Slog.w(TAG, "sensor calibration inactive: " + sReason);
            return false;
        }
    }

    private static boolean installJava() {
        try {
            Context context = BlackBoxCore.getContext();
            if (context == null) {
                noteInactive("guest context is missing");
                return false;
            }
            Object manager = context.getSystemService(Context.SENSOR_SERVICE);
            if (manager == null) manager = newSensorManager(context);
            if (manager == null) {
                noteInactive("sensor manager was not created");
                return false;
            }
            boolean cache = cacheContains(context, manager);
            if (!arm(manager, cache)) return false;
            Slog.w(TAG, "sensor " + sReason);
            return true;
        } catch (Throwable error) {
            noteInactive(error.getClass().getSimpleName() + ": " + error.getMessage());
            Slog.w(TAG, "sensor calibration inactive: " + sReason);
            return false;
        }
    }

    /** Replace the listener map on one manager. True only when that wrapper is in place. */
    static synchronized boolean arm(Object manager, boolean cacheHit) {
        if (manager == null) return false;
        try {
            List<Field> fields = declaredFields(manager.getClass());
            Field mapField = fieldAt(fields, SensorWrapSelection.listenerMap(members(fields)));
            if (mapField == null) {
                fail("no listener map on " + manager.getClass().getName());
                return false;
            }
            Slog.w(TAG, "found member " + mapField.getName() + " type " + mapField.getType().getName()
                    + " generic " + mapField.getGenericType());
            open(mapField);
            Object current = mapField.get(manager);
            if (current instanceof CalibratedListeners) {
                sInstalled = true;
                return true;
            }
            if (!mapField.getType().isAssignableFrom(CalibratedListeners.class)) {
                fail("listener map type " + mapField.getType().getName() + " field " + mapField.getName());
                return false;
            }
            Field listener = queueListenerField(manager.getClass());
            if (listener == null) {
                fail("no queue listener field on " + manager.getClass().getName());
                return false;
            }
            open(listener);
            CalibratedListeners wrapped = new CalibratedListeners(listener);
            if (current instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) current).entrySet()) {
                    wrapped.rewrite(entry.getValue(), entry.getKey());
                    wrapped.putRaw(entry.getKey(), entry.getValue());
                }
            }
            mapField.set(manager, wrapped);
            if (!(mapField.get(manager) instanceof CalibratedListeners)) {
                fail("listener map was not replaced field " + mapField.getName());
                return false;
            }
            markInstalled(mapField, listener, cacheHit);
            return true;
        } catch (Throwable error) {
            fail(error.getClass().getSimpleName() + ": " + error.getMessage());
            return false;
        }
    }

    private static void markInstalled(Field mapField, Field listener, boolean cacheHit) {
        String listenerName = listener == null ? "missing" : listener.getName();
        String strategy = SensorWrapSelection.strategy(cacheHit, true, listener != null);
        sInstalled = listener != null;
        sReason = "strategy=" + strategy + " map=" + mapField.getName() + " listener=" + listenerName;
        if (!sInstalled) {
            sReason = "queue listener field missing after map " + mapField.getName();
            return;
        }
        Slog.w(TAG, "hooked member " + mapField.getName() + " and " + listenerName + " " + sReason);
    }

    private static void fail(String reason) {
        if (!sInstalled) sReason = reason;
        Slog.w(TAG, "sensor calibration inactive: " + reason);
    }

    private static Object newSensorManager(Context context) {
        try {
            Class<?> type = Class.forName("android.hardware.SystemSensorManager");
            Constructor<?> found = null;
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length == 2
                        && Context.class.isAssignableFrom(parameters[0])
                        && Looper.class.isAssignableFrom(parameters[1])) {
                    found = constructor;
                    break;
                }
            }
            if (found == null) {
                Slog.w(TAG, "no SystemSensorManager constructor taking Context and Looper");
                return null;
            }
            Slog.w(TAG, "found member " + found);
            found.setAccessible(true);
            Looper looper = Looper.getMainLooper();
            if (looper == null) return null;
            return found.newInstance(context, looper);
        } catch (Throwable error) {
            Slog.w(TAG, "SystemSensorManager was not created: " + error.getClass().getSimpleName()
                    + ": " + error.getMessage());
            return null;
        }
    }

    private static boolean cacheContains(Context context, Object manager) {
        Object impl = context;
        while (impl instanceof ContextWrapper) {
            Context base = ((ContextWrapper) impl).getBaseContext();
            if (base == null || base == impl) break;
            impl = base;
        }
        for (Class<?> type = impl.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != Object[].class) continue;
                try {
                    field.setAccessible(true);
                    Object raw = field.get(impl);
                    if (!(raw instanceof Object[])) continue;
                    Object[] cache = (Object[]) raw;
                    for (Object slot : cache) {
                        if (slot == manager) {
                            Slog.w(TAG, "found member " + field.getName() + " holding the sensor manager");
                            return true;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private static Field queueListenerField(Class<?> managerClass) {
        Field fallback = null;
        for (Class<?> inner : managerClass.getDeclaredClasses()) {
            List<Field> fields = declaredFields(inner);
            Field field = fieldAt(fields, SensorWrapSelection.queueListener(members(fields)));
            if (field == null) continue;
            Slog.w(TAG, "found member " + inner.getSimpleName() + "." + field.getName()
                    + " type " + field.getType().getName());
            if ("mListener".equals(field.getName())) return field;
            fallback = field;
        }
        if (fallback != null) return fallback;
        try {
            Class<?> queue = Class.forName(managerClass.getName() + "$SensorEventQueue");
            List<Field> fields = declaredFields(queue);
            return fieldAt(fields, SensorWrapSelection.queueListener(members(fields)));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<Field> declaredFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> cursor = type; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) fields.add(field);
        }
        return fields;
    }

    private static SensorWrapSelection.Member[] members(List<Field> fields) {
        SensorWrapSelection.Member[] members = new SensorWrapSelection.Member[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            members[i] = new SensorWrapSelection.Member(
                    field.getName(), field.getType().getName(), String.valueOf(field.getGenericType()));
        }
        return members;
    }

    private static Field fieldAt(List<Field> fields, int index) {
        if (index < 0 || index >= fields.size()) return null;
        return fields.get(index);
    }

    private static void open(Field field) {
        try {
            JniHook.setAccessible(field.getDeclaringClass(), field);
        } catch (Throwable ignored) {
        }
        field.setAccessible(true);
    }

    private static long pageSize() {
        try {
            return android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
        } catch (Throwable ignored) {
            return 4096L;
        }
    }

    public static final class SensorServiceContext extends ContextWrapper {
        SensorServiceContext(Context base) {
            super(base);
        }

        @Override
        public Object getSystemService(String name) {
            Object service = super.getSystemService(name);
            if (Context.SENSOR_SERVICE.equals(name)) arm(service, false);
            return service;
        }
    }

    private static final class CalibratedListeners extends HashMap<Object, Object> {
        private final Field listenerField;

        CalibratedListeners(Field listenerField) {
            this.listenerField = listenerField;
        }

        @Override
        public Object put(Object key, Object value) {
            rewrite(value, key);
            return super.put(key, value);
        }

        void putRaw(Object key, Object value) {
            super.put(key, value);
        }

        void rewrite(Object queue, Object key) {
            if (!(key instanceof SensorEventListener) || queue == null) return;
            if (key instanceof CalibratedListener) return;
            try {
                Object current = listenerField.get(queue);
                if (current instanceof CalibratedListener) return;
                if (current instanceof SensorEventListener) {
                    listenerField.set(queue, wrap((SensorEventListener) current));
                } else {
                    listenerField.set(queue, wrap((SensorEventListener) key));
                }
            } catch (Throwable error) {
                fail("queue listener was not wrapped field " + listenerField.getName()
                        + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }
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
        double degrees = (((seed >>> 8) & 0xffffL) / 65535.0 - 0.5) * 0.7;
        double angle = Math.toRadians(degrees);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        float x = values[0];
        float y = values[1];
        values[0] = (float) (x * cos - y * sin);
        values[1] = (float) (x * sin + y * cos);
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
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
