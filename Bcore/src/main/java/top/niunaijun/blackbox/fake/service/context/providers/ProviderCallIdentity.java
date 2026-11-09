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

    /** Legacy IContentProvider has callingPackage first; later Strings are payload. */
    public static void rewriteLegacyCaller(Object[] args, String callerPackage) {
        if (args != null && args.length > 0 && args[0] instanceof String) {
            args[0] = callerPackage;
        }
    }
}
