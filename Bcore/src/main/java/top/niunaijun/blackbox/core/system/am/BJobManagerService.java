package top.niunaijun.blackbox.core.system.am;

import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import black.android.app.job.BRJobInfo;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.entity.JobRecord;
import top.niunaijun.blackbox.proxy.ProxyManifest;


public class BJobManagerService extends IBJobManagerService.Stub implements ISystemService {
    private static final BJobManagerService sService = new BJobManagerService();

    
    private final Map<String, JobRecord> mJobRecords = new ConcurrentHashMap<>();
    private final Map<Integer, String> mLegacyJobOwners = new ConcurrentHashMap<>();

    public static BJobManagerService get() {
        return sService;
    }

    @Override
    public JobInfo schedule(JobInfo info, int userId, boolean namespaceIsolated) throws RemoteException {
        if (info == null || info.getService() == null) return null;
        ComponentName componentName = info.getService();
        Intent intent = new Intent();
        intent.setComponent(componentName);
        ResolveInfo resolveInfo = BPackageManagerService.get().resolveService(intent, PackageManager.GET_META_DATA, null, userId);
        if (resolveInfo == null) {
            return null;
        }
        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        ProcessRecord processRecord = BProcessManagerService.get().findProcessRecord(serviceInfo.packageName, serviceInfo.processName, userId);
        if (processRecord == null) {
            processRecord = BProcessManagerService.get().
                    startProcessLocked(serviceInfo.packageName, serviceInfo.processName, userId, -1, Binder.getCallingPid());
            if (processRecord == null) {
                throw new RuntimeException(
                        "Unable to create Process " + serviceInfo.processName);
            }
        }
        return scheduleJob(processRecord, info, serviceInfo, userId, namespaceIsolated);
    }

    @Override
    public JobRecord queryJobRecord(String processName, int jobId, int userId) throws RemoteException {
        return mJobRecords.get(formatKey(userId, processName, jobId));
    }

    public JobInfo scheduleJob(ProcessRecord processRecord, JobInfo info, ServiceInfo serviceInfo,
                               int userId, boolean namespaceIsolated) {
        int guestJobId = info.getId();
        String key = formatKey(userId, processRecord.processName, guestJobId);
        if (!namespaceIsolated) {
            String owner = mLegacyJobOwners.putIfAbsent(guestJobId, key);
            if (owner != null && !owner.equals(key)) {
                return null;
            }
        }
        JobRecord jobRecord = new JobRecord();
        jobRecord.mJobInfo = copyJobInfo(info);
        jobRecord.mServiceInfo = serviceInfo;
        jobRecord.mUserId = userId;
        jobRecord.mProcessName = processRecord.processName;
        jobRecord.mGuestJobId = guestJobId;
        jobRecord.mNamespaceIsolated = namespaceIsolated;

        mJobRecords.put(key, jobRecord);
        BRJobInfo.get(info)._set_service(new ComponentName(BlackBoxCore.getHostPkg(), ProxyManifest.getProxyJobService(processRecord.bpid)));
        return info;
    }

    @Override
    public void cancelAll(String processName, int userId) throws RemoteException {
        if (TextUtils.isEmpty(processName)) return;
        String prefix = formatPrefix(userId, processName);
        for (Map.Entry<String, JobRecord> entry : mJobRecords.entrySet()) {
            if (entry.getKey().startsWith(prefix) && mJobRecords.remove(entry.getKey(), entry.getValue())) {
                releaseLegacyOwner(entry.getKey(), entry.getValue());
            }
        }
    }

    @Override
    public int cancel(String processName, int jobId, int userId) throws RemoteException {
        String key = formatKey(userId, processName, jobId);
        JobRecord record = mJobRecords.remove(key);
        if (record == null) return -1;
        releaseLegacyOwner(key, record);
        return jobId;
    }

    @Override
    public List<JobInfo> getAllPendingJobs(String processName, int userId) {
        List<JobInfo> result = new ArrayList<>();
        String prefix = formatPrefix(userId, processName);
        for (Map.Entry<String, JobRecord> entry : mJobRecords.entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue().mJobInfo != null) {
                result.add(copyJobInfo(entry.getValue().mJobInfo));
            }
        }
        return result;
    }

    @Override
    public JobInfo getPendingJob(String processName, int jobId, int userId) {
        JobRecord record = mJobRecords.get(formatKey(userId, processName, jobId));
        return record == null || record.mJobInfo == null ? null : copyJobInfo(record.mJobInfo);
    }

    private void releaseLegacyOwner(String key, JobRecord record) {
        if (record != null && !record.mNamespaceIsolated) {
            mLegacyJobOwners.remove(record.mGuestJobId, key);
        }
    }

    private String formatPrefix(int userId, String processName) {
        return userId + "\u0000" + processName + "\u0000";
    }

    private String formatKey(int userId, String processName, int jobId) {
        return formatPrefix(userId, processName) + jobId;
    }

    private JobInfo copyJobInfo(JobInfo info) {
        if (info == null) return null;
        Parcel parcel = Parcel.obtain();
        try {
            info.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return JobInfo.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    @Override
    public void systemReady() {

    }
}
