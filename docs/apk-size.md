# APK packaging audit — 2026-10-27

Measured from the actual debug APK at commit 60c66b8:

- GitHub artifact ZIP: 117,116,503 bytes.
- APK inside the ZIP: **362,435,047 bytes** (362.4 MB, 345.6 MiB).
- Four native CPU architectures occupy about 254 MiB combined.
- Six desktop libsignal `.dylib`/`.dll` resources occupy about 103 MiB before ZIP compression.
- The extended Compose icon dependency contributes substantial debug bytecode.

The small artifact ZIP concealed the much larger APK. Reporting only its download
size understated the file users would extract and install.

Changes: standalone ARM64, ARMv7 and x86_64 APKs; no universal APK; exclude desktop
native resources; replace the full extended icons collection with the core icons
used by the app; remove the debug Compose inspector dependency. Keep production
Android libsignal JNI and SQLCipher unchanged.

The CI inspects each actual debug/release APK, rejects foreign ABIs, desktop/test
natives or files over 100 MiB, and checks production crypto/database libraries
are present. Android 15 native startup/storage tests still run. Final byte sizes
come from that revision's `apk-size-reports` artifact; they are not estimates of
installed size, which also depends on Android's compiled code, data and cache.
