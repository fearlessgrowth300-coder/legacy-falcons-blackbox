package top.niunaijun.blackboxa.view.main

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import top.niunaijun.blackbox.BlackBoxCore
import android.content.Intent
import top.niunaijun.blackboxa.cloud.Supabase
import top.niunaijun.blackboxa.cloud.VaultKeyStore
import top.niunaijun.blackboxa.view.auth.AuthActivity


class ShortcutActivity:AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Supabase.isSignedIn(this) || !VaultKeyStore.isReady(this)) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        val pkg = intent.getStringExtra("pkg")
        val userID = intent.getIntExtra("userId",0)

        lifecycleScope.launch {
            BlackBoxCore.get().launchApk(pkg,userID)
            finish()
        }
    }
}
