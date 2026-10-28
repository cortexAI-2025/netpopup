# Android API compatibility — 2026-10-27

Reported on a Galaxy A20e running Android 11:
`NoSuchMethodError: SecureRandom.nextInt(II)I`, from `LocalIdentity.loadOrCreate`.

The pure JVM crypto module compiles on Java 17. That compiler accepts the two-argument
random-number method, even though Android 11's boot classes do not supply it. The
previous device job only ran on Android 15 and missed this compatibility defect.

Replace `nextInt(1, 16380)` with `nextInt(16379) + 1`. This preserves SecureRandom,
the exact registration ID range, and existing persisted identities. No reset,
uninstall or key regeneration is required for identities already saved.

Also replace two API-33 `Arrays.compareUnsigned` calls used to order conversation
keys and room fingerprints with a shared unsigned byte comparator. Its ordering
is verified against Java's existing implementation so protocol derivation does
not change. A storage instrumentation test now uses `DataInputStream.readFully`
instead of the API-33 `InputStream.readNBytes` method.

The emulator matrix now covers API 29 (Android 10), 30 (Android 11), and 35
(Android 15). Each runs fresh identity creation/reload, symmetric pair-key
derivation, actual onboarding, offline send/recreation and encrypted storage.
The matrix uses Android boot classes and the production native libraries.

The captured trace identifies this Android 11 failure; it does not establish that
an untraced crash on another handset has the same cause. Physical-device radio
and background delivery still require verification.
