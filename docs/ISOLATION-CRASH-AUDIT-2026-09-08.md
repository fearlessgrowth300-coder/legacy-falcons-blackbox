# Isolation and crash audit - September 8, 2026

Status: targeted local hardening, not a zero-leak certification or a fully verified crash fix. Existing notification changes were preserved. No production update or phone installation was performed in this audit.

## Phone evidence

The connected phone still runs diagnostic version code 210772124, last updated September 6 at 06:25:05. The retained Java crash buffer did not contain a matching new BlackBox exception. Historical Android process-exit records show:

- September 8, 03:46:22: guest slot p0 had a broadcast ANR while handling Google's DeviceStateReceiver and SCREEN_ON. Immediately afterward, the BlackBox manager (:black) exited with DEPENDENCY DIED, explicitly referring to p0's provider.
- September 8, 03:54:12: the same broadcast-ANR / manager-dependency-death pattern occurred again.
- September 8, 03:54:33: a subsequent p0 startup failed to finish (BIND APPLICATION ANR).
- Other entries show low-memory terminations of guest processes and the main BlackBox app. Those are separate events and are not proof of a Java exception or the exact reported stopped dialog.

The system ANR trace file is not readable via ordinary ADB on this phone. Access was denied; no root, permission bypass, or broad device bugreport was used. A full main-thread trace or a fresh reproducible failure is still needed to diagnose the underlying Google broadcast freeze.

## Crash containment change

BProcessManagerService previously held a stable ContentProviderClient specifically for routed GMS persistent processes. Android recorded the manager dying because its guest provider died. The local patch uses an unstable client for these guests, matching the existing main-app behavior. The existing Binder death callback releases the guest record. Logging and the ProcessRecord comment now describe the actual connection type.

This is intended to stop that provider dependency from killing the manager. It does not claim to repair the guest's ANR, prevent low-memory reclamation, or guarantee uninterrupted background push. Those require phone regression tests.

Android reference: https://developer.android.com/reference/android/content/ContentResolver.html#acquireUnstableContentProviderClient(android.net.Uri)

## Isolation findings

1. Workspace identity is shared by apps within the same virtual user, as documented in the prior identity review.
2. IOCore intentionally shares DCIM, Pictures and Movies with the physical phone. This is not a fully private media volume per app. No media-sharing behavior was changed pending the user's isolation preference.
3. Guest processes use the host's real Android UID; a virtual UID is not a separate kernel-enforced Android app sandbox. Filesystem redirection alone is not sufficient evidence of isolation from hostile native guest code.
4. Java redirectPath returns existing /blackbox/ paths unchanged, and the native redirect loop also excludes paths containing /blackbox/. These require synthetic traversal/direct-path tests and a broader storage access-control review; no other app's private files were read as a test.
5. Account-store selection previously used the requested userId without checking the registered caller's workspace. The patch checks that boundary centrally: server-local calls are allowed; registered callers can access only their own workspace; unknown external callers and invalid IDs are denied.
6. Same-workspace account authorization is NOT fully audited. For example, credential getter authorization must be checked against the owning authenticator rather than assuming workspace separation solves access between every app. Package/provider/URI-grant, clipboard, shared storage, backup and native access paths remain to be tested.

No claim that user data was actually exfiltrated is made. These are observed sharing choices, a missing boundary now patched locally, and remaining risks. Do not describe the current build as leak-free or a security boundary for untrusted apps.

Android app-sandbox reference: https://source.android.com/docs/security/app-sandbox

## Verification

- AccountWorkspacePolicyTest: 12 checks passed (own workspace, cross-workspace denial, unknown callers, internal server calls and invalid IDs).
- Existing NotificationNamespaceTest: 35 checks passed again.
- Android compilation: `:Bcore:compileDebugJavaWithJavac` succeeded using Java 21 and the installed SDK. Existing annotation-processor/deprecation warnings remain.
- Device regression tests and full adversarial isolation tests are pending. The phone has not received these changes.
