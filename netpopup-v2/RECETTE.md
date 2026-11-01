# NetPopUp V2 — recette sur deux téléphones (15 minutes)

**Avant de commencer.** Aucun test Bluetooth réel de cette version n'a été fait par ses développeurs. Cette recette est le premier.
Matériel : 2 Samsung (ex. Android 11 et Android 15), Bluetooth et Position activés, Internet inutile (mode avion + Bluetooth réactivé possible).
Désinstallez au besoin l'ancien NetPopUp. bitchat peut rester installé : les deux applications cohabitent.

## Installation
1. Téléchargez l'artefact `netpopup-v2-debug-apk` (zip) de la CI, décompressez-le, prenez `app-universal-debug.apk`.
2. Copiez-le sur chaque téléphone, ouvrez-le, autorisez « installer des applications inconnues » pour l'application utilisée.
3. Premier lancement : acceptez toutes les permissions (Bluetooth, Appareils à proximité, Position, Notifications) et laissez le service
   tourner. Refusez l'optimisation de batterie si l'application le propose.

## Scénario (reprend le critère de réussite)
| # | Action | Résultat attendu | OK ? |
|---|---|---|---|
| 1 | A et B ouvrent NetPopUp côte à côte | Bluetooth « activé », « Téléphones détectés » passe à 1 sous ~1 min | |
| 2 | A : **Créer un salon** `test`, mot de passe `motdepasse-123` (2 fois) | le salon s'ouvre | |
| 3 | B : **Rejoindre un salon** `test`, même mot de passe | le salon s'ouvre | |
| 4 | A écrit « bonjour » | B le reçoit | |
| 5 | B répond « reçu » | A le reçoit | |
| 6 | Doublon : A écrit 3 messages de suite | chacun n'apparaît qu'une fois chez B | |
| 7 | Isolation : B quitte, puis rejoint `test` avec un **autre** mot de passe | B ne voit plus rien de A, A ne voit rien de B | |
| 8 | Fermeture : fermer et rouvrir NetPopUp sur A | le salon est toujours là, la conversation reprend | |

## À noter en cas d'échec
Modèle et version d'Android des deux téléphones, étape en échec, ce que l'écran affiche (capture), et si bitchat fonctionne toujours
entre ces deux mêmes téléphones au même moment.
