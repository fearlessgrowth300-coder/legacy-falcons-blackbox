package top.niunaijun.blackboxa.util

import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.app.AppManager

/** Produces the same stable clone label in the BlackBox UI and external bridge. */
object CloneNameResolver {
    private const val INSTAGRAM_PACKAGE = "com.instagram.android"

    fun resolve(userId: Int, packageName: String, fallback: String): String {
        // A name explicitly chosen by the user must always win. Previously Instagram's
        // generated ordinal was returned first, which made a successfully committed rename
        // appear to disappear as soon as the app list was refreshed or BlackBox restarted.
        val custom = AppManager.mRemarkSharedPreferences
            .getString("cloneName_${userId}_${packageName}", null)
        if (!custom.isNullOrBlank()) return custom

        // Instagram is commonly cloned many times and duplicate labels make a proxy assignment
        // dangerously ambiguous. Derive its ordinal from the actual installed clone users so the
        // identity survives process restarts when the user has not chosen a custom name.
        if (packageName == INSTAGRAM_PACKAGE) {
            try {
                val instagramUsers = BlackBoxCore.get().users
                    .map { it.id }
                    .sorted()
                    .filter { candidateId ->
                        BlackBoxCore.get().getInstalledApplications(0, candidateId)
                            ?.any { it.packageName == INSTAGRAM_PACKAGE } == true
                    }
                val ordinal = instagramUsers.indexOf(userId)
                if (ordinal >= 0) return "Instagram ${ordinal + 1}"
            } catch (_: Throwable) {
                // Fall through to the package label.
            }
        }

        return fallback
    }
}
