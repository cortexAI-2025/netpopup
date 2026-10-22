# Protocole d'essai terrain — NetPopUp (mesh v0)

Ce document sépare ce qui est **démontré par des tests simulés** de ce qui **ne peut l'être que sur téléphones**.
Tant que la colonne « Téléphones » est vide, le point n'est pas validé.

## 1. Ce que couvrent les tests simulés (JVM, CI)

Le moteur (`:mesh-engine`) tourne contre un réseau de transports en boucle (`LoopbackNetwork`), une horloge
virtuelle et de vraies bases SQLite. Parcours couverts (`ScenarioTest`, `RoomScenarioTest`) :

| Parcours | Test simulé | Téléphones |
|---|---|---|
| Échange direct A↔B, ACK, état « acquitté » | oui | à faire |
| Relais A→B→C (B n'est pas destinataire) | oui | à faire |
| Livraison différée (B hors portée puis retour) | oui | à faire |
| Redémarrage d'un nœud (base rouverte) | oui | à faire |
| Expiration des messages | oui | à faire |
| Room privée : création, demande, admission, lecture par tous | oui | à faire |
| Rotation des liens (5–10 min) | partiel (fenêtres d'attente > rotation) | à faire |

**Limites des simulations** : pas de radio réelle (portée, interférences, débit, coupures), pas de Doze / économie
d'énergie, pas de permissions Android réelles, pas de Keystore ni de SQLCipher, pas de Compose, pas de
chiffrement de lien (le chiffrement de bout en bout existe, pas celui du lien Nearby).

## 2. Matériel

- 3 téléphones Android ≥ 10 (minSdk 29), idéalement trois versions : 10–11, 12–13, 14+.
- APK debug de la CI (artefact `netpopup-debug-apk`), installé sur les trois (A, B, C).
- Bluetooth et localisation activés selon la version (voir §3) ; **aucune connexion Internet requise**
  (mettre en mode avion puis réactiver Bluetooth pour le prouver).

## 3. Permissions à vérifier par version

| Android | Permissions demandées à l'exécution |
|---|---|
| 10–11 (API 29–30) | position précise (`ACCESS_FINE_LOCATION`) |
| 12–12L (API 31–32) | `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, position |
| 13+ (API 33+) | `NEARBY_WIFI_DEVICES` + permissions Bluetooth + `POST_NOTIFICATIONS` |

Contrôle : refuser chaque permission → l'app ne plante pas, le service ne démarre pas, l'état affiché est
« arrêté ». Accorder → le service démarre, la notification ne révèle aucun contenu.

## 4. Scénarios

Chaque scénario : noter modèle/version, heure, résultat, capture d'écran de l'état de livraison.

1. **Découverte** — A et B à 2 m. Attendu : « 1 pair » sous 60 s sur les deux. *Échec si* le compteur monte sans
   échange d'inventaire (le compteur n'est censé compter que des liens vérifiés).
2. **Message direct** — A et B échangent leurs liens de contact (copier/coller ou lien partagé), A écrit à B.
   Attendu : B reçoit ; A passe « envoyé » → « relayé » → « acquitté ». Noter les durées.
3. **Relais** — A et C hors de portée l'un de l'autre, B entre les deux. A écrit à C. Attendu : C reçoit via B,
   B ne peut pas lire le contenu (vérifier qu'aucune notification/aperçu ne l'affiche sur B).
4. **Différé** — A écrit à B alors que B est éteint ; rallumer B 10 min plus tard. Attendu : livraison à la reprise.
5. **Redémarrage** — tuer l'app sur A en plein échange (balayer + « forcer l'arrêt »), relancer.
   Attendu : historique intact, messages en attente réexpédiés, pas de doublons.
6. **Expiration** — message de durée minimale ; vérifier la disparition aux deux bouts et dans le store.
7. **Room privée** — A crée une room « ANY_MEMBER » et partage le lien en personne ; B demande l'accès ; A (ou un
   membre) accepte ; C rejoint via B. Attendu : tous lisent tout ; une personne n'ayant que le lien ne lit rien
   tant qu'elle n'est pas admise.
8. **Retrait** — le créateur retire un membre ; vérifier qu'il ne lit pas les messages suivants (rotation de clés).
9. **Endurance** — 3 téléphones, écran éteint, 2 h. Relever la consommation batterie, les coupures de service,
   le nombre de rotations de liens (une toutes les 5–10 min).

## 5. Données à relever

- Version Android, modèle, état batterie/économiseur, version APK (hash de commit).
- Délai découverte → inventaire vérifié ; débit effectif ; messages perdus/dupliqués.
- Tout crash : `adb logcat -b crash`. Jamais de contenu de message dans les rapports.

## 6. Critère d'acceptation v0

Les scénarios 1, 2, 3, 4, 5 et 7 réussissent sur au moins deux versions d'Android différentes, sans crash, sans
doublon, sans fuite de contenu en notification. Les écarts sont reportés dans une issue avant toute évolution du
protocole.
