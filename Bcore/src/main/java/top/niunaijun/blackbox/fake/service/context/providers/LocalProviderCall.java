package top.niunaijun.blackbox.fake.service.context.providers;

/**
 * A guest calling its own provider should hit the local {@code ContentProvider}
 * methods. {@code ContentProvider.Transport} is the Binder entry point and
 * presents the host uid to the provider's caller check.
 */
public final class LocalProviderCall {
    /** No local method matched. Distinct from a provider method that returns null. */
    public static final Object NO_MATCH = new Object();

    private LocalProviderCall() {}

    public static boolean isDirectCall(String method) {
        return "query".equals(method) || "insert".equals(method) || "update".equals(method)
                || "delete".equals(method) || "call".equals(method) || "bulkInsert".equals(method)
                || "openFile".equals(method) || "openAssetFile".equals(method)
                || "openTypedAssetFile".equals(method) || "applyBatch".equals(method)
                || "getType".equals(method) || "getStreamTypes".equals(method)
                || "canonicalize".equals(method) || "uncanonicalize".equals(method)
                || "refresh".equals(method);
    }

    /** Transport's first argument is the caller envelope, not provider data. */
    public static Object[] directArguments(Object[] ipcArgs) {
        if (ipcArgs == null || ipcArgs.length == 0 || !isCallerEnvelope(ipcArgs[0])) return ipcArgs;
        Object[] rest = new Object[ipcArgs.length - 1];
        System.arraycopy(ipcArgs, 1, rest, 0, rest.length);
        return rest;
    }

    public static boolean isCallerEnvelope(Object arg) {
        if (arg instanceof String) return true;
        return arg != null && arg.getClass().getName().contains("AttributionSource");
    }

    /** Local ContentProvider methods take CancellationSignal, not the Binder signal. */
    public static Object[] withoutTrailingSignal(Object[] args) {
        if (args == null || args.length == 0 || !isSignal(args[args.length - 1])) return args;
        Object[] rest = new Object[args.length - 1];
        System.arraycopy(args, 0, rest, 0, rest.length);
        return rest;
    }

    public static boolean isSignal(Object arg) {
        if (arg == null) return false;
        String name = arg.getClass().getName();
        return name.contains("CancellationSignal");
    }
}
