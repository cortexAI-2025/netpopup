# NetPopUp V2 — recette bêta sur deux téléphones (≈15 minutes)

**État.** La version de référence (`0ec03bd`) a fonctionné sur des Samsung (voir `docs/FIELD-TEST-1.md`). Cette recette sert à confirmer que
la version **allégée** (release) n'a rien perdu. Seul un essai sur de vrais téléphones le prouve : la compilation et les tests ne le prouvent pas.

Matériel : 2 téléphones (idéalement Android 11 et Android 15), Bluetooth et Localisation activés. Pas de Wi-Fi ni de 4G nécessaires.

## Installation
1. Récupérez l'artefact `netpopup-v2-release-apk` du build CI (zip) et décompressez-le.
2. Sur un téléphone 64 bits (la quasi-totalité des Samsung récents) : `app-arm64-v8a-release.apk`. Sinon, `app-universal-release.apk`.
3. Copiez le fichier, ouvrez-le, autorisez « installer des applications inconnues ».
   Si Android refuse (« signature différente ») : désinstallez d'abord l'ancienne version de NetPopUp V2 (les APK debug et release n'ont pas la même clé).
4. Premier lancement : acceptez Bluetooth, Appareils à proximité, Localisation, Notifications. Quand l'application le propose, autorisez
   l'exécution en arrière-plan (sans cela, Samsung peut couper le Bluetooth écran éteint).

## Scénario
| # | Action | Résultat attendu | OK ? |
|---|---|---|---|
| 1 | A et B ouvrent NetPopUp côte à côte | « Bluetooth activé » ; « Téléphones détectés » passe à 1 ; l'autre pseudo apparaît dans « Personnes proches » (direct) | |
| 2 | Couper le Bluetooth sur A | A affiche « Bluetooth désactivé » + bouton « Réglages Bluetooth » ; le rallumer rétablit la détection | |
| 3 | Couper la Localisation sur A | A affiche l'avertissement de localisation + bouton ; la rallumer le fait disparaître | |
| 4 | **Discussion publique** : A écrit « test public » | B le reçoit dans « Discussion publique » | |
| 5 | A : **Créer un salon** `test`, mot de passe `motdepasse-123` (2 fois) | le salon s'ouvre | |
| 6 | B : **Rejoindre un salon** `test`, même mot de passe | le salon s'ouvre | |
| 7 | A écrit « bonjour salon » | B le reçoit dans le salon, et **pas** dans la discussion publique | |
| 8 | B répond | A le reçoit | |
| 9 | A écrit 3 messages de suite | chacun n'apparaît qu'une fois chez B | |
| 10 | B quitte puis rejoint `test` avec un **autre** mot de passe | B ne voit rien de A, A ne voit rien de B | |
| 11 | Écran éteint 5 minutes sur B, puis A écrit | B reçoit à la réactivation (si non : noter la marque et la version, c'est la restriction de batterie Samsung) | |
| 12 | Fermer et rouvrir NetPopUp sur A | le salon est toujours listé (les anciens messages, eux, ne sont pas conservés) | |

## À noter en cas d'échec
Modèles et versions Android, étape en échec, capture d'écran, et si la **version de référence** (debug `0ec03bd`) fonctionne toujours au même endroit.
