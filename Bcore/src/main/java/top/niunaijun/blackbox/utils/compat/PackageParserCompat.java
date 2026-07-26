package top.niunaijun.blackbox.utils.compat;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.Package;
import android.os.Build;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.IOException;

import black.android.content.pm.BRPackageParser;
import black.android.content.pm.BRPackageParserLollipop;
import black.android.content.pm.BRPackageParserLollipop22;
import black.android.content.pm.BRPackageParserMarshmallow;
import black.android.content.pm.BRPackageParserNougat;
import black.android.content.pm.BRPackageParserPie;

import static android.os.Build.VERSION_CODES.LOLLIPOP;
import static android.os.Build.VERSION_CODES.LOLLIPOP_MR1;
import static android.os.Build.VERSION_CODES.M;
import static android.os.Build.VERSION_CODES.N;

public class PackageParserCompat {

    public static final int[] GIDS = new int[]{};
    private static final int API_LEVEL = Build.VERSION.SDK_INT;
    private static final int myUserId = 0;

    /**
     * PackageParser only merges feature manifests when it receives the installed package
     * directory. Passing base.apk alone silently drops activities, services and dex files that
     * live in feature splits (modern Chrome keeps its launcher in split_chrome.apk).
     */
    public static File getInstalledPackageParseTarget(ApplicationInfo applicationInfo)
            throws IOException {
        if (applicationInfo == null || applicationInfo.sourceDir == null) {
            throw new IOException("Installed package has no base APK");
        }
        File baseApk = new File(applicationInfo.sourceDir).getCanonicalFile();
        String[] splitSourceDirs = applicationInfo.splitSourceDirs;
        if (splitSourceDirs == null || splitSourceDirs.length == 0) {
            return baseApk;
        }

        File packageDir = baseApk.getParentFile();
        if (packageDir == null || !baseApk.isFile()) {
            throw new IOException("Installed package base APK is unavailable");
        }
        for (String splitPath : splitSourceDirs) {
            if (splitPath == null) {
                throw new IOException("Installed package advertises an empty split path");
            }
            File splitApk = new File(splitPath).getCanonicalFile();
            if (!splitApk.isFile() || !packageDir.equals(splitApk.getParentFile())) {
                throw new IOException("Installed package split is missing or outside its package directory");
            }
        }
        return packageDir;
    }


    public static PackageParser createParser(File packageFile) {
        if (API_LEVEL >= M) {
            return BRPackageParserMarshmallow.get()._new();
        } else if (API_LEVEL >= LOLLIPOP_MR1) {
            return BRPackageParserLollipop22.get()._new();
        } else if (API_LEVEL >= LOLLIPOP) {
            return BRPackageParserLollipop.get()._new();
        }
        return null;
    }

    public static Package parsePackage(PackageParser parser, File packageFile, int flags) throws Throwable {
        if (API_LEVEL >= M) {
            return BRPackageParserMarshmallow.getWithException(parser).parsePackage(packageFile, flags);
        } else if (API_LEVEL >= LOLLIPOP_MR1) {
            return BRPackageParserLollipop22.getWithException(parser).parsePackage(packageFile, flags);
        } else if (API_LEVEL >= LOLLIPOP) {
            return BRPackageParserLollipop.getWithException(parser).parsePackage(packageFile, flags);
        } else {
            return BRPackageParser.getWithException(parser).parsePackage(packageFile, null,
                    new DisplayMetrics(), flags);
        }
    }

    public static void collectCertificates(PackageParser parser, Package p, int flags) throws Throwable {
        if (BuildCompat.isPie()) {
            BRPackageParserPie.getWithException().collectCertificates(p, true);
        } else if (API_LEVEL >= N) {
            BRPackageParserNougat.getWithException().collectCertificates(p, flags);
        } else if (API_LEVEL >= M) {
            BRPackageParserMarshmallow.getWithException(parser).collectCertificates(p, flags);
        } else if (API_LEVEL >= LOLLIPOP_MR1) {
            BRPackageParserLollipop22.getWithException(parser).collectCertificates(p, flags);
        } else if (API_LEVEL >= LOLLIPOP) {
            BRPackageParserLollipop.getWithException(parser).collectCertificates(p, flags);
        } else {
            BRPackageParser.get(parser).collectCertificates(p, flags);
        }
    }
}
