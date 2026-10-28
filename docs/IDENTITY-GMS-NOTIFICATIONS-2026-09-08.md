# Identity, Google sign-in and notifications: September 8 review

## Identity scope (source inspection, not a no-leak certification)

`DeviceProfile.forUser(userId)` stores `device_profile.properties` in the virtual user's directory. Therefore identity belongs to a workspace, not separately to every app within that workspace. Apps sharing one workspace use its profile.

| Requested value | Current implementation |
| --- | --- |
| Build number | Stores buildId/incremental; applies Build.ID, DISPLAY and VERSION.INCREMENTAL |
| Build.BRAND, MANUFACTURER, BOARD | Selected from a device-model profile; these can repeat across workspaces |
| Build.FINGERPRINT | Generated and persisted for the workspace |
| ANDROID_ID | Generated and persisted; a Settings.Secure hook returns the profile value |
| IMEI | Generated profile string returned by telephony hooks; not a new physical modem identity |
| Wi-Fi MAC | Generated profile value returned through hooked Wi-Fi information; not a separate physical network adapter |

Random generation does not mathematically guarantee no collisions. Persistence failures and every Java/native access path have not been audited by this review. The supplied screenshot checks one profile and cannot prove every workspace's identity isolation. No identity values or identity-generation code were changed in this task.

## Google sign-in: still unresolved

The prior on-phone test reached a Google dialog saying biometrics were required but unavailable. At that moment, the test branch's IAuthServiceProxy explicitly returned hardware-unavailable and delivered an error callback. Consequently that dialog does not prove the phone's actual fingerprint sensor is broken, nor that internet access failed. The current adapter does not provide genuine biometric authentication.

Android's Identity Check authenticator has additional permission and strong-biometric requirements. A working implementation must preserve those requirements and use a supported real authentication path. Returning synthetic authentication success, changing device identifiers, or silently removing Identity Check is not a valid fix. No authentication protection was disabled; Google sign-in is not claimed fixed.

Reference: https://developer.android.com/reference/android/hardware/biometrics/BiometricManager.Authenticators#IDENTITY_CHECK

## Notifications: local candidate patch

The existing BNotificationManagerService already forwards guest notifications through the host NotificationManager. On the connected phone, BlackBox POST_NOTIFICATIONS was granted and the notification app-op default was allow. No BNotif error lines were available in the reviewed log buffer; this does not establish end-to-end delivery.

Code defect: channel/group reads used only an in-memory cache, losing visibility of Android-persisted channels after the BlackBox service restarted. Channel deletion also depended on that cache.

Changes:

- Read registered channels/groups from Android and translate their names back for the requesting guest.
- Restrict reads and operations to the caller's workspace and package.
- Use exact workspace suffixes instead of treating any embedded marker as an already-scoped ID.
- Restore channel group IDs as well as channel IDs when returning them to guests.
- Allow deletion of a guest's persisted channel/group even when the local cache was lost; Android remains responsible for blocked-channel settings and notification permission.

Standalone NotificationNamespaceTest: 35 checks passed, including cross-workspace/package separation, reserved-marker IDs, legacy normal-ID compatibility and reading IDs with a new scope instance. These are pure Java mapping tests, not Android instrumentation or push-delivery tests.

Android verification: `:Bcore:compileDebugJavaWithJavac` completed successfully with the installed SDK and Java 21. This candidate has not been installed, published or included in a production update.

## Required phone acceptance test

Use one chosen clone to receive a real test notification; verify the notification appears in the normal shade, respects disabled channels, and opens the intended workspace. Repeat after restarting BlackBox and with a second workspace using the same app/channel ID. Separately verify that remote push registration and background execution work. A working notification forwarding layer cannot guarantee incoming push delivery for every app.

The user reports successful Twitch signup in User 17. This was not independently reproduced in this review, and no Twitch account was created by the assistant.
