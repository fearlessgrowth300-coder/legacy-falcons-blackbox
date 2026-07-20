package top.niunaijun.blackbox.core.system.notification;

import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;

import java.util.HashMap;
import java.util.Map;


public class NotificationRecord {
    public final Map<String, NotificationChannel> mNotificationChannels = new HashMap<>();
    public final Map<String, NotificationChannelGroup> mNotificationChannelGroups = new HashMap<>();
    /** Host notification tag+id identity -> id. Tags include guest package and virtual user. */
    public final Map<String, Integer> mPostedNotifications = new HashMap<>();
}
