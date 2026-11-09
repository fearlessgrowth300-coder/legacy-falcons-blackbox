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

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.KeystoreIsolation;
import top.niunaijun.blackbox.core.system.ISystemService;
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
                if (!KeystoreIsolation.deleteForUser(userId)) {
                    throw new IllegalStateException(
                            "Could not safely delete encrypted keys for User " + userId);
                }
                BPackageManagerService.get().deleteUser(userId);

                mUsers.remove(userId);
                saveUserInfoLocked();
                FileUtils.deleteDir(BEnvironment.getUserDir(userId));
                FileUtils.deleteDir(BEnvironment.getExternalUserDir(userId));
            }
        }
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

    /**
     * A user directory or keep-alive entry can outlive the user list. Put the
     * record back. This does not delete or rewrite that user's files.
     */
    private int recoverMissingUsersLocked() {
        int recovered = 0;
        recovered += recoverUserDirectory(BEnvironment.getUserDir(0).getParentFile(), "data/user");
        recovered += recoverUserDirectory(new File(BEnvironment.getVirtualRoot(), "data/user_de"), "data/user_de");
        File externalUsers = BEnvironment.getExternalUserDir(0).getParentFile();
        recovered += recoverUserDirectory(externalUsers, "external");
        try {
            for (Integer userId : top.niunaijun.blackbox.core.GuestKeepAlive.userIds()) {
                if (userId == null) continue;
                recovered += rememberUser(userId, "keep-alive");
            }
        } catch (Throwable error) {
            Slog.w(TAG, "keep-alive user list unavailable: " + error.getClass().getSimpleName());
        }
        return recovered;
    }

    private int recoverUserDirectory(File parent, String source) {
        File[] children = parent == null ? null : parent.listFiles();
        if (children == null) {
            Slog.w(TAG, "user directory could not be listed: " + parent + " (" + source + ")");
            return 0;
        }
        int recovered = 0;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            int userId;
            try {
                userId = Integer.parseInt(child.getName());
            } catch (NumberFormatException ignored) {
                continue;
            }
            recovered += rememberUser(userId, source);
        }
        return recovered;
    }

    /** Keep a user that still has saved state outside the user list. Does not delete files. */
    public void preserveUser(int userId, String source) {
        synchronized (mUserLock) {
            if (rememberUser(userId, source) > 0) {
                synchronized (mUsers) {
                    saveUserInfoLocked();
                }
            }
        }
    }

    private int rememberUser(int userId, String source) {
        if (userId < 0) return 0;
        synchronized (mUsers) {
            if (mUsers.containsKey(userId)) return 0;
            BUserInfo info = new BUserInfo();
            info.id = userId;
            info.status = BUserStatus.ENABLE;
            info.createTime = System.currentTimeMillis();
            mUsers.put(userId, info);
        }
        Slog.w(TAG, "recovered user " + userId + " from " + source + "; its files were left in place");
        return 1;
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
                    Slog.w(TAG, "user list file is missing; recovering from directories");
                    int recovered = recoverMissingUsersLocked();
                    if (recovered > 0) {
                        synchronized (mUsers) {
                            saveUserInfoLocked();
                        }
                    }
                    Slog.w(TAG, "users ready total=" + mUsers.size()
                            + " recovered=" + recovered + " ids=" + mUsers.keySet());
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
                int recovered = recoverMissingUsersLocked();
                if (recovered > 0) {
                    synchronized (mUsers) {
                        saveUserInfoLocked();
                    }
                }
                Slog.w(TAG, "users ready total=" + mUsers.size()
                        + " recovered=" + recovered + " ids=" + mUsers.keySet());
            } catch (Exception e) {
                Slog.e(TAG, "user list could not be read; the file was kept: " + e);
                int recovered = recoverMissingUsersLocked();
                if (recovered > 0) {
                    synchronized (mUsers) {
                        saveUserInfoLocked();
                    }
                }
            } finally {
                parcel.recycle();
                CloseUtils.close(is);
            }
        }
    }
}
