package top.niunaijun.blackbox.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import black.android.app.ActivityThreadActivityClientRecordContext;
import black.android.app.BRActivityClient;
import black.android.app.BRActivityClientActivityClientControllerSingleton;
import black.android.app.BRActivityManagerNative;
import black.android.app.BRActivityThread;
import black.android.app.BRActivityThreadActivityClientRecord;
import black.android.app.BRActivityThreadCreateServiceData;
import black.android.app.BRActivityThreadH;
import black.android.app.BRIActivityManager;
import black.android.app.servertransaction.BRClientTransaction;
import black.android.app.servertransaction.BRLaunchActivityItem;
import black.android.app.servertransaction.LaunchActivityItemContext;
import black.android.os.BRHandler;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.proxy.record.ProxyActivityRecord;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;



public class HCallbackProxy implements IInjectHook, Handler.Callback {
    public static final String TAG = "HCallbackStub";
    private Handler.Callback mOtherCallback;
    private AtomicBoolean mBeing = new AtomicBoolean(false);

    private Handler.Callback getHCallback() {
        return BRHandler.get(getH()).mCallback();
    }

    private Handler getH() {
        Object currentActivityThread = BlackBoxCore.mainThread();
        return BRActivityThread.get(currentActivityThread).mH();
    }

    @Override
    public void injectHook() {
        mOtherCallback = getHCallback();
        if (mOtherCallback != null && (mOtherCallback == this || mOtherCallback.getClass().getName().equals(this.getClass().getName()))) {
            mOtherCallback = null;
        }
        BRHandler.get(getH())._set_mCallback(this);
    }

    @Override
    public boolean isBadEnv() {
        Handler.Callback hCallback = getHCallback();
        return hCallback != null && hCallback != this;
    }

    @Override
    public boolean handleMessage(@NonNull Message msg) {
        if (!mBeing.getAndSet(true)) {
            try {
                if (BuildCompat.isPie()) {
                    if (msg.what == BRActivityThreadH.get().EXECUTE_TRANSACTION()) {
                        if (handleLaunchActivity(msg.obj)) {
                            getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                            return true;
                        }
                    }
                } else {
                    if (msg.what == BRActivityThreadH.get().LAUNCH_ACTIVITY()) {
                        if (handleLaunchActivity(msg.obj)) {
                            getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                            return true;
                        }
                    }
                }
                if (msg.what == BRActivityThreadH.get().CREATE_SERVICE()) {
                    return handleCreateService(msg.obj);
                }
                if (mOtherCallback != null) {
                    return mOtherCallback.handleMessage(msg);
                }
                return false;
            } finally {
                mBeing.set(false);
            }
        }
        return false;
    }

    private Object getLaunchActivityItem(Object clientTransaction) {
        List<Object> mActivityCallbacks = BRClientTransaction.get(clientTransaction).mActivityCallbacks();

        if (mActivityCallbacks == null) {
            Slog.e(TAG, "mActivityCallbacks is null for clientTransaction: " + clientTransaction);
            return null;
        }

        for (Object obj : mActivityCallbacks) {
            if (BRLaunchActivityItem.getRealClass().getName().equals(obj.getClass().getCanonicalName())) {
                return obj;
            }
        }
        return null;
    }

    private boolean handleLaunchActivity(Object client) {
        Object r;
        if (BuildCompat.isPie()) {
            
            r = getLaunchActivityItem(client);
        } else {
            
            r = client;
        }
        if (r == null)
            return false;

        Intent intent;
        IBinder token;
        if (BuildCompat.isPie()) {
            intent = BRLaunchActivityItem.get(r).mIntent();
            token = BRClientTransaction.get(client).mActivityToken();
        } else {
            ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
            intent = clientRecordContext.intent();
            token = clientRecordContext.token();
        }

        if (intent == null)
            return false;

        ProxyActivityRecord stubRecord = ProxyActivityRecord.create(intent);
        ActivityInfo activityInfo = stubRecord.mActivityInfo;
        if (activityInfo != null) {
            if (BActivityThread.getAppConfig() == null) {
                BlackBoxCore.getBActivityManager().restartProcess(activityInfo.packageName, activityInfo.processName, stubRecord.mUserId);

                AppConfig recoveredConfig = BActivityThread.getAppConfig();
                if (isExpectedProcess(recoveredConfig, activityInfo, stubRecord.mUserId)
                        && stubRecord.mTarget != null) {
                    // Android can reclaim a background proxy process while keeping its physical
                    // task and launch transaction. restartProcess() synchronously reattaches this
                    // P<n> worker. Requeue the untouched transaction so ActivityThread receives
                    // the original guest activity, intent and saved instance state. Replacing it
                    // with the package launcher here used to send Instagram back to Home/Reels
                    // whenever the user returned from Recents.
                    Slog.i(TAG, "Recovered guest process; preserving activity "
                            + activityInfo.name + " for user " + stubRecord.mUserId);
                    return true;
                }

                // Recovery genuinely failed (or the persisted transaction is incomplete). Keep
                // the established launcher fallback instead of allowing ProxyActivity to start a
                // guest target directly on the host network.
                Slog.e(TAG, "Unable to recover saved guest activity; using safe launcher fallback: "
                        + activityInfo.packageName + "/" + activityInfo.processName
                        + " user=" + stubRecord.mUserId);
                Intent launchIntentForPackage = BlackBoxCore.getBPackageManager().getLaunchIntentForPackage(activityInfo.packageName, stubRecord.mUserId);
                ActivityInfo launchActivityInfo = activityInfo;
                if (launchIntentForPackage != null && launchIntentForPackage.getComponent() != null) {
                    ActivityInfo resolvedLaunchInfo = BlackBoxCore.getBPackageManager().getActivityInfo(
                            launchIntentForPackage.getComponent(), 0, stubRecord.mUserId);
                    if (resolvedLaunchInfo != null) {
                        launchActivityInfo = resolvedLaunchInfo;
                    }
                }
                intent.setExtrasClassLoader(this.getClass().getClassLoader());
                // The fallback launch intent may point at a different activity (for example,
                // Instagram's MainTabActivity when InternalLauncher is not a safe virtual root).
                // Keep ActivityInfo paired with the rewritten target; otherwise
                // ActivityThread still instantiates the old class and enters a crash/restart loop.
                ProxyActivityRecord.saveStub(intent, launchIntentForPackage, launchActivityInfo,
                        stubRecord.mActivityRecord, stubRecord.mUserId);
                if (BuildCompat.isPie()) {
                    LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                    launchActivityItemContext._set_mIntent(intent);
                    launchActivityItemContext._set_mInfo(launchActivityInfo);
                } else {
                    ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
                    clientRecordContext._set_intent(intent);
                    clientRecordContext._set_activityInfo(launchActivityInfo);
                }
                return true;
            }
            
            if (!BActivityThread.currentActivityThread().isInit()) {
                BActivityThread.currentActivityThread().bindApplication(activityInfo.packageName,
                        activityInfo.processName);
                return true;
            }

            int taskId = BRIActivityManager.get(BRActivityManagerNative.get().getDefault()).getTaskForActivity(token, false);
            BlackBoxCore.getBActivityManager().onActivityCreated(taskId, token, stubRecord.mActivityRecord);

            if(BuildCompat.isTiramisu()){
                LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                stubRecord.mTarget.setExtrasClassLoader(
                        BActivityThread.currentActivityThread().getApplication().getClassLoader());
                launchActivityItemContext._set_mIntent(stubRecord.mTarget);
                launchActivityItemContext._set_mInfo(activityInfo);
            } else if (BuildCompat.isS()) {
                Object record = BRActivityThread.get(BlackBoxCore.mainThread()).getLaunchingActivity(token);
                ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(record);
                clientRecordContext._set_intent(stubRecord.mTarget);
                clientRecordContext._set_activityInfo(activityInfo);
                clientRecordContext._set_packageInfo(BActivityThread.currentActivityThread().getPackageInfo());

                checkActivityClient();
            } else if (BuildCompat.isPie()) {
                LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                launchActivityItemContext._set_mIntent(stubRecord.mTarget);
                launchActivityItemContext._set_mInfo(activityInfo);
            } else {
                ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
                clientRecordContext._set_intent(stubRecord.mTarget);
                clientRecordContext._set_activityInfo(activityInfo);
            }
        }
        return false;
    }

    private boolean isExpectedProcess(AppConfig config, ActivityInfo activityInfo, int userId) {
        return config != null
                && TextUtils.equals(activityInfo.packageName, config.packageName)
                && TextUtils.equals(activityInfo.processName, config.processName)
                && userId == config.userId;
    }

    private boolean handleCreateService(Object data) {
        if (BActivityThread.getAppConfig() != null) {
            String appPackageName = BActivityThread.getAppPackageName();
            assert appPackageName != null;

            ServiceInfo serviceInfo = BRActivityThreadCreateServiceData.get(data).info();
            if (!serviceInfo.name.equals(ProxyManifest.getProxyService(BActivityThread.getAppPid()))
                    && !serviceInfo.name.equals(ProxyManifest.getProxyJobService(BActivityThread.getAppPid()))) {
                Slog.d(TAG, "handleCreateService: " + data);
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(appPackageName, serviceInfo.name));
                BlackBoxCore.getBActivityManager().startService(intent, null, false, BActivityThread.getUserId());
                return true;
            }
        }
        return false;
    }

    private void checkActivityClient() {
        try {
            Object activityClientController = BRActivityClient.get().getActivityClientController();
            if (!(activityClientController instanceof Proxy)) {
                IActivityClientProxy iActivityClientProxy = new IActivityClientProxy(activityClientController);
                iActivityClientProxy.onlyProxy(true);
                iActivityClientProxy.injectHook();
                Object instance = BRActivityClient.get().getInstance();
                Object o = BRActivityClient.get(instance).INTERFACE_SINGLETON();
                BRActivityClientActivityClientControllerSingleton.get(o)._set_mKnownInstance(iActivityClientProxy.getProxyInvocation());
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
