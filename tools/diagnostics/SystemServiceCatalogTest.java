import java.nio.file.Files;
import java.nio.file.Path;

import top.niunaijun.blackbox.core.CallerIdentityParcels;
import top.niunaijun.blackbox.core.SystemCallerIdentity;
import top.niunaijun.blackbox.core.SystemServiceCatalog;

public final class SystemServiceCatalogTest {
    public static final class ClipboardCall {
        public String callingPackage;
        public String opPackageName;
    }

    public static void main(String[] args) throws Exception {
        Object[] call = new Object[] {"com.facebook.katana", "attribution", 0, 0};
        check(SystemCallerIdentity.rewrite(call, "com.facebook.katana", "top.niunaijun.blackbox") == 1,
                "a clipboard callingPackage string is rewritten");
        check("top.niunaijun.blackbox".equals(call[0]), "the phone sees the host package");
        check("attribution".equals(call[1]), "the attribution tag stays");

        ClipboardCall parcel = new ClipboardCall();
        parcel.callingPackage = "com.facebook.katana";
        parcel.opPackageName = "com.facebook.katana";
        check(CallerIdentityParcels.rewrite(new Object[] {parcel},
                "com.facebook.katana", "top.niunaijun.blackbox") == 2,
                "callingPackage and opPackageName fields are rewritten");
        check("top.niunaijun.blackbox".equals(parcel.callingPackage)
                        && "top.niunaijun.blackbox".equals(parcel.opPackageName),
                "both parcel fields name the host");

        for (String field : SystemServiceCatalog.callerPackageFields()) {
            check(CallerIdentityParcels.isCallerPackageField(field), field + " is a caller field");
        }

        String hooks = Files.readString(Path.of(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java"));
        for (String proxy : SystemServiceCatalog.forwardedProxies()) {
            check(hooks.contains("new " + proxy), proxy + " is installed");
        }
        String clipboard = Files.readString(Path.of(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IClipboardManagerProxy.java"));
        check(!clipboard.contains("method.invoke"),
                "clipboard methods do not call the phone clipboard");
        for (String method : SystemServiceCatalog.clipboardContained()) {
            check(clipboard.contains("@ProxyMethod(\"" + method + "\")"),
                    method + " stays inside the clone");
        }
        System.out.println("SystemServiceCatalogTest ok");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
