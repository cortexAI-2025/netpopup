# Protocole d'essai terrain — NetPopUp mesh v0 (mesh-23, issue #24)

But : mesurer le mesh **en conditions réelles** avant d'ajouter un transport Internet (mesh-24, #25) ou de changer
de routage. **Note :** le propriétaire du projet a fait démarrer mesh-24 (module isolé, non relié à l'application) avant ce rapport ; en revanche, relier le relais à l'application attend toujours le rapport de la §9.

Rien dans ce document n'a encore été exécuté sur téléphone : la colonne « Résultat » reste vide jusqu'à l'essai.

## 1. Ce que prouvent les tests simulés, et ce qu'ils ne prouvent pas

Le moteur (`:mesh-engine`) tourne en CI contre un réseau en boucle, une horloge virtuelle et de vraies bases SQLite
(`ScenarioTest`, `RoomScenarioTest`, `ModerationScenarioTest`). L'interface est testée sous Robolectric/Compose.

| Parcours | Test simulé (CI) | Téléphones |
|---|---|---|
| Échange direct A↔B, ACK, état « acquitté » | oui | à faire (S2) |
| Relais A→B→C | oui | à faire (S3) |
| Livraison différée | oui | à faire (S4) |
| Redémarrage d'un nœud | oui | à faire (S5) |
| Expiration | oui | à faire (S6) |
| Room privée : création, demande, admission, lecture par tous (3 membres) | oui | à faire (S7) |
| Retrait d'un membre / rotation | cryptographie testée ; pas de parcours moteur complet | à faire (S8) |
| Blocage local, notifications sans contenu, réglages | oui (Robolectric) | à faire (S10) |
| Radio réelle, portée, débit, coupures | **non** | à faire (S1, S9, S11-S13) |
| Permissions Android réelles par version | liste vérifiée par test ; **octroi réel non testé** | à faire (§4) |
| Service au premier plan, Doze, tueurs de services constructeurs | **non** | à faire (S9, §6) |
| SQLCipher + Keystore réels | **non** (SQLite JDBC en test) | à faire (S5) |

Limites connues : pas de chiffrement du lien Nearby (le bout-en-bout existe) ; `hopCount` et latence ne sont pas
exposés par l'application, donc **mesurés à la main** (§7).

## 2. Matériel

- **Au moins 5 téléphones de marques différentes** (ex. Samsung, Xiaomi/Redmi, Google Pixel, Huawei/Honor ou
  OnePlus/Oppo, Motorola). Couvrir au minimum Android 10-11, 12-12L, 13, 14 ; noter modèle, version, niveau de batterie.
- APK debug de la CI (artefact `netpopup-debug-apk`, identifiant `com.netpopup.debug`). Vérifier le SHA-256 affiché
  dans le résumé du run avant installation :
  `sha256sum netpopup-debug.apk` ; installation : `adb install -r netpopup-debug.apk` (ou transfert de fichier).
- Aucune connexion Internet requise : mode avion + Bluetooth réactivé sur chaque appareil.
- Un chronomètre par binôme, un tableur (§8), `adb logcat -b crash` pour les plantages (jamais de contenu de message
  dans les rapports).

## 3. Préparation

1. Installer l'APK, lancer, parcourir l'onboarding, accorder les permissions. Noter tout écart (§4).
2. Désactiver l'optimisation de batterie pour NetPopUp (et verrouiller l'app dans les récentes sur les marques qui
   tuent les services) **uniquement pour la série « nominale »** ; la série S9 teste le cas par défaut.
3. Heure automatique activée partout. Pour S12, désynchroniser volontairement un téléphone.

## 4. Permissions par version d'Android (à contrôler sur appareil)

| Android | Attendu à l'onboarding |
|---|---|
| 10-11 (API 29-30) | position précise |
| 12 (API 31) | Bluetooth (scan, advertise, connect) **et** position précise + approximative (si l'utilisateur n'accorde que l'approximative, le mesh reste bloqué et l'app l'indique) |
| 12L (API 32) | Bluetooth (scan, advertise, connect) |
| 13+ (API 33+) | Bluetooth + appareils Wi-Fi proches + notifications |

Pour chaque appareil : accorder tout → le service démarre ; refuser chaque permission → l'app ne plante pas, le
service ne démarre pas, l'onboarding propose « Try again » / réglages Android / « Continue without the mesh ». À confirmer :
`NEARBY_WIFI_DEVICES` est-elle réellement demandée/nécessaire sur Android 13+ ?

## 5. Scénarios

Chaque scénario : date, appareils (A, B, C…), résultat, durée, anomalies. Un scénario n'est « validé » que s'il
réussit sur **au moins deux versions d'Android et deux marques**.

| # | Scénario | Mode | Critère de réussite |
|---|---|---|---|
| S1 | **Découverte** A-B à 2 m | statique | « 1 phone(s) in range » sous 60 s des deux côtés ; le compteur ne monte pas sans échange d'inventaire |
| S2 | **Message direct** après échange de liens de contact (copier/coller) | statique | B reçoit ; A passe en file → relayé → **acquitté** ; durées notées |
| S3 | **Relais** A et C hors de portée, B entre eux ; A écrit à C | statique | C reçoit via B ; B ne montre aucun contenu (notification, écran) |
| S4 | **Différé** : A écrit alors que B est éteint, B rallumé 10 min plus tard | statique | livraison à la reprise, sans doublon |
| S5 | **Redémarrage** : forcer l'arrêt de l'app sur A en plein échange, relancer | statique | historique intact, messages en attente réexpédiés, pas de doublon ; base lisible (Keystore OK) |
| S6 | **Expiration** : durée 1 h dans Réglages, attendre | statique | disparition aux deux bouts |
| S7 | **Room privée** : A crée (tout membre), partage le lien en personne ; B demande ; A accepte ; C rejoint via B | statique | tous lisent tout ; qui n'a que le lien ne lit rien tant qu'il n'est pas admis |
| S8 | **Retrait** : le créateur retire un membre | statique | le retiré ne lit pas les messages suivants |
| S9 | **Arrière-plan** : écran éteint, app non ouverte, 30 min puis 2 h | statique | le service survit ; messages reçus ; noter marque/modèle qui le tue (§6) |
| S10 | **Réglages/blocage** : bloquer un expéditeur, couper les notifications, changer la durée | statique | message masqué, notification « New message » seulement, pas de notification pour la conversation ouverte |
| S11 | **Mouvement** : 5 téléphones, groupes qui se croisent (marche) pendant 30 min | mouvement | taux de livraison, latence, rotation des liens (une toutes les 5-10 min) sans perte |
| S12 | **Horloge décalée** : un téléphone avancé de 3 h | statique | avertissement d'horloge dans Réglages après quelques messages ; aucun message accepté de cet appareil |
| S13 | **Foule** : ≥ 5 téléphones dans une pièce, tous écrivent dans la room locale | foule | pas de saturation (seau à jetons), pas de plantage, consommation notée |

## 6. Arrière-plan par marque

Pour chaque marque, noter : le service au premier plan survit-il écran éteint 30 min / 2 h ? Que faut-il régler
(optimisation de batterie, démarrage automatique, « verrouiller dans les récentes ») ? Cas à vérifier en priorité :
Xiaomi/MIUI-HyperOS, Huawei/EMUI, Oppo-OnePlus/ColorOS, Samsung (« applications en veille »). Consigner la
procédure qui fonctionne : elle ira dans le guide utilisateur.

## 7. Mesures

| Mesure | Comment | Remarque |
|---|---|---|
| Taux de livraison | messages reçus / envoyés par scénario | décompte manuel sur les écrans |
| Latence | chronomètre : envoi → apparition chez le destinataire | par saut si possible |
| `hopCount` à la livraison | **non exposé** par l'app : déduire du nombre de téléphones traversés (S3, S11) | candidat à une instrumentation si le besoin se confirme |
| Batterie par heure | % consommé sur 2 h écran éteint (S9), même modèle avec/sans mesh | |
| Durée des connexions Nearby | durée entre « 1 phone in range » et la perte | observer la rotation 5-10 min |
| Calibrage des cycles | essayer cycles advertising/discovery plus courts/longs (config) | à documenter, pas à régler en séance |

## 8. Tableur de relevés (colonnes)

`date, scénario, appareil, marque, modèle, Android, batterie début, batterie fin, envoyés, reçus, latence médiane (s), latence max (s), sauts, plantage (oui/non), notes`

## 9. Rapport (livrable de mesh-23)

À rédiger après les essais, dans `docs/field-test-report.md` :

1. Appareils et versions testés ; scénarios exécutés et résultat (validé / partiel / échec) avec les relevés.
2. Mesures de la §7 avec leurs limites.
3. Comportement en arrière-plan par marque et procédures de contournement.
4. Défauts trouvés (une issue chacun) ; permissions à corriger.
5. **Recommandations** : plafond de sauts authentifié ou Spray-and-Wait, quotas, calibrage des cycles, besoin d'un
   transport Internet (mesh-24) — chiffres à l'appui.
6. Décision : mesh-24 peut-il démarrer ? (oui seulement si S1-S5 et S7 sont validés et que les limites ci-dessus sont acceptées)

## 10. Critère d'acceptation v0

S1, S2, S3, S4, S5 et S7 réussissent sur au moins deux versions d'Android différentes et deux marques, sans plantage,
sans doublon, sans fuite de contenu en notification ; les écarts sont reportés en issues avant toute évolution du
protocole.
