# NetPopUp — Android mesh messaging

NetPopUp exchanges ephemeral text messages between nearby Android phones using
Nearby Connections and store-carry-and-forward routing. The current application
works without a Firebase project or an Internet relay.

- Public local room, private rooms with admission requests, direct contacts.
- Local cryptographic identity, libsignal private conversations, encrypted SQLCipher storage.
- Local blocking, message expiry, delivery acknowledgements, discreet notifications.
- Android 10+ (`minSdk 29`), Kotlin, Compose, Hilt, coroutines.

The local room is public. A message waiting for a peer is not a delivered message.
Nearby radio range and actual background delivery still require tests on two phones.

## Build

Use Java 17, Gradle 8.6 and Android SDK 34:

```bash
./gradlew assembleDebug
./gradlew :core-dtn:test :core-crypto:test :store-sql:test :mesh-engine:test
./gradlew :app:testDebugUnitTest :transport-nearby:testDebugUnitTest
./gradlew :app:lintDebug :transport-nearby:lintDebug
```

No `google-services.json` is required. The archived Firebase prototype is in
[`legacy-firebase/`](legacy-firebase/README.md), outside the application sources.

## Android verification

The CI builds debug APKs and runs JVM tests, lint and an Android 15 emulator job.
The device job exercises real onboarding, the name-to-permissions transition,
activity recreation, offline message storage and native encrypted database tests.
A successful APK build alone does not establish that the device job passed.

```bash
./gradlew :app:connectedDebugAndroidTest
```

Download `netpopup-debug-apk` in the successful run's Artifacts section. Extract
the ZIP; `SHA256SUMS.txt` contains the SHA-256 of the APK itself. Debug builds use
`com.netpopup.debug`. CI attempts to preserve the debug signing certificate via
its cache; loss of the cache may require uninstalling an older test build.
Release APK/AAB artifacts signed with a generated CI certificate are for tests;
they are not a production signing identity.

## Startup recovery

Storage and crypto initialize before chat screens collect data. A failed startup
shows **Retry** and **Copy diagnostic** without deleting the database or identity.
The offline onboarding option keeps the mesh service disabled until re-enabled
in Settings. Previously uncaught crashes can still be copied on next launch.

## Modules

| Module | Role |
|---|---|
| `app` | Compose UI, permissions, foreground service, native storage |
| `core-dtn` | Chat contract, envelopes, routing, presenters |
| `core-crypto` | Identity, pairwise crypto and private rooms |
| `store-sql` | Persistent engine and protocol stores |
| `mesh-engine` | Production mesh/chat integration |
| `transport-nearby` | Nearby Connections transport |

See [French user guide](docs/user-guide.fr.md), [field test protocol](docs/field-test-protocol.md),
[threat model](docs/threat-model.md), and [validation status](docs/startup-validation.md).

## License

GNU Affero General Public License v3.0; see [LICENSE](LICENSE).
