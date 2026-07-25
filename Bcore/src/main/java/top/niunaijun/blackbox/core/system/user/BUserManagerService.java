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


public class BUserManagerService extends IBUserManagerService.Stub implements ISystemService {
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
                    return;
                }
                is = new FileInputStream(BEnvironment.getUserInfoConf());
                byte[] bytes = FileUtils.toByteArray(is);
                parcel.unmarshall(bytes, 0, bytes.length);
                parcel.setDataPosition(0);

                ArrayList<BUserInfo> loadUsers = parcel.createTypedArrayList(BUserInfo.CREATOR);
                if (loadUsers == null)
                    return;
                synchronized (mUsers) {
                    mUsers.clear();
                    for (BUserInfo loadUser : loadUsers) {
                        mUsers.put(loadUser.id, loadUser);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                parcel.recycle();
                CloseUtils.close(is);
            }
        }
    }
}
