package top.niunaijun.blackbox.core.system.accounts;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts account secrets for the BlackBox server process.
 * The key is not exportable. Guests share the host uid, so this is at-rest
 * protection against copies of the file, not a kernel boundary.
 */
public final class AccountKeystore implements AccountSecrets.Sealer {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "blackbox_account_secrets_v1";
    private static volatile AccountKeystore instance;

    private AccountKeystore() {}

    public static AccountKeystore getOrNull() {
        AccountKeystore current = instance;
        if (current != null) return current;
        try {
            key();
            instance = new AccountKeystore();
            return instance;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public String seal(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] packed = new byte[1 + iv.length + encrypted.length];
        packed[0] = (byte) iv.length;
        System.arraycopy(iv, 0, packed, 1, iv.length);
        System.arraycopy(encrypted, 0, packed, 1 + iv.length, encrypted.length);
        return Base64.encodeToString(packed, Base64.NO_WRAP);
    }

    @Override
    public String open(String stored) throws Exception {
        byte[] packed = Base64.decode(stored, Base64.NO_WRAP);
        if (packed.length < 13) throw new IllegalArgumentException("short secret");
        int ivLength = packed[0] & 0xff;
        if (ivLength < 12 || ivLength >= packed.length) throw new IllegalArgumentException("bad iv");
        byte[] iv = new byte[ivLength];
        byte[] encrypted = new byte[packed.length - 1 - ivLength];
        System.arraycopy(packed, 1, iv, 0, ivLength);
        System.arraycopy(packed, 1 + ivLength, encrypted, 0, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
