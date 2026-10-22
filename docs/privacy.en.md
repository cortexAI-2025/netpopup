# NetPopUp — privacy policy (v0)

**In short: NetPopUp collects nothing.** There is no server, no account, no ads and no analytics. The app does not
even hold the Internet permission.

## Data on your phone
Cryptographic identity, contacts, rooms, messages and in-transit messages are stored **locally** in an encrypted
database (SQLCipher) whose key is protected by the Android Keystore. Android backup is disabled. Messages expire
(6 h by default, 24 h at most) and are erased. Uninstalling removes everything.

## What other phones can see
| Who | Sees |
|---|---|
| Your recipient / room members | The text and your display name |
| A phone relaying your message | That an encrypted envelope is passing: approximate size, time, a destination tag that changes hourly. **Not** the text, your name or the conversation |
| Someone listening to the radio | That a NetPopUp phone is in range, when, and a volume of traffic |
| Any peer in range, in the public local room | **Everything**: text and display name |

## Limits you should know
- **Link-level encryption** between two phones is not yet in this version: messages remain end-to-end encrypted,
  but envelope headers (type, rotating tag, timestamp) travel in clear on the link.
- The local room is **public** by design.
- A member removed from a room may read a few already-circulating messages until they expire.
- Nearby Connections is provided by Google Play services; its internals are closed.
- A possible future optional Internet relay would only see anonymous activity counters per tag and hour, and
  would be switchable off.

## Contact and licence
Free software, GNU AGPL-3.0. Source and issue tracker: https://github.com/cortexAI-2025/netpopup
