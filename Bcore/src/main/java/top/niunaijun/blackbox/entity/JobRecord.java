package top.niunaijun.blackbox.entity;

import android.app.job.JobInfo;
import android.app.job.JobService;
import android.content.pm.ServiceInfo;
import android.os.Parcel;
import android.os.Parcelable;



public class JobRecord implements Parcelable {

    public JobInfo mJobInfo;
    public ServiceInfo mServiceInfo;
    public int mUserId;
    public String mProcessName;
    public int mGuestJobId;
    public boolean mNamespaceIsolated;

    public JobService mJobService;

    public JobRecord() {
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(this.mJobInfo, flags);
        dest.writeParcelable(this.mServiceInfo, flags);
        dest.writeInt(this.mUserId);
        dest.writeString(this.mProcessName);
        dest.writeInt(this.mGuestJobId);
        dest.writeInt(this.mNamespaceIsolated ? 1 : 0);
    }

    protected JobRecord(Parcel in) {
        this.mJobInfo = in.readParcelable(JobInfo.class.getClassLoader());
        this.mServiceInfo = in.readParcelable(ServiceInfo.class.getClassLoader());
        this.mUserId = in.readInt();
        this.mProcessName = in.readString();
        this.mGuestJobId = in.readInt();
        this.mNamespaceIsolated = in.readInt() != 0;
    }

    public static final Creator<JobRecord> CREATOR = new Creator<JobRecord>() {
        @Override
        public JobRecord createFromParcel(Parcel source) {
            return new JobRecord(source);
        }

        @Override
        public JobRecord[] newArray(int size) {
            return new JobRecord[size];
        }
    };
}
