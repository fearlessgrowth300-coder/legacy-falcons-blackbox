import java.util.concurrent.atomic.AtomicInteger;
import top.niunaijun.blackbox.core.CallerUidResolver;
import top.niunaijun.blackbox.fake.service.context.providers.LocalProviderCall;
import top.niunaijun.blackbox.fake.service.context.providers.ProviderCallIdentity;
import top.niunaijun.blackbox.fake.service.context.providers.UidPackageIdentity;

public final class IdentityPolicyTest {
    public static final class CancellationSignalMarker {}

    private static int checks;
    private static void equal(Object expected, Object actual) {
        checks++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
    public static void main(String[] args) throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        java.util.function.IntUnaryOperator lookup = pid -> {
            lookups.incrementAndGet();
            return pid == 202 ? 10005 : -1;
        };
        equal(1000, CallerUidResolver.resolve(1000, 10477, 202, 101, 10004, lookup));
        equal(10999, CallerUidResolver.resolve(10999, 10477, 202, 101, 10004, lookup));
        equal(10477, CallerUidResolver.resolve(10477, 10477, 0, 101, 10004, lookup));
        equal(10004, CallerUidResolver.resolve(10477, 10477, 101, 101, 10004, lookup));
        equal(0, lookups.get());
        equal(10005, CallerUidResolver.resolve(10477, 10477, 202, 101, 10004, lookup));
        equal(10477, CallerUidResolver.resolve(10477, 10477, 303, 101, 10004, lookup));
        equal(10477, CallerUidResolver.resolve(10477, 10477, 202, 101, 10004, p -> 1000));
        equal(110005, CallerUidResolver.resolve(10477, 10477, 202, 101, 10004, p -> 110005));
        Object payload = new Object();
        Object[] legacy = {"old.pkg", "authority", "get_accounts", "selection = ?", payload};
        ProviderCallIdentity.rewriteLegacyCaller(legacy, "caller.pkg");
        equal("caller.pkg", legacy[0]);
        equal("authority", legacy[1]);
        equal("get_accounts", legacy[2]);
        equal("selection = ?", legacy[3]);
        equal(payload, legacy[4]);
        Object envelope = new Object();
        Object[] modern = {envelope, "authority", "data"};
        ProviderCallIdentity.rewriteLegacyCaller(modern, "caller.pkg");
        equal(envelope, modern[0]);
        equal("authority", modern[1]);
        equal("data", modern[2]);
        ProviderCallIdentity.rewriteLegacyCaller(null, "caller.pkg");
        ProviderCallIdentity.rewriteLegacyCaller(new Object[0], "caller.pkg");
        equal(10004, ProviderCallIdentity.attributionUid(26, true, 10004, 10477));
        equal(10477, ProviderCallIdentity.attributionUid(26, false, 10004, 10477));
        equal(10004, ProviderCallIdentity.attributionUid(31, true, 10004, 10477));
        equal(10004, ProviderCallIdentity.attributionUid(36, true, 10004, 10477));
        equal(10477, ProviderCallIdentity.attributionUid(36, false, 10004, 10477));
        equal(true, ProviderCallIdentity.sameProcessProvider(true, "com.facebook.katana", "top.niunaijun.blackbox"));
        equal(false, ProviderCallIdentity.sameProcessProvider(false, "com.facebook.katana", "top.niunaijun.blackbox"));
        equal(false, ProviderCallIdentity.sameProcessProvider(true, "top.niunaijun.blackbox", "top.niunaijun.blackbox"));
        equal("com.instagram.android", ProviderCallIdentity.callerPackage(true, "com.instagram.android", "top.niunaijun.blackbox"));
        equal("top.niunaijun.blackbox", ProviderCallIdentity.callerPackage(false, "com.instagram.android", "top.niunaijun.blackbox"));
        equal(10004, ProviderCallIdentity.presentedUid(true, 10477, 10004));
        equal(25351, ProviderCallIdentity.presentedUid(true, 10477, 2225351));
        equal(10004, ProviderCallIdentity.presentedUid(false, 10477, 10004));
        equal(25351, top.niunaijun.blackbox.core.GuestUid.myUid(true, 2225351, 10477));
        equal(10477, top.niunaijun.blackbox.core.GuestUid.myUid(false, 2225351, 10477));
        equal(10477, top.niunaijun.blackbox.core.GuestUid.myUid(true, 0, 10477));
        equal(true, ProviderCallIdentity.selfProvider("com.facebook.katana", "com.facebook.katana", "top.niunaijun.blackbox", true));
        equal(true, ProviderCallIdentity.selfProvider("top.niunaijun.blackbox", "com.facebook.katana", "top.niunaijun.blackbox", true));
        equal(false, ProviderCallIdentity.selfProvider("top.niunaijun.blackbox", "com.facebook.katana", "top.niunaijun.blackbox", false));
        equal(false, ProviderCallIdentity.selfProvider("com.instagram.android", "com.facebook.katana", "top.niunaijun.blackbox", true));
        equal(true, LocalProviderCall.isDirectCall("delete"));
        equal(true, LocalProviderCall.isDirectCall("bulkInsert"));
        equal(true, LocalProviderCall.isDirectCall("openFile"));
        equal(false, LocalProviderCall.isDirectCall("asBinder"));
        Object[] ipc = {"com.facebook.katana", "content://dedup", "payload"};
        Object[] direct = LocalProviderCall.directArguments(ipc);
        equal(2, direct.length);
        equal("content://dedup", direct[0]);
        equal("payload", direct[1]);
        Object[] kept = LocalProviderCall.directArguments(new Object[]{"not-a-caller-wait"});
        equal(0, kept.length);
        Object marker = new CancellationSignalMarker();
        Object[] withSignal = {"uri", marker};
        Object[] noSignal = LocalProviderCall.withoutTrailingSignal(withSignal);
        equal(1, noSignal.length);
        equal("uri", noSignal[0]);
        equal(true, UidPackageIdentity.representsGuest(10477, 10477, 10022, 10022));
        equal(true, UidPackageIdentity.representsGuest(10022, 10477, 10022, 22));
        equal(true, UidPackageIdentity.representsGuest(22, 10477, 10022, 22));
        equal(false, UidPackageIdentity.representsGuest(1000, 10477, 10022, 22));
        equal("com.facebook.katana", UidPackageIdentity.nameForUid(10477, 10477, 10022, 22, "com.facebook.katana"));
        equal(null, UidPackageIdentity.nameForUid(1000, 10477, 10022, 22, "com.facebook.katana"));
        String[] filled = UidPackageIdentity.packagesForCaller(10477, 10477, 10022, 22, "com.facebook.katana", new String[0]);
        equal(1, filled.length);
        equal("com.facebook.katana", filled[0]);
        String[] already = UidPackageIdentity.packagesForCaller(10477, 10477, 10022, 22, "com.facebook.katana", new String[]{"com.facebook.katana"});
        equal(1, already.length);
        equal(0, UidPackageIdentity.packagesForCaller(1000, 10477, 10022, 22, "com.facebook.katana", new String[0]).length);
        byte[] cert = new byte[]{1, 2, 3, 4};
        equal(true, top.niunaijun.blackbox.utils.compat.SigningCertificateMatch.matches(
                new byte[][]{cert}, cert, top.niunaijun.blackbox.utils.compat.SigningCertificateMatch.RAW_X509));
        equal(false, top.niunaijun.blackbox.utils.compat.SigningCertificateMatch.matches(
                new byte[][]{cert}, new byte[]{9}, top.niunaijun.blackbox.utils.compat.SigningCertificateMatch.RAW_X509));
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(cert);
        equal(true, top.niunaijun.blackbox.utils.compat.SigningCertificateMatch.matches(
                new byte[][]{cert}, digest, top.niunaijun.blackbox.utils.compat.SigningCertificateMatch.SHA256));
        java.io.File dir = java.nio.file.Files.createTempDirectory("caller-uid").toFile();
        equal(true, top.niunaijun.blackbox.core.CallerUidMap.publish(dir, 16258, 10004));
        equal(true, top.niunaijun.blackbox.core.CallerUidMap.publish(dir, 16314, 10005));
        equal(10004, top.niunaijun.blackbox.core.CallerUidMap.lookup(dir, 16258));
        equal(10005, top.niunaijun.blackbox.core.CallerUidMap.lookup(dir, 16314));
        equal(-1, top.niunaijun.blackbox.core.CallerUidMap.lookup(dir, 1));
        top.niunaijun.blackbox.core.CallerUidMap.clear(dir, 16258);
        equal(-1, top.niunaijun.blackbox.core.CallerUidMap.lookup(dir, 16258));
        System.out.println("PASS: " + checks + " identity/payload checks");
    }
}
