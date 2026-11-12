import top.niunaijun.blackbox.utils.compat.TaskIconPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TaskIconPolicyTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        check(TaskIconPolicy.parcelUsesAshmem(33), "API 33 parcels the task icon through ashmem");
        check(TaskIconPolicy.parcelUsesAshmem(34), "API 34 parcels the task icon through ashmem");
        check(TaskIconPolicy.parcelUsesAshmem(35), "API 35 parcels the task icon through ashmem");
        check(TaskIconPolicy.parcelUsesAshmem(36), "API 36 parcels the task icon through ashmem");
        check(!TaskIconPolicy.parcelUsesAshmem(32), "API 32 is below the ashmem task-icon path");
        check(!TaskIconPolicy.parcelUsesAshmem(26), "API 26 is below the ashmem task-icon path");

        check(TaskIconPolicy.mustStripIcon(36, TaskIconPolicy.BitmapState.SOFTWARE),
                "a software bitmap is still stripped on API 36");
        check(TaskIconPolicy.mustStripIcon(33, TaskIconPolicy.BitmapState.HARDWARE),
                "a hardware bitmap is stripped on API 33");
        check(TaskIconPolicy.mustStripIcon(28, TaskIconPolicy.BitmapState.HARDWARE),
                "a hardware bitmap is stripped before ashmem too");
        check(TaskIconPolicy.mustStripIcon(26, TaskIconPolicy.BitmapState.RECYCLED),
                "a recycled bitmap is stripped");
        check(TaskIconPolicy.mustStripIcon(26, TaskIconPolicy.BitmapState.NULL_NATIVE),
                "a bitmap with no native buffer is stripped");
        check(TaskIconPolicy.mustStripIcon(21, TaskIconPolicy.BitmapState.ABSENT),
                "a missing bitmap is not attached");
        check(!TaskIconPolicy.mustStripIcon(26, TaskIconPolicy.BitmapState.SOFTWARE),
                "a live software bitmap may be copied on API 26");
        check(TaskIconPolicy.canCopySafely(TaskIconPolicy.BitmapState.SOFTWARE),
                "only a live software bitmap can be copied");
        check(!TaskIconPolicy.canCopySafely(TaskIconPolicy.BitmapState.HARDWARE),
                "a hardware bitmap is not copied into the task icon");

        Path root = Path.of(args.length > 0 ? args[0] : ".");
        String activity = Files.readString(
                root.resolve("Bcore/src/main/java/top/niunaijun/blackbox/utils/compat/ActivityCompat.java"),
                StandardCharsets.UTF_8);
        check(!activity.contains("TaskDescription(label, icon)"),
                "launch does not build a task description from a bitmap");
        check(activity.contains("withoutIcon"),
                "launch publishes a label-only task description");

        String compat = Files.readString(
                root.resolve("Bcore/src/main/java/top/niunaijun/blackbox/utils/compat/TaskDescriptionCompat.java"),
                StandardCharsets.UTF_8);
        check(compat.contains("new ActivityManager.TaskDescription(label, (Bitmap) null, color)"),
                "the published description passes a null icon");
        check(!compat.contains("return td"),
                "the original description, bitmap included, is not forwarded");
        check(compat.contains("catch (Throwable ignored)"),
                "a parcel failure does not escape the hook");

        String client = Files.readString(
                root.resolve("Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IActivityClientProxy.java"),
                StandardCharsets.UTF_8);
        check(client.contains("TaskDescriptionCompat.publish"),
                "ActivityClient.setTaskDescription strips the icon before the binder call");

        System.out.println("PASS: " + checks + " task icon checks");
    }
}
