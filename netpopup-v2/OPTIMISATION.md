# NetPopUp V2 — allègement (build CI du commit `065042a`, run 41299200204)

Point de départ : version de référence `0ec03bd` (premier succès terrain, voir `docs/FIELD-TEST-1.md`).

## Tailles
| APK | Avant (référence, rapporté) | Après | Gain |
|---|---|---|---|
| ARM64 **release** (R8 + réduction des ressources) | n'existait pas | **5 989 662 octets (5,71 Mio)** | — |
| universal **release** (ARM64 + ARM32) | n'existait pas | 6 039 735 octets (5,76 Mio) | — |
| ARM64 debug | 45,28 Mo (rapporté sur téléphone) | 30 004 137 octets (28,61 Mio) | −34 % |
| universal debug | 77,21 Mo (rapporté sur téléphone) | 30 054 210 octets (28,66 Mio) | −61 % |

Mesure intermédiaire, avant retraits (commit `daf702a`, release R8 seul) : ARM64 release 17,78 Mio, dont Tor natif 5,2 Mio, ML Kit 4,7 Mio,
tables PQC « Picnic » 1,2 Mio, modèles ML Kit 0,8 Mio. Les retraits ci-dessous ont fait passer de 17,78 à 5,71 Mio.

## Retiré
- Tor (bibliothèque native `libarti_android.so`, toutes ABI) : jamais démarré, son code Java/Kotlin reste inactif dans l'arbre.
- Scanner de QR (ML Kit barcode, CameraX) et sa feuille d'interface `VerificationSheet.kt` ; test instrumenté associé.
- Ancien écran de discussion `ChatScreen.kt` (remplacé par l'interface NetPopUp). `ChatInputSection` est conservée (utilisée par `MeshPeerListSheet`).
- Tables PQC « Picnic » de BouncyCastle (ressources inutilisées).
- Architectures x86 et x86_64 (téléphones ARM seulement).
- Permissions : INTERNET, CHANGE_NETWORK_STATE, CAMERA, RECORD_AUDIO, READ_MEDIA_*, READ_EXTERNAL_STORAGE, ACCESS/CHANGE_WIFI_STATE,
  NEARBY_WIFI_DEVICES, ACCESS_LOCAL_NETWORK ; fonctionnalités matérielles caméra et Wi-Fi Aware.
- Démarrage de Tor, de l'annuaire de relais Nostr et de la connexion aux relais.
- Icônes PNG bitchat ; textes « bitchat » (les mentions de licence et d'attribution sont conservées).

## Conservé volontairement
Tout le moteur Bluetooth (advertising, scan, GATT, relais mesh, Noise, signatures) ; les salons NetPopUp (Argon2id + AES-256-GCM) ;
les permissions Bluetooth, localisation (dont arrière-plan), notifications, services au premier plan et exemption d'optimisation de batterie.
Le code Nostr, géohash, hotspot, voix et fichiers reste dans l'arbre mais n'est plus atteignable depuis l'interface ; il n'a pas été supprimé
car il est entremêlé au `ChatViewModel` : le retirer demanderait une chirurgie risquée sans pouvoir tester sur téléphone.

## Vérifications automatiques (CI)
Compilation debug et release, tests unitaires, contrôle que `INTERNET` est absent du manifeste fusionné **release** (échec du build sinon).

## Limites
- Verrous de dépendances Gradle passés en mode indulgent (des dépendances ont été retirées sans régénérer les verrous).
- APK release signé avec une clé bêta **non secrète** (`signing/README.md`) : ne pas publier tel quel.
- R8 transforme le code : seul un essai sur téléphone prouve que la version release se comporte comme la version debug testée.
