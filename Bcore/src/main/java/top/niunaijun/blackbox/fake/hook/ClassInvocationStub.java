package top.niunaijun.blackbox.fake.hook;

import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import top.niunaijun.blackbox.utils.MethodParameterUtils;


public abstract class ClassInvocationStub implements InvocationHandler, IInjectHook {
    public static final String TAG = ClassInvocationStub.class.getSimpleName();

    private final Map<String, MethodHook> mMethodHookMap = new HashMap<>();
    private Object mBase;
    private Object mProxyInvocation;
    private boolean onlyProxy;

    protected abstract Object getWho();

    protected abstract void inject(Object baseInvocation, Object proxyInvocation);

    protected void onBindMethod() {

    }

    protected Object getProxyInvocation() {
        return mProxyInvocation;
    }

    protected Object getBase() {
        return mBase;
    }

    protected void onlyProxy(boolean o) {
        onlyProxy = o;
    }

    /** Class whose methods should be Pine-hooked when this stub has no binder base. */
    protected Class<?> hookTargetClass() {
        return null;
    }

    public boolean hasHook(String name) {
        return name != null && mMethodHookMap.containsKey(name);
    }

    /** Pine after-call. Default keeps the original result. Must not throw out of the hook. */
    protected void afterPineHook(String methodName, Object self, Object[] args, Object frame) {
    }

    @Override
    public void injectHook() {
        try {
            bindAnnotations();
            mBase = getWho();
            if (mBase == null) {
                Log.w(TAG, getClass().getSimpleName() + " has no base; binder proxy not installed");
                attachPine();
                return;
            }
            mProxyInvocation = Proxy.newProxyInstance(mBase.getClass().getClassLoader(),
                    MethodParameterUtils.getAllInterface(mBase.getClass()), this);
            if (!onlyProxy) {
                inject(mBase, mProxyInvocation);
            }
            attachPine();
        } catch (Throwable error) {
            Log.w(TAG, getClass().getSimpleName() + " inject degraded: "
                    + error.getClass().getSimpleName());
        }
    }

    private void bindAnnotations() {
        onBindMethod();
        Class<?>[] declaredClasses = this.getClass().getDeclaredClasses();
        for (Class<?> declaredClass : declaredClasses) {
            initAnnotation(declaredClass);
        }
        ScanClass scanClass = this.getClass().getAnnotation(ScanClass.class);
        if (scanClass != null) {
            for (Class<?> aClass : scanClass.value()) {
                for (Class<?> declaredClass : aClass.getDeclaredClasses()) {
                    initAnnotation(declaredClass);
                }
            }
        }
    }

    private void attachPine() {
        Class<?> target;
        try {
            target = hookTargetClass();
        } catch (Throwable error) {
            Log.w(TAG, getClass().getSimpleName() + " hook target unavailable");
            return;
        }
        if (target == null) return;
        long page = 4096L;
        try {
            page = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
        } catch (Throwable ignored) {
        }
        if (top.niunaijun.blackbox.core.PineRuntime.hooksCrashThisRuntime(
                android.os.Build.VERSION.SDK_INT, page)) {
            Log.w(TAG, getClass().getSimpleName()
                    + " class hooks skipped; Pine is unsafe on this runtime");
            return;
        }
        try {
            PineClassAttacher.attach(target, this);
        } catch (Throwable error) {
            Log.w(TAG, getClass().getSimpleName() + " class hooks degraded: "
                    + error.getClass().getSimpleName());
        }
    }

    protected void initAnnotation(Class<?> clazz) {
        ProxyMethod proxyMethod = clazz.getAnnotation(ProxyMethod.class);
        if (proxyMethod != null) {
            final String name = proxyMethod.value();
            if (!TextUtils.isEmpty(name)) {
                try {
                    addMethodHook(name, (MethodHook) clazz.newInstance());
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }
        ProxyMethods proxyMethods = clazz.getAnnotation(ProxyMethods.class);
        if (proxyMethods != null) {
            String[] value = proxyMethods.value();
            for (String name : value) {
                try {
                    addMethodHook(name, (MethodHook) clazz.newInstance());
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }
    }

    protected void addMethodHook(MethodHook methodHook) {
        mMethodHookMap.put(methodHook.getMethodName(), methodHook);
    }

    protected void addMethodHook(String name, MethodHook methodHook) {
        mMethodHookMap.put(name, methodHook);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {











        MethodHook methodHook = mMethodHookMap.get(method.getName());
        if (methodHook == null || !methodHook.isEnable()) {
            try {
                return method.invoke(mBase, args);
            } catch (Throwable e) {
                throw e.getCause();
            }
        }

        Object result = methodHook.beforeHook(mBase, method, args);
        if (result != null) {
            return result;
        }
        result = methodHook.hook(mBase, method, args);
        result = methodHook.afterHook(result);
        return result;
    }
}
