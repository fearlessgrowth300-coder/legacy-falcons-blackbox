package top.niunaijun.blackboxa;

import android.app.Activity;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Offline standalone probe for two fresh BlackBox users using the same logical key alias. */
public final class KeystoreProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String result;
        try {
            result = "PASS\n" + runProbe();
        } catch (Throwable t) {
            result = "FAIL\n" + t.getClass().getSimpleName() + ": "
                    + (t.getMessage() == null ? "" : t.getMessage());
        }
        Log.i("KeystoreProbe", result.replace('\n', ' '));
        TextView text = new TextView(this);
        text.setText(result);
        text.setTextSize(22f);
        text.setPadding(48, 96, 48, 48);
        setContentView(text);
    }

    private String runProbe() throws Exception {
        String alias = "same_logical_login_key";
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            generator.generateKey();
        }
        SecretKey key = (SecretKey) store.getKey(alias, null);
        byte[] plain = "clone-keystore-roundtrip".getBytes(StandardCharsets.UTF_8);
        Cipher encrypt = Cipher.getInstance("AES/GCM/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = encrypt.doFinal(plain);
        Cipher decrypt = Cipher.getInstance("AES/GCM/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, encrypt.getIV()));
        byte[] decrypted = decrypt.doFinal(encrypted);
        if (!java.util.Arrays.equals(plain, decrypted)) {
            throw new IllegalStateException("AES round-trip mismatch");
        }
        List<String> visible = new ArrayList<>();
        Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements()) visible.add(aliases.nextElement());
        if (!visible.contains(alias)) throw new IllegalStateException("Logical alias hidden from owner");
        for (String visibleAlias : visible) {
            if (visibleAlias.startsWith("bbx1_")) {
                throw new IllegalStateException("Internal namespace exposed");
            }
        }
        return "AndroidKeyStore round-trip OK\nVisible aliases: " + visible.size()
                + "\nLogical alias: " + alias;
    }
}
