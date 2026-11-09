package top.niunaijun.blackbox.core;

/**
 * Chooses how to calibrate sensors without ART method patching.
 * Field identity is by type, with the name used only to break ties.
 */
public final class SensorWrapSelection {
    public static final String LISTENER_MAP = "listener-map";
    public static final String QUEUE_LISTENER = "queue-listener";
    public static final String SERVICE_CACHE = "service-cache";
    public static final String NONE = "none";

    private SensorWrapSelection() {
    }

    public static final class Member {
        public final String name;
        public final String typeName;
        public final String genericName;

        public Member(String name, String typeName, String genericName) {
            this.name = name == null ? "" : name;
            this.typeName = typeName == null ? "" : typeName;
            this.genericName = genericName == null ? "" : genericName;
        }
    }

    /** Index of the map that holds sensor listeners, or -1. */
    public static int listenerMap(Member[] fields) {
        if (fields == null) return -1;
        int only = -1;
        int named = -1;
        int typed = -1;
        int maps = 0;
        for (int i = 0; i < fields.length; i++) {
            Member field = fields[i];
            if (field == null || !isMap(field.typeName)) continue;
            maps++;
            only = i;
            if (field.name.toLowerCase().contains("listener")) named = i;
            if (field.genericName.contains("SensorEventListener")
                    || field.genericName.contains("SensorEventQueue")) {
                typed = i;
            }
        }
        if (typed >= 0) return typed;
        if (named >= 0) return named;
        if (maps == 1) return only;
        return -1;
    }

    /** Index of the single listener inside a sensor queue, or -1. */
    public static int queueListener(Member[] fields) {
        if (fields == null) return -1;
        int named = -1;
        int typed = -1;
        for (int i = 0; i < fields.length; i++) {
            Member field = fields[i];
            if (field == null || isMap(field.typeName)) continue;
            boolean listener = field.typeName.contains("SensorEventListener")
                    || field.typeName.contains("TriggerEventListener");
            if (!listener) continue;
            typed = i;
            if ("mListener".equals(field.name)) named = i;
        }
        return named >= 0 ? named : typed;
    }

    /** Index of the per-context service-cache slot, or -1. */
    public static int cacheIndexField(Member[] fields) {
        if (fields == null) return -1;
        int only = -1;
        int named = -1;
        int ints = 0;
        for (int i = 0; i < fields.length; i++) {
            Member field = fields[i];
            if (field == null || !"int".equals(field.typeName)) continue;
            ints++;
            only = i;
            String name = field.name.toLowerCase();
            if (name.contains("cache") || name.contains("index")) named = i;
        }
        if (named >= 0) return named;
        if (ints == 1) return only;
        return -1;
    }

    public static String strategy(boolean cacheArmed, boolean listenerMapArmed, boolean queueListenerReady) {
        if (listenerMapArmed && queueListenerReady && cacheArmed) return SERVICE_CACHE + "+" + LISTENER_MAP;
        if (listenerMapArmed && queueListenerReady) return LISTENER_MAP;
        if (queueListenerReady) return QUEUE_LISTENER;
        return NONE;
    }

    private static boolean isMap(String typeName) {
        return "java.util.Map".equals(typeName)
                || "java.util.HashMap".equals(typeName)
                || typeName.endsWith(".HashMap")
                || typeName.endsWith(".ArrayMap");
    }
}
