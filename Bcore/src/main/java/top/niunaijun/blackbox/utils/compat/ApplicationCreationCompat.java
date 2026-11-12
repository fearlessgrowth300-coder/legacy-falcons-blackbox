package top.niunaijun.blackbox.utils.compat;

import java.lang.reflect.Method;

/**
 * Selects the framework's cached application factory on Android 13 and newer.
 *
 * <p>The public {@code makeApplication} entry point deliberately allows a second
 * Application in the same process. Android 13+ then runs {@code onCreate} again
 * when another LoadedApk launches an activity, which crashes guests whose
 * {@code Application.onCreate} is not idempotent (WorkManager is the usual one).
 * The internal factory reuses the process-wide instance. Some OEM builds only
 * expose the three-argument form, so a missing two-argument method falls through
 * to that form with duplicate instances disabled.
 */
public final class ApplicationCreationCompat {
    private ApplicationCreationCompat() {}

    // The parameter type is supplied so this reflection boundary can be tested
    // without Android. Production always passes android.app.Instrumentation.class.
    public static Object create(Object loadedApk, Class<?> instrumentationType,
                                boolean useCachedEntryPoint) throws ReflectiveOperationException {
        if (useCachedEntryPoint) {
            try {
                return invoke(loadedApk, instrumentationType, "makeApplicationInner");
            } catch (NoSuchMethodException missingTwoArg) {
                return invokeThree(loadedApk, instrumentationType);
            }
        }
        return invoke(loadedApk, instrumentationType, "makeApplication");
    }

    private static Object invoke(Object loadedApk, Class<?> instrumentationType, String methodName)
            throws ReflectiveOperationException {
        Method factory = loadedApk.getClass().getDeclaredMethod(
                methodName, boolean.class, instrumentationType);
        factory.setAccessible(true);
        // null Instrumentation defers onCreate until BlackBox installs providers.
        // Never retry with a default Application after a guest constructor fails.
        return factory.invoke(loadedApk, false, null);
    }

    private static Object invokeThree(Object loadedApk, Class<?> instrumentationType)
            throws ReflectiveOperationException {
        Method factory = loadedApk.getClass().getDeclaredMethod(
                "makeApplicationInner", boolean.class, instrumentationType, boolean.class);
        factory.setAccessible(true);
        // The third argument is allowDuplicateInstances. False reuses the cached guest.
        return factory.invoke(loadedApk, false, null, false);
    }
}
