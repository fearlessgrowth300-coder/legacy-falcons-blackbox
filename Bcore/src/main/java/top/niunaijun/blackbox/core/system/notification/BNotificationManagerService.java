package top.niunaijun.blackbox.core.system.notification;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import black.android.app.BRNotificationChannel;
import black.android.app.BRNotificationChannelGroup;
import black.android.app.BRNotificationO;
import black.android.app.NotificationChannelContext;
import black.android.app.NotificationChannelGroupContext;
import black.android.app.NotificationOContext;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class BNotificationManagerService extends IBNotificationManagerService.Stub implements ISystemService {
    private final static BNotificationManagerService sService = new BNotificationManagerService();
    public static final String CHANNEL_BLACK = "@black-";
    public static final String GROUP_BLACK = "@black-group-";

    private NotificationChannelManager mNotificationChannelManager;
    private final Map<String, NotificationRecord> mNotificationRecords = new HashMap<>();

    private final NotificationManager mRealNotificationManager =
            (NotificationManager) BlackBoxCore.getContext().getSystemService(Context.NOTIFICATION_SERVICE);

    public static BNotificationManagerService get() {
        return sService;
    }

    @Override
    public void systemReady() {
        mNotificationChannelManager = NotificationChannelManager.get();
    }


    private NotificationRecord getNotificationRecord(String packageName, int userId) {
        String key = packageName + "-" + userId;
        synchronized (mNotificationRecords) {
            NotificationRecord notificationRecord = mNotificationRecords.get(key);
            if (notificationRecord == null) {
                notificationRecord = new NotificationRecord();
                mNotificationRecords.put(key, notificationRecord);
            }
            return notificationRecord;
        }
    }

    private void removeNotificationRecord(String packageName, int userId) {
        String key = packageName + "-" + userId;
        synchronized (mNotificationRecords) {
            mNotificationRecords.remove(key);
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.O)
    public NotificationChannel getNotificationChannel(String channelId, int userId) throws RemoteException {
        int callingPid = getCallingPid();
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(callingPid);
        if (processByPid == null)
            return null;
        NotificationRecord notificationRecord = getNotificationRecord(processByPid.getPackageName(), userId);
        synchronized (notificationRecord.mNotificationChannels) {
            return notificationRecord.mNotificationChannels.get(channelId);
        }
    }

    @Override
    public List<NotificationChannel> getNotificationChannels(String packageName, int userId) throws RemoteException {
        NotificationRecord notificationRecord = getNotificationRecord(packageName, userId);
        synchronized (notificationRecord.mNotificationChannels) {
            return new ArrayList<>(notificationRecord.mNotificationChannels.values());
        }
    }

    @Override
    public List<NotificationChannelGroup> getNotificationChannelGroups(String packageName, int userId) throws RemoteException {
        NotificationRecord notificationRecord = getNotificationRecord(packageName, userId);
        synchronized (notificationRecord.mNotificationChannelGroups) {
            return new ArrayList<>(notificationRecord.mNotificationChannelGroups.values());
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.O)
    public void createNotificationChannel(NotificationChannel notificationChannel, int userId) {
        int callingPid = getCallingPid();
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(callingPid);
        if (processByPid == null)
            return;
        handleNotificationChannel(notificationChannel, userId, processByPid.getPackageName());
        mRealNotificationManager.createNotificationChannel(notificationChannel);

        resetNotificationChannel(notificationChannel);
        NotificationRecord notificationRecord = getNotificationRecord(processByPid.getPackageName(), userId);
        synchronized (notificationRecord.mNotificationChannels) {
            notificationRecord.mNotificationChannels.put(notificationChannel.getId(), notificationChannel);
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.O)
    public void deleteNotificationChannel(String channelId, int userId) {
        int callingPid = getCallingPid();
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(callingPid);
        if (processByPid == null)
            return;
        NotificationRecord notificationRecord = getNotificationRecord(processByPid.getPackageName(), userId);
        synchronized (notificationRecord.mNotificationChannels) {
            NotificationChannel remove = notificationRecord.mNotificationChannels.remove(channelId);
            if (remove != null) {
                String blackChannelId = getBlackChannelId(remove.getId(), userId, processByPid.getPackageName());
                mRealNotificationManager.deleteNotificationChannel(blackChannelId);
            }
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.O)
    public void createNotificationChannelGroup(NotificationChannelGroup notificationChannelGroup, int userId) {
        int callingPid = getCallingPid();
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(callingPid);
        if (processByPid == null)
            return;
        handleNotificationGroup(notificationChannelGroup, userId, processByPid.getPackageName());
        mRealNotificationManager.createNotificationChannelGroup(notificationChannelGroup);

        resetNotificationGroup(notificationChannelGroup);
        NotificationRecord notificationRecord = getNotificationRecord(processByPid.getPackageName(), userId);
        synchronized (notificationRecord.mNotificationChannelGroups) {
            notificationRecord.mNotificationChannelGroups.put(notificationChannelGroup.getId(), notificationChannelGroup);
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.O)
    public void deleteNotificationChannelGroup(String groupId, int userId) {
        int callingPid = getCallingPid();
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(callingPid);
        if (processByPid == null)
            return;
        NotificationRecord notificationRecord = getNotificationRecord(processByPid.getPackageName(), userId);
        synchronized (notificationRecord.mNotificationChannelGroups) {
            NotificationChannelGroup remove = notificationRecord.mNotificationChannelGroups.remove(groupId);
            if (remove != null) {
                String blackGroupId = getBlackGroupId(remove.getId(), userId, processByPid.getPackageName());
                mRealNotificationManager.deleteNotificationChannelGroup(blackGroupId);
            }
        }
    }

    @Override
    public void enqueueNotificationWithTag(int id, String tag, Notification notification, int userId) {
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(Binder.getCallingPid());
        if (processByPid == null)
            return;
        String packageName = processByPid.getPackageName();
        String hostTag = getHostNotificationTag(packageName, userId, tag);
        int notificationId = id;

        if (BuildCompat.isOreo()) {
            NotificationOContext notificationOContext = BRNotificationO.get(notification);
            
            if (notificationOContext._check_mChannelId() != null) {
                String blackChannelId = getBlackChannelId(notificationOContext.mChannelId(), userId, packageName);
                notificationOContext._set_mChannelId(blackChannelId);
            }
            
            if (notificationOContext._check_mGroupKey() != null) {
                String blackGroupId = getBlackGroupId(notificationOContext.mGroupKey(), userId, packageName);
                notificationOContext._set_mGroupKey(blackGroupId);
            }
        }
        NotificationRecord notificationRecord = getNotificationRecord(packageName, userId);
        synchronized (notificationRecord.mPostedNotifications) {
            notificationRecord.mPostedNotifications.put(postedKey(hostTag, notificationId), notificationId);
        }
        Notification toPost = enhanceForHost(packageName, userId,
                getNotificationId(userId, id, packageName), notification);
        try {
            mRealNotificationManager.notify(hostTag, notificationId, toPost);
            top.niunaijun.blackbox.utils.Slog.d("BNotif", "posted host notif id=" + notificationId
                    + " pkg=" + processByPid.getPackageName() + " icon=" + toPost.getSmallIcon());
        } catch (Throwable e) {
            top.niunaijun.blackbox.utils.Slog.w("BNotif", "notify threw: " + e.getMessage() + " — retrying original");
            try { mRealNotificationManager.notify(hostTag, notificationId, notification); } catch (Throwable ignored) {}
        }
    }

    /**
     * Make a guest app's notification actually show on the real phone:
     *  - guest resource small-icons don't resolve on the host and Android DROPS such
     *    notifications, so swap in a host-resolvable icon (guest app icon as large icon);
     *  - label the notification with the guest app's name so you know which app it is;
     *  - set a tap action that relaunches that app inside BlackBox (pkg + userId).
     */
    private Notification enhanceForHost(String pkg, int userId, int notificationId, Notification original) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return original;
        try {
            Context ctx = BlackBoxCore.getContext();
            String label = pkg;
            android.graphics.Bitmap iconBmp = null;
            try {
                android.content.pm.PackageManager pm = ctx.getPackageManager();
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                label = pm.getApplicationLabel(ai).toString();
                iconBmp = drawableToBitmap(pm.getApplicationIcon(ai));
            } catch (Throwable ignored) {
            }

            Notification.Builder b = Notification.Builder.recoverBuilder(ctx, original);
            b.setSmallIcon(android.R.drawable.stat_notify_chat);
            if (iconBmp != null) b.setLargeIcon(iconBmp);
            b.setSubText(label);
            try {
                // Notification.EXTRA_SUBSTITUTE_APP_NAME (hidden) — show the guest app's
                // name in the notification header instead of "BlackBox".
                b.getExtras().putString("android.substName", label);
            } catch (Throwable ignored) {
            }
            b.setAutoCancel(true);

            android.content.Intent li = new android.content.Intent();
            li.setClassName(ctx.getPackageName(), "top.niunaijun.blackboxa.view.main.ShortcutActivity");
            li.putExtra("pkg", pkg);
            li.putExtra("userId", userId);
            li.setData(android.net.Uri.parse("blackbox-notification://u/" + userId + "/p/"
                    + android.net.Uri.encode(pkg) + "/n/" + notificationId));
            li.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, notificationId, li, flags);
            b.setContentIntent(pi);
            return b.build();
        } catch (Throwable e) {
            top.niunaijun.blackbox.utils.Slog.w("BNotif", "enhance failed, posting original: " + e.getMessage());
            return original;
        }
    }

    private static android.graphics.Bitmap drawableToBitmap(android.graphics.drawable.Drawable d) {
        if (d == null) return null;
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            android.graphics.Bitmap bm = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            if (bm != null) return bm;
        }
        int w = Math.max(1, d.getIntrinsicWidth());
        int h = Math.max(1, d.getIntrinsicHeight());
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        d.setBounds(0, 0, c.getWidth(), c.getHeight());
        d.draw(c);
        return bmp;
    }

    @Override
    public void cancelNotificationWithTag(int id, String tag, int userId) throws RemoteException {
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(Binder.getCallingPid());
        if (processByPid == null)
            return;
        String packageName = processByPid.getPackageName();
        String hostTag = getHostNotificationTag(packageName, userId, tag);
        mRealNotificationManager.cancel(hostTag, id);

        NotificationRecord notificationRecord = getNotificationRecord(packageName, userId);
        synchronized (notificationRecord.mPostedNotifications) {
            notificationRecord.mPostedNotifications.remove(postedKey(hostTag, id));
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void handleNotificationChannel(NotificationChannel notificationChannel, int userId, String packageName) {
        NotificationChannelContext channelContext = BRNotificationChannel.get(notificationChannel);
        String channelId = channelContext.mId();
        String blackChannelId = getBlackChannelId(channelId, userId, packageName);
        channelContext._set_mId(blackChannelId);

        notificationChannel.setGroup(getBlackGroupId(notificationChannel.getGroup(), userId, packageName));
    }

    private void resetNotificationChannel(NotificationChannel notificationChannel) {
        NotificationChannelContext channelContext = BRNotificationChannel.get(notificationChannel);
        String channelId = channelContext.mId();
        String realChannelId = getRealChannelId(channelId);
        channelContext._set_mId(realChannelId);
    }

    private void handleNotificationGroup(NotificationChannelGroup notificationChannelGroup, int userId, String packageName) {
        NotificationChannelGroupContext groupContext = BRNotificationChannelGroup.get(notificationChannelGroup);
        String groupId = groupContext.mId();
        String blackGroupId = getBlackGroupId(groupId, userId, packageName);
        groupContext._set_mId(blackGroupId);

        List<NotificationChannel> notificationChannels = groupContext.mChannels();
        if (notificationChannels != null) {
            for (NotificationChannel notificationChannel : notificationChannels) {
                createNotificationChannel(notificationChannel, userId);
            }
        }
    }

    private void resetNotificationGroup(NotificationChannelGroup notificationChannelGroup) {
        NotificationChannelGroupContext groupContext = BRNotificationChannelGroup.get(notificationChannelGroup);
        String groupId = groupContext.mId();
        String realGroupId = getRealGroupId(groupId);
        groupContext._set_mId(realGroupId);

        List<NotificationChannel> notificationChannels = groupContext.mChannels();
        if (notificationChannels != null) {
            for (NotificationChannel notificationChannel : notificationChannels) {
                resetNotificationChannel(notificationChannel);
            }
        }
    }

    @SuppressLint("NewApi")
    public void deletePackageNotification(String packageName, int userId) {
        NotificationRecord notificationRecord = getNotificationRecord(packageName, userId);
        if (BuildCompat.isOreo()) {
            synchronized (notificationRecord.mNotificationChannelGroups) {
                for (NotificationChannelGroup value : notificationRecord.mNotificationChannelGroups.values()) {
                    String blackGroupId = getBlackGroupId(value.getId(), userId, packageName);
                    mRealNotificationManager.deleteNotificationChannelGroup(blackGroupId);
                }
                notificationRecord.mNotificationChannelGroups.clear();
            }
            synchronized (notificationRecord.mNotificationChannels) {
                for (NotificationChannel value : notificationRecord.mNotificationChannels.values()) {
                    String blackChannelId = getBlackChannelId(value.getId(), userId, packageName);
                    mRealNotificationManager.deleteNotificationChannel(blackChannelId);
                }
                notificationRecord.mNotificationChannels.clear();
            }
        }
        synchronized (notificationRecord.mPostedNotifications) {
            for (Map.Entry<String, Integer> posted : notificationRecord.mPostedNotifications.entrySet()) {
                mRealNotificationManager.cancel(postedTag(posted.getKey()), posted.getValue());
            }
            notificationRecord.mPostedNotifications.clear();
        }
        removeNotificationRecord(packageName, userId);
    }

    private String getBlackChannelId(String channelId, int userId, String packageName) {
        if (channelId == null || channelId.contains(CHANNEL_BLACK)) {
            return channelId;
        }
        return channelId + CHANNEL_BLACK + userId + "-" + packageName;
    }

    private String getRealChannelId(String channelId) {
        if (channelId == null || !channelId.contains(CHANNEL_BLACK)) {
            return channelId;
        }
        return channelId.split(CHANNEL_BLACK)[0];
    }

    private String getBlackGroupId(String groupId, int userId, String packageName) {
        if (groupId == null || groupId.contains(GROUP_BLACK))
            return groupId;
        return groupId + GROUP_BLACK + userId + "-" + packageName;
    }

    private String getRealGroupId(String groupId) {
        if (groupId == null || !groupId.contains(GROUP_BLACK))
            return groupId;
        return groupId.split(GROUP_BLACK)[0];
    }

    public static int getNotificationId(int userId, int notificationId, String packageName) {
        return (packageName + userId + notificationId).hashCode();
    }

    private static String getHostNotificationTag(String packageName, int userId, String guestTag) {
        return "blackbox|" + userId + "|" + packageName + "|" + (guestTag == null ? "" : guestTag);
    }

    private static String postedKey(String hostTag, int id) {
        return hostTag + '\u0000' + id;
    }

    private static String postedTag(String key) {
        int split = key.lastIndexOf('\u0000');
        return split < 0 ? key : key.substring(0, split);
    }
}
