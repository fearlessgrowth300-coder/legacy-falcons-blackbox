package top.niunaijun.blackbox.core;

import android.content.ClipData;
import android.content.Context;
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

import top.niunaijun.blackbox.BlackBoxCore;

/** Private clipboard storage. A clone never reads or writes the host/global clipboard. */
public final class CloneClipboardStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "blackbox_clone_clipboard_v1";
    private static final byte[] MAGIC = new byte[]{'B', 'X', 'C', '1'};
    private static final int MAX_BYTES = 128 * 1024;

    private CloneClipboardStore() {}

    public static synchronized void set(int userId, String pkg, ClipData clip) {
        File target = file(userId, pkg);
        try {
            if (clip == null || clip.getItemCount() == 0) {
                new AtomicFile(target).delete();
                return;
            }
            // URI and Intent clip items can carry cross-app grants. Only text is accepted.
            ClipData.Item item = clip.getItemAt(0);
            CharSequence text = item.getText();
            if (text == null) {
                new AtomicFile(target).delete();
                return;
            }
            String value = text.toString();
            if (value.length() > 65536) value = value.substring(0, 65536);
            JSONObject json = new JSONObject();
            json.put("text", value);
            CharSequence label = clip.getDescription() == null ? null : clip.getDescription().getLabel();
            json.put("label", label == null ? "" : label.toString());
            write(target, userId, pkg, json.toString());
        } catch (Throwable ignored) {
            // Fail closed: a failed write never falls through to the host clipboard.
            new AtomicFile(target).delete();
        }
    }

    public static synchronized ClipData get(int userId, String pkg) {
        File target = file(userId, pkg);
        if (!target.isFile()) return null;
        try {
            JSONObject json = new JSONObject(read(target, userId, pkg));
            return ClipData.newPlainText(json.optString("label", ""), json.getString("text"));
        } catch (Throwable ignored) {
            new AtomicFile(target).delete();
            return null;
        }
    }

    public static synchronized void clear(int userId, String pkg) {
        new AtomicFile(file(userId, pkg)).delete();
    }

    private static File file(int userId, String pkg) {
        if (userId < 0 || pkg == null || !pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) {
            throw new SecurityException("Invalid clipboard identity");
        }
        Context context = BlackBoxCore.getContext();
        return new File(new File(new File(context.getNoBackupFilesDir(), "clone_clipboard"),
                Integer.toString(userId)), pkg + ".bin");
    }

    private static void write(File target, int userId, String pkg, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(aad(userId, pkg));
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] stored;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeByte(cipher.getIV().length);
            output.write(cipher.getIV());
            output.write(encrypted);
            stored = bytes.toByteArray();
        }
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IllegalStateException("Clipboard directory unavailable");
        }
        AtomicFile atomic = new AtomicFile(target);
        FileOutputStream output = atomic.startWrite();
        try {
            output.write(stored);
            output.getFD().sync();
            atomic.finishWrite(output);
        } catch (Throwable error) {
            atomic.failWrite(output);
            throw error;
        }
    }

    private static String read(File target, int userId, String pkg) throws Exception {
        if (target.length() <= MAGIC.length || target.length() > MAX_BYTES) {
            throw new SecurityException("Invalid clipboard size");
        }
        byte[] stored;
        try (FileInputStream input = new FileInputStream(target);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) target.length())) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            stored = output.toByteArray();
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(stored))) {
            byte[] magic = new byte[MAGIC.length];
            input.readFully(magic);
            for (int i = 0; i < MAGIC.length; i++) if (magic[i] != MAGIC[i]) {
                throw new SecurityException("Invalid clipboard header");
            }
            int ivLength = input.readUnsignedByte();
            if (ivLength < 12 || ivLength > 32) throw new SecurityException("Invalid clipboard IV");
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
        return ("BlackBoxClipboard|1|" + userId + "|" + pkg).getBytes(StandardCharsets.UTF_8);
    }
}
