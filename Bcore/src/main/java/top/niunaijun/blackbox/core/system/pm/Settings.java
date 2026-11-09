package top.niunaijun.blackbox.core.system.pm;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.os.Parcel;
import android.os.Process;
import android.util.ArrayMap;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.user.BUserInfo;
import top.niunaijun.blackbox.core.system.user.BUserManagerService;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.PackageParserCompat;


 class Settings {
    public static final String TAG = "Settings";

    final ArrayMap<String, BPackageSettings> mPackages = new ArrayMap<>();
    private final Map<String, Integer> mAppIds = new HashMap<>();
    private final Map<String, SharedUserSetting> mSharedUsers = SharedUserSetting.sSharedUsers;
    private int mCurrUid = 0;

    public Settings() {
        synchronized (mPackages) {
            loadUidLP();
            SharedUserSetting.loadSharedUsers();
        }
    }

    BPackageSettings getPackageLPw(String name, PackageParser.Package aPackage, InstallOption installOption) {
        BPackageSettings pkgSettings;
        BPackageSettings origSettings = new BPackageSettings();
        origSettings.pkg = new BPackage(aPackage);
        origSettings.pkg.installOption = installOption;
        origSettings.installOption = installOption;
        origSettings.pkg.mExtras = origSettings;
        origSettings.pkg.applicationInfo = PackageManagerCompat.generateApplicationInfo(origSettings.pkg, 0, BPackageUserState.create(), 0);
        synchronized (mPackages) {
            pkgSettings = mPackages.get(name);
            if (pkgSettings != null) {
                origSettings.appId = pkgSettings.appId;
                origSettings.userState = pkgSettings.userState;
            } else {
                boolean b = registerAppIdLPw(origSettings);
                if (!b) {
                    throw new RuntimeException("registerAppIdLPw err.");
                }
            }
        }
        return origSettings;
    }

    boolean registerAppIdLPw(BPackageSettings p) {
        boolean createdNew = false;
        String sharedUserId = p.pkg.mSharedUserId;
        SharedUserSetting sharedUserSetting = null;
        if (sharedUserId != null) {
            sharedUserSetting = mSharedUsers.get(sharedUserId);
            if (sharedUserSetting == null) {
                sharedUserSetting = new SharedUserSetting(sharedUserId);
                sharedUserSetting.userId = acquireAndRegisterNewAppIdLPw(p);
                mSharedUsers.put(sharedUserId, sharedUserSetting);
            }
        }
        if (sharedUserSetting != null) {
            p.appId = sharedUserSetting.userId;
            Slog.d(TAG, p.pkg.packageName + " sharedUserId = " + sharedUserId + ", setAppId = " + p.appId);
        }
        if (p.appId == 0) {
            
            p.appId = acquireAndRegisterNewAppIdLPw(p);
        }
        if (p.appId < 0) {
            createdNew = false;




        } else {
            createdNew = true;
        }
        saveUidLP();
        SharedUserSetting.saveSharedUsers();
        return createdNew;
    }

    private int acquireAndRegisterNewAppIdLPw(BPackageSettings obj) {
        
        Integer integer = mAppIds.get(obj.pkg.packageName);
        if (integer != null)
            return integer;

        if (mCurrUid >= Process.LAST_APPLICATION_UID) {
            return -1;
        }
        mCurrUid++;
        mAppIds.put(obj.pkg.packageName, mCurrUid);
        return Process.FIRST_APPLICATION_UID + mCurrUid;
    }

    private void saveUidLP() {
        Parcel parcel = Parcel.obtain();
        FileOutputStream fileOutputStream = null;
        AtomicFile atomicFile = new AtomicFile(BEnvironment.getUidConf());
        try {
            Set<String> pkgName = mPackages.keySet();
            for (String s : new HashSet<>(mAppIds.keySet())) {
                if (!pkgName.contains(s)) {
                    mAppIds.remove(s);
                }
            }
            parcel.writeInt(mCurrUid);
            parcel.writeMap(mAppIds);

            fileOutputStream = atomicFile.startWrite();
            FileUtils.writeParcelToOutput(parcel, fileOutputStream);
            atomicFile.finishWrite(fileOutputStream);
        } catch (Exception e) {
            e.printStackTrace();
            atomicFile.failWrite(fileOutputStream);
        } finally {
            parcel.recycle();
        }
    }

    private void loadUidLP() {
        Parcel parcel = Parcel.obtain();
        try {
            byte[] uidBytes = FileUtils.toByteArray(BEnvironment.getUidConf());
            if (uidBytes == null || uidBytes.length == 0) {
                
                return;
            }
            
            parcel.unmarshall(uidBytes, 0, uidBytes.length);
            parcel.setDataPosition(0);

            mCurrUid = parcel.readInt();
            HashMap hashMap = parcel.readHashMap(HashMap.class.getClassLoader());
            synchronized (mAppIds) {
                mAppIds.clear();
                mAppIds.putAll(hashMap);
            }
        } catch (Exception e) {
            // Leave uid.conf on disk. Deleting it on a parse error remaps every
            // package the next time an id is assigned.
            Slog.e(TAG, "uid.conf could not be read; keeping the file and the previous ids: " + e);
        } finally {
            parcel.recycle();
        }
    }

    public void scanPackage() {
        synchronized (mPackages) {
            File appRootDir = BEnvironment.getAppRootDir();
            FileUtils.mkdirs(appRootDir);
            File[] apps = appRootDir.listFiles();
            if (apps == null) {
                Slog.e(TAG, "package directory could not be listed: " + appRootDir);
                return;
            }
            int loaded = 0;
            int skipped = 0;
            for (File app : apps) {
                if (!app.isDirectory()) {
                    continue;
                }
                if (scanPackage(app.getName())) loaded++;
                else skipped++;
            }
            Slog.w(TAG, "package scan finished: directories=" + apps.length
                    + " loaded=" + loaded + " skipped=" + skipped
                    + " inMemory=" + mPackages.size());
        }
    }

    /**
     * Names of package directories only. Does not open or walk the files inside them.
     * The installed-package snapshot is taken under the lock; the disk walk is not.
     */
    void logOrphanedData() {
        Map<String, Set<Integer>> installed = new HashMap<>();
        synchronized (mPackages) {
            for (Map.Entry<String, BPackageSettings> entry : mPackages.entrySet()) {
                BPackageSettings settings = entry.getValue();
                if (settings == null) continue;
                Set<Integer> users = new HashSet<>();
                for (Integer userId : settings.userState.keySet()) {
                    if (userId != null && settings.getInstalled(userId)) users.add(userId);
                }
                installed.put(entry.getKey(), users);
            }
        }
        int orphans = 0;
        orphans += logPackageTree(installed, new File(BEnvironment.getVirtualRoot(), "data/user"), false, "data/user");
        orphans += logPackageTree(installed, new File(BEnvironment.getVirtualRoot(), "data/user_de"), false, "data/user_de");
        File externalUsers = BEnvironment.getExternalUserDir(0).getParentFile();
        orphans += logPackageTree(installed, externalUsers, true, "external");
        Slog.w(TAG, "orphaned package data directories=" + orphans);
        for (BUserInfo user : BUserManagerService.get().getUsers()) {
            File internal = BEnvironment.getUserDir(user.id);
            File deviceProtected = new File(BEnvironment.getVirtualRoot(), "data/user_de/" + user.id);
            Slog.w(TAG, "user data present user=" + user.id
                    + " data/user=" + internal.isDirectory()
                    + " user_de=" + deviceProtected.isDirectory());
        }
    }

    private int logPackageTree(Map<String, Set<Integer>> installed, File userRoot,
                               boolean external, String place) {
        File[] users = userRoot == null ? null : userRoot.listFiles();
        if (users == null) {
            Slog.w(TAG, "data root absent: " + place + " path=" + userRoot);
            return 0;
        }
        int orphans = 0;
        for (File userDir : users) {
            if (!userDir.isDirectory()) continue;
            int userId;
            try {
                userId = Integer.parseInt(userDir.getName());
            } catch (NumberFormatException ignored) {
                continue;
            }
            File packageRoot = external ? new File(userDir, "Android/data") : userDir;
            String[] packages = packageRoot.list();
            if (packages == null) continue;
            for (String packageName : packages) {
                if (packageName.indexOf('.') < 0) continue;
                File packageDir = new File(packageRoot, packageName);
                if (!packageDir.isDirectory()) continue;
                Set<Integer> usersWithPackage = installed.get(packageName);
                if (usersWithPackage != null && usersWithPackage.contains(userId)) continue;
                orphans++;
                Slog.w(TAG, "orphaned data user=" + userId + " pkg=" + packageName
                        + " place=" + place + " path=" + packageDir.getAbsolutePath());
            }
        }
        return orphans;
    }

    public boolean scanPackage(String packageName) {
        synchronized (mPackages) {
            return updatePackageLP(BEnvironment.getAppDir(packageName));
        }
    }

    private boolean updatePackageLP(File app) {
        String packageName = app.getName();
        Parcel packageSettingsIn = Parcel.obtain();
        File packageConf = BEnvironment.getPackageConf(packageName);
        try {
            byte[] bPackageSettingsBytes = FileUtils.toByteArray(packageConf);
            if (bPackageSettingsBytes == null || bPackageSettingsBytes.length == 0) {
                Slog.e(TAG, "skipped package " + packageName + ": package.conf is missing or empty; files were kept");
                return false;
            }

            packageSettingsIn.unmarshall(bPackageSettingsBytes, 0, bPackageSettingsBytes.length);
            packageSettingsIn.setDataPosition(0);

            BPackageSettings bPackageSettings = new BPackageSettings(packageSettingsIn);
            if (bPackageSettings.pkg == null) {
                Slog.e(TAG, "skipped package " + packageName + ": package.conf has no package; files were kept");
                return false;
            }
            bPackageSettings.pkg.mExtras = bPackageSettings;
            if (bPackageSettings.installOption != null
                    && bPackageSettings.installOption.isFlag(InstallOption.FLAG_SYSTEM)) {
                PackageInfo packageInfo = BlackBoxCore.getPackageManager().getPackageInfo(packageName, PackageManager.GET_META_DATA);
                String currPackageSourcePath = packageInfo.applicationInfo.sourceDir;
                String[] splitSourceDirs = packageInfo.applicationInfo.splitSourceDirs;
                boolean installedAsSplitPackage =
                        splitSourceDirs != null && splitSourceDirs.length > 0;
                if (!currPackageSourcePath.equals(bPackageSettings.pkg.baseCodePath)
                        || installedAsSplitPackage) {

                    BProcessManagerService.get().killAllByPackageName(bPackageSettings.pkg.packageName);
                    BPackageSettings newPkg = reInstallBySystem(packageInfo, bPackageSettings.installOption);
                    bPackageSettings.pkg = newPkg.pkg;
                    bPackageSettings.pkg.mExtras = bPackageSettings;
                }
            } else if (bPackageSettings.pkg.applicationInfo == null) {
                bPackageSettings.pkg.applicationInfo = PackageManagerCompat.generateApplicationInfo(bPackageSettings.pkg, 0, BPackageUserState.create(), 0);
            }
            int restored = restoreInstalledFromDataDirs(bPackageSettings);
            bPackageSettings.save();
            mPackages.put(bPackageSettings.pkg.packageName, bPackageSettings);
            Slog.w(TAG, "loaded package " + packageName
                    + " users=" + bPackageSettings.userState.size()
                    + " restored=" + restored);
            return true;
        } catch (Throwable e) {
            // A record that cannot be read must stay on disk. Deleting the
            // directory here is what emptied user 0 after an upgrade.
            Slog.e(TAG, "skipped package " + packageName + " without deleting its files: " + e);
            return false;
        } finally {
            packageSettingsIn.recycle();
        }
    }

    /** A data directory with no user-state row is still an installed clone. */
    private int restoreInstalledFromDataDirs(BPackageSettings settings) {
        if (settings.pkg == null || settings.pkg.packageName == null) return 0;
        File parent = BEnvironment.getUserDir(0).getParentFile();
        File[] children = parent == null ? null : parent.listFiles();
        if (children == null) return 0;
        int restored = 0;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            int userId;
            try {
                userId = Integer.parseInt(child.getName());
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (userId < 0 || settings.userState.containsKey(userId)) continue;
            if (!BUserManagerService.get().exists(userId)) continue;
            // Leftover directories must not turn a deleted user back into an install.
            if (BUserManagerService.get().isRecoveredStub(userId)) continue;
            File data = BEnvironment.getDataDir(settings.pkg.packageName, userId);
            if (!data.isDirectory()) continue;
            settings.setInstalled(true, userId);
            restored++;
            Slog.w(TAG, "restored installed flag for " + settings.pkg.packageName + " user " + userId);
        }
        return restored;
    }

    private BPackageSettings reInstallBySystem(PackageInfo systemPackageInfo, InstallOption option) throws Exception {
        Slog.d(TAG, "reInstallBySystem: " + systemPackageInfo.packageName);
        File parseTarget = PackageParserCompat.getInstalledPackageParseTarget(
                systemPackageInfo.applicationInfo);
        PackageParser.Package aPackage = parserApk(parseTarget.getAbsolutePath());
        if (aPackage == null) {
            throw new RuntimeException("parser apk error.");
        }
        aPackage.applicationInfo = systemPackageInfo.applicationInfo;
        return getPackageLPw(aPackage.packageName, aPackage, option);
    }

    public void removePackage(String packageName) {
        mPackages.remove(packageName);
    }

    private PackageParser.Package parserApk(String file) {
        try {
            PackageParser parser = PackageParserCompat.createParser(new File(file));
            PackageParser.Package aPackage = PackageParserCompat.parsePackage(parser, new File(file), 0);
            PackageParserCompat.collectCertificates(parser, aPackage, 0);
            return aPackage;
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return null;
    }
}
