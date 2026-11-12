package top.niunaijun.blackbox.core;

/**
 * A content-provider authority that belongs to an installed clone must be
 * served by that clone. Asking the phone starts the real app.
 */
public final class ProviderAcquireGuard {
    private ProviderAcquireGuard() {}

    private static final ThreadLocal<Boolean> LOOKING_UP = new ThreadLocal<>();

    public static boolean ownedByInstalledPackage(String authority, Installed installed) {
        if (authority == null || authority.isEmpty() || installed == null) return false;
        // The installed() lookup reaches the package service through BlackBox's own
        // provider, whose acquire lands back here. That provider is never a clone's,
        // and asking again recursed until StackOverflowError and froze startup.
        if (LOOKING_UP.get() != null) return false;
        LOOKING_UP.set(Boolean.TRUE);
        try {
            String name = authority;
            while (!name.isEmpty()) {
                if (installed.installed(name)) return true;
                int dot = name.lastIndexOf('.');
                if (dot <= 0) return false;
                name = name.substring(0, dot);
            }
            return false;
        } finally {
            LOOKING_UP.remove();
        }
    }

    /** The clone did not resolve this authority, and the phone's copy must not be used. */
    public static boolean refuseHost(boolean resolvedInGuest, boolean ownedByInstalledGuest) {
        return !resolvedInGuest && ownedByInstalledGuest;
    }

    /** An unhooked acquire would hand the original authority to the phone. */
    public static boolean blockUnhookedAcquire(boolean ownedByInstalledGuest) {
        return ownedByInstalledGuest;
    }

    public static boolean isAcquire(String methodName) {
        return methodName != null && (methodName.startsWith("getContentProvider")
                || "openContentUri".equals(methodName));
    }

    public interface Installed {
        boolean installed(String packageName);
    }
}
