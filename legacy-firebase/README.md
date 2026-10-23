# Code Firebase de la première version (PR #1)

Retiré de l'application par mesh-15 : Firebase n'est plus une dépendance de l'app, qui fonctionne
hors ligne par radio. Ces fichiers ne sont **pas compilés** ; ils sont gardés pour le futur transport
Internet (mesh-24), qui pourra en reprendre l'authentification anonyme et l'accès Firestore.

| Fichier | Rôle |
|---|---|
| `data/repository/AuthRepository.kt` | authentification anonyme Firebase |
| `data/repository/MessageRepository.kt` | messages Firestore (écoute, envoi, signalement) |
| `data/repository/RoomRepository.kt` | rooms locales par zone et privées par code |
| `data/model/Message.kt`, `Room.kt` | modèles Firestore |
| `service/NetPopUpMessagingService.kt` | réception FCM |
| `util/GeoUtils.kt` | zones géographiques de la room locale |
| `di/AppModule.kt` | instances Firebase (Hilt) |

Les règles côté serveur sont restées à la racine : `firestore.rules`, `firestore.indexes.json`.
Pour réactiver : redéplacer les fichiers sous `app/src/main/java/com/netpopup/` (ou, mieux, dans le
module `:transport-internet`), rétablir les dépendances Firebase et le plugin `google-services`.
