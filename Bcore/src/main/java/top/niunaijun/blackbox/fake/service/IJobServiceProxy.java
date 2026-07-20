package top.niunaijun.blackbox.fake.service;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import black.android.app.job.BRIJobSchedulerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Virtualizes JobScheduler without ever falling through to a real, physically installed app.
 * Android 14+ namespaces are replaced with a stable per-BlackBox-user/process namespace. Older
 * Android releases are protected by BJobManagerService's single-owner check for legacy job IDs.
 */
public class IJobServiceProxy extends BinderInvocationStub {
    public static final String TAG = "JobServiceStub";

    public IJobServiceProxy() {
        super(BRServiceManager.get().getService(Context.JOB_SCHEDULER_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder jobScheduler = BRServiceManager.get().getService(Context.JOB_SCHEDULER_SERVICE);
        return BRIJobSchedulerStub.get().asInterface(jobScheduler);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    @ProxyMethod("schedule")
    public static class Schedule extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return scheduleInternal(who, method, args);
        }
    }

    @ProxyMethod("enqueue")
    public static class Enqueue extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            return scheduleInternal(who, method, args);
        }
    }

    @ProxyMethod("cancel")
    public static class Cancel extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            int jobIdIndex = findLastIntegerArg(args);
            if (jobIdIndex < 0) return defaultValue(method);
            int guestJobId = (Integer) args[jobIdIndex];
            try {
                int systemJobId = BlackBoxCore.getBJobManager().cancel(processName(), guestJobId);
                if (systemJobId < 0) return defaultValue(method);
                args[jobIdIndex] = systemJobId;
                applyIsolatedNamespace(method, args);
                return method.invoke(who, args);
            } catch (Throwable t) {
                Slog.w(TAG, "Cancel failed closed for virtual job " + guestJobId, t);
                return defaultValue(method);
            }
        }
    }

    @ProxyMethod("cancelAll")
    public static class CancelAll extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            try {
                BlackBoxCore.getBJobManager().cancelAll(processName());
                // On namespace-capable Android this cancels only the current virtual app's jobs.
                // Legacy cancelAll is intentionally not forwarded because it would cancel jobs
                // belonging to every clone sharing the BlackBox host UID.
                if (applyIsolatedNamespace(method, args)) {
                    return method.invoke(who, args);
                }
            } catch (Throwable t) {
                Slog.w(TAG, "Cancel-all failed closed for virtual process " + processName(), t);
            }
            return defaultValue(method);
        }
    }

    @ProxyMethod("getAllPendingJobs")
    public static class GetAllPendingJobs extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            try {
                // Modern Android can safely ask the real scheduler after constraining the query to
                // this clone's namespace. Legacy Android receives only BlackBox's virtual records.
                if (applyIsolatedNamespace(method, args)) {
                    return method.invoke(who, args);
                }
                List<JobInfo> jobs = BlackBoxCore.getBJobManager().getAllPendingJobs(processName());
                return adaptJobList(method.getReturnType(), jobs);
            } catch (Throwable t) {
                Slog.w(TAG, "Pending-job query failed closed", t);
                return adaptJobList(method.getReturnType(), Collections.emptyList());
            }
        }
    }

    @ProxyMethod("getPendingJob")
    public static class GetPendingJob extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            int jobIdIndex = findLastIntegerArg(args);
            if (jobIdIndex < 0) return null;
            try {
                if (applyIsolatedNamespace(method, args)) {
                    return method.invoke(who, args);
                }
                return BlackBoxCore.getBJobManager().getPendingJob(
                        processName(), (Integer) args[jobIdIndex]);
            } catch (Throwable t) {
                Slog.w(TAG, "Pending-job lookup failed closed", t);
                return null;
            }
        }
    }

    private static Object scheduleInternal(Object who, Method method, Object[] args) {
        int jobInfoIndex = findJobInfoArg(args);
        if (jobInfoIndex < 0) {
            Slog.w(TAG, method.getName() + " rejected: no JobInfo argument");
            return JobScheduler.RESULT_FAILURE;
        }
        JobInfo guestJob = (JobInfo) args[jobInfoIndex];
        if (guestJob == null) return JobScheduler.RESULT_FAILURE;
        int guestJobId = guestJob.getId();
        boolean namespaceIsolated = applyIsolatedNamespace(method, args);
        try {
            JobInfo proxyJob = BlackBoxCore.getBJobManager().schedule(
                    guestJob, namespaceIsolated);
            if (proxyJob == null) return JobScheduler.RESULT_FAILURE;
            args[jobInfoIndex] = proxyJob;
            Object result = method.invoke(who, args);
            if (result instanceof Integer
                    && ((Integer) result) != JobScheduler.RESULT_SUCCESS) {
                BlackBoxCore.getBJobManager().cancel(processName(), guestJobId);
            }
            return result;
        } catch (Throwable t) {
            BlackBoxCore.getBJobManager().cancel(processName(), guestJobId);
            Slog.w(TAG, method.getName() + " failed closed for virtual job " + guestJobId, t);
            return JobScheduler.RESULT_FAILURE;
        }
    }

    private static int findJobInfoArg(Object[] args) {
        if (args == null) return -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof JobInfo) return i;
        }
        return -1;
    }

    private static int findLastIntegerArg(Object[] args) {
        if (args == null) return -1;
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof Integer) return i;
        }
        return -1;
    }

    private static boolean applyIsolatedNamespace(Method method, Object[] args) {
        if (method == null || args == null) return false;
        Class<?>[] parameterTypes = method.getParameterTypes();
        int count = Math.min(parameterTypes.length, args.length);
        for (int i = 0; i < count; i++) {
            if (parameterTypes[i] == String.class) {
                String guestNamespace = args[i] instanceof String ? (String) args[i] : null;
                args[i] = isolatedNamespace(guestNamespace);
                return true;
            }
        }
        return false;
    }

    private static String isolatedNamespace(String guestNamespace) {
        AppConfig config = BActivityThread.getAppConfig();
        int userId = config == null ? BActivityThread.getUserId() : config.userId;
        String process = config == null ? processName() : config.processName;
        int processHash = process == null ? 0 : process.hashCode();
        int namespaceHash = guestNamespace == null ? 0 : guestNamespace.hashCode();
        return "bb_u" + userId + "_p" + Integer.toHexString(processHash)
                + "_n" + Integer.toHexString(namespaceHash);
    }

    private static String processName() {
        AppConfig config = BActivityThread.getAppConfig();
        if (config != null && config.processName != null) return config.processName;
        String process = BlackBoxCore.getAppProcessName();
        return process == null ? "" : process;
    }

    private static Object adaptJobList(Class<?> returnType, List<JobInfo> jobs) {
        if (returnType == null || returnType.isAssignableFrom(jobs.getClass())
                || List.class.isAssignableFrom(returnType)) {
            return jobs;
        }
        try {
            Constructor<?> constructor = returnType.getDeclaredConstructor(List.class);
            constructor.setAccessible(true);
            return constructor.newInstance(jobs);
        } catch (Throwable t) {
            Slog.w(TAG, "Unable to create virtual pending-job result", t);
            return null;
        }
    }

    private static Object defaultValue(Method method) {
        if (method == null) return null;
        Class<?> type = method.getReturnType();
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        return null;
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
