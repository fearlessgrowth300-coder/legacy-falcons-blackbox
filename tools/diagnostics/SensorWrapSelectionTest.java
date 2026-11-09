import top.niunaijun.blackbox.core.SensorWrapSelection;

public final class SensorWrapSelectionTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        SensorWrapSelection.Member[] manager = {
                new SensorWrapSelection.Member("mFullSensorsList", "java.util.ArrayList", "java.util.List<Sensor>"),
                new SensorWrapSelection.Member("mHandleToSensor", "android.util.SparseArray", "android.util.SparseArray<Sensor>"),
                new SensorWrapSelection.Member("mSensorListeners", "java.util.HashMap",
                        "java.util.HashMap<android.hardware.SensorEventListener, SensorEventQueue>"),
                new SensorWrapSelection.Member("mRuntimeLists", "java.util.HashMap",
                        "java.util.HashMap<java.lang.Integer, java.util.List>")
        };
        check(SensorWrapSelection.listenerMap(manager) == 2,
                "the listener map is chosen by its SensorEventListener type, not by being the only map");

        SensorWrapSelection.Member[] namedOnly = {
                new SensorWrapSelection.Member("mListeners", "java.util.HashMap", "java.util.HashMap<String, String>"),
                new SensorWrapSelection.Member("mOther", "java.util.HashMap", "java.util.HashMap<Integer, Integer>")
        };
        check(SensorWrapSelection.listenerMap(namedOnly) == 0,
                "a listener name wins when the generic type is erased");
        check(SensorWrapSelection.listenerMap(new SensorWrapSelection.Member[]{
                new SensorWrapSelection.Member("only", "java.util.HashMap", "")
        }) == 0, "a lone map is the listener map");
        check(SensorWrapSelection.listenerMap(new SensorWrapSelection.Member[]{
                new SensorWrapSelection.Member("a", "java.util.HashMap", ""),
                new SensorWrapSelection.Member("b", "java.util.HashMap", "")
        }) == -1, "two anonymous maps are not guessed");

        SensorWrapSelection.Member[] queue = {
                new SensorWrapSelection.Member("mSensorsEvents", "android.util.SparseArray", ""),
                new SensorWrapSelection.Member("mListener", "android.hardware.SensorEventListener", "")
        };
        check(SensorWrapSelection.queueListener(queue) == 1, "mListener is the queue listener");
        check(SensorWrapSelection.queueListener(new SensorWrapSelection.Member[]{
                new SensorWrapSelection.Member("callback", "android.hardware.SensorEventListener", "")
        }) == 0, "a SensorEventListener field is accepted without the historical name");

        SensorWrapSelection.Member[] fetcher = {
                new SensorWrapSelection.Member("mCacheIndex", "int", ""),
                new SensorWrapSelection.Member("mFlags", "int", "")
        };
        check(SensorWrapSelection.cacheIndexField(fetcher) == 0, "the cache index is the int named cache or index");
        check(SensorWrapSelection.cacheIndexField(new SensorWrapSelection.Member[]{
                new SensorWrapSelection.Member("mOnly", "int", "")
        }) == 0, "a single int is the cache index");

        check(SensorWrapSelection.strategy(true, true, true).equals("service-cache+listener-map"),
                "arming the cached manager through its listener map names both");
        check(SensorWrapSelection.strategy(false, true, true).equals("listener-map"),
                "a replaced listener map is enough");
        check(SensorWrapSelection.strategy(false, false, true).equals("queue-listener"),
                "a wrapped queue listener is reported on its own");
        check(SensorWrapSelection.strategy(false, false, false).equals("none"),
                "nothing installed stays fail closed");
        check(SensorWrapSelection.NONE.equals("none"), "the none token is stable");

        System.out.println("SensorWrapSelectionTest ok (" + checks + " checks)");
    }
}
