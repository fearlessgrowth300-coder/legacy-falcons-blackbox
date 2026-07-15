package top.niunaijun.blackbox.app.configuration;

import java.io.File;


public abstract class ClientConfiguration {

    public boolean isHideRoot() {
        return false;
    }



    public abstract String getHostPackageName();

    public boolean isEnableDaemonService() {
        return true;
    }

    public boolean isEnableLauncherActivity() {
        return true;
    }

    
    public boolean isUseVpnNetwork() {
        return false;
    }

    public boolean isDisableFlagSecure() {
        return false;
    }

    
    public boolean requestInstallPackage(File file, int userId) {
        return false;
    }

    
    public String getLogSenderChatId() {
        // Return EMPTY to DISABLE the upstream crash-log upload. The stock NewBlackbox ships a
        // hard-coded Telegram chat id here and auto-uploads the guest's full logcat (account/auth
        // flows, IPs, proxy creds, device fingerprints) to an external endpoint on every crash —
        // a data-exfiltration path to the upstream author. sendLogs() early-returns on empty id.
        return "";
    }
}
