# Essai terrain n° 1 — NetPopUp V2 (référence fonctionnelle)

Premier essai positif de NetPopUp V2 sur de vrais téléphones. **Cette version est la référence à préserver.**

| Élément | Valeur |
|---|---|
| Commit | `0ec03bd9ebbc7bfecf6e3be0eb4551c1e68d5cee` (branche `feature/netpopup-v2-ble-mesh`) |
| Pointeur figé | branche `reference/netpopup-v2-first-field-success` (le tag `netpopup-v2-first-field-success` n'a pas pu être poussé : connexion coupée par le proxy de l'environnement ; commande pour le créer : `git tag -a netpopup-v2-first-field-success 0ec03bd -m "premier succès terrain" && git push origin netpopup-v2-first-field-success`) |
| Build CI | run 41294566927 (workflow « NetPopUp V2 ») : compilation et 851 tests unitaires réussis |
| Fichier testé | `app-universal-debug.apk` — SHA-256 attendu `2368e0d6098824b6de75ca1a788bfc7f4fbef00099da7b9c15ee11b2f9d41571` |
| Artefact GitHub | `netpopup-v2-debug-apk` du run ci-dessus ; **expire le 01/12/2026** (rétention 30 jours) |

## Conditions rapportées par l'utilisateur (non mesurées par nous)
- Au moins deux téléphones Samsung (l'un est un Galaxy A14 SM-A145R, Android 15, One UI 7 ; le modèle et la version du second ne sont pas connus).
- Sans 4G ni Wi-Fi ; Bluetooth et Localisation activés.
- Résultat : « l'application fonctionne très bien ».

## Ce qu'on ne sait pas
Quels scénarios de `RECETTE.md` ont été joués (salon privé, mauvais mot de passe, doublons, redémarrage), à quelle distance,
combien de messages, et si la version Android 11 a été essayée. **À préciser** avant de dire que ces points sont validés.

## Vérifier que le fichier testé est bien cette référence
`sha256sum app-universal-debug.apk` doit donner la valeur ci-dessus. Sinon, le fichier vient d'un autre build : notez son SHA-256.
Conservez une copie du fichier testé : l'artefact GitHub expire.
