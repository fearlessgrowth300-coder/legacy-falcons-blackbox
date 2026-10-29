package top.niunaijun.blackbox.fake.hook;

import java.lang.reflect.Method;

import top.canyie.pine.Pine;
import top.niunaijun.blackbox.utils.Slog;

/** Attaches already-registered method hooks with Pine. Loaded only when Pine is safe to load. */
public final class PineClassAttacher {
    private PineClassAttacher() {}

    static void attach(Class<?> target, ClassInvocationStub stub) {
        if (target == null || stub == null) return;
        int attached = 0;
        for (Method method : target.getDeclaredMethods()) {
            if (!stub.hasHook(method.getName())) continue;
            try {
                method.setAccessible(true);
                final String name = method.getName();
                Pine.hook(method, new top.canyie.pine.callback.MethodHook() {
                    @Override
                    public void afterCall(Pine.CallFrame frame) {
                        try {
                            stub.afterPineHook(name, frame.thisObject, frame.args, frame);
                        } catch (Throwable error) {
                            Slog.w("PineClassAttacher", name + " degraded: "
                                    + error.getClass().getSimpleName());
                        }
                    }
                });
                attached++;
            } catch (Throwable error) {
                Slog.w("PineClassAttacher", target.getSimpleName() + "." + method.getName()
                        + " not hooked: " + error.getClass().getSimpleName());
            }
        }
        Slog.d("PineClassAttacher", target.getSimpleName() + " hooks attached: " + attached);
    }

    public static Object getResult(Object frame) {
        if (frame instanceof Pine.CallFrame) return ((Pine.CallFrame) frame).getResult();
        return null;
    }

    public static void setResult(Object frame, Object value) {
        if (frame instanceof Pine.CallFrame) {
            ((Pine.CallFrame) frame).setResult(value);
        }
    }
}
