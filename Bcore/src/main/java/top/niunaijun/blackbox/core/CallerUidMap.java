package top.niunaijun.blackbox.core;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Pid to virtual-uid table shared by guest processes. Android 16 resolves
 * Binder.getCallingUid in native code, so it cannot ask Java which process
 * sent the transaction. The file is the table.
 */
public final class CallerUidMap {
    private CallerUidMap() {}

    public static File directory(File systemDir) {
        return new File(systemDir, "caller-uid");
    }

    public static File file(File directory, int pid) {
        return new File(directory, Integer.toString(pid));
    }

    public static boolean publish(File directory, int pid, int virtualUid) {
        if (directory == null || pid <= 0 || virtualUid < 10000) return false;
        if (!directory.isDirectory() && !directory.mkdirs()) return false;
        File target = file(directory, pid);
        File pending = new File(directory, pid + ".pending");
        try (FileOutputStream out = new FileOutputStream(pending)) {
            out.write(Integer.toString(virtualUid).getBytes(StandardCharsets.US_ASCII));
            out.getFD().sync();
        } catch (Throwable error) {
            pending.delete();
            return false;
        }
        if (!pending.renameTo(target)) {
            pending.delete();
            return target.isFile() && lookup(directory, pid) == virtualUid;
        }
        return true;
    }

    public static int lookup(File directory, int pid) {
        if (directory == null || pid <= 0) return -1;
        File target = file(directory, pid);
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(target.toPath());
            int end = 0;
            while (end < bytes.length && bytes[end] >= '0' && bytes[end] <= '9') end++;
            if (end == 0) return -1;
            int uid = Integer.parseInt(new String(bytes, 0, end, StandardCharsets.US_ASCII));
            return uid >= 10000 ? uid : -1;
        } catch (Throwable error) {
            return -1;
        }
    }

    public static void clear(File directory, int pid) {
        if (directory == null || pid <= 0) return;
        file(directory, pid).delete();
    }
}
