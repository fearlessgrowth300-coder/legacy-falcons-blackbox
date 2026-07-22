package top.niunaijun.blackbox.proxy;

import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.app.dispatcher.AppServiceDispatcher;
import top.niunaijun.blackbox.proxy.record.ProxyServiceRecord;


public class ProxyService extends Service {
    public static final String TAG = "StubService";

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // Android may deliver a null intent when a virtual service is being
        // recreated after its guest process was reclaimed. There is no target
        // service to dispatch in that case; returning null keeps the host
        // process alive and lets the guest be started normally on the next
        // explicit request.
        if (intent == null) {
            Log.w(TAG, "onBind received a null proxy intent");
            return null;
        }
        Log.d(TAG, "onBind entering; guest initialized="
                + BActivityThread.currentActivityThread().isInit());
        if (!restoreGuestProcess(intent)) {
            Log.w(TAG, "onBind could not restore the guest process");
            return null;
        }
        IBinder binder = AppServiceDispatcher.get().onBind(intent);
        Log.d(TAG, "onBind completed; binder="
                + (binder == null ? "null" : binder.getClass().getName()));
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // A sticky service restart can arrive without the original stub
        // intent. Never pass that through ProxyServiceRecord.create(), which
        // expects a real intent and would crash the guest process.
        if (intent == null) return START_NOT_STICKY;
        if (!restoreGuestProcess(intent)) return START_NOT_STICKY;
        return AppServiceDispatcher.get().onStartCommand(intent, flags, startId);
    }

    /** Android may recreate a sticky proxy service after its BlackBox process record died. The
     * stub intent carries the original virtual user and component, so rebuild that record before
     * touching guest classes. This also prevents a stale restart from falling back to user 0. */
    private boolean restoreGuestProcess(Intent intent) {
        if (BActivityThread.currentActivityThread().isInit()) return true;
        if (intent == null) return false;
        ProxyServiceRecord record = ProxyServiceRecord.create(intent);
        if (record.mServiceInfo == null) return false;
        // ActiveServices already selected the exact virtual process slot.  Initialize this
        // Android-created ProxyService process with that same config before dispatching the guest
        // service.  Calling restartProcess() here can only start another process; it cannot attach
        // the current P<n> process and therefore returned a null binder to API 36 clients.
        if (BActivityThread.getAppConfig() == null && record.mAppConfig != null) {
            try {
                BActivityThread.currentActivityThread().initProcess(record.mAppConfig);
            } catch (Throwable e) {
                Log.w(TAG, "Unable to initialize proxy service guest", e);
                return false;
            }
        }
        if (BActivityThread.getAppConfig() != null) return true;
        try {
            BlackBoxCore.getBActivityManager().restartProcess(
                    record.mServiceInfo.packageName,
                    record.mServiceInfo.processName,
                    record.mUserId);
        } catch (Throwable ignored) {
            return false;
        }
        return BActivityThread.getAppConfig() != null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AppServiceDispatcher.get().onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        AppServiceDispatcher.get().onConfigurationChanged(newConfig);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        AppServiceDispatcher.get().onLowMemory();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        AppServiceDispatcher.get().onTrimMemory(level);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (intent == null) return false;
        AppServiceDispatcher.get().onUnbind(intent);
        return false;
    }

    public static class P0 extends ProxyService {

    }

    public static class P1 extends ProxyService {

    }

    public static class P2 extends ProxyService {

    }

    public static class P3 extends ProxyService {

    }

    public static class P4 extends ProxyService {

    }

    public static class P5 extends ProxyService {

    }

    public static class P6 extends ProxyService {

    }

    public static class P7 extends ProxyService {

    }

    public static class P8 extends ProxyService {

    }

    public static class P9 extends ProxyService {

    }

    public static class P10 extends ProxyService {

    }

    public static class P11 extends ProxyService {

    }

    public static class P12 extends ProxyService {

    }

    public static class P13 extends ProxyService {

    }

    public static class P14 extends ProxyService {

    }

    public static class P15 extends ProxyService {

    }

    public static class P16 extends ProxyService {

    }

    public static class P17 extends ProxyService {

    }

    public static class P18 extends ProxyService {

    }

    public static class P19 extends ProxyService {

    }

    public static class P20 extends ProxyService {

    }

    public static class P21 extends ProxyService {

    }

    public static class P22 extends ProxyService {

    }

    public static class P23 extends ProxyService {

    }

    public static class P24 extends ProxyService {

    }

    public static class P25 extends ProxyService {

    }

    public static class P26 extends ProxyService {

    }

    public static class P27 extends ProxyService {

    }

    public static class P28 extends ProxyService {

    }

    public static class P29 extends ProxyService {

    }

    public static class P30 extends ProxyService {

    }

    public static class P31 extends ProxyService {

    }

    public static class P32 extends ProxyService {

    }

    public static class P33 extends ProxyService {

    }

    public static class P34 extends ProxyService {

    }

    public static class P35 extends ProxyService {

    }

    public static class P36 extends ProxyService {

    }

    public static class P37 extends ProxyService {

    }

    public static class P38 extends ProxyService {

    }

    public static class P39 extends ProxyService {

    }

    public static class P40 extends ProxyService {

    }

    public static class P41 extends ProxyService {

    }

    public static class P42 extends ProxyService {

    }

    public static class P43 extends ProxyService {

    }

    public static class P44 extends ProxyService {

    }

    public static class P45 extends ProxyService {

    }

    public static class P46 extends ProxyService {

    }

    public static class P47 extends ProxyService {

    }

    public static class P48 extends ProxyService {

    }

    public static class P49 extends ProxyService {

    }
}
