package top.niunaijun.blackbox.core;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-bound authenticated storage for per-clone proxy credentials. */
final class ProxyConfigCrypto {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "blackbox_proxy_config_v1";
    private static final byte[] MAGIC = new byte[]{'B', 'X', 'P', '1'};
    private static final int MAX_CONFIG_BYTES = 64 * 1024;

    private ProxyConfigCrypto() {}

    static String readText(File target, int userId, String pkg) throws Exception {
        byte[] stored = readLimited(target);
        if (!startsWith(stored, MAGIC)) {
            String legacy = new String(stored, StandardCharsets.UTF_8);
            // Only migrate structurally valid legacy JSON. Invalid legacy state remains a hard
            // launch failure and is never mistaken for a missing/unmanaged route.
            new JSONObject(legacy);
            writeText(target, userId, pkg, legacy);
            return legacy;
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(stored))) {
            byte[] magic = new byte[MAGIC.length];
            input.readFully(magic);
            int ivLength = input.readUnsignedByte();
            if (ivLength < 12 || ivLength > 32) throw new SecurityException("Invalid proxy config IV");
            byte[] iv = new byte[ivLength];
            input.readFully(iv);
            byte[] encrypted = new byte[input.available()];
            input.readFully(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(userId, pkg));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        }
    }

    static void writeText(File target, int userId, String pkg, String value) throws Exception {
        byte[] plain = value.getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_CONFIG_BYTES) throw new SecurityException("Proxy config too large");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(aad(userId, pkg));
        byte[] encrypted = cipher.doFinal(plain);
        byte[] stored;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(buffer)) {
            output.write(MAGIC);
            output.writeByte(cipher.getIV().length);
            output.write(cipher.getIV());
            output.write(encrypted);
            output.flush();
            stored = buffer.toByteArray();
        }

        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IllegalStateException("Proxy config directory unavailable");
        }
        AtomicFile atomic = new AtomicFile(target);
        FileOutputStream output = atomic.startWrite();
        try {
            output.write(stored);
            output.getFD().sync();
            atomic.finishWrite(output);
        } catch (Exception e) {
            atomic.failWrite(output);
            throw e;
        }
    }

    static boolean delete(File target) {
        new AtomicFile(target).delete();
        return !target.exists() && !new File(target.getPath() + ".bak").exists();
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static byte[] aad(int userId, String pkg) {
        String identity = pkg == null || pkg.isEmpty() ? "_user" : pkg;
        return ("BlackBoxProxy|1|" + userId + "|" + identity).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readLimited(File target) throws Exception {
        if (target.length() <= 0 || target.length() > MAX_CONFIG_BYTES) {
            throw new SecurityException("Invalid proxy config size");
        }
        try (FileInputStream input = new FileInputStream(target);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) target.length())) {
            byte[] buffer = new byte[4096];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_CONFIG_BYTES) throw new SecurityException("Proxy config too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }
}
