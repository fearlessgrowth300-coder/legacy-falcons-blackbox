# Isolation and fingerprint audit — October 2026

## Remediation status

The findings below are the original source reading. This section is the status after the isolation changes on `cursor/isolation-fingerprint-audit-908b`, which includes the Android 14–16 crash fixes from `cursor/android-14-16-guest-stability-4e5e`. The ShieldProxy connect-hook contract was read from `docs/BLACKBOX-CONNECT-HOOK-CONTRACT.md` on `cursor/route-isolation-leak-audit-575d`. Nothing in this change bypasses, spoofs, or tampers with Play Integrity, SafetyNet, or any other attestation API. Those surfaces are still shared or absent, on purpose.

Guests still share the host app's Linux uid. Path rules, libc hooks, and keystore encryption are not a kernel boundary. A guest that issues `syscall` directly, or that opens the real `keystore2` service without the in-process wrapper, is outside what these hooks can see. That limit is unchanged.

| Gap | Status | What changed |
| --- | --- | --- |
| 1. Storage and `/blackbox/` passthrough | **Partial** | `PathIsolation` denies another user's tree, `system/`, and `..` escapes for guest `IOCore.redirectPath` and the native `IO::redirectPath`. `fopen`, `access`, `stat`, `faccessat`, `fstatat`, `readlinkat`, `opendir`, and the non-variadic `__open_2` / `__openat_2` are hooked and degrade to a log if Dobby cannot install them. Variadic `open` / `openat` stay off on API 36+ because they crashed Instagram on Android 16. The host uid is still shared. |
| 2. Accounts | **Partial** | Each virtual user is stored in `blackbox/system/accounts/user-<id>.parcel`. Passwords and auth tokens are sealed with Android Keystore (`AccountKeystore`) before that write, and the old combined `accounts.conf` is removed only after every user file is sealed. If the keystore is unavailable the combined file is left in place and secrets are not rewritten as plaintext. `getPassword`, `getUserData`, `peekAuthToken`, `setPassword`, `setAuthToken`, and `setUserData` require the calling package to be the authenticator that owns the account type. The keystore key is still in the host uid. |
| 3. Providers, media, URI grants | **Partial** | `checkUriPermission` no longer returns granted for every URI. Media and GSF/GMS/Play URIs and other-user `/blackbox/` paths are denied unless the profile opts in. DCIM/Pictures/Movies stay on the per-user volume unless `shareHostMedia=true`. GSF/GMS/Play authorities are not handed the phone's providers unless `shareHostGoogleProviders=true`. Settings and telephony stay on the host so the per-profile android_id rewrite and telephony hooks still run. Turning Google providers off will break sign-in and push that depend on the phone's GMS; the switch is `setProfilePolicy` on the bridge and `profile_policy.properties`. |
| 4. Notifications | **Fixed** in the hooked getters | `getActiveNotifications`, `getAppActiveNotifications`, and `getHistoricalNotifications` return only items whose host tag is `blackbox\|<userId>\|<package>\|`. A filter failure returns an empty list. The shade still shows both profiles to the person holding the phone. |
| 5. Network | **Partial** | `requireProxy` defaults to true. A configured clone dials only its ShieldProxy loopback SOCKS listener (`127.0.0.1`, ports 20000–27999) with `socksUser` / `socksPassword`. The hook does not dial the upstream node and does not fall back to the phone network; a missing or refused listener is `ECONNREFUSED` / the guest does not start. `timezoneId`, `locale`, and the supplied latitude/longitude are applied to that clone. `setKeepAlive` defaults to off and does not install GMS. `WifiInfo`, `LinkProperties`, and the redirected `/proc/net` files use a profile `10.x` address. Non-loopback UDP is refused rather than relayed through SOCKS UDP associate. A raw `syscall` redirect is installed when Dobby accepts it and is skipped with a log when it does not. WebRTC was not captured on a phone. |
| 6. Identity hooks and per-profile values | **Partial** | `ClassInvocationStub` registers hooks even when `getWho()` is null, then Pine-attaches the target class only when `PineRuntime` says Pine will not crash the process. On Android 16 and 16 KB pages those class hooks are skipped and logged. `IDnsResolverProxy` now resolves `IDnsResolver` and refuses the real resolver while a proxy is required. Wi-Fi SSID/BSSID, `Build.HOST` / `USER` / `TIME` / `TYPE` / `TAGS` / `BOOTLOADER`, and a `clock_gettime` uptime offset are per profile. `Build` SDK, ABI, GPU keys, and attestation are not rewritten. |
| Shared Linux uid | **Partial, real profile added** | The hooked mode is unchanged and still shares one uid. A workspace can instead use a separate Android managed profile (`DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE`, Blackbox as profile owner). That user has its own uid (user 10+), data directory, Android ID, keystore, account manager, and GMS. The second workspace recommends this mode. Android allows only one such profile; an existing work profile, device owner, or Samsung Secure Folder/Knox blocks it and the UI says so. Android 12–16 also requires the two provisioning activities below. Beta 215333402 did not declare them, so setup stopped before a user existed. |

## Separate Android profile

Hooked workspaces still share the host uid. **Separate Android profile** is the kernel boundary: Android's managed-profile user.

- Create the second workspace and choose **Separate Android profile**. The first workspace stays **same profile, hooked**.
- Android's setup screen must succeed. Continue launches that screen with a result contract, because `PreProvisioningActivity` reads `getCallingPackage()` and shows "Can't set up device" when the caller used `startActivity`. The intent sets `EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME` and `EXTRA_PROVISIONING_SKIP_ENCRYPTION`. It does not set the deprecated package-name extra or `EXTRA_PROVISIONING_SKIP_USER_CONSENT`. On API 33+ it sets `EXTRA_PROVISIONING_ALLOW_OFFLINE` so a role-holder download is not required. `GetProvisioningModeActivity` returns `PROVISIONING_MODE_MANAGED_PROFILE` (or the personal-device profile mode when that is the only allowed profile mode) and never fully-managed device. `AdminPolicyComplianceActivity` returns `RESULT_OK`. Both are exported with `android.permission.BIND_DEVICE_ADMIN`. Blackbox's `BlackBoxDeviceAdminReceiver` becomes profile owner only, then names and enables the profile.
- Open **Profile separation** to see this side's Android user id, Linux uid, and data directory. On the main user the screen lists the work profile when `UserManager`, `CrossProfileApps`, `LauncherApps`, or the id saved from `ACTION_MANAGED_PROFILE_PROVISIONED` can see it. It does not call that profile Samsung Secure Folder. Knox platform packages are not Secure Folder. Only the Blackbox workspace bound to that Android user is labelled separate. The others stay hooked. Text uses a color that contrasts with the dark window. **Open Blackbox in the work profile** starts this app inside user 10, where it enables the profile, unhides its launcher activity, and enables Play Store, the browser, Files, and Settings so the Work tab is not empty. **Remove profile and set up again** asks the profile owner to wipe that user.
- Install guest apps, Play services, and ShieldProxy from the installer that runs **inside** the managed profile (`installExistingPackage`, then `enableSystemApp` if the package is a system app).
- Launching an app in a separate-mode workspace uses `LauncherApps` in that Android user. It does not start a hooked guest in the main process.

**ShieldProxy.** A VPN is per Android user. The main profile's ShieldProxy does not carry the work profile. Install and run ShieldProxy inside the work profile, and give that side its own clone listener, or account B uses the phone's network. The connect-hook contract is unchanged for whichever user the guest runs in: dial `127.0.0.1:<localPort>` and fail closed.

**What still links the two users on one phone.** Privileged apps and the radio stack can still see the same hardware IMEI/MEID. The screen, GPU, sensors, and battery are the same device. The Samsung account, if signed in for the whole phone, is outside this profile. The public IP is the same unless ShieldProxy is running in both users with different exits. This build does not spoof or bypass Play Integrity, SafetyNet, or hardware attestation.

Verify on a computer with:

```sh
sh scripts/verify-profile-isolation.sh top.niunaijun.blackbox
```

Windows: `powershell -File scripts/verify-profile-isolation.ps1 -Package top.niunaijun.blackbox`

### Phone evidence, 28 October 2026

The attached dropbox text was not present in the workspace. The report below uses the stack the owner described from `dumpsys dropbox --print` on SM-S918U1, Android 16 / SDK 36. The installed build is 4.1.0, versionCode 210772124, targetSdk 28, installed 2026-09-06. That build does not contain PR #20 or this branch.

`top.niunaijun.blackbox:p2` died in `NativeCore.getCallingUid` inside `libblackbox.so`, with `SimpleCrashFix$1.run` on the same stack. That is the nested main looper, not the cause. PR #20 stopped Pine on this runtime. It did not stop the Binder.getCallingUid hook from calling back into Java, which is the crash. This branch returns the virtual uid from native on API 36 and does not enter Java there.

`com.android.vending:quick_launch` repeated a ClassCastException and then SIGSEGV. That process is refused before a proxy slot is taken. The main Play process is still allowed. Two Finsky providers were already skipped. This is not an attestation change.

The phone has only Android user 0, no profile owner, no device owner, and no Secure Folder. `isProvisioningAllowed(ACTION_PROVISION_MANAGED_PROFILE)` should succeed there.

### Phone evidence, provisioning on beta 215333402

SM-S918U1, Android 16 / SDK 36, One UI, only user 0. Manage users → Isolation mode → Separate Android profile → Continue opened `com.android.managedprovisioning.PreProvisioningActivity` and immediately showed "Can't set up device - Contact your IT admin for help". `pm list users` stayed at user 0.

`dumpsys package top.niunaijun.blackbox` showed the admin receiver with only `PROFILE_PROVISIONING_COMPLETE` and `DEVICE_ADMIN_ENABLED`. There was no activity for `android.app.action.GET_PROVISIONING_MODE` and none for `android.app.action.ADMIN_POLICY_COMPLIANCE`. Since Android 12, ManagedProvisioning rejects a DPC that does not export both, each with `android.permission.BIND_DEVICE_ADMIN`. The same dialog is also what `PreProvisioningActivity` shows when `getCallingPackage()` is null, which is what `startActivity` (without a result) produces. This branch now does both.

The app still targets SDK 28. AOSP's Android 12 rule is that the two handlers must exist; it does not reject a DPC for targeting below 31. Targeting below 31 is why `PROFILE_PROVISIONING_COMPLETE` is still delivered. Raising the whole container's targetSdk would change storage, export, and background behavior, so it stays at 28. The beta's failure matches the missing handlers and the result-less launch, not a targetSdk rejection. `device_admin.xml` keeps a single `watch-login` policy and does not request wipe or reset. `EXTRA_PROVISIONING_SKIP_ENCRYPTION` remains set for Samsung phones that are already encrypted.

If the next beta still shows the IT-admin dialog after the consent screen, the remaining platform cause is the device-policy role holder failing to download. `EXTRA_PROVISIONING_ALLOW_OFFLINE` is set on API 33+ to skip that requirement. This change does not touch Play Integrity or attestation.

### Phone test

1. Install this build over the September APK only after checking the signer. Open **Profile separation** and note the uid.
2. Manage users → create a user → **Separate Android profile** → complete Android's work-profile screen.
3. Open **Profile separation** again, then **Open status** on the other user. Confirm the user id and uid differ and the data directories differ.
4. In the work profile, enable ShieldProxy and the guest app. Confirm the main profile's hooked user 0 still launches the old way.
5. On a phone that already has Secure Folder or a work profile, confirm the UI explains the block and hooked mode still works.

`tools/diagnostics/IsolationBoundaryTest` covers the path, policy, account, notification, and network decisions. It does not boot a guest.

Phone checks still required: a second profile opening the first profile's `device_profile.properties` with Java and with native `open`; account migration from an existing `accounts.conf`; Google sign-in with `shareHostGoogleProviders` false and then true; notification listing across profiles; a no-proxy launch being refused and a proxied launch still passing the Android 14–16 crash fixes; Wi-Fi scan and WebRTC local addresses.

Status: source inspection of `Bcore/` (`top.niunaijun.blackbox`), followed by the remediation above. No phone, emulator, or instrumented guest was run. Where a conclusion depends on Android enforcing a permission, SELinux rule, or vendor service that this tree does not show, the finding is marked **unverified**.

This is not a certification that any channel is leak-free. A hooked Java API is isolated only for callers that go through that hook. A native guest, a direct file path, or a binder method with no hook is a separate channel.

The question is two-directional:

1. Can an app in profile A read data, accounts, or activity that belong to profile B?
2. Can an app, or a server it talks to, tell that both profiles are the same physical phone or the same person?

"Profile" here means one BlackBox virtual user (`userId`). `DeviceProfile.forUser` stores one identity for that user, not one identity per app. Apps that share a profile share that identity. That within-profile sharing is called out where it matters. The ranked findings are about crossing between two profiles, or linking them.

## How a guest is actually sandboxed

Guest processes are started with the host app's Linux uid:

```101:107:Bcore/src/main/java/top/niunaijun/blackbox/core/system/BProcessManagerService.java
            app = new ProcessRecord(info, processName);
            app.uid = Process.myUid();
            app.bpid = bpid;
            // Keep the complete virtual UID. Storing only appId here makes process-death cleanup
            // address the wrong mProcessMap entry and can leave a stale record from another user.
            app.buid = buid;
```

`buid` is a virtual uid. The kernel uid is `Process.myUid()`, the BlackBox host. There is no second Android user, no per-profile SELinux domain, and no mount namespace in this tree. `OsStub.getuid` rewrites the libcore `getuid` result to `BActivityThread.getBAppId()`, which is the package app id with the virtual user stripped (`BUserHandle.getAppId`). The same package in two profiles therefore reports the same libcore uid. The native `getuid` syscall is not hooked, so native code sees the host uid.

All virtual-user files live under the host app's own directory:

```12:13:Bcore/src/main/java/top/niunaijun/blackbox/core/env/BEnvironment.java
    private static final File sVirtualRoot = new File(BlackBoxCore.getContext().getCacheDir().getParent(), "blackbox");
    private static final File sExternalVirtualRoot = BlackBoxCore.getContext().getExternalFilesDir("blackbox");
```

That is `<host data dir>/blackbox/`, including `data/user/<userId>/...`, `system/accounts.conf`, and `device_profile.properties`. External per-user files live under the host's `Android/data/.../files/blackbox/`. From the kernel's point of view these are the host app's files. An app may read its own data directory. Guests are that app.

Path rewriting is the only file boundary, and it is incomplete:

- `IOCore.redirectPath` returns any path containing `/blackbox/` unchanged (`IOCore.redirectPath`).
- The native helper does the same (`IO::redirectPath` in `Bcore/src/main/cpp/IO.cpp`).
- Java `libcore.io.Os` string paths are rewritten only by `OsStub.invoke`, which calls that same function.
- `UnixFileSystemHook` rewrites a subset of `java.io.UnixFileSystem` JNI methods, then calls the original. It does not hook libc.
- `FileSystemHook::init` resolves `open`, `openat`, and `open64` and deliberately does not patch them. The comment says Instagram's native pool crashed on Android 16 when those hooks were installed.
- `AntiDetection::install_file_hooks` opens `libc.so` and closes it. The `access` / `stat` / `fopen` / `open` wrappers in that file are never installed. The host-package path list in that file is inert.

A guest whose own `ApplicationInfo.dataDir` already sits under `/blackbox/` can walk to a sibling user with ordinary `java.io.File` calls. Those paths contain `/blackbox/`, so both redirectors leave them alone. Native `open` was never redirected. This was not executed on a device. It follows from the path rules plus the shared uid, and the files are inside the host app's own data directory, which the app sandbox allows that uid to read.

`IOCore.proc` rewrites only `/proc/self/cmdline` and `/proc/<own-pid>/cmdline`. `maps`, `status`, `environ`, `net`, `fd`, and other processes' `/proc/<pid>` are not rewritten.

## Ranked findings

Severity is about cross-profile impact: **high** means one profile can read the other's private data, or a remote service can reliably treat the two profiles as one device; **medium** means a strong link or a conditional leak; **low** means a weak or noisy signal, or a shared surface that does not by itself identify the person.

### 1. Shared host uid and no libc file redirect — high

| | |
| --- | --- |
| Channel | Private files of the other profile: app databases, tokens, cookies, `device_profile.properties`, account store, clipboard ciphertext |
| Where | `BProcessManagerService` process start (`app.uid = Process.myUid()`); `BEnvironment.getVirtualRoot`; `FileSystemHook::init`; `AntiDetection::install_file_hooks`; `IOCore.proc` |
| Disposition | Shared. Not spoofed. Passed through at the kernel. |
| Verified | Code. Not executed on a phone. |

The virtual uid is bookkeeping. It is not a kernel sandbox. Anything the host app can open, every guest can open, including another profile's tree under `blackbox/data/user/<other>/` and `blackbox/system/`.

`/proc/self/maps` is not rewritten, so the host data path is visible inside the guest as the path of the engine library. The prefix does not have to be guessed. Other guests' `/proc/<pid>/cmdline` is world-readable on Android and is not rewritten, so one profile can see the other's process names. `/proc/<pid>/maps` for the same uid is typically readable. That was not executed here.

Recommended fix: give each profile a real kernel identity (separate app uid, or a mount/PID namespace the guest cannot leave). Deny `open`/`openat` outside that profile's root in a place libc cannot skip. Treat path rewriting as a compatibility shim, not as the boundary. Until that exists, do not describe profiles as isolated from each other.

### 2. `/blackbox/` paths are exempt from redirection — high

| | |
| --- | --- |
| Channel | Same private files as finding 1, reachable from Java `File`, `libcore.io.Os`, and `UnixFileSystem` as well as from native code |
| Where | `IOCore.redirectPath(String)`; `IO::redirectPath(const char *)` |
| Disposition | Passed through on purpose |
| Verified | Code. The early return is unconditional. |

```61:66:Bcore/src/main/java/top/niunaijun/blackbox/core/IOCore.java
    public String redirectPath(String path) {
        if (TextUtils.isEmpty(path))
            return path;
        if (path.contains("/blackbox/")) {
            return path;
        }
```

The native copy skips relocation when the path contains `/blackbox/` (`IO.cpp`). A guest data directory is already under that prefix, so `..` from it is not rewritten. Rules that were meant to hide host paths do not apply once the string contains the marker.

Recommended fix: canonicalize, then allow only a prefix owned by this virtual user. Reject or redirect paths that escape it. Do not special-case the virtual root as "already safe."

### 3. One account file holds every profile's passwords — high

| | |
| --- | --- |
| Channel | Account names, passwords, user data, and auth tokens for every virtual user |
| Where | `BAccountManagerService.saveAllAccounts` / `loadAccounts`; `BEnvironment.getAccountsConf`; `BAccount.password` |
| Disposition | Shared on disk. The binder API is per-profile (see finding 4). |
| Verified | Code. The parcel layout is visible. A guest read was not performed. |

`getAccountsConf()` is `blackbox/system/accounts.conf`. `saveAllAccounts` writes the whole `mUserAccountsMap` (every user id) into that one file. `BAccount` parcels `password` as a string. Finding 1 makes that file readable by any guest. The September workspace check does not cover the file.

Recommended fix: store each user in its own file, outside any path a guest can open, encrypted with a key that guest uids cannot use. Stop putting passwords in a parcel the guest can read. Assume the binder check is not the boundary until the file is.

### 4. Account binder API is per-workspace, and same-workspace apps are not separated — high for the file, medium for the API

| | |
| --- | --- |
| Channel | `AccountManager` passwords, user data, auth tokens, account lists |
| Where | `AccountWorkspacePolicy.allows`; `BAccountManagerService.getUserAccounts`, `getPassword`, `getUserData`, `peekAuthToken`, `setPassword`, `getAccountsAsUser`, `copyAccountToUser` |
| Disposition | Cross-profile binder calls are denied. Same-profile calls are not authenticated to the owning authenticator. The file in finding 3 bypasses both. |
| Verified | Code and `AccountWorkspacePolicyTest` (unit checks only, from the September audit). Not re-run here. Not phone-tested. |

`allows` returns true when the caller pid is the account service itself, or when the caller's registered virtual user equals the requested user. Other registered users and unknown pids are denied. `getUserAccounts` throws `SecurityException` when `allows` is false. `copyAccountToUser` calls `getUserAccounts` for both the source and the destination, so a guest that passes another profile's user id should be rejected. That rejection was not exercised on a device.

Inside one profile the check stops. `getPassword`, `getUserData`, `peekAuthToken`, and `setPassword` then read or write the account with no test that the caller is the authenticator that owns it. `getAccountsAsUser` returns every account of a type in that profile, without the visibility map that `getAccountsForPackage` consults. Any app in profile A can read profile A's account secrets through this API. It cannot, through this API, request profile B's user id. It can still read profile B from `accounts.conf`.

`IAccountManagerProxy` does send guests to `BAccountManager`, which stamps the current virtual user on most calls. That does not fix the on-disk map or the missing authenticator check.

Recommended fix: keep the workspace check. Before returning a password or token, require the caller to be the registered authenticator package for that account type, the same rule as Android's `AccountManagerService`. Split the file as in finding 3.

### 5. Settings, media, telephony, GSF, GMS, and Play Store providers are the phone's — high

| | |
| --- | --- |
| Channel | GSF android id, host GMS/Play content providers, MediaStore, Settings rows other than the Pine-hooked `android_id` |
| Where | `IActivityManagerProxy.GetContentProvider.hook`; `ContentProviderDelegate.update`; `SystemProviderStub.invoke` |
| Disposition | Passed through to the real provider, with the host package and host uid stamped on the caller |
| Verified | Code. Whether a given provider accepts the call on Android 16 was not run. |

`GetContentProvider` calls the real ActivityManager for authorities `settings`, `media`, `telephony`, and any authority whose name contains `com.google.android.gms`, `com.android.vending`, or `com.google.android.gsf`, plus a few OEM settings authorities. `ContentProviderDelegate.update` wraps `settings`, `media`, and `telephony` in `SystemProviderStub`, and wraps GMS/GSF/Play in `ContentProviderStub` constructed with the **host** package. `ContentProviderStub.invoke` then treats that provider as non-virtual and rewrites the caller to the host uid and host package.

Consequences:

- GSF's `gservices` android id is the phone's id for every profile, if the real provider answers. There is no per-profile GSF id in this tree. That id is a standard cross-app device key.
- MediaStore is the phone's library. Combined with finding 6, both profiles see the same photos, videos, and media database rows.
- Settings queries other than `Settings.Secure.getString` / `getStringForUser` for the exact name `android_id` hit the real settings provider. `advertising_id`, bluetooth address, and other secure settings are not substituted. A `ContentResolver.query` of the settings URI is not the Pine hook.
- Virtual GMS installed by `GmsCore.installGApps(userId)` has a per-user data directory, but these authorities never reach that copy. They reach the phone's GMS. `GuestProxy.syncGmsRouteForUser` also documents that, inside one profile, GMS is one shared route for every app. Across profiles, the provider shortcut still points at the same physical GMS.

`ISettingsProviderProxy`, `ISettingsSystemProxy`, `AndroidIdProxy`, and `DeviceIdProxy` extend `ClassInvocationStub` and return null from `getWho()`. `ClassInvocationStub.injectHook` returns immediately in that case, before method hooks are registered. Those classes do not install. The live SSAID hook is `DeviceProfile.hookAndroidId`, which Pine-hooks `Settings.Secure.getString` and `getStringForUser` and replaces the result only when some argument equals `android_id`.

Recommended fix: do not hand guests the phone's GSF, GMS, or MediaStore providers. Serve a per-profile provider, or fail the lookup. If GMS must run, run the per-user copy and never fall through to the host. Treat a successful call to the real GSF provider as a shared device id.

### 6. Shared camera roll and a granted media permission — high

| | |
| --- | --- |
| Channel | DCIM, Pictures, Movies, and the real MediaStore |
| Where | `IOCore.enableRedirect` (`sharedMediaDirs`); `IActivityManagerProxy.checkPermission` / `checkUriPermission`; `SystemProviderStub` authority `media` |
| Disposition | Shared on purpose for those three directories and for the media provider |
| Verified | Code. The September audit already recorded the directory choice. Not re-tested on a phone. |

`enableRedirect` installs no-op rules so `/sdcard/DCIM`, `/sdcard/Pictures`, `/sdcard/Movies`, and the matching `/storage/emulated/<host user>/...` paths stay on the physical volume. The trie keeps the longest match, so those rules win over the broad `/sdcard` redirect. The rest of external storage is redirected to `BEnvironment.getExternalUserDir(userId)`, and `BStorageManagerService.getVolumeList` rewrites volume paths to that per-user directory. An app that only uses the rewritten volume root is not automatically in the shared DCIM folder. An app that uses `/sdcard/DCIM`, the emulated DCIM path, or `content://media` is.

`checkPermission` forces `READ_MEDIA_*`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, camera, and several other permissions to `PERMISSION_GRANTED`. `checkUriPermission` returns granted for every URI. `grantUriPermission` swaps in the host uid and calls the real ActivityManager. URI grants are host-uid grants. They are not per-profile.

Recommended fix: decide explicitly. A private profile needs its own media root and its own media provider, with URI checks enforced. Sharing the phone's camera roll with every profile should be an owner-facing switch, not the default for both workspaces.

### 7. Notifications are posted into one host pool, and listing them is not filtered — high

| | |
| --- | --- |
| Channel | Notification title, text, and content from the other profile |
| Where | `BNotificationManagerService.enqueueNotificationWithTag`; `getHostNotificationTag`; `INotificationManagerProxy.invoke` |
| Disposition | Channel CRUD is scoped to the caller. Posted notifications are the host's. Unhooked getters are passed through. |
| Verified | Code. A cross-profile `getActiveNotifications` call was not run. |

Posts go to the real `NotificationManager` with tag `blackbox|<userId>|<package>|<guest tag>`. Channel reads and deletes in `BNotificationManagerService` require the caller's process record to match the user and package. That part matches the September notification tests.

`INotificationManagerProxy.invoke` rewrites installed package names to the host package, then `ClassInvocationStub.invoke` forwards any method that has no `@ProxyMethod` to the real service. `getActiveNotifications` and similar getters are not hooked. A guest that calls them is asking as the host package, against the service that holds every profile's notifications. The tag embeds the other profile's user id and package.

The shade also shows both profiles to the person holding the phone. That is a human-visible shared surface. The API issue is that a guest can query the same pool.

Recommended fix: hook the getters and return only the caller's user and package. Keep posting to the host shade if the owner should see notifications, but do not let a guest enumerate the host pool.

### 8. Same public IP and DNS unless each app has its own proxy — high

| | |
| --- | --- |
| Channel | Exit IP, DNS resolver, UDP/STUN/QUIC, local IPv4 |
| Where | `GuestProxy.apply`; `BActivityThread.initProcess`; `ProxyRedirect.cpp` (`my_connect`, `my_socket`, `my_getaddrinfo`); `IConnectivityManagerProxy.GetLinkProperties`; `IWifiManagerProxy.GetConnectionInfo` |
| Disposition | Per app only when that app's proxy file is valid. Otherwise the phone's network, shared by every profile. |
| Verified | Code. No packet capture in this audit. |

`GuestProxy.apply` returns `NOT_CONFIGURED` when the app has no proxy file and no required-route flag. `BActivityThread.initProcess` allows `NOT_CONFIGURED` to continue. Two profiles with no proxy share the phone's public IP, the phone's DNS, and a working UDP stack. WebRTC and STUN then see the phone's addresses. That is the same-person link the question asks about, and it is the default when a proxy was not assigned.

When a proxy is applied, `connect` tunnels TCP (HTTP CONNECT or SOCKS5) for that process. `getaddrinfo` returns a synthetic `198.18.0.0/15` address and the proxy resolves the name. Non-loopback `SOCK_DGRAM` sockets are refused, which blocks the usual unconnected UDP path for STUN and QUIC. Loopback is still direct. Raw IPv6 destinations that are not v4-mapped are refused. This is per process, and the assignment is per app (`proxy_<package>.json`), not automatically one route for the whole profile. Two apps in one profile can differ. Two profiles can differ only if their apps were each assigned a different proxy.

These still expose the phone while a proxy is on:

- `GetLinkProperties` returns the real `LinkProperties` when the real call succeeds, including interface addresses.
- `GetConnectionInfo` does not change the address inside `WifiInfo`. The helper `intIP2StringIP` is unused. The LAN address stays.
- `NetworkInterface` and `/proc/net` are not hooked. `AntiDetection.is_safe_path` even treats `/proc/net/` as something file hooks must not hide, and those hooks are not installed anyway.
- `getaddrinfo` on a numeric host is not rewritten (`isNumericHost`). The TCP `connect` hook still redirects that flow if the proxy is on.
- `IDnsResolverProxy.getWho` returns null, so that Java binder proxy never installs. DNS isolation is the native `getaddrinfo` hook, and only while the proxy is enabled.
- The leak guard in `BActivityThread.verifyLeakGuards` runs during route verification, not on every packet. It checks one DNS lookup, one UDP send, the Java boot-id redirect, and the sensor hook flag.

WebRTC host candidates and local-IP leaks were not captured. The code leaves the local address available through `WifiInfo`, link properties, and `/proc`. Treat them as open.

Recommended fix: fail closed when a profile is supposed to be network-isolated (`NOT_CONFIGURED` should not mean "use the phone"). Give a profile one route and apply it to every process in that profile, including GMS. Hide or replace local addresses on `WifiInfo`, `LinkProperties`, and `NetworkInterface`. Keep refusing unproxied UDP. Do not claim WebRTC is covered until those address surfaces are gone.

### 9. Hardware and OS fingerprint is one phone — high for linkage, not for reading the other profile

These do not, by themselves, let profile A open profile B's files. They do let a server correlate the two profiles, and correlate both with the physical device. `DeviceProfile` says this directly: virtual identity is not a second kernel, GPU, or sensor.

| Surface | Where | What the guest gets |
| --- | --- | --- |
| `Build.MODEL`, `BRAND`, `DEVICE`, `PRODUCT`, `MANUFACTURER`, `BOARD`, `HARDWARE`, `FINGERPRINT`, `SERIAL`, `ID`, `DISPLAY`, `VERSION.INCREMENTAL`, `VERSION.SECURITY_PATCH` | `DeviceProfile.prepareEarly` | Spoofed per profile, from a GPU-family pool. `HARDWARE` is set in Java only. `ro.hardware` is not pushed, so native property reads of it stay real. |
| `Build.HOST`, `USER`, `TIME`, `TYPE`, `TAGS`, `BOOTLOADER`, `RADIO`, `SUPPORTED_ABIS`, `VERSION.SDK_INT`, `VERSION.RELEASE` | not assigned in `prepareEarly` | Passed through. Shared. SDK and ABI must stay real or apps crash; they also identify the phone. |
| GPU / SoC: `ro.hardware*`, `ro.board.platform`, `ro.soc.*`, `ro.gfx.*`, `ro.chipname`, `ro.arch`, EGL/Vulkan | `VirtualSpoof.cpp` `is_graphics_key` | Passed through. `GL_RENDERER` is the real GPU. The profile picker only chooses a model whose GPU family matches. |
| Screen size, density, refresh, cutout | `IDisplayManagerProxy` only rewrites `createVirtualDisplay` | Passed through. Density is read so the fake model is close (`DeviceProfile.realDensity`). The panel is still the phone's. |
| Fonts | no hook | Passed through. Shared font list and files. |
| WebView UA and WebView package | `WebViewProxy` / `WebViewFactoryProxy` | The " BlackBox" suffix in `WebViewProxy.configureWebView` is not installed: `getWho()` is null, so `injectHook` returns first. The live UA is the system WebView's, including the real WebView/Chrome version. `Build.MODEL` inside that UA is the spoofed model if WebView reads it after `prepareEarly`. |
| SSAID / `Settings.Secure.ANDROID_ID` | `DeviceProfile.hookAndroidId` | Spoofed per profile for `getString` / `getStringForUser` when an argument is exactly `android_id`. Other read paths are finding 5. The value is one per profile, shared by every app in it. |
| IMEI, MEID, device id | `ITelephonyManagerProxy` (`getDeviceId`, `getImeiForSlot`, `getMeidForSlot`, `getDeviceIdWithFeature`) | Spoofed per profile. `DeviceIdProxy` does not install. |
| IMSI | `ITelephonyManagerProxy.getSubscriberId`, `IPhoneSubInfoProxy.getSubscriberIdForSubscriber` | Spoofed per profile (`310260` plus random digits). |
| Phone number, ICCID | `getLine1NumberForDisplay`, `IPhoneSubInfoProxy` ICCID methods | Nulled, not a per-profile substitute. |
| SIM country, operator, network country | `ITelephonyManagerProxy` `cloneMccMnc` / `cloneSimIso` / `cloneOperatorName` | Spoofed only when `DeviceProfile.simSpoofed` is set, which `GuestProxy.applyGeoConsistency` does when a proxy is applied. With no proxy these calls pass through. Both profiles then show the real SIM. |
| Cell location and neighbor cells | `GetCellLocation`, `GetAllCellInfo`, `GetNeighboringCellInfo` | Passed through unless fake location is on. Shared radio environment. |
| Serial | `Build.SERIAL`, `ro.serialno`, `ro.boot.serialno`, `IDeviceIdentifiersPolicyProxy.getSerialForPackage` | Spoofed on those Java and property paths. Sysfs serial files are redirected only through the IO rules (finding 11). |
| Advertising id | `DeviceProfile.hookGaid`; `ServiceConnectionDelegate.AdIdStub` | Spoofed per profile when the guest uses `AdvertisingIdClient.getAdvertisingIdInfo` or binds the ads-identifier action. `hookGaid` waits 1.5 seconds and then retries up to six times, three seconds apart, and then gives up if the class is missing. A read of the real GMS provider (finding 5) or of `Settings.Secure` `advertising_id` is not this hook. `AdIdStub` answers `isLimitAdTrackingEnabled` with 0 for every profile. |
| Widevine / MediaDrm `deviceUniqueId` | `DeviceProfile.hookMediaDrm` | Java `MediaDrm.getPropertyByteArray` / `getPropertyString` replace `deviceUniqueId` only. Other properties (vendor, security level, system id) pass through and are shared. The hook is Pine on the Java class. NDK MediaDrm is not hooked. **Unverified** whether a given app uses the Java method. |
| Play Integrity, SafetyNet, Firebase installation id, App Set ID | no references in `Bcore/` | No per-profile implementation. Firebase installation ids stored in an app's private directory follow finding 1 (per directory if the app uses its data dir; readable across profiles via the file gap). Integrity and SafetyNet, if they run, describe this phone or fail the same way for every profile. **Unverified** at runtime; there is simply no code. |
| Wi-Fi MAC | `IWifiManagerProxy.cloneMac` | Spoofed per profile on `getConnectionInfo` only. |
| Wi-Fi BSSID and SSID | `IWifiManagerProxy.GetConnectionInfo` | Shared constants `ac:62:5a:82:65:c4` and `AndroidWifi` for every profile. That links profiles to each other and marks the container. |
| Wi-Fi scan, DHCP, nearby BSSIDs | `IWifiManagerProxy` has no scan hook; `IWifiScannerProxy` has no method hooks | Passed through. The same AP list is a location and a device link. |
| Bluetooth address | no spoof | Passed through if the platform returns it to this uid. **Unverified** on Android 16, where the public API often returns a placeholder unless the host holds the permission. |
| Boot id | `DeviceProfile.addKernelIdentityRedirects`, `deriveKernelBootId` | Java and `UnixFileSystem` reads of the exact path `/proc/sys/kernel/random/boot_id` get a per-profile UUID derived from `kernelSeed` and the **real** boot id. Native `open` of that path sees the real boot id (finding 1). `SystemClock.elapsedRealtime` / uptime are not hooked. Two profiles reboot together. |
| SoC serial, DMI serial, `/proc/cpuinfo` Serial line | same redirect map | Rewritten for the listed paths through IO rules only. Native `open` sees the real files. CPU feature lines in the sanitized `cpuinfo` are copied from the phone, so the CPU still matches. |
| Sensors | `SensorSignalIsolation.install` | 3-axis events get a sub-degree rotation and tiny noise keyed by the profile's android id. The physical sensor and its timing are shared. The class comment says so. Correlation of motion still links profiles. Other sensor types and lengths other than 3 are unchanged. |
| Battery | `IPowerManagerProxy` only stubs wake locks | Passed through. Level, temperature, and charging state match. |
| Storage stats and free space | `IStorageStatsManagerProxy.invoke` replaces the package with the host and calls the real service; `UnixFileSystemHook` `getSpace0` redirects only paths that hit an IO rule | `StorageStats` for guests is the host app's, so profiles match each other. `StatFs` on `/data` or `/storage` is the phone's volume. The per-user external quota is not what `statfs` on the real volume reports. |
| Time zone and locale | `GuestProxy.applyGeoConsistency` | Changed only when a proxy route is applied. Otherwise the phone's zone and locale. `ILocaleManagerProxy` is installed on API 33+; it was not fully traced. Assume the process default is the one `applyGeoConsistency` sets, and binder locale calls need their own check. **Partial.** |
| Location | `ILocationManagerProxy` | Real GPS unless fake location is enabled. A proxy route calls `BLocationManager.setLocation` for that app. Two profiles without that see the same fix. |
| Installed packages | `BPackageManagerService.getInstalledPackages`, `PackageManagerCompat.checkUseInstalledOrHidden` | The virtual package list omits packages whose user state is not installed, so profile B's clones are not in profile A's `getInstalledPackages` result. `getPackageInfo` of the host package returns the real host `PackageInfo`. `AppSystemEnv.isOpenPackage` falls through to the real package manager for a fixed system list (WebView, LatinIME, and others). The APK blobs under `blackbox/data/app/` are still files (finding 1). |
| Backup | `black.android.app.backup.IBackupManager` is a reflection stub only. No proxy is registered in `HookManager`. | No virtual backup service. A guest that reaches the real `BackupManager` is the host uid. **Unverified** whether that call succeeds. Treat it as shared if it does. |
| Contacts, call log, calendar, downloads | not in the `GetContentProvider` shortcut list. `SystemProviderStub.isSystemProviderAuthority` would treat them as system authorities if a stub were already holding them. | A direct `getContentProvider` for those names goes through the virtual resolver and returns null when the profile has no such provider. **Unverified** for other acquisition paths. Do not call them isolated. |

Recommended fix: stop promising per-profile hardware. Keep the Java id hooks, and extend them only where a wrong value does not crash drivers (do not spoof `ro.hardware` to a different GPU). For linkage, the honest control is a different network exit plus accepting that GPU, screen, sensors, radio, and uptime still match. Per-profile BSSID/SSID constants should at least stop being one global string. Native readers have to be covered by the same policy as Java, or documented as out of scope.

### 10. Clipboard API is per profile; the ciphertext is still a shared-uid file — medium

| | |
| --- | --- |
| Channel | Clipboard text |
| Where | `IClipboardManagerProxy`; `CloneClipboardStore`; `KeystoreIsolation`; `Keystore2ServiceIsolation` |
| Disposition | Hooked `ClipboardManager` is per user and per package. The ciphertext file is not. The Java keystore alias is namespaced when the per-user marker is on. The keystore daemon still authorizes the host uid. |
| Verified | Code. No second guest decrypted a clip. |

`IClipboardManagerProxy` handles set/get/clear/has locally and does not call the real clipboard service. `CloneClipboardStore` keeps plain text only (URI and intent clips are dropped), under `context.getNoBackupFilesDir()/clone_clipboard/<userId>/<package>.bin`. That directory is not the virtual-user data root, so the `/blackbox/` redirect does not apply. Finding 1 still lets another profile open the `.bin` files. The file is AES-GCM with AAD `BlackBoxClipboard|1|<userId>|<package>`. The AAD stops one file from decrypting as another user by accident. It is not an access check.

The key alias is `blackbox_clone_clipboard_v1`, with no user-authentication requirement. On a build that has run `BUserManagerService`, every existing user is marked with `keystore_isolation_v1.flag` (`migrateExistingUsersToIsolatedKeystore`), and new users are marked in `createUserLocked`. `KeystoreIsolation.installForCurrentProcess` then namespaces AndroidKeyStore for that guest:

- Android 12 and newer: `Keystore2ServiceIsolation` replaces the `keystore2` binder in that process and prefixes APP-domain aliases.
- Older releases: Pine prefixes `java.security.KeyStore` alias arguments. The class comment says Pine on boot classes is unsafe on Android 16, which is why the binder path exists.

The prefix is per virtual user and per guest package (`KeystoreIsolation.prefix`). A normal `KeyStore.getKey("blackbox_clone_clipboard_v1")` in profile B does not select profile A's key. Inventory calls through that proxy hide other prefixes.

That namespace is the guest's ServiceManager wrapper, not a keystore2 policy. The daemon still grants every alias to the host uid. A caller that opens the real `keystore2` service without the wrapper can use another profile's prefixed alias. The wrapper was not bypassed in this audit. If the marker file is absent, `installForCurrentProcess` returns success and installs nothing, and the raw alias is shared. Current user-manager startup throws if it cannot write the marker, so a process that reached `systemReady` should have the marker. A guest started by an older build, before that migration, was not tested.

Listener registration is dropped, so the hooked API also does not deliver the phone clipboard to guests.

Recommended fix: keep the API split and the alias prefix. Store the ciphertext where a guest cannot open it, and hold the key in a process that does not share the guest uid. Do not treat the binder prefix as a kernel boundary.

### 11. Kernel identity files are a Java-only redirect — medium

| | |
| --- | --- |
| Channel | `/proc/sys/kernel/random/boot_id`, sysfs serials, DMI serial, a sanitized `/proc/cpuinfo` |
| Where | `DeviceProfile.addKernelIdentityRedirects`; `IOCore.enableKernelIdentityRedirects` |
| Disposition | Spoofed for redirected lookups. Passed through for libc `open`. |
| Verified | Code. `BActivityThread.verifyLeakGuards` checks the Java path during route verification only. |

`enableKernelIdentityRedirects` is required at process start. Failure throws and the guest is not started. That proves the rule was registered, not that native code observes it. `deriveKernelBootId` mixes the profile seed with the real boot id, so the spoofed value changes when the phone reboots and is different per profile. A native read still returns the real boot id, which is identical across profiles.

Recommended fix: the same open policy as finding 1. If libc cannot be hooked safely, the guest cannot be told that boot id and sysfs serial are isolated.

### 12. Dead hooks that look like isolation — medium

| Class | Why it does nothing |
| --- | --- |
| `AndroidIdProxy`, `DeviceIdProxy`, `ISettingsProviderProxy`, `ISettingsSystemProxy`, `ISystemSensorManagerProxy`, `WebViewProxy`, `WebViewFactoryProxy`, `FileSystemProxy` | `getWho()` returns null. `ClassInvocationStub.injectHook` returns before `@ProxyMethod` hooks are registered. |
| `IDnsResolverProxy` | Same null `getWho()`, so `inject` never replaces the `dnsresolver` service. |
| `AntiDetection` file hooks | Wrappers exist; `install_file_hooks` does not patch libc. |
| `FileSystemHook` | Symbols are resolved; the comment records that production guests do not patch them. |

`DeviceProfile.hookAndroidId`, `hookGaid`, `hookMediaDrm`, and `SensorSignalIsolation.install` use Pine directly and are the ones that can be live. `installRuntimeHooks` must succeed or process start throws. A failed Pine hook refuses the guest. That does not cover the dead classes above.

Recommended fix: delete or stop registering hooks that cannot attach, so later audits do not count them. Wire WebView and settings behavior through Pine or a real binder stub if they are still required.

### 13. Same-profile GMS and account sharing — medium, inside one profile

| | |
| --- | --- |
| Channel | One GMS install and one account store per virtual user, shared by every app in that user |
| Where | `GmsCore.installGApps`; `GuestProxy.syncGmsRouteForUser`; `BAccountManagerService` |
| Disposition | Isolated across users at the binder layer (finding 4) and **not** isolated across apps in the user. The provider shortcut in finding 5 still ties every user to the phone's GMS. |
| Verified | Code. Google sign-in on a device is still unresolved in the September notes. This audit does not claim sign-in works. |

Two profiles get two `installGApps` data directories and two account map entries. They do not get two physical GMS providers, because finding 5 skips the virtual provider. A Google account created inside profile A's virtual account store is not returned by profile B's `getUserAccounts`. The same account can still appear if both profiles talk to the phone's GMS and the phone has that account. That last step was not observed here.

Recommended fix: one GMS data directory and one account namespace per profile, reached by every GMS authority, with the authenticator check from finding 4. Do not query the host GMS.

### 14. Process, binder, and socket neighborhood — medium

| | |
| --- | --- |
| Channel | Other guests' processes, unix sockets, binder services in the host |
| Where | `BProcessManagerService` shared uid; `BinderHook` / `NativeCore.getCallingUid`; `CallerUidResolver` |
| Disposition | Shared kernel. Virtual uids exist only where code consults them. |
| Verified | Code. No cross-process probe was run. |

`Binder.getCallingUid` is JNI-hooked. `CallerUidResolver` remaps a caller only when the original uid is already the host uid and the pid is a known guest; otherwise the real uid is kept. That is a calling-identity fix for BlackBox services. It does not create a binder namespace. Any guest can attempt binder or abstract-namespace sockets that the host uid is allowed to open. App ops and runtime permissions are the host's, which is why so many checks are forced to granted.

Recommended fix: separate uids, or a broker that is the only process allowed to open host services. Guests should not share a unix-socket namespace with each other.

### 15. Low signals

| Channel | Disposition |
| --- | --- |
| Constant Wi-Fi SSID `AndroidWifi` together with one BSSID | Already in finding 9. Listed again because it is a container signature even when the MAC differs. |
| `TaskDescriptionCompat` prefixes the recents label with `[B<userId>]` | The person using the phone can see the profile index. Not an app-to-app channel by itself. |
| Route probe user agent `BlackBoxRouteProbe/1` in `BActivityThread` | Used by the container's own exit check, not by guest WebViews. |
| `IPowerManagerProxy` wake-lock stubs returning 0 | Does not isolate battery state. |
| Clipboard listeners dropped | Guests do not observe the phone clipboard through the hooked API. The file channel is finding 10. |
| `hideRoot` su-path redirects | Only if `isHideRoot()` is on. Hides a few su paths from redirected lookups. Does not affect profile separation. |
| Variant library name (`VariantConfig.libName`) | Changes the `.so` name across build flavors. Every profile of one install still loads the same library. |

## What is actually per-profile today

Only on the paths named. Anywhere the table says "Java hook," a native or unhooked binder read is still the phone.

| Value | Per profile when the hook runs |
| --- | --- |
| SSAID via `Settings.Secure.getString` / `getStringForUser` | Yes, one value for the whole profile |
| IMEI / MEID / telephony device id / IMSI | Yes |
| `Build` fields listed in finding 9, and the matching `ro.product.*` / `ro.build.fingerprint` / `ro.serialno` properties | Yes, except graphics keys |
| `getSerialForPackage` | Yes, if `DeviceProfile.CURRENT` is set; otherwise `md5(host package)`, which is shared |
| Wi-Fi MAC on `getConnectionInfo` | Yes |
| GAID via `AdvertisingIdClient` or the ads-identifier bind | Yes, if the hook installed in time |
| MediaDrm Java `deviceUniqueId` | Yes |
| Boot id via redirected Java/`UnixFileSystem` read of that exact path | Yes, derived from the real boot id |
| Clipboard `ClipboardManager` API | Yes, and also per package. The `.bin` files are still shared-uid files (finding 10). |
| AndroidKeyStore aliases through the guest's `keystore2` proxy or Pine `KeyStore` hooks | Prefixed per user and per package when `keystore_isolation_v1.flag` is present (`KeystoreIsolation`, `Keystore2ServiceIsolation`). The keystore daemon still authorizes the host uid. A client that skips the wrapper is not covered. |
| Account binder API | Yes, workspace check; not per app |
| Virtual package list | Yes, by install state |
| App private directories | Only if the open is redirected and the path does not already contain `/blackbox/` |
| TCP exit and DNS | Only for processes with a live proxy |
| Sensor 3-axis samples | A small per-profile transform, not an independent sensor |

## September audit, and what changed in this reading

The 8 September isolation note is still right: shared DCIM/Pictures/Movies, host uid, `/blackbox/` passthrough, native redirect exclusions, and an incomplete same-workspace account check. `AccountWorkspacePolicy` is present and is applied at `getUserAccounts`. This audit does not reopen that patch. It does say the policy is not sufficient, because `accounts.conf` is one map for every user and credential getters do not check the authenticator.

`KeystoreIsolation` is newer than that note. `BUserManagerService` now writes a per-user marker and guest startup installs a keystore alias prefix. That separates Java keystore names across profiles. It does not separate the uid that keystore2 trusts, and it does not cover files.

No production build was installed for this document. The September phone notes (diagnostic version `210772124` on a Samsung SM-S918U1) are not evidence about these channels.

## What was not done

- No device test, no second profile launched, no file opened from a guest, no packet capture, no WebRTC sample, no GSF query, no Play Integrity call.
- SELinux denials that might still block a specific path were not collected. The expectation above is the normal same-uid app-data allow, not a measured `avc`.
- `ILocaleManagerProxy` and `IVpnManagerProxy` were not line-audited past their registration. Locale and VPN should be treated as unverified.
- Third-party SDK behavior inside a guest (which identifier it reads first) was not traced. The table is the container's surface, not a particular app's choice.

## Fix order

1. Kernel identity for each profile, or an open policy that guests cannot skip. This removes findings 1, 2, 3, and 11 together, and it is what makes the clipboard file and the keystore daemon actually per-profile.
2. Split and encrypt the account store, and check authenticator identity on credential reads.
3. Stop routing GSF, GMS, Play, and MediaStore to the phone's providers.
4. Make shared media opt-in, and stop returning `PERMISSION_GRANTED` for every URI.
5. Filter notification getters.
6. Require an explicit network route per profile and hide local addresses.
7. Remove dead hooks so the remaining Java spoof list is the real list.
8. Document GPU, screen, sensors, radio, uptime, and WebView version as shared. Do not call the product a second device.
