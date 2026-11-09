package top.niunaijun.blackbox.core.system.user;

import android.os.Parcel;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Os;

import androidx.core.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
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

    /** True when this record was inserted from a leftover directory, not created by the user. */
    public boolean isRecoveredStub(int userId) {
        if (userId != 3 && userId != 7 && userId != 8 && userId != 9 && userId != 10) return false;
        BUserInfo info = getUserInfo(userId);
        // createUser leaves createTime at 0. Directory recovery was the only writer.
        return info != null && info.createTime > 0;
    }

    private boolean deleteUserFiles(int userId) {
        if (userId < 0) return false;
        File deviceProtected = new File(BEnvironment.getVirtualRoot(), "data/user_de/" + userId);
        boolean cleared = deleteUserTree(BEnvironment.getUserDir(userId), userId, "user");
        cleared &= deleteUserTree(deviceProtected, userId, "user_de");
        cleared &= deleteUserTree(BEnvironment.getExternalUserDir(userId), userId, "emulated");
        return cleared;
    }

    /**
     * Delete one user's directory. Refuses any path whose parent is not the
     * expected user folder, so a bad path cannot reach another user.
     */
    private boolean deleteUserTree(File dir, int userId, String parentName) {
        if (dir == null || !existsOrLink(dir)) return true;
        File parent = dir.getParentFile();
        if (parent == null || !parentName.equals(parent.getName())
                || !String.valueOf(userId).equals(dir.getName())) {
            Slog.e(TAG, "refusing to delete unexpected path " + dir);
            return false;
        }
        // The shared parent only needs to be writable so this one directory can be unlinked.
        parent.setWritable(true);
        String failure = wipeUserTree(dir);
        if (!existsOrLink(dir)) return true;
        Slog.e(TAG, "files remain after delete: " + dir.getAbsolutePath()
                + (failure == null ? "" : " first=" + failure));
        return false;
    }

    /**
     * FileUtils.deleteDir stops when a listing comes back null, skips a directory
     * its symlink check misreads, and cannot unlink a read-only entry. Walk this
     * one tree: make each directory readable, unlink real symlinks without
     * following them, then remove children before the parent.
     */
    private String wipeUserTree(File root) {
        ArrayDeque<File> pending = new ArrayDeque<>();
        ArrayDeque<File> removeOrder = new ArrayDeque<>();
        pending.push(root);
        String firstFailure = null;
        while (!pending.isEmpty()) {
            File node = pending.pop();
            removeOrder.push(node);
            if (isLink(node) || !node.isDirectory()) continue;
            makeDeletable(node, true);
            File[] children = node.listFiles();
            if (children == null) {
                makeDeletable(node, true);
                children = node.listFiles();
            }
            if (children == null) {
                if (firstFailure == null) firstFailure = node.getAbsolutePath() + " unlistable";
                continue;
            }
            for (File child : children) {
                pending.push(child);
            }
        }
        while (!removeOrder.isEmpty()) {
            File node = removeOrder.pop();
            if (!isLink(node)) makeDeletable(node, node.isDirectory());
            String failure = unlinkFile(node);
            if (failure != null && firstFailure == null) firstFailure = failure;
        }
        return firstFailure;
    }

    private static boolean isLink(File file) {
        try {
            Os.readlink(file.getAbsolutePath());
            return true;
        } catch (ErrnoException ignored) {
            return false;
        }
    }

    private static boolean existsOrLink(File file) {
        return file.exists() || isLink(file);
    }

    private static void makeDeletable(File file, boolean directory) {
        if (file == null || isLink(file)) return;
        file.setReadable(true);
        file.setWritable(true);
        if (directory) file.setExecutable(true);
        try {
            Os.chmod(file.getAbsolutePath(), directory ? 0700 : 0600);
        } catch (ErrnoException ignored) {
        }
    }

    private static String unlinkFile(File file) {
        if (file.delete() && !existsOrLink(file)) return null;
        try {
            Os.remove(file.getAbsolutePath());
        } catch (ErrnoException error) {
            if (existsOrLink(file)) {
                return file.getAbsolutePath() + " errno=" + error.errno;
            }
            return null;
        }
        return existsOrLink(file) ? file.getAbsolutePath() : null;
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
