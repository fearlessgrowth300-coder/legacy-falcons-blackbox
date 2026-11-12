package top.niunaijun.blackbox.utils.compat;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Stops a second {@code Application.onCreate} for the same instance from killing the guest.
 * WorkManager and several other libraries throw {@link IllegalStateException} when their
 * initializer runs twice in one process.
 */
public final class ApplicationOnCreateGuard {
    private static final Set<Object> INVOKED =
            Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());

    private ApplicationOnCreateGuard() {}

    public static boolean alreadyInvoked(Object application) {
        return application != null && INVOKED.contains(application);
    }

    public static void markInvoked(Object application) {
        if (application != null) {
            INVOKED.add(application);
        }
    }

    /** Visible to tests so a fresh process is not required between cases. */
    public static void resetForTests() {
        INVOKED.clear();
    }

    public static boolean isDuplicateInitialization(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (cursor instanceof IllegalStateException && message != null
                    && (message.contains("already initialized")
                    || message.contains("already created")
                    || message.contains("Application instance was already"))) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
