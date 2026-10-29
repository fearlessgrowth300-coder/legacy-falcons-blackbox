package top.niunaijun.blackboxa.profile

import android.content.Context
import top.niunaijun.blackbox.core.ManagedProfilePolicy

/** Remembers which isolation mode a Blackbox workspace uses. Missing entries use the recommendation. */
object WorkspaceModeStore {
    private const val PREF = "workspace_isolation_mode"

    fun mode(context: Context, workspaceId: Int): ManagedProfilePolicy.Mode {
        val stored = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(key(workspaceId), null)
        return when (stored) {
            ManagedProfilePolicy.Mode.SEPARATE.name -> ManagedProfilePolicy.Mode.SEPARATE
            ManagedProfilePolicy.Mode.HOOKED.name -> ManagedProfilePolicy.Mode.HOOKED
            else -> ManagedProfilePolicy.recommended(workspaceId)
        }
    }

    fun set(context: Context, workspaceId: Int, mode: ManagedProfilePolicy.Mode) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(key(workspaceId), mode.name)
            .apply()
    }

    /** Workspaces whose isolation choice was saved as separate, not merely recommended. */
    fun explicitSeparateIds(context: Context): IntArray {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return prefs.all.keys.mapNotNull { name ->
            if (!name.startsWith("mode_")) return@mapNotNull null
            val id = name.removePrefix("mode_").toIntOrNull() ?: return@mapNotNull null
            if (prefs.getString(name, null) == ManagedProfilePolicy.Mode.SEPARATE.name) id else null
        }.sorted().toIntArray()
    }

    /**
     * Launch and status labels follow the workspace bound to the Android profile.
     * A missing preference is hooked. Recommending separate for every id above 0
     * made every stored workspace look bound to the one work profile.
     */
    fun launchesInManagedProfile(context: Context, workspaceId: Int): Boolean {
        val bound = top.niunaijun.blackbox.core.ProfileStatusCopy.resolveBoundWorkspace(
            ManagedProfileStore.boundWorkspaceId(context),
            explicitSeparateIds(context)
        )
        return top.niunaijun.blackbox.core.ProfileStatusCopy.launchesSeparate(workspaceId, bound)
    }

    fun boundWorkspaceId(context: Context): Int {
        return top.niunaijun.blackbox.core.ProfileStatusCopy.resolveBoundWorkspace(
            ManagedProfileStore.boundWorkspaceId(context),
            explicitSeparateIds(context)
        )
    }

    private fun key(workspaceId: Int) = "mode_$workspaceId"
}
