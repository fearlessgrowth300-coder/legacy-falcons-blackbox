import java.lang.reflect.InvocationTargetException;
import top.niunaijun.blackbox.utils.compat.ApplicationCreationCompat;

public final class ApplicationCreationCompatTest {
    public static final class Instrumentation {}
    public static final class Factory {
        static Object cached;
        int legacyCalls;
        int cachedCalls;
        public Object makeApplication(boolean forceDefault, Instrumentation instrumentation) {
            check(!forceDefault && instrumentation == null, "defer onCreate, preserve guest class");
            legacyCalls++;
            return new Object();
        }
        public Object makeApplicationInner(boolean forceDefault, Instrumentation instrumentation) {
            check(!forceDefault && instrumentation == null, "cached factory arguments");
            cachedCalls++;
            if (cached == null) cached = new Object();
            return cached;
        }
    }
    public static final class BrokenFactory {
        int legacyCalls;
        public Object makeApplicationInner(boolean forceDefault, Instrumentation instrumentation) {
            throw new IllegalStateException("guest initialization failed");
        }
        public Object makeApplication(boolean forceDefault, Instrumentation instrumentation) {
            legacyCalls++;
            return new Object();
        }
    }
    public static final class LegacyOnly {
        int calls;
        public Object makeApplication(boolean forceDefault, Instrumentation instrumentation) {
            calls++;
            return new Object();
        }
    }
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }
    public static void main(String[] args) throws Exception {
        Factory first = new Factory(), second = new Factory();
        Object app = ApplicationCreationCompat.create(first, Instrumentation.class, true);
        check(app == ApplicationCreationCompat.create(second, Instrumentation.class, true),
                "newer framework entry point reuses Application across LoadedApk instances");
        check(first.legacyCalls == 0 && first.cachedCalls == 1 && second.cachedCalls == 1,
                "modern dispatch uses only cached factory");
        ApplicationCreationCompat.create(first, Instrumentation.class, false);
        check(first.legacyCalls == 1, "older Android retains legacy factory");
        BrokenFactory broken = new BrokenFactory();
        try {
            ApplicationCreationCompat.create(broken, Instrumentation.class, true);
            throw new AssertionError("guest exception was swallowed");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalStateException, "preserve original cause");
        }
        check(broken.legacyCalls == 0, "never retry a failed guest initialization");
        LegacyOnly missing = new LegacyOnly();
        try {
            ApplicationCreationCompat.create(missing, Instrumentation.class, true);
            throw new AssertionError("missing cached factory was ignored");
        } catch (NoSuchMethodException expected) {
            check(missing.calls == 0, "missing factory must not silently allow duplicates");
        }
        System.out.println("Application creation compatibility checks passed.");
    }
}
