package top.niunaijun.blackboxa.profile

import android.content.Context
import android.content.Intent
import android.os.UserHandle

/** User-0 record of the Android work profile and the one workspace bound to it. */
object ManagedProfileStore {
    private const val PREF = "managed_profile_state"
    private const val ANDROID_USER = "android_user_id"
    private const val BOUND = "bound_workspace_id"
    private const val PENDING = "pending_workspace_id"

    fun androidUserId(context: Context): Int = prefs(context).getInt(ANDROID_USER, -1)

    fun rememberAndroidUser(context: Context, userId: Int) {
        if (userId < 0) return
        prefs(context).edit().putInt(ANDROID_USER, userId).apply()
    }

    fun boundWorkspaceId(context: Context): Int = prefs(context).getInt(BOUND, -1)

    fun setPendingWorkspace(context: Context, workspaceId: Int) {
        prefs(context).edit().putInt(PENDING, workspaceId).apply()
    }

    fun bindWorkspace(context: Context, workspaceId: Int) {
        if (workspaceId < 0) return
        prefs(context).edit().putInt(BOUND, workspaceId).apply()
    }

    fun clearBoundWorkspace(context: Context) {
        prefs(context).edit().remove(BOUND).apply()
    }

    fun commitPendingWorkspace(context: Context) {
        val pending = prefs(context).getInt(PENDING, -1)
        if (pending >= 0) {
            prefs(context).edit().putInt(BOUND, pending).remove(PENDING).apply()
        }
    }

    fun capture(context: Context, intent: Intent?) {
        val id = userIdFrom(intent) ?: return
        if (id < 0) return
        prefs(context).edit().putInt(ANDROID_USER, id).apply()
    }

    @Suppress("DEPRECATION")
    fun userIdFrom(intent: Intent?): Int? {
        if (intent == null) return null
        val handle = intent.getParcelableExtra<UserHandle>(Intent.EXTRA_USER)
        if (handle != null) {
            val id = userId(handle)
            if (id >= 0) return id
        }
        val asInt = intent.getIntExtra("android.intent.extra.USER_ID", -1)
        return if (asInt >= 0) asInt else null
    }

    private fun userId(user: UserHandle): Int {
        return try {
            val method = UserHandle::class.java.getDeclaredMethod("getIdentifier")
            method.isAccessible = true
            method.invoke(user) as Int
        } catch (error: Throwable) {
            -1
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
