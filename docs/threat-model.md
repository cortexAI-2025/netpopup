# Threat model — NetPopUp mesh v0

> Issue : mesh-02 (#3) · Référence : document « NetPopUp — Architecture mesh v0.1 »
> · À réviser à chaque changement du format d'enveloppe, du protocole de session
> ou de la crypto.

> **Conception cible, pas état actuel.** Ce document décrit les protections que
> le mesh v0 doit apporter. Aucune n'est encore implémentée : l'app actuelle est
> le MVP Firebase, qui envoie contenu, pseudo et identifiant en clair à Firestore,
> sans chiffrement de bout en bout, sans SQLCipher ni `FLAG_SECURE`. Une mitigation
> n'est effective qu'une fois l'issue citée en regard livrée et fusionnée.

Le mesh v0 doit protéger le **contenu** des messages et l'**appartenance aux rooms
privées** contre les relais, les observateurs radio et un appareil volé verrouillé.
Il ne protégera pas les **métadonnées radio** (qui est à portée de qui, quand, avec
quel volume de trafic), ni la **room locale**, publique par conception. Ce document
dit qui peut voir quoi, ce qui l'en empêche, et ce qui reste assumé.

---

## 1. Système en bref

- Des téléphones Android échangent des **enveloppes** de pair en pair (Nearby
  Connections, store-carry-and-forward, routage épidémique). Aucun serveur en v0.
- Chaque enveloppe a un **en-tête clair de 64 octets** (version, type, ID,
  étiquette de destination tournante, horodatage, durée de vie, sauts, champ
  `ack`) et un **payload chiffré** XChaCha20-Poly1305 dont l'AAD est l'en-tête
  immuable.
- Dans le payload, le message est chiffré une seconde fois par **libsignal** :
  X3DH/PQXDH puis Double Ratchet en 1:1, Sender Keys dans les rooms privées.
- Les pairs connectés sont **anonymes** l'un pour l'autre : la poignée de main
  `HELLO` chiffre le lien avec des clés éphémères, sans identité.
- Sur l'appareil : base SQLCipher (clé enveloppée par l'Android Keystore),
  backup désactivé, `FLAG_SECURE`, notifications sans contenu.

## 2. Actifs

| Actif | Pourquoi il compte | Où il vit |
|---|---|---|
| Texte des messages | Cœur de la confidentialité | Payload chiffré en transit ; base SQLCipher chez les destinataires |
| Identité (clé d'identité, empreinte) | Usurpation, liaison entre conversations | Base SQLCipher ; empreinte dans le payload uniquement |
| Pseudo | Lien avec une personne réelle | Payload chiffré uniquement |
| Appartenance aux rooms privées | Révèle des groupes | Secrets de room dans la base ; jamais en clair sur le réseau |
| Graphe social (qui parle à qui) | Surveillance | Déductible des étiquettes, du timing et de la présence radio |
| Clés (identité, sessions, Sender Keys, secrets de room, clé de base) | Compromission de tout le reste | Base SQLCipher + Android Keystore |
| Disponibilité du mesh | Utilité même de l'app | Stores et liens radio de tous les appareils |
| Batterie et stockage de l'appareil | Coût imposé aux relais | Chaque appareil |

## 3. Hypothèses

| # | Hypothèse | Si elle tombe |
|---|---|---|
| H1 | Android, son Keystore et l'appareil ne sont pas compromis (pas de root, pas de malware avec accès root) | Toutes les clés et tous les messages de l'appareil sont exposés |
| H2 | libsignal et libsodium sont correctement implémentés | La confidentialité du contenu tombe |
| H3 | Les primitives tiennent : X25519, Ed25519/XEdDSA, SHA-256, HMAC, HKDF, XChaCha20-Poly1305 | Idem ; voir P-02 pour le post-quantique |
| H4 | Nearby Connections (Play services, code fermé) se comporte comme documenté ; NetPopUp ne compte pas sur son chiffrement propre | Au pire, les trames restent chiffrées par `HELLO` et par le payload |
| H5 | Les horloges des téléphones divergent de moins de 2 h | Messages refusés ou expirés trop tôt (disponibilité, pas confidentialité) |
| H6 | Les contacts sensibles vérifient leurs empreintes par QR | Sinon le premier contact via une room est du TOFU (T-15) |

## 4. Adversaires

| # | Adversaire | Capacités | Objectifs |
|---|---|---|---|
| A1 | Relais malveillant | Fait tourner une app modifiée ; reçoit, garde, modifie, rejoue, supprime ou injecte des enveloppes | Lire, relier, censurer, saturer |
| A2 | Observateur radio passif | Capte BLE, Bluetooth et Wi-Fi sur une zone, éventuellement avec plusieurs capteurs | Savoir qui est où, quand, et qui communique |
| A3 | Attaquant radio actif | Faux pairs en nombre (Sybil), brouillage, connexions répétées | Isoler une victime, épuiser sa batterie, contourner les quotas |
| A4 | Membre malveillant ou retiré d'une room | Connaît les anciennes clés de la room ; peut rester à portée | Lire après son retrait, usurper, perturber l'admission |
| A5 | Personne qui a l'appareil en main | Vol, saisie, accès physique, appareil verrouillé ou non | Lire messages et contacts |
| A6 | Autre app sur l'appareil, sans root | API publiques d'Android : captures, notifications, sauvegardes | Exfiltrer des messages |
| A7 | Spammeur | Peut produire des enveloppes valides en masse | Saturer les stores, noyer les rooms |
| A8 | Opérateur du futur transport Internet (mesh-24, #25) | Voit tout ce qui passe par le relais Firebase | Relier activités et rooms |
| A9 | Chaîne d'approvisionnement | Dépendance ou build compromis | Tout |

## 5. Ce que voit chaque acteur

| Acteur | Texte | Pseudo / identité | Room ou conversation | Étiquette `destTag` | Moment et volume | Présence radio |
|---|---|---|---|---|---|---|
| Destinataire légitime | oui | oui | oui | oui | oui | oui (s'il est à portée) |
| Relais (A1) | non | non | non, sauf corrélation (T-03) | oui, change chaque heure | oui | oui, du pair direct seulement |
| Observateur passif (A2) | non | non | non | non (lien chiffré par `HELLO`) | oui | oui |
| Membre retiré (A4) | anciens messages + enveloppes en vol (T-06) | oui pour les anciens | oui jusqu'à la rotation | oui jusqu'à la rotation | oui | oui |
| Appareil volé verrouillé (A5) | non (H1) | non | non | non | non | — |
| Appareil volé déverrouillé, app ouverte (A5) | oui | oui | oui | — | — | — |
| Futur serveur Internet (A8) | non | non | non | oui, par époque | oui : compteurs par étiquette et par heure | non |
| Room locale publique : tout pair à portée | **oui** | **oui** | oui | oui | oui | oui |

## 6. Menaces et mitigations

Statut **de conception**, une fois l'issue citée livrée : **M** mitigée · **P** partiellement mitigée · **A** acceptée en v0. Aucune ligne n'est encore implémentée.

### Confidentialité

| # | Menace | Adversaire | Mitigation | Issue | Statut |
|---|---|---|---|---|---|
| T-01 | Un relais lit le contenu | A1 | Chiffrement de bout en bout libsignal + couche extérieure AEAD | mesh-09/10/11 (#10, #11, #12) | M |
| T-02 | Un relais relie les messages d'une conversation grâce à la clé de ratchet laissée en clair par libsignal | A1 | Couche extérieure chiffrée avec `pairEnvKey` / `roomEnvKey` : la clé de ratchet n'est jamais visible | mesh-09 (#10) | M |
| T-03 | Un relais relie les enveloppes d'une même room ou conversation | A1 | `destTag` tourne chaque heure (HMAC d'une clé secrète). Reste visible : même étiquette pendant une heure, corrélation par timing et volume | mesh-04 (#5) | P |
| T-04 | Un observateur passif lit les en-têtes | A2 | Lien chiffré par la poignée de main `HELLO` (X25519 éphémère) | mesh-06 (#7) | M |
| T-05 | Un tiers devine un code de room à 6 caractères | A1, A2 | Le code ne donne qu'une demande d'accès ; un membre autorisé doit accepter et envoyer les clés par une session X3DH | mesh-11 (#12) | M |
| T-06 | Un membre retiré lit des messages postérieurs à son retrait | A4 | Nouveau secret et nouvelles Sender Keys. **Ne couvre pas** les enveloppes déjà en vol (lisibles jusqu'à 24 h + 2 h), ni les messages envoyés par un membre qui n'a pas encore reçu la rotation, ni l'historique déjà déchiffré | mesh-11 (#12) | P |
| T-07 | Lecture de la room locale | tous | Aucune : publique par conception, signalée partout dans l'UI | mesh-20 (#21) | A |
| T-08 | Lecture de la base d'un appareil volé verrouillé | A5 | SQLCipher sur toute la base, clé enveloppée par le Keystore | mesh-13 (#14) | M (H1) |
| T-09 | Captures d'écran, enregistrement, aperçu des apps récentes | A5, A6 | `FLAG_SECURE` sur toutes les fenêtres | mesh-14 (#15) | M |
| T-10 | Exfiltration par sauvegarde Android (cloud ou transfert) | A5, A6 | `allowBackup=false`, règles d'extraction qui excluent tout | mesh-13 (#14) | M |
| T-11 | Contenu révélé par une notification ou sur l'écran verrouillé | A5, A6 | Texte fixe « Nouveau message », `VISIBILITY_SECRET` | mesh-17 (#18) | M |
| T-12 | Appareil déverrouillé avec l'app ouverte | A5 | Aucune en v0 ; l'éphémérité limite ce qui reste lisible | — | A |

### Intégrité et authenticité

| # | Menace | Adversaire | Mitigation | Issue | Statut |
|---|---|---|---|---|---|
| T-13 | Un relais modifie le payload | A1 | AEAD : tout changement fait échouer le déchiffrement chez le destinataire | mesh-09 (#10) | M |
| T-14 | Un relais modifie l'en-tête immuable (durée de vie, étiquette…) | A1 | En-tête dans l'AAD : l'enveloppe modifiée est indéchiffrable ; elle change aussi d'`envelopeId`. Un relais ne peut pas le détecter lui-même, donc la copie modifiée consomme du stockage jusqu'à sa TTL (voir T-20) | mesh-04 (#5) | M (contenu), P (stockage) |
| T-15 | Usurpation d'un contact | A1, A4 | Vérification d'empreinte par QR. Un premier contact via `ANNOUNCE` dans une room est du TOFU tant que l'empreinte n'est pas vérifiée | mesh-10 (#11) | P (H6) |
| T-16 | Faux `ROOM_KEY` en mode « créateur seulement » | A4 | Les clients ignorent tout `ROOM_KEY` non signé par le créateur | mesh-11 (#12) | M |
| T-17 | `ACK` forgé pour faire supprimer un message | A1 | Seul le destinataire connaît `ackToken` (dans le payload chiffré) ; un relais vérifie H(`ackToken`) = champ `ack` | mesh-12 (#13) | M |
| T-18 | Rejeu d'anciennes enveloppes | A1 | Déduplication par ID, pierres tombales jusqu'à `createdAt + lifetime + 3 h`, expiration ; libsignal refuse aussi une clé de message déjà utilisée | mesh-04/05 (#5, #6) | M |
| T-19 | `hopCount` falsifié pour arrêter la propagation | A1 | `hopCount` n'est jamais utilisé pour supprimer ou bloquer en v0 | mesh-04 (#5) | M |
| T-20 | Horodatage falsifié (`createdAt` futur, durée de vie excessive) | A1, A7 | Rejet si `createdAt` > maintenant + 2 h ou `lifetime` > 24 h | mesh-04 (#5) | M |

### Disponibilité

| # | Menace | Adversaire | Mitigation | Issue | Statut |
|---|---|---|---|---|---|
| T-21 | Inondation d'enveloppes valides | A7 | Seau à jetons par pair (rafale de 20, puis 1/s), plafond de 1 000 par `destTag`, plafond du store, éviction qui protège les enveloppes `SELF` | mesh-05/06 (#6, #7) | P |
| T-22 | Contournement des quotas par pair avec de faux pairs en nombre (Sybil) | A3, A7 | Les pairs sont anonymes, donc les quotas par pair ne bornent pas un attaquant qui multiplie les identités radio. Plafonds globaux seulement ; preuve de travail envisagée après le test terrain | mesh-23 (#24) | A |
| T-23 | Relais qui absorbent sans retransmettre (trou noir), isolement d'une victime (éclipse) | A1, A3 | Redondance du routage épidémique et rotation des pairs toutes les 5 à 10 min. Inefficace si l'attaquant contrôle tous les voisins | mesh-06/07 (#7, #8) | P |
| T-24 | Épuisement de la batterie par connexions répétées | A3 | 3 à 4 connexions au plus, cycles advertising/discovery, rotation | mesh-07/08 (#8, #9) | P |
| T-25 | Brouillage radio | A3 | Aucune possible au niveau applicatif | — | A |
| T-26 | Trames malformées visant le parseur (crash, allocation excessive) | A1, A3 | Parsing strict à taille bornée, fuzzing en CI | mesh-04/21 (#5, #22) | M |
| T-27 | Pluie de demandes d'accès à une room | A7 | Aucune limite dédiée prévue à ce jour (voir Q-01) | — | A |
| T-28 | Horloge locale décalée de plus de 2 h | — | Alerte dans les réglages ; les messages peuvent être perdus | mesh-18 (#19) | P (H5) |

### Métadonnées et vie privée

| # | Menace | Adversaire | Mitigation | Issue | Statut |
|---|---|---|---|---|---|
| T-29 | Suivi de présence et de déplacements par la radio | A2 | Noms d'advertising aléatoires renouvelés à chaque cycle ; adresses Bluetooth aléatoires gérées par Android (selon le constructeur). Être à portée reste observable | mesh-08 (#9) | A |
| T-30 | Inférence du graphe social par corrélation de présence et de timing | A1, A2 | Aucune en v0 au-delà de l'anonymat des pairs et des étiquettes tournantes | — | A |
| T-31 | Longueur des messages | A1 | Remplissage du contenu à 256, 512 ou 1 024 octets avant chiffrement. Reste visible : la classe de taille (≤ 256, ≤ 512 ou ≤ 1 024 octets) | mesh-04/09 (#5, #10) | P |
| T-32 | Un `ACK` révèle aux relais quel message a été livré | A1 | Accepté : il ne révèle ni l'émetteur ni le destinataire | mesh-12 (#13) | A |
| T-33 | Le serveur Internet compte l'activité par étiquette et par heure | A8 | Aucun identifiant en clair ; documenté dans l'UI et la politique de confidentialité | mesh-24 (#25) | A |

### Implémentation et chaîne d'approvisionnement

| # | Menace | Adversaire | Mitigation | Issue | Statut |
|---|---|---|---|---|---|
| T-34 | Mauvais usage de la crypto | — | libsignal pour tout le protocole ; seules la couche extérieure et les dérivations HKDF sont propres à NetPopUp, testées sur vecteurs connus | mesh-09/21 (#10, #22) | P |
| T-35 | Dépendance compromise | A9 | Versions figées ; vérification des dépendances Gradle à mettre en place (Q-02) | mesh-03 (#4) | P |
| T-36 | Nearby (Play services) est du code fermé | A9 | Aucune confiance dans son chiffrement ; une implémentation BLE ouverte est envisagée pour iOS | — | A (H4) |
| T-37 | Relais Internet (mesh-24, non relié à l'app) : le serveur voit étiquettes, volumes par heure, tailles, IP et quelles étiquettes un compte anonyme relève | A8 | Contenu chiffré de bout en bout ; étiquettes qui tournent chaque heure ; compte anonyme sans donnée NetPopUp ; module hors de l'APK tant qu'il n'est pas relié et validé. Voir `docs/internet-relay.md` | mesh-24 (#25) | P |

## 7. Limites assumées en v0

Ces limites seront expliquées aux utilisateurs dans la documentation (mesh-22, #23) :

1. **La room locale est publique** : quiconque est à portée lit et relaie ses messages (T-07).
2. **Les métadonnées radio sont observables** : présence, timing, volume (T-29, T-30).
3. **Un membre retiré peut encore lire pendant un temps** les enveloppes en vol et celles des membres pas encore informés de la rotation (T-06).
4. **Pas de défense Sybil** : un attaquant déterminé peut saturer le voisinage (T-22) ou brouiller (T-25).
5. **Un appareil déverrouillé, app ouverte, montre tout** (T-12).
6. **Un nouveau membre lit ce que sa room contient encore** : les clés de room voyagent avec retard, donc chaque Sender Key est distribuée depuis le début de l'époque. Un membre admis peut lire les messages de l'époque encore en circulation (24 h au plus) ; un retrait change d'époque et de clé. Mesuré : sans cela, un message écrit avant que la clé n'atteigne un membre lui restait illisible à jamais.
7. **Le premier contact via une room est du TOFU** tant que l'empreinte n'est pas vérifiée (T-15).

## 8. Hors périmètre

- Appareil rooté ou OS compromis (H1).
- Attaques physiques sur le matériel (extraction de puce, canaux auxiliaires).
- iOS, LoRa, multi-appareils (hors périmètre v0 de l'architecture).
- Contrainte exercée sur l'utilisateur pour déverrouiller son téléphone.

## 9. Questions ouvertes

| # | Question | Piste |
|---|---|---|
| Q-01 | Limiter les demandes d'accès à une room (T-27) | Seau à jetons par `joinTag` chez les membres ; à ajouter à mesh-11 (#12) ou en issue dédiée |
| Q-02 | Vérification des dépendances (T-35) | `gradle/verification-metadata.xml` en CI, dans mesh-03 (#4) |
| Q-03 | Exiger un Keystore matériel (StrongBox / TEE) ? | Constater la proportion d'appareils concernés au test terrain avant de trancher |
| Q-04 | Résistance post-quantique | Un enregistrement fait aujourd'hui pourrait être déchiffré plus tard. La couche extérieure (DH statique X25519) et les Sender Keys ne sont pas post-quantiques ; pour le 1:1, cela dépend de ce qu'offre la version retenue de libsignal (PQXDH à l'établissement, éventuellement un ratchet post-quantique). À vérifier dans mesh-10 (#11) |
| Q-05 | Verrou de l'app (code ou biométrie) contre T-12 | Après la v0 |
