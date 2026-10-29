package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.util.Log;

import java.lang.reflect.Method;

import black.android.net.wifi.BRIWifiManagerStub;
import black.android.net.wifi.BRWifiInfo;
import black.android.net.wifi.BRWifiSsid;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.DeviceProfile;
import top.niunaijun.blackbox.core.NetworkIdentity;


public class IWifiManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IWifiManagerProxy";

    // Per-clone Wi-Fi MAC. Stock BlackBox hardcodes one MAC + SSID "BlackBox_Wifi"
    // for every clone -> identical MAC across clones + an obvious virtualization tell.
    static String cloneMac() {
        DeviceProfile p = DeviceProfile.CURRENT;
        String v = (p != null && p.macWifi != null) ? p.macWifi : "02:00:00:00:00:00";
        return v;
    }

    public IWifiManagerProxy() {
        super(BRServiceManager.get().getService(Context.WIFI_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIWifiManagerStub.get().asInterface(BRServiceManager.get().getService(Context.WIFI_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.WIFI_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getConnectionInfo")
    public static class GetConnectionInfo extends MethodHook {
        
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            WifiInfo wifiInfo = (WifiInfo) method.invoke(who, args);
            DeviceProfile profile = DeviceProfile.CURRENT;
            String bssid = profile != null && profile.wifiBssid != null
                    ? profile.wifiBssid : NetworkIdentity.bssid("profile");
            String ssid = profile != null && profile.wifiSsid != null
                    ? profile.wifiSsid : NetworkIdentity.ssid("profile");
            BRWifiInfo.get(wifiInfo)._set_mBSSID(bssid);
            BRWifiInfo.get(wifiInfo)._set_mMacAddress(cloneMac());
            try {
                BRWifiInfo.get(wifiInfo)._set_mSSID(ssid);
            } catch (Throwable ignored) {
            }
            BRWifiInfo.get(wifiInfo)._set_mWifiSsid(BRWifiSsid.get().createFromAsciiEncoded(ssid));
            try {
                int userId = profile == null ? 0 : BlackBoxCore.getUserId();
                String seed = profile != null && profile.androidId != null ? profile.androidId : "profile";
                BRWifiInfo.get(wifiInfo)._set_mIpAddress(
                        java.net.InetAddress.getByAddress(NetworkIdentity.localIpv4(userId, seed)));
            } catch (Throwable ignored) {
            }
            return wifiInfo;
        }

        public static String intIP2StringIP(int ip) {
            return (ip & 0xFF) + "." +
                    ((ip >> 8) & 0xFF) + "." +
                    ((ip >> 16) & 0xFF) + "." +
                    (ip >> 24 & 0xFF);
        }

        public static int ip2Int(String ipString) {
            
            String[] ipSlices = ipString.split("\\.");
            int rs = 0;
            for (int i = 0; i < ipSlices.length; i++) {
                
                int intSlice = Integer.parseInt(ipSlices[i]) << 8 * i;
                
                rs = rs | intSlice;
            }
            return rs;
        }
    }
}
