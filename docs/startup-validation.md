# Startup reliability — 2026-10-27

## Changes

- Lazy single-flight engine initialization; only successful initialization is cached.
- Startup screen gates chat navigation and catches initialization/native loading errors.
- Retry and copy diagnostic preserve database, identity, messages and contacts.
- Name/permission onboarding step survives activity recreation; IME/navigation insets.
- Explicit offline onboarding disables the mesh setting, including later service restarts.
- Foreground-service requests tolerate permission revocation/background rejection.
- StrongBox fallback covers both initialization and actual key generation.
- Failed schema initialization closes the database handle.
- Diagnostic reads tolerate missing/unreadable paths and cap their size.
- Real Android 15 onboarding/send/recreation and SQLCipher/Keystore tests added to CI.
- Debug ZIP includes the APK checksum; CI release signing is explicitly a test identity.

## Evidence

The previous diagnostic-only commit `3ceee4a` passed run 40016124543 on 2026-10-23.
That result does not validate the changes in this branch. Check this branch's exact
commit and both CI jobs before distributing its APK.

Local environment: Java 17 is installed; Android SDK and Gradle cache are absent.
Local whitespace/diff checks are available. Full compilation and execution must
be established from GitHub Actions; no successful new CI result is claimed here.

## Remaining physical-device checks

1. Install the new APK on the Galaxy A14 / Android 15. Complete onboarding,
   accept/deny permissions, background and reopen the app.
2. On two phones: local delivery, contact exchange, private room admission,
   disconnect/reconnect, encrypted persistence, block/unblock and expiry.
3. Check notifications, Bluetooth/Wi-Fi toggles, permission revocation and battery restrictions.

An emulator cannot establish radio behavior on Samsung hardware. Without the
original phone's stack trace, the original name-to-permissions crash is not
considered proven fixed. The Android 15 test covers that transition going forward.
The isolated Internet relay PR is outside this offline startup change.
