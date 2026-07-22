package top.niunaijun.blackbox.proxy.record;

import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import top.niunaijun.blackbox.utils.compat.BundleCompat;
import top.niunaijun.blackbox.entity.AppConfig;


public class ProxyServiceRecord {
    private static final String CONFIG_PREFIX = "_B_|_app_config_";
    private static final String CONFIG_PACKAGE = CONFIG_PREFIX + "package";
    private static final String CONFIG_PROCESS = CONFIG_PREFIX + "process";
    private static final String CONFIG_BPID = CONFIG_PREFIX + "bpid";
    private static final String CONFIG_BUID = CONFIG_PREFIX + "buid";
    private static final String CONFIG_UID = CONFIG_PREFIX + "uid";
    private static final String CONFIG_USER_ID = CONFIG_PREFIX + "user_id";
    private static final String CONFIG_CALLING_BUID = CONFIG_PREFIX + "calling_buid";
    private static final String CONFIG_TOKEN = CONFIG_PREFIX + "token";
    public Intent mServiceIntent;
    public ServiceInfo mServiceInfo;
    public IBinder mToken;
    public int mUserId;
    public int mStartId;
    public AppConfig mAppConfig;

    public ProxyServiceRecord(Intent serviceIntent, ServiceInfo serviceInfo, IBinder token,
                              int userId, int startId, AppConfig appConfig) {
        mServiceIntent = serviceIntent;
        mServiceInfo = serviceInfo;
        mUserId = userId;
        mStartId = startId;
        mToken = token;
        mAppConfig = appConfig;
    }

    public static void saveStub(Intent shadow, Intent target, ServiceInfo serviceInfo, IBinder token,
                                int userId, int startId, AppConfig appConfig) {
        shadow.putExtra("_B_|_target_", target);
        shadow.putExtra("_B_|_service_info_", serviceInfo);
        shadow.putExtra("_B_|_user_id_", userId);
        shadow.putExtra("_B_|_start_id_", startId);
        // Intents pass through system_server before reaching the selected proxy process.
        // system_server cannot load BlackBox's AppConfig Parcelable, and Android 16 eagerly
        // inspects nested extras for intent-redirection hardening.  Carry only framework-safe
        // primitives and reconstruct AppConfig in the host process.
        if (appConfig != null) {
            shadow.putExtra(CONFIG_PACKAGE, appConfig.packageName);
            shadow.putExtra(CONFIG_PROCESS, appConfig.processName);
            shadow.putExtra(CONFIG_BPID, appConfig.bpid);
            shadow.putExtra(CONFIG_BUID, appConfig.buid);
            shadow.putExtra(CONFIG_UID, appConfig.uid);
            shadow.putExtra(CONFIG_USER_ID, appConfig.userId);
            shadow.putExtra(CONFIG_CALLING_BUID, appConfig.callingBUid);
            BundleCompat.putBinder(shadow, CONFIG_TOKEN, appConfig.token);
        }
        BundleCompat.putBinder(shadow, "_B_|_token_", token);
    }

    public static ProxyServiceRecord create(Intent intent) {
        Intent target = intent.getParcelableExtra("_B_|_target_");
        ServiceInfo serviceInfo = intent.getParcelableExtra("_B_|_service_info_");
        int userId = intent.getIntExtra("_B_|_user_id_", 0);
        int startId = intent.getIntExtra("_B_|_start_id_", 0);
        IBinder token = BundleCompat.getBinder(intent, "_B_|_token_");
        AppConfig appConfig = null;
        String packageName = intent.getStringExtra(CONFIG_PACKAGE);
        if (packageName != null) {
            appConfig = new AppConfig();
            appConfig.packageName = packageName;
            appConfig.processName = intent.getStringExtra(CONFIG_PROCESS);
            appConfig.bpid = intent.getIntExtra(CONFIG_BPID, -1);
            appConfig.buid = intent.getIntExtra(CONFIG_BUID, 0);
            appConfig.uid = intent.getIntExtra(CONFIG_UID, 0);
            appConfig.userId = intent.getIntExtra(CONFIG_USER_ID, userId);
            appConfig.callingBUid = intent.getIntExtra(CONFIG_CALLING_BUID, 0);
            appConfig.token = BundleCompat.getBinder(intent, CONFIG_TOKEN);
        }
        return new ProxyServiceRecord(target, serviceInfo, token, userId, startId, appConfig);
    }
}
