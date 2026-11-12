package top.niunaijun.blackbox.core;

/**
 * Choices for a Blackbox workspace. SEPARATE means a real Android managed profile
 * (a different Linux uid). HOOKED means the in-process virtual user, which still
 * shares the host uid.
 */
public final class ManagedProfilePolicy {
    public enum Mode {
        HOOKED,
        SEPARATE
    }

    private ManagedProfilePolicy() {}

    /** The first workspace stays in this Android user. Later ones recommend a real profile. */
    public static Mode recommended(int workspaceId) {
        return workspaceId > 0 ? Mode.SEPARATE : Mode.HOOKED;
    }

    public static String shortLabel(Mode mode) {
        if (mode == Mode.SEPARATE) return "separate Android profile";
        return "same profile, hooked";
    }

    /**
     * @param provisioningAllowed DevicePolicyManager.isProvisioningAllowed
     * @param alreadyProfileOwner this install is already the profile owner
     * @param samsungContainerInstalled Knox or Secure Folder package is present
     * @param provisionActivityResolved the system can handle ACTION_PROVISION_MANAGED_PROFILE
     * @return null when provisioning can start, otherwise a message for the user
     */
    public static String blockReason(boolean provisioningAllowed, boolean alreadyProfileOwner,
                                     boolean samsungContainerInstalled, boolean provisionActivityResolved) {
        return blockReason(provisioningAllowed, alreadyProfileOwner, samsungContainerInstalled,
                provisionActivityResolved, false);
    }

    /**
     * @param ownedProfileKnown our managed profile is visible or was recorded, even from user 0
     *                          where {@code isProfileOwnerApp} is false
     */
    public static String blockReason(boolean provisioningAllowed, boolean alreadyProfileOwner,
                                     boolean samsungContainerInstalled, boolean provisionActivityResolved,
                                     boolean ownedProfileKnown) {
        if (alreadyProfileOwner || ownedProfileKnown) return null;
        if (!provisionActivityResolved) {
            return "This phone cannot start Android's managed-profile setup.";
        }
        if (provisioningAllowed) return null;
        if (samsungContainerInstalled) {
            return "Android will not create another managed profile. Samsung Secure Folder "
                    + "is already using the one work-profile slot. Remove that folder, or keep this "
                    + "workspace in the weaker hooked mode.";
        }
        return "Android will not create another managed profile. A work profile, device owner, "
                + "or OEM folder that is not this app is already using that slot. Remove it, or keep "
                + "this workspace in the weaker hooked mode.";
    }
}
