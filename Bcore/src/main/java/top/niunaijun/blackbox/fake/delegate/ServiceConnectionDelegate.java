package top.niunaijun.blackbox.fake.delegate;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.HashMap;
import java.util.Map;

import black.android.app.BRIServiceConnectionO;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class ServiceConnectionDelegate extends IServiceConnection.Stub {
    private static final Map<IBinder, ServiceConnectionDelegate> sServiceConnectDelegate = new HashMap<>();
    private final IServiceConnection mConn;
    private final ComponentName mComponentName;
    private final boolean mIsAdId;

    private ServiceConnectionDelegate(IServiceConnection mConn, ComponentName targetComponent, boolean isAdId) {
        this.mConn = mConn;
        this.mComponentName = targetComponent;
        this.mIsAdId = isAdId;
    }

    /** GMS resolves the ad-id service to a generic Chimera class, so key off the ACTION
     *  (and, as a fallback, the component class name). */
    public static boolean isAdIdBind(Intent intent) {
        try {
            String a = intent.getAction();
            if (a != null && a.contains("ads.identifier")) return true;
            ComponentName c = intent.getComponent();
            String cls = c == null ? null : c.getClassName();
            return cls != null && (cls.contains("ads.identifier") || cls.contains("AdvertisingId"));
        } catch (Throwable t) {
            return false;
        }
    }

    public static ServiceConnectionDelegate getDelegate(IBinder iBinder) {
        return sServiceConnectDelegate.get(iBinder);
    }

    public static IServiceConnection createProxy(IServiceConnection base, Intent intent) {
        final IBinder iBinder = base.asBinder();
        ServiceConnectionDelegate delegate = sServiceConnectDelegate.get(iBinder);
        if (delegate == null) {
            try {
                iBinder.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        sServiceConnectDelegate.remove(iBinder);
                        iBinder.unlinkToDeath(this, 0);
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            delegate = new ServiceConnectionDelegate(base, intent.getComponent(), isAdIdBind(intent));
            sServiceConnectDelegate.put(iBinder, delegate);
        }
        return delegate;
    }

    @Override
    public void connected(ComponentName name, IBinder service) throws RemoteException {
        connected(name, service, false);
    }

    public void connected(ComponentName name, IBinder service, boolean dead) throws RemoteException {
        IBinder deliver = maybeSpoofAdvertisingId(service);
        if (BuildCompat.isOreo()) {
            BRIServiceConnectionO.get(mConn).connected(mComponentName, deliver, dead);
        } else {
            mConn.connected(name, deliver);
        }
    }

    /**
     * Per-clone Google Advertising ID (GAID), obfuscation-proof. When a guest binds the
     * GMS advertising-id service, hand it OUR stub binder instead of the real one — the
     * stub answers getId() with this clone's GAID. Works for shaded apps (Instagram) too
     * because the service + AIDL belong to Google, not the app. Only touches this one
     * service, so there is zero overhead on any other binder traffic.
     */
    private IBinder maybeSpoofAdvertisingId(IBinder real) {
        try {
            if (mIsAdId) {
                top.niunaijun.blackbox.core.DeviceProfile cur =
                        top.niunaijun.blackbox.core.DeviceProfile.CURRENT;
                if (cur != null && cur.gaid != null) {
                    top.niunaijun.blackbox.utils.Slog.d("GAID", "served advertising id " + cur.gaid + " to guest");
                    return new AdIdStub(cur.gaid);
                }
            }
        } catch (Throwable ignored) {
        }
        return real;
    }

    /** Minimal IAdvertisingIdService (com.google.android.gms.ads.identifier). */
    private static class AdIdStub extends android.os.Binder {
        static final String DESC = "com.google.android.gms.ads.identifier.internal.IAdvertisingIdService";
        private final String gaid;

        AdIdStub(String gaid) {
            this.gaid = gaid;
            // No attachInterface: the guest's AdvertisingIdClient.Stub.asInterface() will
            // queryLocalInterface(), get null, and use transact() — handled by onTransact.
        }

        @Override
        protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
                throws RemoteException {
            switch (code) {
                case INTERFACE_TRANSACTION:
                    if (reply != null) reply.writeString(DESC);
                    return true;
                case 1: // getId()
                    data.enforceInterface(DESC);
                    if (reply != null) { reply.writeNoException(); reply.writeString(gaid); }
                    return true;
                case 2: // isLimitAdTrackingEnabled(boolean)
                    data.enforceInterface(DESC);
                    if (reply != null) { reply.writeNoException(); reply.writeInt(0); } // not limited
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }
}
