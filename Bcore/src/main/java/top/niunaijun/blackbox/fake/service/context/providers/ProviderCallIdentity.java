package top.niunaijun.blackbox.fake.service.context.providers;

/** Caller identity carried into a content-provider transaction. */
public final class ProviderCallIdentity {
    private ProviderCallIdentity() {}

    /**
     * API 31+ unparcels AttributionSource inside the provider and requires its uid
     * to equal Binder.getCallingUid(). A virtual provider sees the sender's virtual
     * uid. A host provider runs in system_server and sees the host uid. API 26–30
     * still uses the callingPackage string, so the same split is safe there.
     */
    public static int attributionUid(int apiLevel, boolean virtualProvider,
                                     int callerUid, int hostUid) {
        if (!virtualProvider) return hostUid;
        // API 31+ compares this value with Binder.getCallingUid in the provider.
        // API 26–30 names the caller through callingPackage and ignores the uid.
        if (apiLevel < 31) return callerUid;
        return callerUid;
    }

    /**
     * A local binder is this process. Guest providers run here; system providers do not.
     * The host package is not a guest calling its own provider.
     */
    public static boolean sameProcessProvider(boolean localBinder, String guestPackage, String hostPackage) {
        return localBinder && guestPackage != null && !guestPackage.isEmpty()
                && !guestPackage.equals(hostPackage);
    }

    /**
     * A provider that belongs to this guest. A local binder labeled with the host
     * package is still the guest's own provider; system providers are not local.
     */
    public static boolean selfProvider(String providerPackage, String guestPackage,
                                       String hostPackage, boolean localBinder) {
        if (guestPackage == null || guestPackage.isEmpty() || guestPackage.equals(hostPackage)) {
            return false;
        }
        if (guestPackage.equals(providerPackage)) return true;
        return localBinder && (providerPackage == null || providerPackage.equals(hostPackage));
    }

    /** Same-app calls must name the guest. Other calls keep the host package. */
    public static String callerPackage(boolean sameProcess, String guestPackage, String hostPackage) {
        return sameProcess ? guestPackage : hostPackage;
    }

    /**
     * A self-call must report the same uid {@code Process.myUid()} reports.
     * That is the guest app id, not the Linux host uid.
     */
    public static int presentedUid(boolean sameProcess, int processUid, int virtualUid) {
        return sameProcess
                ? top.niunaijun.blackbox.core.GuestUid.myUid(true, virtualUid, processUid)
                : virtualUid;
    }

    /** Legacy IContentProvider has callingPackage first; later Strings are payload. */
    public static void rewriteLegacyCaller(Object[] args, String callerPackage) {
        if (args != null && args.length > 0 && args[0] instanceof String) {
            args[0] = callerPackage;
        }
    }
}
