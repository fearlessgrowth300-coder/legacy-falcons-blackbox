import top.niunaijun.blackbox.core.ExitProbeBudget;
import top.niunaijun.blackbox.core.ProviderAcquireGuard;

public final class ProviderAcquireGuardTest {
    public static void main(String[] args) {
        ProviderAcquireGuard.Installed installed = name -> "com.facebook.katana".equals(name);
        check(ProviderAcquireGuard.ownedByInstalledPackage(
                "com.facebook.katana.provider.PlatformProvider", installed),
                "a clone authority names the installed package");
        check(!ProviderAcquireGuard.ownedByInstalledPackage(
                "com.google.android.gms.chimera", installed),
                "play services is not this clone");
        check(ProviderAcquireGuard.refuseHost(false, true),
                "an unresolved clone authority must not use the phone");
        check(!ProviderAcquireGuard.refuseHost(true, true),
                "a provider resolved in the clone is served there");
        check(ProviderAcquireGuard.blockUnhookedAcquire(true),
                "an unhooked acquire of a clone authority is refused");
        check(!ProviderAcquireGuard.blockUnhookedAcquire(false),
                "a phone-only authority can still be acquired");
        check(ProviderAcquireGuard.isAcquire("getContentProviderExternal"),
                "external provider acquires are covered");
        int[] lookups = {0};
        ProviderAcquireGuard.Installed reentrant = new ProviderAcquireGuard.Installed() {
            public boolean installed(String name) {
                lookups[0]++;
                // The package lookup acquires the host's own provider, which re-enters the guard.
                return ProviderAcquireGuard.ownedByInstalledPackage("top.niunaijun.blackbox.stub", this)
                        || "com.facebook.katana".equals(name);
            }
        };
        check(ProviderAcquireGuard.ownedByInstalledPackage("com.facebook.katana.provider", reentrant)
                && lookups[0] < 10, "a lookup that re-enters the guard does not recurse");
        check(ProviderAcquireGuard.ownedByInstalledPackage(
                "com.facebook.katana.provider.PlatformProvider", installed),
                "the guard is usable again after a nested lookup");
        check(ExitProbeBudget.ATTEMPTS >= 3, "a cold start gets more than one probe");
        check(ExitProbeBudget.CONNECT_TIMEOUT_MS >= 12000, "a cold dial has time to connect");
        check(ExitProbeBudget.retry(0) && !ExitProbeBudget.retry(ExitProbeBudget.ATTEMPTS - 1),
                "retries stop after the budget");
        System.out.println("ProviderAcquireGuardTest ok (12 checks)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
