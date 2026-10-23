# NetPopUp — politique de confidentialité (v0)

**Résumé : NetPopUp ne collecte rien.** Il n'y a aucun serveur, aucun compte, aucune publicité, aucun outil de
mesure d'audience. L'application n'a même pas la permission Internet.

## Données sur ton téléphone
Identité cryptographique, contacts, rooms, messages et messages en transit sont stockés **localement**, dans une base
chiffrée (SQLCipher) dont la clé est protégée par l'Android Keystore. La sauvegarde Android est désactivée. Les
messages expirent (6 h par défaut, 24 h au plus) et sont effacés. Désinstaller l'application supprime tout.

## Ce que voient les autres téléphones
| Qui | Voit |
|---|---|
| Ton destinataire / les membres de ta room | Le texte, ton pseudo |
| Un téléphone qui relaie ton message | Qu'une enveloppe chiffrée transite : taille approximative, heure, étiquette de destination qui change chaque heure. **Pas** le texte, ni ton pseudo, ni la conversation |
| Quelqu'un qui écoute la radio | Qu'un téléphone NetPopUp est à portée, à quel moment, et un volume d'échanges |
| Tout pair à portée, dans la room locale publique | **Tout** : texte et pseudo |

## Limites à connaître
- Le **chiffrement du lien radio** entre deux téléphones n'est pas encore en place dans cette version : les
  messages restent chiffrés de bout en bout, mais les en-têtes d'enveloppes (type, étiquette tournante,
  horodatage) circulent en clair sur le lien.
- La room locale est **publique** par conception.
- Un membre retiré d'une room peut lire quelques messages déjà en circulation jusqu'à leur expiration.
- Nearby Connections est fourni par les services Google Play ; son fonctionnement interne n'est pas ouvert.
- Un futur relais Internet optionnel, s'il est ajouté, verrait uniquement des compteurs anonymes d'activité par
  étiquette et par heure, et sera désactivable.

## Contact et licence
Logiciel libre GNU AGPL-3.0. Code source et signalement de problèmes : https://github.com/cortexAI-2025/netpopup
