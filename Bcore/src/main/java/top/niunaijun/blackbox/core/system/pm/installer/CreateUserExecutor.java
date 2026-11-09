package top.niunaijun.blackbox.core.system.pm.installer;

import java.io.File;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.pm.BPackageSettings;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;


public class CreateUserExecutor implements Executor {
    private static final String TAG = "CreateUserExecutor";

    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        String packageName = ps.pkg.packageName;
        File data = BEnvironment.getDataDir(packageName, userId);
        boolean reuse = data.isDirectory();
        // mkdir does not empty a directory that is already there. Install and
        // re-add leave data/user/<id>/<pkg> untouched, including databases and prefs.
        FileUtils.mkdirs(data);
        FileUtils.mkdirs(BEnvironment.getDataCacheDir(packageName, userId));
        FileUtils.mkdirs(BEnvironment.getDataFilesDir(packageName, userId));
        FileUtils.mkdirs(BEnvironment.getDataDatabasesDir(packageName, userId));
        FileUtils.mkdirs(BEnvironment.getDeDataDir(packageName, userId));
        if (reuse) {
            Slog.w(TAG, "reusing existing data for " + packageName + " user " + userId
                    + " at " + data.getAbsolutePath());
        } else {
            Slog.w(TAG, "created new data directory for " + packageName + " user " + userId
                    + " at " + data.getAbsolutePath());
        }
        return 0;
    }
}
