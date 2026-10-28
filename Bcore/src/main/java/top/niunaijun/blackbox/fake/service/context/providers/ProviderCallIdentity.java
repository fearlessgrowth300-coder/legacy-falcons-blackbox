package top.niunaijun.blackbox.fake.service.context.providers;

/** Legacy IContentProvider has callingPackage first; later Strings are payload. */
public final class ProviderCallIdentity {
    private ProviderCallIdentity() {}
    public static void rewriteLegacyCaller(Object[] args, String callerPackage) {
        if (args != null && args.length > 0 && args[0] instanceof String) {
            args[0] = callerPackage;
        }
    }
}
