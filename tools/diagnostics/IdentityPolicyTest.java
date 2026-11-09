import java.util.concurrent.atomic.AtomicInteger;
import top.niunaijun.blackbox.core.CallerUidResolver;
import top.niunaijun.blackbox.fake.service.context.providers.ProviderCallIdentity;

public final class IdentityPolicyTest {
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
