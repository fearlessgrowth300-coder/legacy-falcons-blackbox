import java.util.Arrays;
import top.niunaijun.blackbox.core.PineRuntime;
import top.niunaijun.blackbox.utils.compat.ApplicationOnCreateGuard;
import top.niunaijun.blackbox.utils.compat.BindServiceFlagsCompat;
import top.niunaijun.blackbox.utils.compat.ForegroundServiceTypeCompat;
import top.niunaijun.blackbox.utils.compat.ReceiverRegistrationCompat;
import top.niunaijun.blackbox.utils.compat.SettingsIdentity;

public final class GuestLaunchCompatTest {
    private static int checks;
    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        long externalLong = (1L << 62) | 1L;
        check(BindServiceFlagsCompat.stripExternal(externalLong) == 1L,
                "strip BIND_EXTERNAL_SERVICE_LONG and keep the other flags");
        check(BindServiceFlagsCompat.stripExternal(0x80000001L) == 1L,
                "strip the unsigned 32-bit external flag");
        check(BindServiceFlagsCompat.stripExternal(0xFFFFFFFF80000001L) == 1L,
                "strip a sign-extended external flag");
        check(Integer.valueOf(1).equals(BindServiceFlagsCompat.stripExternalServiceFlag(0x80000001)),
                "integer flags stay integers");
        check(BindServiceFlagsCompat.stripExternalServiceFlag(Long.valueOf(externalLong)) instanceof Long,
                "long flags stay longs");
        check(BindServiceFlagsCompat.instanceNameIndex("bindServiceInstance", 9) == 6,
                "instance name follows the flags");
        check(BindServiceFlagsCompat.instanceNameIndex("bindService", 8) == -1,
                "plain bindService has no instance name");

        check(ForegroundServiceTypeCompat.supportedType(0)
                        == ForegroundServiceTypeCompat.SPECIAL_USE,
                "a missing type stays in the foreground as specialUse");
        check(ForegroundServiceTypeCompat.supportedType(ForegroundServiceTypeCompat.CAMERA)
                        == ForegroundServiceTypeCompat.CAMERA,
                "a declared camera type is preserved");
        check(ForegroundServiceTypeCompat.supportedType(ForegroundServiceTypeCompat.MEDIA_PROJECTION)
                        == ForegroundServiceTypeCompat.SPECIAL_USE,
                "an undeclared type falls back to specialUse");
        check(ForegroundServiceTypeCompat.supportedType(
                        ForegroundServiceTypeCompat.LOCATION | ForegroundServiceTypeCompat.SHORT_SERVICE)
                        == ForegroundServiceTypeCompat.LOCATION,
                "shortService is not kept when a declared type is present");
        check(ForegroundServiceTypeCompat.isStopRequest(new Object[]{"name", null, 0, null, 0}),
                "notification id 0 is a stop");
        check(!ForegroundServiceTypeCompat.isStopRequest(new Object[]{"name", null, 7, null, 0}),
                "a positive notification id is a start");

        int exported = ReceiverRegistrationCompat.ensureExportFlag(0, true);
        check((exported & ReceiverRegistrationCompat.RECEIVER_EXPORTED) != 0,
                "system broadcasts are exported");
        int hidden = ReceiverRegistrationCompat.ensureExportFlag(0, false);
        check((hidden & ReceiverRegistrationCompat.RECEIVER_NOT_EXPORTED) != 0,
                "app-private receivers stay unexported");
        check(ReceiverRegistrationCompat.ensureExportFlag(ReceiverRegistrationCompat.RECEIVER_EXPORTED, false)
                        == ReceiverRegistrationCompat.RECEIVER_EXPORTED,
                "an explicit flag is preserved");
        check(ReceiverRegistrationCompat.looksLikeSystemBroadcast(
                        Arrays.asList("android.intent.action.SCREEN_ON")),
                "SCREEN_ON is a system broadcast");
        check(!ReceiverRegistrationCompat.looksLikeSystemBroadcast(Arrays.asList("com.example.PRIVATE")),
                "a private action is not treated as a system broadcast");

        check(SettingsIdentity.isSecureAndroidIdCall(new Object[]{
                        "caller", "settings", "GET_secure", "android_id"}),
                "secure android_id call is recognized");
        check(!SettingsIdentity.isSecureAndroidIdCall(new Object[]{"GET_system", "android_id"}),
                "system settings are not rewritten as secure android_id");
        check(!SettingsIdentity.isSecureAndroidIdCall(new Object[]{"GET_secure", "bluetooth_on"}),
                "other secure settings pass through");

        check(!PineRuntime.hooksCrashThisRuntime(35, 4096L), "Pine remains available on 4 KB Android 15");
        check(PineRuntime.hooksCrashThisRuntime(35, 16384L), "Pine is skipped on 16 KB pages");
        check(PineRuntime.hooksCrashThisRuntime(36, 4096L), "Pine is skipped on Android 16");

        ApplicationOnCreateGuard.resetForTests();
        Object app = new Object();
        check(!ApplicationOnCreateGuard.alreadyInvoked(app), "first onCreate is allowed");
        ApplicationOnCreateGuard.markInvoked(app);
        check(ApplicationOnCreateGuard.alreadyInvoked(app), "second onCreate for the same instance is skipped");
        check(!ApplicationOnCreateGuard.alreadyInvoked(new Object()), "a different instance is not skipped");
        check(ApplicationOnCreateGuard.isDuplicateInitialization(
                        new IllegalStateException("WorkManager is already initialized")),
                "WorkManager double initialization is recognized");
        check(!ApplicationOnCreateGuard.isDuplicateInitialization(new IllegalStateException("disk full")),
                "unrelated initialization failures still propagate");

        System.out.println("PASS: " + checks + " guest launch compatibility checks");
    }
}
