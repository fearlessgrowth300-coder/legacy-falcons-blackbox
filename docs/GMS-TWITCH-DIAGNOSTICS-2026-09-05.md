# BlackBox: GMS corrections and Twitch request diagnostics

Date: 2026-09-05. Base commit: a808f5f91d277f7a34c41aed4c855ede36df84fc.

## Status

Local candidate corrections are implemented. The Android core compilation and the independent regression checks passed. These changes have NOT been installed on the phone or validated by a successful Google sign-in/Twitch signup. No app data has been cleared. No account credentials or security checks were bypassed.

The installed Twitch WebView exposes no debugging socket. Its exact failing signup request remains unknown. The project requires its existing permanent signing key for both debug and release APKs. No SHIELD_SUITE signing environment variables or user Gradle signing configuration were found. A same-key diagnostic update is needed before completing on-device request capture; do not uninstall BlackBox or replace its key.

## Confirmed earlier device evidence

Twitch reproduced the generic network-error banner after Sign Up, despite Android reporting a validated Wi-Fi network. Ordinary logcat did not include the signup request response.

During cloned Play Store sign-in, logs showed:
- Calling uid: 10005 doesn't match source uid: 10004
- Package com.google.android.gms does not belong to uid 10477
- com.google.android.gms from uid 10477 not allowed to perform USE_BIOMETRIC
- ContentProviderStub returning a safe default and SimpleCrashFix swallow=true

These establish Google identity/permission failures, not a proven cause for Twitch's separate error.

## What changed and why

1. **Current caller, not original launcher.** Previously, NativeCore used AppConfig.callingBUid, a process-launch value, as the caller of later Binder transactions. Outbound provider attribution also used that launch value. The patch uses the outbound process's own virtual UID and resolves incoming transaction senders using their actual Binder PID. The lookup is restricted to registered live guests in the same workspace. Unknown/external/system callers retain their real Linux identity; the re-entry guard avoids recursive UID lookups.

2. **Preserve provider data and failures.** The previous wrapper could rewrite unrelated String arguments and nested application payloads. It also returned true, 1, or empty accounts after failures. The new wrapper changes only the caller envelope/legacy caller-package argument and propagates the original exception. Provider data, query selections, operation names, and genuine service results are preserved. This may surface errors previously hidden; it is not proof all Google authentication is repaired.

3. **Explicit biometric failure, not a stalled callback.** An isolated-profile adapter reports biometric hardware unavailable through the error callback instead of submitting a mismatched Google package identity to the host service. It reports no successful authentication and does not access the phone owner's biometric/credential state. This intentionally leaves biometric authentication unavailable inside virtual profiles; the guest decides whether it can offer another sign-in method. Runtime fallback behavior is still untested.

4. **Opt-in Twitch WebView diagnostics.** A debug-only Gradle option enables WebView debugging for one explicitly selected guest package. Release builds always set the diagnostic package to empty. No mixed-content, TLS, certificate, integrity, user-agent, or proxy bypass was added.

5. **Redacted request capture.** tools/diagnostics/capture-webview.mjs reads CDP network events and records Twitch origins, a small allowlist of endpoint labels, HTTP status, and structured network errors. It excludes headers, cookies, passwords, bodies, full paths, query strings, and unrelated sites. Output uses exclusive creation so previous evidence is never overwritten. HTTP status alone may not reveal an application-level error; a narrowly redacted response inspection may still be needed afterward.

## Verification completed

- :Bcore:compileDebugJavaWithJavac with diagnosticWebViewPackage=tv.twitch.android.app: BUILD SUCCESSFUL.
- Standalone IdentityPolicyTest: 17 checks passed.
- node --test tools/diagnostics/request-summary.test.mjs: 3 tests passed.
- node --check tools/diagnostics/capture-webview.mjs: passed.
- git diff --check: passed, apart from informational Windows line-ending warnings.

Not completed: full APK packaging/signing, installation, cross-process Android instrumentation, successful Google sign-in, successful Twitch signup, fresh Gmail/WhatsApp reproduction. Compilation is not a substitute for these tests.

## Continue after locating the existing signing configuration

Use the project's permanent suite signing configuration. Do not paste passwords into a chat. On this PC, Java 21 is available in Android Studio's jbr folder and the Android SDK is installed.

PowerShell build command (after the existing signing configuration is loaded):

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\UPCOMING\AppData\Local\Android\Sdk'
.\gradlew.bat :app:assembleOrigDebug '-PdiagnosticWebViewPackage=tv.twitch.android.app' --no-daemon --max-workers=2
```

Verify the APK signer matches the installed app before updating. Use a data-preserving update, never uninstall to work around a signature mismatch.

After launching Twitch in the diagnostic build, inspect adb shell cat /proc/net/unix for the exact webview_devtools_remote socket. Forward only that socket (example below uses a placeholder which must be replaced with the observed name):

```text
adb -s R5CW12ZCJCD forward tcp:9223 localabstract:OBSERVED_WEBVIEW_SOCKET
node tools/diagnostics/capture-webview.mjs 9223 60 twitch-request-summary.jsonl
```

Reproduce one signup attempt while capture is ready. Review the result before changing DNS/proxy/network code. Do not repeatedly submit signups or disable security protections based solely on the generic banner. Remove the temporary port forward afterward with adb forward --remove tcp:9223 and return to a same-key release build after diagnosis.

For GMS, repeat Play Store Sign in and check whether the earlier UID mismatch disappears and whether the unavailable-biometric callback lets the UI proceed. If Google rejects a certificate, account authorization, or the virtual environment itself, report that actual failure; do not replace it with success.

## Notebook summary

BlackBox contained a caller-identity mismatch: process-launch identity was reused for later service calls. Candidate corrections now identify the actual sender, preserve provider request data, and expose failed operations honestly. Unsupported biometric requests receive an explicit failure callback. Debug-only request logging was prepared for Twitch, but its precise signup failure and the on-device effect of the GMS corrections still require a signed diagnostic update and retesting.

## Primary references

- https://developer.android.com/reference/android/os/Binder
- https://developer.android.com/reference/android/content/AttributionSource
- https://developer.android.com/develop/ui/views/layout/webapps/debug-chrome-devtools
- https://android.googlesource.com/platform/frameworks/base/+/main/core/java/android/hardware/biometrics/IAuthService.aidl
- https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/hardware/biometrics/IBiometricServiceReceiver.aidl
