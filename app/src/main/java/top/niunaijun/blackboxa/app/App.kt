package top.niunaijun.blackboxa.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.cloud.CloudSync
import top.niunaijun.blackboxa.cloud.CrashReporter
import top.niunaijun.blackboxa.cloud.Supabase


class App : Application() {

    companion object {

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private lateinit var mContext: Context

        @JvmStatic
        fun getContext(): Context {
            return mContext
        }
    }

    override fun attachBaseContext(base: Context?) {
        try {
            super.attachBaseContext(base)
            if (base != null) CloudSync.recoverInterruptedRestore(base)

            // Tell the engine which build variant this is — must be the FIRST thing, before any
            // hooks or process allocation, so variant-specific behavior (hook order, stub pool
            // size, prop-push order) is in effect from the very start. Runs in every process.
            try {
                top.niunaijun.blackbox.core.VariantConfig.tag =
                    top.niunaijun.blackboxa.BuildConfig.VARIANT_TAG
            } catch (e: Throwable) {
                Log.e("App", "VariantConfig init failed: ${e.message}")
            }

            try {
                BlackBoxCore.get().closeCodeInit()
            } catch (e: Exception) {
                Log.e("App", "Error in closeCodeInit: ${e.message}")
            }

            try {
                BlackBoxCore.get().onBeforeMainApplicationAttach(this, base)
            } catch (e: Exception) {
                Log.e("App", "Error in onBeforeMainApplicationAttach: ${e.message}")
            }

            mContext = base!!

            try {
                AppManager.doAttachBaseContext(base)
            } catch (e: Exception) {
                Log.e("App", "Error in doAttachBaseContext: ${e.message}")
            }

            try {

                BlackBoxCore.get().onAfterMainApplicationAttach(this, base)

            } catch (e: Exception) {

                Log.e("App", "Error in onAfterMainApplicationAttach: ${e.message}")

            }
        } catch (e: Exception) {
            Log.e("App", "Critical error in attachBaseContext: ${e.message}")
            if (base != null) {
                mContext = base
            }
        }
    }

    override fun onCreate() {
        try {
            super.onCreate()
            CrashReporter.install(this)
            CrashReporter.flushAsync(this)
            Supabase.retryPendingLogoutAsync(this)
            AppManager.doOnCreate(mContext)
        } catch (e: Exception) {
            Log.e("App", "Error in onCreate: ${e.message}")
        }
    }
}
