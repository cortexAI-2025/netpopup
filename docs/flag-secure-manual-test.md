# Test manuel : FLAG_SECURE (mesh-14)

`FLAG_SECURE` est posé par `SecureWindow.Callbacks` avant `onCreate` de chaque activité
(`NetPopUpApplication`), et répété dans `MainActivity` avant `setContent`.
Le test instrumenté `SecureWindowInstrumentedTest` vérifie le drapeau ; seuls les essais ci-dessous
prouvent l'effet réel, qui dépend du téléphone.

## À vérifier sur un appareil (un par version : Android 10, 12, 14 si possible)

1. **Capture d'écran** : écran de conversation ouvert, boutons du téléphone. Attendu : message
   « Impossible de prendre une capture d'écran » ou capture noire.
2. **Enregistrement d'écran** (réglages rapides) : lancer, ouvrir l'app, arrêter. Attendu : la vidéo
   est noire pendant que l'app est au premier plan.
3. **Applications récentes** : ouvrir l'app, revenir à l'accueil, ouvrir la liste des récentes.
   Attendu : la vignette de l'app est vide ou noire.
4. **Menus et dialogues** (ce sont des fenêtres séparées) : ouvrir le menu d'un message (appui long :
   bloquer, signaler) puis tenter une capture. Attendu : l'écran reste noir, menu compris.
   Faire de même avec tout dialogue ou menu ajouté plus tard.
5. **Projection d'écran / Cast / `adb exec-out screencap -p`** : l'image est noire pour la fenêtre de l'app.
6. **Constructeurs** : noter les téléphones où une capture passe malgré tout (certaines surcouches
   d'accessibilité ou ROM modifiées ignorent le drapeau).

## À ne pas oublier

- Les notifications ne contiennent aucun texte de message (mesh-17) : `FLAG_SECURE` ne les couvre pas.
- Les `Toast` système sont hors fenêtre de l'app : ne jamais y mettre de contenu de message.
- Un lecteur d'écran (TalkBack) lit le contenu : c'est voulu, c'est l'accessibilité.
