# NetPopUp — guide utilisateur (v0)

NetPopUp envoie des messages **de téléphone à téléphone**, sans compte, sans serveur et sans Internet.
Chaque téléphone transmet aussi les messages des autres : un message peut ainsi traverser plusieurs
téléphones avant d'arriver.

## Comment ça marche
- Les téléphones proches se découvrent par Bluetooth / Wi-Fi (Nearby). Garde l'application ouverte une fois, puis
  elle continue en arrière-plan grâce à une **notification permanente** (elle n'affiche jamais de message).
- Un message **n'est pas instantané** : il part quand un téléphone qui peut le transmettre passe à portée.
  Il peut arriver quelques minutes — ou heures — plus tard.
- Les messages sont **éphémères** : 6 h par défaut, 24 h au maximum, puis ils disparaissent de tous les téléphones.

## Trois sortes de conversations
1. **Contact (1:1)** — chiffré de bout en bout. Échange de **liens de contact** en personne (copier/coller ou QR).
   Compare l'empreinte (numéro de sécurité) avec ton contact pour être sûr de qui il est.
   États d'un message : *en file* (chez toi), *relayé* (confié à au moins un téléphone), *acquitté* (le destinataire l'a reçu).
2. **Room privée** — chiffrée, pour un groupe (32 membres au plus). On y entre avec un **lien d'invitation**
   remis en personne. Le lien ne donne **aucun accès** : un membre doit accepter la demande. À la création, tu choisis
   qui peut accepter : *tout membre* ou *le créateur seulement*. Seul le créateur retire un membre.
3. **Room locale publique** — ouverte à tous ceux à portée. **Tout le monde peut la lire** ; ne t'en sers pas pour
   un message privé. Elle est marquée « public » en permanence.

## Permissions demandées
| Android | Pourquoi |
|---|---|
| 10-11 | Position (exigée par le Bluetooth pour chercher des téléphones ; ta position n'est ni enregistrée ni envoyée) |
| 12 | Bluetooth (chercher, être visible, se connecter) + position |
| 13 et + | Bluetooth + appareils Wi-Fi proches + notifications |
Si tu refuses, l'application reste consultable mais n'échange plus rien. Tu peux réactiver dans les réglages Android.

## Ce qu'il faut savoir
- **Portée limitée** : quelques mètres à quelques dizaines de mètres, moins avec des murs.
- **Pas de récupération** : si tu perds ton téléphone ou réinstalles, tes contacts et rooms sont perdus.
  La sauvegarde Android est désactivée exprès.
- **Captures d'écran bloquées** dans l'application.
- **Membre retiré** : il peut lire quelques messages déjà en circulation jusqu'à leur expiration.
- **Qui est à portée est observable** par quelqu'un qui écoute la radio, même sans lire les messages.

## Licence et code source
NetPopUp est un logiciel libre sous licence **GNU AGPL-3.0**. Code source : https://github.com/cortexAI-2025/netpopup
