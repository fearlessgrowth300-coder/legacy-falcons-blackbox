package top.niunaijun.blackbox.core;

/**
 * Per-build-variant engine configuration. Each shipped flavor (orig/nova/vault/prism) sets its
 * {@link #tag} from BuildConfig.VARIANT_TAG in App.onCreate, and the virtualization engine reads
 * these knobs so the variants are genuinely different INTERNALLY — not just in package name:
 *
 *   - hookSeed()      -> different (deterministic) hook installation order per variant
 *   - stubPoolSize()  -> different number of declared/used proxy stub components per variant
 *                        (the flavor manifest removes the stubs above this index)
 *   - propSpoofSeed() -> different order in which device props are pushed to native
 *
 * These are wired off the tag so a detector can't rely on one identical container signature across
 * millions of installs. (Package name, data dir, provider authorities and the ":<token>N" process
 * names already differ per flavor too.)
 */
public final class VariantConfig {

    /** Set once, very early, from the app module's BuildConfig.VARIANT_TAG. Defaults to "orig". */
    public static volatile String tag = "orig";

    private VariantConfig() {}

    /** Stable, distinct-per-variant seed for deterministic orderings. */
    public static int seed() {
        String t = tag == null ? "orig" : tag;
        return t.hashCode();
    }

    /** Hook installation order seed (HookManager). */
    public static long hookSeed() {
        return seed() * 0x9E3779B1L;   // spread bits
    }

    /** Device-property push order seed (DeviceProfile.apply). */
    public static long propSpoofSeed() {
        return seed() * 0x27D4EB2FL + 17;
    }

    /**
     * Number of proxy stub components (activities/services/…​) this variant declares and uses.
     * Must be &lt;= 50 (the classes P0..P49 that exist) and match what the flavor manifest keeps.
     * Every stub index is derived from a process bpid in [0, stubPoolSize), so capping here caps
     * the max concurrent guest processes — 40+ is far more than a handful of clones needs.
     */
    public static int stubPoolSize() {
        String t = tag == null ? "orig" : tag;
        switch (t) {
            case "nova":  return 46;
            case "vault": return 44;
            case "prism": return 42;
            case "orig":
            default:      return 50;
        }
    }

    /**
     * Native engine library base name (the "x" in lib&lt;x&gt;.so). Each variant ships the engine
     * under a DIFFERENT filename so all 4 don't share the tell-tale "libblackbox.so" in
     * /proc/self/maps / the APK lib dir. The build renames + patches the .so to match this.
     * MUST stay in sync with the rename map in app/build.gradle.
     */
    public static String libName() {
        String t = tag == null ? "orig" : tag;
        switch (t) {
            case "nova":  return "nvspace";
            case "vault": return "cvault";
            case "prism": return "prism";
            case "orig":
            default:      return "blackbox";
        }
    }
}
