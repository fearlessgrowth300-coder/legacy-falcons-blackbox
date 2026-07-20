// IBJobManagerService.aidl
package top.niunaijun.blackbox.core.system.am;

import android.content.Intent;
import android.content.ComponentName;
import android.os.IBinder;
import java.lang.String;
import java.util.List;
import android.app.job.JobInfo;
import top.niunaijun.blackbox.entity.JobRecord;

// Declare any non-default types here with import statements

interface IBJobManagerService {
    JobInfo schedule(in JobInfo info, int userId, boolean namespaceIsolated);
    JobRecord queryJobRecord(String processName, int jobId, int userId);
    void cancelAll(String processName, int userId);
    int cancel(String processName, int jobId, int userId);
    List<JobInfo> getAllPendingJobs(String processName, int userId);
    JobInfo getPendingJob(String processName, int jobId, int userId);

}
