# Safe update rollout

BlackBox 4.0.5 is the first build that understands `rolloutPercent`. Older builds ignore that field,
so 4.0.5 itself must be treated as the rollout-control baseline and published at 100% only after the
Android 13/16 beta group passes.

Publish 4.0.5 with `channel=beta`. This creates a signed GitHub prerelease but deliberately leaves
the stable updater metadata on 4.0.4. Give that beta APK only to the test group. After it passes, use
**Promote BlackBox beta to stable** with version code 405 and 100%.

For every later release:

1. Publish at 5%.
2. Wait at least 24 hours and require production monitors plus crash volume to remain green.
3. Promote to 25%, then 50%, then 100%, waiting and checking at every stage.
4. Set the percentage to 0 immediately if update, auth, RLS, APK availability or crash monitoring
   fails. This pauses new offers without changing any installed copy.

The cohort is calculated locally from a random installation identifier, package name and version.
That identifier never leaves the device. Promotion changes only the signed-APK metadata; the APK,
hash and certificate remain immutable.

Use the **Promote BlackBox staged rollout** workflow with the exact current version code. The
workflow refuses to modify a different version or an APK hosted outside the official repository.
