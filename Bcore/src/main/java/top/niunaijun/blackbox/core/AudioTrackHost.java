package top.niunaijun.blackbox.core;

import android.os.Parcel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.ContextCompat;

/**
 * Rewrites the attribution parcel and op package that {@code AudioTrack.native_setup}
 * hands to AudioFlinger. Called from the native hook, because that call never
 * enters {@code IAudioService}.
 */
public final class AudioTrackHost {
    public static final String TAG = "AudioTrackProxy";

    private AudioTrackHost() {}

    public static boolean inGuest() {
        return AudioClientIdentity.inGuest(guestPackage(), hostPackage());
    }

    public static String opPackage(String reported) {
        return AudioClientIdentity.packageForAudio(reported, guestPackage(), hostPackage());
    }

    /**
     * A parcel AudioFlinger can accept, or null to keep the original.
     * {@code realUid} is the process uid from native {@code getuid()}.
     */
    public static Parcel hostAttribution(Parcel source, int realUid) {
        if (source == null || !inGuest()) return null;
        String host = hostPackage();
        int uid = AudioClientIdentity.uidForAudio(true, realUid, hostUid());
        if (host == null || host.isEmpty() || uid <= 0) return null;
        Parcel copy = null;
        try {
            int size = source.dataSize();
            if (size <= 0) return null;
            copy = Parcel.obtain();
            copy.appendFrom(source, 0, size);
            copy.setDataPosition(0);
            Class<?> type = Class.forName("android.content.AttributionSource");
            Object creator = type.getField("CREATOR").get(null);
            Method create = creator.getClass().getMethod("createFromParcel", Parcel.class);
            Object parsed = create.invoke(creator, copy);
            if (parsed == null) return null;
            Object fixed = parsed;
            try {
                Method withPackage = type.getMethod("withPackageName", String.class);
                Object renamed = withPackage.invoke(parsed, host);
                if (renamed != null) fixed = renamed;
            } catch (Throwable ignored) {
            }
            writeIdentity(fixed, host, uid);
            ContextCompat.fixAttributionSourceState(fixed, uid, host);
            String written = String.valueOf(type.getMethod("getPackageName").invoke(fixed));
            int writtenUid = (Integer) type.getMethod("getUid").invoke(fixed);
            if (!host.equals(written) || writtenUid != uid) {
                Slog.w(TAG, "attribution rewrite missed package=" + host + " uid=" + uid);
                return null;
            }
            Parcel out = Parcel.obtain();
            try {
                type.getMethod("writeToParcel", Parcel.class, int.class).invoke(fixed, out, 0);
                out.setDataPosition(0);
                return out;
            } catch (Throwable writeFailed) {
                out.recycle();
                throw writeFailed;
            }
        } catch (Throwable t) {
            Slog.w(TAG, "attribution rewrite failed package=" + host + " uid=" + uid
                    + " " + t.getClass().getSimpleName());
            return null;
        } finally {
            if (copy != null) copy.recycle();
        }
    }

    public static int outputForStatus(int status) {
        return AudioClientIdentity.outputForStatus(status);
    }

    public static void logCreateTrack(String pkg, int uid, int session, int output, int status,
                                      boolean parcelRewritten) {
        Slog.i(TAG, AudioClientIdentity.createTrackLog(
                pkg, uid, session, output, status, parcelRewritten));
    }

    private static void writeIdentity(Object source, String host, int uid) {
        try {
            Field stateField = source.getClass().getDeclaredField("mAttributionSourceState");
            stateField.setAccessible(true);
            Object state = stateField.get(source);
            if (state == null) return;
            setIdentity(state, host, uid);
            Field nextField = state.getClass().getDeclaredField("next");
            nextField.setAccessible(true);
            Object next = nextField.get(state);
            if (next instanceof Object[]) {
                for (Object child : (Object[]) next) {
                    if (child != null) setIdentity(child, host, uid);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void setIdentity(Object state, String host, int uid) throws Exception {
        Field uidField = state.getClass().getDeclaredField("uid");
        uidField.setAccessible(true);
        uidField.setInt(state, uid);
        Field pkgField = state.getClass().getDeclaredField("packageName");
        pkgField.setAccessible(true);
        pkgField.set(state, host);
    }

    private static String guestPackage() {
        try {
            return BActivityThread.getAppPackageName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String hostPackage() {
        try {
            return BlackBoxCore.getHostPkg();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int hostUid() {
        try {
            return BlackBoxCore.getHostUid();
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
