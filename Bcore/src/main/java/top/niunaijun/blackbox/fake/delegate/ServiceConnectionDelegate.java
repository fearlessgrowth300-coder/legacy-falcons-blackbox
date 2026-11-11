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
    private final boolean mPlayOrGms;

    private ServiceConnectionDelegate(IServiceConnection mConn, ComponentName targetComponent,
                                      boolean isAdId, boolean playOrGms) {
        this.mConn = mConn;
        this.mComponentName = targetComponent;
        this.mIsAdId = isAdId;
        this.mPlayOrGms = playOrGms;
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
            delegate = new ServiceConnectionDelegate(base, intent.getComponent(), isAdIdBind(intent),
                    isPlayOrGms(intent));
            sServiceConnectDelegate.put(iBinder, delegate);
        }
        return delegate;
    }

    /** Real Play Store and Play services. Their callbacks must reach the guest unchanged. */
    public static boolean isPlayOrGms(Intent intent) {
        if (intent == null) return false;
        String pkg = intent.getPackage();
        if (pkg == null && intent.getComponent() != null) {
            pkg = intent.getComponent().getPackageName();
        }
        if ("com.android.vending".equals(pkg) || "com.google.android.gms".equals(pkg)
                || "com.google.android.gsf".equals(pkg)) {
            return true;
        }
        String action = intent.getAction();
        return action != null && (action.startsWith("com.google.android.gms.")
                || action.startsWith("com.android.vending.")
                || action.contains("finsky"));
    }

    @Override
    public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
            throws RemoteException {
        // The local compatibility AIDL has the old two-argument method, while Android 8+ sends a
        // wider hidden IServiceConnection payload. Rebuild it on Android 16 so the guest sees its
        // target component and its per-user GAID binder.
        //
        // Only the two leading fields may be decoded. Android 16 (API 36) carries
        // connected(ComponentName, IBinder service, IBinderSession session, boolean dead) — four
        // arguments, not three. Consuming a trailing boolean here read `dead` out of the session's
        // slot and dropped the session from the rebuilt parcel, so the guest's real API-36 Stub
        // then read a null session and a garbage boolean, never delivered onServiceConnected, and
        // every in-clone bind silently failed. Instagram's in-app browser reported "Callback
        // service is not available" on a 3s retry loop and rendered "An unknown error occurred"
        // instead of the page. Copy everything after the service binder through verbatim so this
        // stays correct for API 36 and for any argument a later platform appends.
        if (mPlayOrGms && !mIsAdId) {
            // Play Integrity and Age Signals wait on this callback. Rewriting the parcel
            // dropped fields and the guest never saw success or the real error.
            data.setDataPosition(0);
            top.niunaijun.blackbox.utils.Slog.i("GuestServiceBind",
                    "callback code=" + code + " cmp=" + mComponentName);
            if (code == android.os.IBinder.FIRST_CALL_TRANSACTION) {
                android.os.Parcel traced = null;
                try {
                    data.enforceInterface("android.app.IServiceConnection");
                    ComponentName name = data.readTypedObject(ComponentName.CREATOR);
                    IBinder service = data.readStrongBinder();
                    int tailPos = data.dataPosition();
                    int tailLen = data.dataSize() - tailPos;
                    traced = android.os.Parcel.obtain();
                    traced.writeInterfaceToken("android.app.IServiceConnection");
                    traced.writeTypedObject(name, 0);
                    traced.writeStrongBinder(service == null ? null
                            : new GmsCallTrace(service, String.valueOf(name)));
                    if (tailLen > 0) traced.appendFrom(data, tailPos, tailLen);
                    return mConn.asBinder().transact(code, traced, reply, flags);
                } catch (Throwable t) {
                    data.setDataPosition(0);
                } finally {
                    if (traced != null) traced.recycle();
                }
            }
            return mConn.asBinder().transact(code, data, reply, flags);
        }
        if (code == android.os.IBinder.FIRST_CALL_TRANSACTION
                && android.os.Build.VERSION.SDK_INT >= 36) {
            android.os.Parcel forwarded = null;
            try {
                data.setDataPosition(0);
                data.enforceInterface("android.app.IServiceConnection");
                ComponentName name = data.readTypedObject(ComponentName.CREATOR);
                IBinder service = data.readStrongBinder();
                // Whatever remains (API 36: IBinderSession + dead) is forwarded untouched.
                final int tailPos = data.dataPosition();
                final int tailLen = data.dataSize() - tailPos;

                forwarded = android.os.Parcel.obtain();
                forwarded.writeInterfaceToken("android.app.IServiceConnection");
                forwarded.writeTypedObject(mComponentName != null ? mComponentName : name, 0);
                forwarded.writeStrongBinder(maybeSpoofAdvertisingId(service));
                if (tailLen > 0) {
                    // appendFrom preserves flattened binder objects, so the session survives intact.
                    forwarded.appendFrom(data, tailPos, tailLen);
                }
                top.niunaijun.blackbox.utils.Slog.d("SvcConn",
                        "Forwarding API 36 service callback for "
                                + (mComponentName != null ? mComponentName : name)
                                + " (+" + tailLen + "B tail)");
                return mConn.asBinder().transact(code, forwarded, reply, flags);
            } catch (Throwable t) {
                top.niunaijun.blackbox.utils.Slog.d("SvcConn",
                        "API 36 callback rewrite failed: " + t.getClass().getSimpleName());
                if (mIsAdId) return true; // fail closed: unavailable is safer than host GAID
                data.setDataPosition(0);
                return mConn.asBinder().transact(code, data, reply, flags);
            } finally {
                if (forwarded != null) forwarded.recycle();
            }
        }
        return super.onTransact(code, data, reply, flags);
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
                    top.niunaijun.blackbox.utils.Slog.d("GAID", "served per-user advertising id to guest");
                    return new AdIdStub(cur.gaid);
                }
            }
        } catch (Throwable ignored) {
        }
        return real;
    }

    /**
     * Diagnostic: logs every call a guest makes on a Play or GMS service binder, so a sign-up
     * step that waits forever shows which Google call it made last.
     */
    private static class GmsCallTrace extends android.os.Binder {
        private final IBinder real;
        private final String label;

        GmsCallTrace(IBinder real, String label) {
            this.real = real;
            this.label = label;
        }

        /**
         * Play Integrity checks that the package named in the request belongs to the calling
         * UID. A clone runs under the container's UID, so Play threw "Different UID from the
         * calling app" inside a oneway call and never answered. Facebook waited on that token
         * after the gender step forever. Ask under the container's own package so Play answers.
         */
        private static android.os.Parcel asHostPackage(String descriptor, android.os.Parcel data) {
            if (descriptor == null || !descriptor.startsWith(
                    "com.google.android.play.core.integrity.protocol.")) {
                return null;
            }
            android.os.Parcel out = null;
            try {
                data.setDataPosition(0);
                data.enforceInterface(descriptor);
                if (data.readInt() == 0) return null;
                android.os.Bundle request = android.os.Bundle.CREATOR.createFromParcel(data);
                String guest = request.getString("package.name");
                String host = top.niunaijun.blackbox.BlackBoxCore.getHostPkg();
                if (guest == null || host == null || guest.equals(host)) return null;
                request.putString("package.name", host);
                int tailPos = data.dataPosition();
                out = android.os.Parcel.obtain();
                out.writeInterfaceToken(descriptor);
                out.writeInt(1);
                request.writeToParcel(out, 0);
                if (data.dataSize() > tailPos) out.appendFrom(data, tailPos, data.dataSize() - tailPos);
                out.setDataPosition(0);
                return out;
            } catch (Throwable t) {
                if (out != null) out.recycle();
                return null;
            } finally {
                data.setDataPosition(0);
            }
        }

        @Override
        public String getInterfaceDescriptor() {
            try {
                return real.getInterfaceDescriptor();
            } catch (RemoteException e) {
                return null;
            }
        }

        @Override
        public boolean isBinderAlive() {
            return real.isBinderAlive();
        }

        @Override
        public boolean pingBinder() {
            return real.pingBinder();
        }

        @Override
        public void linkToDeath(DeathRecipient recipient, int flags) {
            try {
                real.linkToDeath(recipient, flags);
            } catch (RemoteException ignored) {
            }
        }

        @Override
        public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            return real.unlinkToDeath(recipient, flags);
        }

        @Override
        protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply,
                                     int flags) throws RemoteException {
            String descriptor = null;
            try {
                descriptor = real.getInterfaceDescriptor();
            } catch (Throwable ignored) {
            }
            long start = android.os.SystemClock.uptimeMillis();
            boolean ok = false;
            String error = null;
            android.os.Parcel rewritten = null;
            try {
                data.setDataPosition(0);
                rewritten = asHostPackage(descriptor, data);
                ok = real.transact(code, rewritten != null ? rewritten : data, reply, flags);
                return ok;
            } catch (RemoteException | RuntimeException e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                throw e;
            } finally {
                if (rewritten != null) rewritten.recycle();
                top.niunaijun.blackbox.utils.Slog.i("GmsCallTrace", label + " " + descriptor
                        + (rewritten != null ? " asHost" : "")
                        + " code=" + code + ((flags & FLAG_ONEWAY) != 0 ? " oneway" : "")
                        + " ok=" + ok + " ms=" + (android.os.SystemClock.uptimeMillis() - start)
                        + (error == null ? "" : " error=" + error));
            }
        }
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
