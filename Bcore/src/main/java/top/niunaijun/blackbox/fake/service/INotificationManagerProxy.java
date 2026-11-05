package top.niunaijun.blackbox.fake.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.content.Context;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import androidx.annotation.RequiresApi;

import java.lang.reflect.Method;
import java.util.List;

import black.android.app.BRNotificationManager;
import black.android.content.pm.BRParceledListSlice;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.BNotificationManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.core.system.notification.NotificationVisibility;
import top.niunaijun.blackbox.utils.compat.BuildCompat;
import top.niunaijun.blackbox.utils.compat.ParceledListSliceCompat;


public class INotificationManagerProxy extends BinderInvocationStub {
    public static final String TAG = "INotificationManagerProxy";

    public INotificationManagerProxy() {
        super(BRNotificationManager.get().getService().asBinder());
    }

    @Override
    protected Object getWho() {
        return BRNotificationManager.get().getService();
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        BRNotificationManager.get()._set_sService(getProxyInvocation());
        replaceSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

        MethodParameterUtils.replaceAllAppPkg(args);
        return super.invoke(proxy, method, args);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getNotificationChannel")
    public static class GetNotificationChannel extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            NotificationChannel notificationChannel = BNotificationManager.get().getNotificationChannel((String) args[args.length - 1]);
            return notificationChannel;
        }
    }

    @ProxyMethod("getNotificationChannels")
    public static class GetNotificationChannels extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            List<NotificationChannel> notificationChannels = BNotificationManager.get().getNotificationChannels(BActivityThread.getAppPackageName());
            return ParceledListSliceCompat.create(notificationChannels);
        }
    }

    @ProxyMethod("cancelNotificationWithTag")
    public static class CancelNotificationWithTag extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String tag = (String) args[getTagIndex()];
            int id = (int) args[getIdIndex()];
            BNotificationManager.get().cancelNotificationWithTag(id, tag);
            return 0;
        }

        public int getTagIndex() {
            if (BuildCompat.isR()) {
                return 2;
            }
            return 1;
        }

        public int getIdIndex() {
            return getTagIndex() + 1;
        }
    }


    @ProxyMethod("enqueueNotificationWithTag")
    public static class EnqueueNotificationWithTag extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String tag = (String) args[getTagIndex()];
            int id = (int) args[getIdIndex()];
            Notification notification = MethodParameterUtils.getFirstParam(args, Notification.class);
            BNotificationManager.get().enqueueNotificationWithTag(id, tag, notification);
            return 0;
        }

        public int getTagIndex() {
            return 2;
        }

        public int getIdIndex() {
            return getTagIndex() + 1;
        }
    }

    @ProxyMethod("createNotificationChannels")
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static class CreateNotificationChannels extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            List<?> list = BRParceledListSlice.get(args[1]).getList();
            if (list == null)
                return 0;
            for (Object o : list) {
                BNotificationManager.get().createNotificationChannel((NotificationChannel) o);
            }
            return 0;
        }
    }

    @ProxyMethod("deleteNotificationChannel")
    public static class DeleteNotificationChannel extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BNotificationManager.get().deleteNotificationChannel((String) args[1]);
            return 0;
        }
    }

    @ProxyMethod("createNotificationChannelGroups")
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static class CreateNotificationChannelGroups extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            List<?> list = BRParceledListSlice.get(args[1]).getList();
            for (Object o : list) {
                BNotificationManager.get().createNotificationChannelGroup((NotificationChannelGroup) o);
            }
            return 0;
        }
    }

    @ProxyMethod("deleteNotificationChannelGroup")
    public static class DeleteNotificationChannelGroup extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BNotificationManager.get().deleteNotificationChannelGroup((String) args[1]);
            return 0;
        }
    }

    @ProxyMethod("getNotificationChannelGroups")
    public static class GetNotificationChannelGroups extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            List<NotificationChannelGroup> notificationChannelGroups = BNotificationManager.get().getNotificationChannelGroups(BActivityThread.getAppPackageName());
            return ParceledListSliceCompat.create(notificationChannelGroups);
        }
    }

    private static Object onlyOwnNotifications(Object result) {
        int userId = BActivityThread.getUserId();
        String packageName = BActivityThread.getAppPackageName();
        try {
            if (result == null) return null;
            if (result instanceof StatusBarNotification[]) {
                List<StatusBarNotification> kept = new java.util.ArrayList<>();
                for (StatusBarNotification notification : (StatusBarNotification[]) result) {
                    if (notification != null && NotificationVisibility.ownsPostedTag(
                            notification.getTag(), userId, packageName)) {
                        kept.add(notification);
                    }
                }
                return kept.toArray(new StatusBarNotification[0]);
            }
            if (result instanceof List) {
                List<Object> kept = new java.util.ArrayList<>();
                for (Object item : (List<?>) result) {
                    if (item instanceof StatusBarNotification && NotificationVisibility.ownsPostedTag(
                            ((StatusBarNotification) item).getTag(), userId, packageName)) {
                        kept.add(item);
                    }
                }
                return kept;
            }
        } catch (Throwable ignored) {
        }
        return new StatusBarNotification[0];
    }

    @ProxyMethod("getActiveNotifications")
    public static class GetActiveNotifications extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return onlyOwnNotifications(method.invoke(who, args));
            } catch (Throwable error) {
                return new StatusBarNotification[0];
            }
        }
    }

    @ProxyMethod("getAppActiveNotifications")
    public static class GetAppActiveNotifications extends GetActiveNotifications {}

    @ProxyMethod("getHistoricalNotifications")
    public static class GetHistoricalNotifications extends GetActiveNotifications {}
}
