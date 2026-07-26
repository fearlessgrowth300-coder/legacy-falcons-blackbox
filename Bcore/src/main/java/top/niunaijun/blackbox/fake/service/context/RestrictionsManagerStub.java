package top.niunaijun.blackbox.fake.service.context;

import android.content.Context;
import android.os.Bundle;

import java.lang.reflect.Method;

import black.android.content.BRIRestrictionsManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.GuestProxy;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;


public class RestrictionsManagerStub extends BinderInvocationStub {

    public RestrictionsManagerStub() {
        super(BRServiceManager.get().getService(Context.RESTRICTIONS_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIRestrictionsManagerStub.get().asInterface(BRServiceManager.get().getService(Context.RESTRICTIONS_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.RESTRICTIONS_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getApplicationRestrictions")
    public static class GetApplicationRestrictions extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Chromium's built-in resolver opens its own UDP DNS sockets. GuestProxy deliberately
            // refuses every Internet UDP socket because its authenticated upstream is TCP-only;
            // allowing those packets to fall back to Android would disclose the phone DNS/route.
            //
            // Chrome officially supports these Android managed restrictions. With the built-in
            // resolver and DoH disabled, Chrome uses libc/netd resolution, whose getaddrinfo path
            // GuestProxy replaces with a synthetic address and resolves remotely during the
            // authenticated HTTP CONNECT/SOCKS handshake. QUIC is disabled for the same reason.
            String guestPackage = BActivityThread.getAppPackageName();
            if (guestPackage != null
                    && guestPackage.toLowerCase(java.util.Locale.ROOT).contains("chrome")
                    && GuestProxy.CURRENT_ROUTE_ID != null) {
                Bundle restrictions = new Bundle();
                restrictions.putBoolean("BuiltInDnsClientEnabled", false);
                restrictions.putString("DnsOverHttpsMode", "off");
                restrictions.putBoolean("QuicAllowed", false);
                return restrictions;
            }
            args[0] = BlackBoxCore.getHostPkg();
            return method.invoke(who, args);
        }
    }
}
