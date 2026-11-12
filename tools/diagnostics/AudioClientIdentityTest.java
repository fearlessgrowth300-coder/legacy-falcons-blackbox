import top.niunaijun.blackbox.core.AudioClientIdentity;

public final class AudioClientIdentityTest {
    public static void main(String[] args) {
        check("top.niunaijun.blackbox".equals(AudioClientIdentity.packageForAudio(
                "com.instagram.android", "com.instagram.android", "top.niunaijun.blackbox")),
                "an Instagram reel uses the host package");
        check("top.niunaijun.blackbox".equals(AudioClientIdentity.packageForAudio(
                "com.facebook.katana", "com.facebook.katana", "top.niunaijun.blackbox")),
                "a Facebook track uses the host package");
        check(!"com.instagram.android".equals(AudioClientIdentity.packageForAudio(
                "com.instagram.android", "com.instagram.android", "top.niunaijun.blackbox")),
                "AudioFlinger is not told the clone package");
        check("top.niunaijun.blackbox".equals(AudioClientIdentity.packageForAudio(
                "top.niunaijun.blackbox", null, "top.niunaijun.blackbox")),
                "the host process keeps its own package");
        check("com.instagram.android".equals(AudioClientIdentity.packageForAudio(
                "com.instagram.android", "top.niunaijun.blackbox", "top.niunaijun.blackbox")),
                "no clone means the reported package stays");
        check(AudioClientIdentity.uidForAudio(true, 10477, 25351) == 10477,
                "the Linux uid wins over the hooked guest app id");
        check(AudioClientIdentity.uidForAudio(true, 0, 10477) == 10477,
                "a missing Linux uid falls back to the host uid");
        check(AudioClientIdentity.outputForStatus(AudioClientIdentity.NATIVE_INIT_FAILED) == 0,
                "native init failure is AudioFlinger output 0");
        check(AudioClientIdentity.outputForStatus(0) == -1,
                "a created track does not return the io handle");
        String line = AudioClientIdentity.createTrackLog(
                "top.niunaijun.blackbox", 10477, 0, 0, -20, true);
        check("createTrack package=top.niunaijun.blackbox uid=10477 session=0 output=0 status=-20 parcel=host".equals(line),
                "the createTrack log carries package uid session output and status");
        check(AudioClientIdentity.createTrackLog(
                "top.niunaijun.blackbox", 10477, 0, 0, -20, false).endsWith("parcel=kept"),
                "a failed parcel rewrite is visible in the log");
        String parcel5 = "(Ljava/lang/Object;Ljava/lang/Object;[IIIIII[ILandroid/os/Parcel;JZILjava/lang/Object;Ljava/lang/String;)I";
        int[] slots = AudioClientIdentity.nativeSetupSlots(parcel5);
        check(slots != null && slots[0] == 11 && slots[1] == 16 && slots[2] == 10 && slots[3] == 17,
                "the Android 16 AudioTrack descriptor maps parcel, string, and session");
        String extra = "(Ljava/lang/Object;Ljava/lang/Object;[IIIIII[ILandroid/os/Parcel;JZILjava/lang/Object;Ljava/lang/String;I)I";
        int[] extraSlots = AudioClientIdentity.nativeSetupSlots(extra);
        check(extraSlots != null && extraSlots[0] == 11 && extraSlots[1] == 16 && extraSlots[2] == 10,
                "a trailing argument does not move the parcel or the package string");
        check(AudioClientIdentity.nativeSetupSlots("(FF)I") == null,
                "a float signature is not hooked");
        check(AudioClientIdentity.nativeSetupSlots("(Ljava/lang/String;)V") == null,
                "a non-int return is not hooked");
        System.out.println("AudioClientIdentityTest ok (15 checks)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
