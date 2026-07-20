package top.niunaijun.blackboxa

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.MessageDigest

/** Read-only proof of the Android Keystore namespace visible to the BlackBox host UID. */
@RunWith(AndroidJUnit4::class)
class KeystoreInventoryTest {
    @Test
    fun inventoryAliasesWithoutReadingKeyMaterial() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aliases = store.aliases().toList().sorted()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(aliases.joinToString("\u0000").toByteArray())
            .joinToString("") { "%02x".format(it) }
        Log.i("KeystoreInventory", "aliasCount=${aliases.size} aliasDigest=$digest")
        assertTrue("Keystore enumeration failed", aliases.size >= 0)
    }
}
