package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.IBinder;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import black.android.os.BRServiceManager;
import black.com.android.internal.telephony.BRITelephonyStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.entity.location.BCell;
import top.niunaijun.blackbox.fake.frameworks.BLocationManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Md5Utils;
import top.niunaijun.blackbox.core.DeviceProfile;


public class ITelephonyManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ITelephonyManagerProxy";

    // Per-clone IMEI / IMSI from the active device profile (falls back to a stable
    // per-install value if no profile is applied). Without this every clone returns
    // md5(hostPkg) -> identical IMEI across all clones = account linkage.
    static String cloneImei() {
        DeviceProfile p = DeviceProfile.CURRENT;
        String v = (p != null && p.imei != null) ? p.imei : Md5Utils.md5(BlackBoxCore.getHostPkg());
        return v;
    }

    static String cloneImsi() {
        DeviceProfile p = DeviceProfile.CURRENT;
        String v = (p != null && p.imsi != null) ? p.imsi : Md5Utils.md5(BlackBoxCore.getHostPkg());
        return v;
    }

    // SIM/carrier country — WhatsApp/Instagram default the phone-number country from these.
    // Return null when the SIM isn't spoofed (no proxy) so the hook passes through to the REAL
    // value (real IP + real SIM = coherent for a no-proxy clone).
    static String cloneMccMnc() {
        DeviceProfile p = DeviceProfile.CURRENT;
        return (p != null && p.simSpoofed && p.mccMnc != null) ? p.mccMnc : null;
    }

    static String cloneSimIso() {
        DeviceProfile p = DeviceProfile.CURRENT;
        return (p != null && p.simSpoofed && p.simCountryIso != null) ? p.simCountryIso : null;
    }

    static String cloneOperatorName() {
        DeviceProfile p = DeviceProfile.CURRENT;
        return (p != null && p.simSpoofed && p.simOperatorName != null) ? p.simOperatorName : null;
    }

    public ITelephonyManagerProxy() {
        super(BRServiceManager.get().getService(Context.TELEPHONY_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder telephony = BRServiceManager.get().getService(Context.TELEPHONY_SERVICE);
        return BRITelephonyStub.get().asInterface(telephony);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.TELEPHONY_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getDeviceId")
    public static class GetDeviceId extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return cloneImei();
        }
    }

    @ProxyMethod("getImeiForSlot")
    public static class getImeiForSlot extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return cloneImei();
        }
    }

    @ProxyMethod("getMeidForSlot")
    public static class GetMeidForSlot extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return cloneImei();
        }
    }

    @ProxyMethod("isUserDataEnabled")
    public static class IsUserDataEnabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return true;
        }
    }


    @ProxyMethod("getLine1NumberForDisplay")
    public static class getLine1NumberForDisplay extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("getSubscriberId")
    public static class GetSubscriberId extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return cloneImsi();
        }
    }

    @ProxyMethod("getDeviceIdWithFeature")
    public static class GetDeviceIdWithFeature extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return cloneImei();
        }
    }

    @ProxyMethod("getCellLocation")
    public static class GetCellLocation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Log.d(TAG, "getCellLocation");
            if (BLocationManager.isFakeLocationEnable()) {
                BCell cell = BLocationManager.get().getCell(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
                if (cell != null) {
                    
                    return null;
                }
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getAllCellInfo")
    public static class GetAllCellInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (BLocationManager.isFakeLocationEnable()) {
                List<BCell> cell = BLocationManager.get().getAllCell(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
                
                return cell;
            }
            try {
                return method.invoke(who, args);
            } catch (Throwable e) {
                return null;
            }
        }
    }

    @ProxyMethod("getNetworkOperator")
    public static class GetNetworkOperator extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String v = cloneMccMnc();
            return v != null ? v : method.invoke(who, args);
        }
    }

    @ProxyMethod("getNetworkOperatorForPhone")
    public static class GetNetworkOperatorForPhone extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String v = cloneMccMnc();
            return v != null ? v : method.invoke(who, args);
        }
    }

    @ProxyMethod("getSimOperator")
    public static class GetSimOperator extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String v = cloneMccMnc();
            return v != null ? v : method.invoke(who, args);
        }
    }

    @ProxyMethod("getSimOperatorForPhone")
    public static class GetSimOperatorForPhone extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String v = cloneMccMnc();
            return v != null ? v : method.invoke(who, args);
        }
    }

    @ProxyMethod("getNetworkCountryIso")
    public static class GetNetworkCountryIso extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String v = cloneSimIso();
            return v != null ? v : method.invoke(who, args);
        }
    }

    @ProxyMethod("getNetworkCountryIsoForPhone")
    public static class GetNetworkCountryIsoForPhone extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String v = cloneSimIso();
            return v != null ? v : method.invoke(who, args);
        }
    }

    @ProxyMethod("getSimCountryIso")
    public static class GetSimCountryIso extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String v = cloneSimIso();
            return v != null ? v : method.invoke(who, args);
        }
    }

    @ProxyMethod("getSimCountryIsoForPhone")
    public static class GetSimCountryIsoForPhone extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String v = cloneSimIso();
            return v != null ? v : method.invoke(who, args);
        }
    }

    @ProxyMethod("getNetworkOperatorName")
    public static class GetNetworkOperatorName extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String v = cloneOperatorName();
            return v != null ? v : method.invoke(who, args);
        }
    }

    @ProxyMethod("getSimOperatorNameForPhone")
    public static class GetSimOperatorNameForPhone extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String v = cloneOperatorName();
            return v != null ? v : method.invoke(who, args);
        }
    }

    @ProxyMethod("getNetworkTypeForSubscriber")
    public static class GetNetworkTypeForSubscriber extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(who, args);
            } catch (Throwable e) {
                return 0;
            }
        }
    }

    @ProxyMethod("getNeighboringCellInfo")
    public static class GetNeighboringCellInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Log.d(TAG, "getNeighboringCellInfo");
            if (BLocationManager.isFakeLocationEnable()) {
                List<BCell> cell = BLocationManager.get().getNeighboringCell(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
                
                return null;
            }
            return method.invoke(who, args);
        }
    }
}
