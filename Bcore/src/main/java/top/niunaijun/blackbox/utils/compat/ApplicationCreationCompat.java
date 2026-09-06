package top.niunaijun.blackbox.utils.compat;

import java.lang.reflect.Method;

/** Selects the framework's cached application factory on Android 13 and newer. */
public final class ApplicationCreationCompat {
    private ApplicationCreationCompat() {}

    // The parameter type is supplied so this reflection boundary can be tested
    // without Android. Production always passes android.app.Instrumentation.class.
    public static Object create(Object loadedApk, Class<?> instrumentationType,
                                boolean useCachedEntryPoint) throws ReflectiveOperationException {
        String methodName = useCachedEntryPoint ? "makeApplicationInner" : "makeApplication";
        Method factory = loadedApk.getClass().getDeclaredMethod(
                methodName, boolean.class, instrumentationType);
        factory.setAccessible(true);
        // null Instrumentation defers onCreate until BlackBox installs providers.
        // Never retry with a default Application after a guest constructor fails.
        return factory.invoke(loadedApk, false, null);
    }
}
