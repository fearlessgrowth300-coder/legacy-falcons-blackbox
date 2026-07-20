package top.niunaijun.blackbox.proxy.record;

import android.content.Intent;
import android.net.Uri;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;


public class ProxyPendingRecord {
    public int mUserId;
    public Intent mTarget;
    public boolean mRequireForeground;

    public ProxyPendingRecord(Intent target, int userId, boolean requireForeground) {
        mUserId = userId;
        mTarget = target;
        mRequireForeground = requireForeground;
    }

    public static void saveStub(Intent shadow, Intent target, int userId, String guestPackage) {
        saveStub(shadow, target, userId, guestPackage, false);
    }

    public static void saveStub(Intent shadow, Intent target, int userId, String guestPackage,
                                boolean requireForeground) {
        shadow.putExtra("_B_|_P_user_id_", userId);
        shadow.putExtra("_B_|_P_target_", target);
        shadow.putExtra("_B_|_P_foreground_", requireForeground);
        // PendingIntent matching ignores extras. Give the host stub an identity that includes the
        // virtual user, originating guest package and target filter, preventing slot reuse or an
        // equal request code from aliasing another clone's PendingIntent.
        String digest = targetDigest(userId, guestPackage, target);
        shadow.setAction("top.niunaijun.blackbox.PENDING." + digest);
        shadow.setData(new Uri.Builder().scheme("blackbox-pending")
                .authority("u" + userId)
                .appendPath(guestPackage == null ? "unknown" : guestPackage)
                .appendPath(digest)
                .build());
    }

    private static String targetDigest(int userId, String guestPackage, Intent target) {
        try {
            StringBuilder canonical = new StringBuilder()
                    .append(userId).append('\n').append(guestPackage).append('\n')
                    .append(target.getAction()).append('\n')
                    .append(target.getDataString()).append('\n')
                    .append(target.getType()).append('\n')
                    .append(target.getPackage()).append('\n')
                    .append(target.getComponent() == null ? null
                            : target.getComponent().flattenToString()).append('\n');
            Set<String> categories = target.getCategories();
            if (categories != null) {
                ArrayList<String> sorted = new ArrayList<>(categories);
                Collections.sort(sorted);
                for (String category : sorted) canonical.append(category).append('\n');
            }
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) hex.append(String.format("%02x", hash[i]));
            return hex.toString();
        } catch (Throwable ignored) {
            return Integer.toHexString((userId + "|" + guestPackage + "|" + target.toUri(0)).hashCode());
        }
    }

    public static ProxyPendingRecord create(Intent intent) {
        int userId = intent.getIntExtra("_B_|_P_user_id_", 0);
        Intent target = intent.getParcelableExtra("_B_|_P_target_");
        boolean foreground = intent.getBooleanExtra("_B_|_P_foreground_", false);
        return new ProxyPendingRecord(target, userId, foreground);
    }

    @Override
    public String toString() {
        return "ProxyPendingActivityRecord{" +
                "mUserId=" + mUserId +
                ", mTarget=" + mTarget +
                '}';
    }
}
