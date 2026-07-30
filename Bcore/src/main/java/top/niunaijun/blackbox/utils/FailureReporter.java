package top.niunaijun.blackbox.utils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports a failure that is NOT a crash, so it can be counted without holding the phone.
 *
 * Every serious defect found on real devices so far failed silently. A clone frozen on its logo threw
 * nothing in this app: the launch had already failed and only wrote to logcat. Crash reporting saw
 * none of it, so each one had to be reproduced over a cable on one specific handset — which does not
 * scale past a handful of users, and cannot catch a fault that only appears on one vendor's phones.
 *
 * The engine cannot depend on the host app, so the host registers a {@link Sink} at startup and this
 * class does nothing until it does. Reports carry no account, package data or proxy details beyond
 * what the caller passes, and the host applies its own scrubbing before anything is uploaded.
 */
public class FailureReporter {

    public interface Sink {
        void report(String kind, String detail, Map<String, Object> extras);
    }

    private static volatile Sink sSink;

    public static void install(Sink sink) {
        sSink = sink;
    }

    /** Never throws: reporting a failure must not become a second failure. */
    public static void report(String kind, String detail, Map<String, Object> extras) {
        Sink sink = sSink;
        if (sink == null) {
            return;
        }
        try {
            sink.report(kind, detail, extras == null ? new LinkedHashMap<>() : extras);
        } catch (Throwable error) {
            Slog.w("FailureReporter", "Could not report " + kind + ": "
                    + error.getClass().getSimpleName());
        }
    }

    /** Convenience for the common package + user shape. */
    public static void report(String kind, String detail, String packageName, int userId) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("pkg", packageName);
        extras.put("userId", userId);
        report(kind, detail, extras);
    }
}
