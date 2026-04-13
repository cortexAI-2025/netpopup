# NetPopUp — Android MVP

> Ultra-fast, anonymous, text-only messaging. Local geo rooms + private invite rooms.

---

## Architecture

```
com.netpopup/
├── data/
│   ├── model/          ← Plain data classes (User, Message, Room)
│   ├── local/          ← DataStore (UserPreferences)
│   └── repository/     ← Firebase data sources (Auth, Messages, Rooms)
├── di/                 ← Hilt modules
├── service/            ← FCM push notification handler
├── ui/
│   ├── theme/          ← Dark color scheme, typography
│   ├── navigation/     ← NavGraph (3 destinations)
│   ├── screen/
│   │   ├── local/      ← Geo chatroom screen + ViewModel
│   │   ├── private/    ← Private room chat screen + ViewModel
│   │   └── rooms/      ← Create / Join room screen + ViewModel
│   └── component/      ← Reusable Compose components
└── util/               ← GeoUtils, UsernameGenerator, ModerationUtils
```

**Tech stack:** Kotlin · Jetpack Compose · MVVM · Hilt · Firebase (Auth + Firestore + FCM) · DataStore · Coroutines/Flow

---

## Firebase Setup (required before building)

### 1. Create a Firebase project

1. Go to [firebase.google.com](https://firebase.google.com) → **Add project**
2. Name it `netpopup` (or anything you prefer)

### 2. Add an Android app

- Package name: **`com.netpopup`**
- Download **`google-services.json`**
- Place it at: `app/google-services.json`

### 3. Enable services

| Service | Where |
|---|---|
| Anonymous Authentication | Firebase Console → Authentication → Sign-in method → Anonymous → Enable |
| Firestore Database | Firebase Console → Firestore → Create database (production mode) |
| Cloud Messaging | Enabled automatically for Android apps |

### 4. Deploy Firestore rules & indexes

```bash
# Install Firebase CLI if needed
npm install -g firebase-tools
firebase login

# Deploy security rules
firebase deploy --only firestore:rules

# Deploy compound index (required for message queries)
firebase deploy --only firestore:indexes
```

### 5. Enable Firestore TTL (ephemeral messages)

In the Firebase Console → Firestore → **TTL policies** → Add TTL policy:
- Collection: `messages`
- Field: `expiresAt`

This auto-deletes expired messages server-side at no extra cost.

---

## Build & Run

```bash
# Clone
git clone https://github.com/cortexai-2025/netpopup.git
cd netpopup

# Add your google-services.json (see above)
cp /path/to/google-services.json app/google-services.json

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

---

## Features

### Authentication
- Fully anonymous — no email, no phone required
- Auto-generated username (e.g. `Wolf_4821`)
- Session persisted via DataStore across app restarts

### Local Chatroom (Geo-based)
- Coarse location permission used once to compute a **zone ID**
- Zone = `round(lat, 2)` × `round(lng, 2)` ≈ 1.1 km × 1.1 km grid cell
- All users in the same cell share one Firestore room
- No precise GPS stored anywhere

### Private Rooms
- Generate a 6-character alphanumeric invite code
- Join any room by entering its code
- Copy code to clipboard with one tap

### Messaging
- Text only (500 char limit, enforced client + Firestore rules)
- Rate limit: 1 message / second (client-side)
- Ephemeral: messages expire after 1 hour (configurable via `Message.TTL_MS`)

### Moderation
- Long-press any message → **Report** or **Block user**
- Blocked users filtered locally (DataStore)
- Reported messages flagged in Firestore for admin review
- Basic banned-word filter (`ModerationUtils`)

### Push Notifications
- FCM delivers new message notifications when the app is in the background
- Notification channel: `netpopup_messages` (high importance)

---

## Geo Zone Logic

```kotlin
// GeoUtils.kt
fun computeZoneId(lat: Double, lng: Double): String {
    val roundedLat = (lat * 100).roundToInt() / 100.0  // 48.8566 → 48.86
    val roundedLng = (lng * 100).roundToInt() / 100.0  // 2.3522  → 2.35
    // Encode: "." → "p", "-" → "n"
    return "zone_48p86_2p35"
}
```

Each zone cell is ~1.1 km wide — large enough for a neighbourhood, small enough to be local.

---

## Firestore Data Model

```
/rooms/{roomId}
  id        : String
  type      : "LOCAL" | "PRIVATE"
  zoneId    : String   (local rooms)
  code      : String   (private rooms)
  createdAt : Long (epoch ms)

/messages/{messageId}
  id        : String
  roomId    : String
  userId    : String
  username  : String
  content   : String  (≤500 chars)
  timestamp : Long (epoch ms)
  expiresAt : Long (epoch ms)  ← TTL field
  reported  : Boolean
```

---

## Performance Notes

- Firestore offline persistence enabled → messages appear instantly from cache
- Snapshot listeners (not polling) → sub-300ms real-time delivery
- Messages limited to last 100 per room per query
- LazyColumn with `key = { it.id }` → efficient list diffing

---

## Extending for Production

| Feature | Where to add |
|---|---|
| Server-side rate limiting | Firebase Cloud Functions (trigger on `messages` create) |
| Push to specific users | Store FCM token in `/users/{uid}` doc; target from Cloud Functions |
| Admin kick/ban | Cloud Function + Firestore admin collection |
| Manual zone switch | Expose `zoneId` param in `LocalChatViewModel.initialize()` |
| Message search | Firestore `array-contains` on tokenised words (or Algolia) |

---

## License

MIT
