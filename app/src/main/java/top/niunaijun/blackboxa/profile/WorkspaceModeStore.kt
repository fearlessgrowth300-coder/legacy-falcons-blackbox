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

    private fun key(workspaceId: Int) = "mode_$workspaceId"
}
