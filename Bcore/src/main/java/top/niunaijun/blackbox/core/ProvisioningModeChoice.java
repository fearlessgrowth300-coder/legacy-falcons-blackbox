package top.niunaijun.blackbox.core;

import java.util.List;

/**
 * Picks the provisioning mode returned to ManagedProvisioning.
 * This app becomes a profile owner only. It never returns fully-managed device.
 *
 * Values match {@code DevicePolicyManager} on API 31+:
 * 1 fully managed, 2 managed profile, 3 managed profile on a personal device.
 */
public final class ProvisioningModeChoice {
    public static final int NONE = 0;
    public static final int FULLY_MANAGED_DEVICE = 1;
    public static final int MANAGED_PROFILE = 2;
    public static final int MANAGED_PROFILE_ON_PERSONAL_DEVICE = 3;

    private ProvisioningModeChoice() {}

    /**
     * Prefer a personally owned work profile. When the system sends no allow-list,
     * return managed profile, which is the mode for {@code ACTION_PROVISION_MANAGED_PROFILE}.
     * A non-empty list that omits both profile modes cannot be answered.
     */
    public static int choose(List<Integer> allowed) {
        if (allowed == null || allowed.isEmpty()) return MANAGED_PROFILE;
        boolean personal = false;
        for (Integer mode : allowed) {
            if (mode == null) continue;
            if (mode == MANAGED_PROFILE) return MANAGED_PROFILE;
            if (mode == MANAGED_PROFILE_ON_PERSONAL_DEVICE) personal = true;
        }
        return personal ? MANAGED_PROFILE_ON_PERSONAL_DEVICE : NONE;
    }
}
