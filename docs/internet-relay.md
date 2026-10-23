# Relais Internet — mesh-24 (issue #25)

État : **module et tests prêts, non relié à l'application, jamais exécuté contre un vrai Firestore.**
L'APK livré n'a toujours pas la permission `INTERNET` (testé en CI par `ManifestPermissionsTest`).

> Condition de départ de l'issue : « après test terrain » (mesh-23). Le test terrain n'est pas fait ; le démarrage de
> mesh-24 a été décidé par le propriétaire du projet sans l'attendre. Rien ici ne remplace ce test : le relais
> Internet ne sera relié à l'application qu'après lui (voir « Relier à l'application »).

## Principe

Un téléphone qui n'a aucun voisin Nearby peut tout de même échanger, par un **relais muet** : un magasin d'octets
chiffrés rangés sous l'étiquette de destination de l'heure. Le relais n'a aucune logique de messagerie.

```
 moteur (MeshEngine) ── liens ──  TransportManager ── NearbyTransport   (gratuit, préféré)
                                        └─────────── RelayTransport      (« payant » : METERED)
                                                         │  parle INVENTORY / ENVELOPE au moteur
                                                         ▼
                                                  RelayBackend ── FirestoreRestBackend ──► relay/{étiquette}/e/{id}
```

`RelayTransport` est un `Transport` ordinaire à **un seul pair** (« relay »). Il joue le rôle du pair dans le protocole
de synchronisation du mesh : à l'`INVENTORY` du moteur, il relève sur le relais les enveloppes des étiquettes que le
moteur écoute, répond par son propre `INVENTORY`, pousse ce qui manque au moteur, et range sur le relais ce que le
moteur envoie. `SENT` n'est renvoyé que si le relais a accepté l'enveloppe : « relayé » reste vrai.

Le moteur ne remet au transport que des **étiquettes calculées** (`MeshEngine.listeningTags`), jamais les clés qui les
produisent.

## Ce que voit le serveur

| Information | Vue par le relais ? |
|---|---|
| Texte, pseudo, nom de room | Non (chiffré de bout en bout, avant d'arriver au transport) |
| Qui parle à qui | Non : pas d'identifiant ; l'étiquette est un HMAC qui change chaque heure |
| Étiquette de destination (16 o), heure de dépôt, taille (≤ 4 Ko), fin de vie | **Oui** |
| Combien d'enveloppes par étiquette et par heure | **Oui** (« compteurs anonymes d'activité par étiquette d'époque », T-33) |
| Quelles étiquettes un téléphone relève, et quand | **Oui** : le serveur voit un compte anonyme interroger un ensemble d'étiquettes. Des relèves répétées des mêmes étiquettes peuvent relier des heures entre elles pour un observateur du serveur |
| Adresse IP du téléphone, type d'appareil (agent HTTP) | **Oui**, comme tout serveur. Pas d'usage d'identifiants publicitaires ni de SDK |
| Compte | Compte **anonyme** Firebase, créé pour le contrôle d'abus seulement ; aucune donnée NetPopUp n'y est attachée ; jeton gardé en mémoire (un nouveau compte par lancement) |

Autres limites à connaître : le relais ne peut pas empêcher un utilisateur muni d'un compte anonyme de **déposer du
bruit** (voir « Abus »), et il ne chiffre pas les métadonnées ci-dessus.

## Déploiement (à faire par le propriétaire du projet Firebase)

1. Créer un projet Firebase ; activer **Authentication → Anonyme** ; créer Firestore (mode natif).
2. Déployer `relay/firestore.rules` (**à vérifier d'abord dans l'émulateur** : `firebase emulators:exec` avec des
   tests de règles ; ils n'existent pas encore).
3. Politique TTL sur le champ `x` du groupe de collection `e` :
   `gcloud firestore fields ttls update x --collection-group=e --enable-ttl`
   (la suppression par TTL peut accuser jusqu'à ~24 h de retard : le client ignore de toute façon les documents expirés).
4. Restreindre la clé d'API Web (restrictions d'API : *Identity Toolkit* et *Cloud Firestore* seulement) ; mettre des
   **alertes de budget** ; activer App Check si l'on veut limiter les clients non officiels.
5. Fournir `projectId` et `apiKey` à `FirestoreRestBackend` (la clé d'API Firebase n'est pas un secret, mais elle doit
   être restreinte).

## Coût et quotas (calcul, à confirmer à l'usage)

Une lecture Firestore par étiquette et par époque interrogée, même si le résultat est vide. Avec `K` étiquettes
écoutées (room publique, boîte de contact, chaque room et ses demandes, chaque 1:1) :

- relève courante : 3 époques × `K` lectures toutes les 120 s ;
- balayage complet (28 époques × `K`) au plus toutes les 30 min, au début d'une session ;
- les liens sont renouvelés toutes les 5 à 10 min par le gestionnaire : chaque session relit les époques récentes.

Pour `K = 6` : environ 16 000 lectures par jour et par téléphone. Le quota gratuit de Firestore (50 000 lectures par
jour pour tout le projet) ne couvre donc que quelques téléphones ; au-delà, les lectures sont facturées. Les écritures
sont plafonnées à 200 par session (`maxPublishPerSession`). **Aucune mesure réelle n'a été faite.**

Conséquence de conception : une conversation nouvellement apprise (nouveau contact) ne récupère ses messages de plus de
~2 h qu'au balayage complet suivant (30 min au plus). Pas de notification poussée : ce n'est pas du FCM, c'est de la relève.

## Abus

Un compte anonyme peut écrire des documents valides en grand nombre : les règles bornent la taille et la durée de vie
mais pas le débit. Parades à mettre en place côté projet Firebase : App Check, quotas, alertes de budget. Côté client :
les enveloppes reçues passent par la validation du moteur (format, âge, doublons, seau à jetons) comme toute autre.

## Ce qui est testé, et ce qui ne l'est pas

Testé (JVM, en CI) :
- `RelayTransportTest` (18) : cycle de vie, `SENT` seulement si le relais a rangé l'enveloppe, idempotence, plafond
  d'écritures, relève limitée aux étiquettes écoutées, ni expirées ni déjà connues du moteur, rythme sous le seau à
  jetons du moteur, panne puis reprise, rotation, balayage complet limité. Trois mutations vérifiées (SENT à tort,
  poussée sans tenir compte de l'inventaire, balayage permanent).
- `FirestoreRestBackendTest` (14) contre un **faux** serveur HTTP : chemin et champs envoyés, aucun identifiant
  superflu, jeton anonyme réutilisé puis renouvelé sans nouveau compte, `409`, refus de règles, pannes, documents mal
  formés ignorés, aucune redirection suivie.
- `RelayScenarioTest` (5) : de vrais moteurs, qui ne communiquent que par un relais **en mémoire** : message différé
  avec accusé, room publique, panne puis reprise, pont radio ↔ Internet, rien d'intelligible dans le relais.
- `ListeningTagsTest` (2) : les étiquettes annoncées sont celles que le moteur sait recevoir.

**Non testé :** le vrai Firestore (formats de réponse réels, quotas, latence), les règles dans l'émulateur,
l'authentification anonyme réelle, les performances de la relève, la consommation de batterie et de données, un
téléphone. Les formats REST suivent la documentation de l'API ; une erreur de format ne se verra qu'à la première
exécution réelle.

## Relier à l'application (non fait)

À faire seulement après le test terrain et une décision explicite, car cela change ce que l'application promet :

1. Dépendance `implementation(project(":transport-internet"))` dans `:app`, et permission `INTERNET` dans le manifeste.
2. Réglage **désactivé par défaut** (« Utiliser un relais Internet »), expliqué, avec un interrupteur qui arrête le
   transport ; l'application doit rester entièrement fonctionnelle sans.
3. Dans `MeshRuntime` : créer `RelayTransport(FirestoreRestBackend(...), scope, RelayConfig(tags = engine::listeningTags))`
   et l'ajouter à la liste des transports quand le réglage est actif.
4. Mettre à jour `docs/privacy.*.md` (« l'application n'a pas la permission Internet » ne sera plus vrai), le guide
   utilisateur, `docs/threat-model.md` (T-33) et `ManifestPermissionsTest` (qui interdit aujourd'hui `INTERNET`).
5. Essai sur téléphones avec et sans relais (protocole mesh-23 étendu).

## Réutilisation du code Firebase de la PR #1

Réutilisé : l'idée de l'authentification anonyme, la forme des règles de validation, le principe d'un TTL.
Non réutilisé : le modèle de données (rooms, messages en clair, topics FCM), incompatible avec des enveloppes opaques ;
le SDK Firebase (remplacé par REST pour ne rien ajouter à l'APK ni dépendre de `google-services.json`).
