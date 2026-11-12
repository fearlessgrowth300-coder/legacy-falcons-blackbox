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
        System.out.println("AudioClientIdentityTest ok (11 checks)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
