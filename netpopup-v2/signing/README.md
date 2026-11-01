# Clé de signature **bêta** (non secrète)

`netpopup-beta.jks` signe les APK **bêta** pour qu'une version puisse être installée par-dessus la précédente.
Son mot de passe (`netpopup-beta`) est public, dans le dépôt : **n'importe qui peut donc signer un APK avec cette clé**.
Elle ne convient ni à une publication (Play Store, F-Droid, GitHub Releases officielles) ni à une confiance durable.
Pour une version publique, le propriétaire du projet crée sa propre clé, la garde hors du dépôt et la fournit à la CI par secret.
