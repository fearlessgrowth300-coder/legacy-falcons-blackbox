package top.niunaijun.blackbox.core.system.user;

import android.os.Parcel;
import android.os.RemoteException;

import androidx.core.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import top.niunaijun.blackbox.core.GuestKeepAlive;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.KeystoreIsolation;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.core.system.accounts.BAccountManagerService;
import top.niunaijun.blackbox.core.system.location.BLocationManagerService;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.utils.CloseUtils;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;


public class BUserManagerService extends IBUserManagerService.Stub implements ISystemService {
    private static final String TAG = "BUserManagerService";
    private static BUserManagerService sService = new BUserManagerService();
    public final HashMap<Integer, BUserInfo> mUsers = new HashMap<>();
    public final Object mUserLock = new Object();

    public static BUserManagerService get() {
        return sService;
    }

    @Override
    public void systemReady() {
        scanUserL();
        migrateExistingUsersToIsolatedKeystore();
    }

    /**
     * Older builds allowed virtual users to share the host AndroidKeyStore namespace. Apps such as
     * Instagram keep login-encryption keys there, so signing in to one clone could replace another
     * clone's key and make that account appear logged out later.
     *
     * Hardware-backed keys cannot be exported or renamed. The safe migration is therefore to give
     * every existing virtual user its own namespace before any guest process can start. A legacy
     * account may require one final login after this upgrade, but subsequent keys cannot collide.
     */
    private void migrateExistingUsersToIsolatedKeystore() {
        synchronized (mUserLock) {
            synchronized (mUsers) {
                for (BUserInfo user : mUsers.values()) {
                    if (user.id >= 0 && !KeystoreIsolation.enableForUser(user.id)) {
                        throw new IllegalStateException(
                                "Could not enable encrypted-login isolation for User " + user.id);
                    }
                }
            }
        }
    }

    @Override
    public BUserInfo getUserInfo(int userId) {
        synchronized (mUserLock) {
            return mUsers.get(userId);
        }
    }

    @Override
    public boolean exists(int userId) {
        synchronized (mUsers) {
            return mUsers.get(userId) != null;
        }
    }

    @Override
    public BUserInfo createUser(int userId) throws RemoteException {
        synchronized (mUserLock) {
            if (exists(userId)) {
                return getUserInfo(userId);
            }
            return createUserLocked(userId);
        }
    }

    @Override
    public List<BUserInfo> getUsers() {
        synchronized (mUsers) {
            ArrayList<BUserInfo> bUsers = new ArrayList<>();
            for (BUserInfo value : mUsers.values()) {
                if (value.id >= 0) {
                    bUsers.add(value);
                }
            }
            return bUsers;
        }
    }

    public List<BUserInfo> getAllUsers() {
        synchronized (mUsers) {
            return new ArrayList<>(mUsers.values());
        }
    }

    @Override
    public void deleteUser(int userId) throws RemoteException {
        synchronized (mUserLock) {
            synchronized (mUsers) {
                if (mUsers.get(userId) == null) return;
            }
            if (!KeystoreIsolation.deleteForUser(userId)) {
                throw new IllegalStateException(
                        "Could not safely delete encrypted keys for User " + userId);
            }
            // Uninstall while the user still exists so running clones release their files.
            BPackageManagerService.get().deleteUser(userId);
            // keepalive.txt, fake-location.conf and data/user_de/<id> were never
            // touched here, and a failed directory listing aborted the rest after
            // the user record had already been saved. Clear those first, and keep
            // the user record until every directory is actually gone.
            GuestKeepAlive.forget(userId);
            BLocationManagerService.get().deleteUser(userId);
            BAccountManagerService.get().deleteUser(userId);
            if (!deleteUserFiles(userId)) {
                throw new IllegalStateException("Could not delete all files for User " + userId);
            }
            synchronized (mUsers) {
                mUsers.remove(userId);
                saveUserInfoLocked();
            }
        }
    }

    /**
     * Users 3, 7, 8, 9 and 10 were put back from leftover directories. Remove
     * them once, only when that recovery created the record and no package is
     * installed for them.
     */
    public void dropRecoveredEmptyUsers() {
        int[] recoveredIds = {3, 7, 8, 9, 10};
        for (int userId : recoveredIds) {
            if (!isRecoveredStub(userId)) {
                BUserInfo info = getUserInfo(userId);
                if (info == null) {
                    Slog.w(TAG, "recovered user " + userId + " is not in the user list");
                } else {
                    Slog.w(TAG, "keeping user " + userId
                            + "; it was not restored from a leftover directory");
                }
                continue;
            }
            if (BPackageManagerService.get().hasInstalledPackage(userId)) {
                Slog.w(TAG, "keeping recovered user " + userId + " because a package is still installed");
                continue;
            }
            try {
                deleteUser(userId);
                Slog.w(TAG, "removed recovered empty user " + userId);
            } catch (Throwable error) {
                Slog.e(TAG, "could not remove recovered user " + userId + ": " + error);
            }
        }
    }

    /** True when this record was inserted from a leftover directory, not created by the user. */
    public boolean isRecoveredStub(int userId) {
        if (userId != 3 && userId != 7 && userId != 8 && userId != 9 && userId != 10) return false;
        BUserInfo info = getUserInfo(userId);
        // createUser leaves createTime at 0. Directory recovery was the only writer.
        return info != null && info.createTime > 0;
    }

    private boolean deleteUserFiles(int userId) {
        File deviceProtected = new File(BEnvironment.getVirtualRoot(), "data/user_de/" + userId);
        boolean cleared = deleteTree(BEnvironment.getUserDir(userId));
        cleared &= deleteTree(deviceProtected);
        cleared &= deleteTree(BEnvironment.getExternalUserDir(userId));
        return cleared;
    }

    private boolean deleteTree(File dir) {
        if (dir == null || !dir.exists()) return true;
        FileUtils.deleteDir(dir);
        if (!dir.exists()) return true;
        Slog.e(TAG, "files remain after delete: " + dir.getAbsolutePath());
        return false;
    }

    private BUserInfo createUserLocked(int userId) {
        BUserInfo bUserInfo = new BUserInfo();
        bUserInfo.id = userId;
        bUserInfo.status = BUserStatus.ENABLE;
        mUsers.put(userId, bUserInfo);
        synchronized (mUsers) {
            saveUserInfoLocked();
        }
        // New users are safe from their first app launch.
        if (!top.niunaijun.blackbox.core.ProfilePolicy.store(
                top.niunaijun.blackbox.core.ProfilePolicy.fileFor(BEnvironment.getUserDir(userId)),
                top.niunaijun.blackbox.core.ProfilePolicy.defaults())) {
            Slog.w(TAG, "default isolation policy was not written for user " + userId);
        }
        if (!KeystoreIsolation.markNewUser(userId)) {
            mUsers.remove(userId);
            synchronized (mUsers) { saveUserInfoLocked(); }
            KeystoreIsolation.deleteForUser(userId);
            throw new IllegalStateException("Could not create isolated key namespace for User " + userId);
        }
        return bUserInfo;
    }

    private void saveUserInfoLocked() {
        Parcel parcel = Parcel.obtain();
        AtomicFile atomicFile = new AtomicFile(BEnvironment.getUserInfoConf());
        FileOutputStream fileOutputStream = null;
        try {
            ArrayList<BUserInfo> bUsers = new ArrayList<>(mUsers.values());
            parcel.writeTypedList(bUsers);
            try {
                fileOutputStream = atomicFile.startWrite();
                FileUtils.writeParcelToOutput(parcel, fileOutputStream);
                atomicFile.finishWrite(fileOutputStream);
            } catch (IOException e) {
                e.printStackTrace();
                atomicFile.failWrite(fileOutputStream);
            } finally {
                CloseUtils.close(fileOutputStream);
            }
        } finally {
            parcel.recycle();
        }
    }

    private void scanUserL() {
        synchronized (mUserLock) {
            Parcel parcel = Parcel.obtain();
            InputStream is = null;
            try {
                File userInfoConf = BEnvironment.getUserInfoConf();
                if (!userInfoConf.exists()) {
                    Slog.w(TAG, "user list file is missing; no users were created");
                    return;
                }
                is = new FileInputStream(BEnvironment.getUserInfoConf());
                byte[] bytes = FileUtils.toByteArray(is);
                parcel.unmarshall(bytes, 0, bytes.length);
                parcel.setDataPosition(0);

                ArrayList<BUserInfo> loadUsers = parcel.createTypedArrayList(BUserInfo.CREATOR);
                if (loadUsers == null) {
                    Slog.e(TAG, "user list was empty or unreadable; the file was kept");
                } else {
                    synchronized (mUsers) {
                        mUsers.clear();
                        int skipped = 0;
                        for (BUserInfo loadUser : loadUsers) {
                            if (loadUser == null || loadUser.id < 0) {
                                skipped++;
                                Slog.w(TAG, "skipped a user record with no id");
                                continue;
                            }
                            mUsers.put(loadUser.id, loadUser);
                        }
                        Slog.w(TAG, "users read from file=" + loadUsers.size()
                                + " kept=" + mUsers.size() + " skipped=" + skipped);
                    }
                }
                synchronized (mUsers) {
                    Slog.w(TAG, "users ready total=" + mUsers.size() + " ids=" + mUsers.keySet());
                }
            } catch (Exception e) {
                Slog.e(TAG, "user list could not be read; the file was kept: " + e);
            } finally {
                parcel.recycle();
                CloseUtils.close(is);
            }
        }
    }
}
