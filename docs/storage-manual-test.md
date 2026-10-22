# Test manuel : base chiffrée et sauvegarde désactivée (mesh-13)

Les tests JVM (`:store-sql`) couvrent le schéma, le store d'enveloppes, les blocages et les états libsignal.
Ce qui touche à SQLCipher, au Keystore et à `adb backup` ne se vérifie que sur Android. Les tests
instrumentés de `MeshDatabaseInstrumentedTest` ne sont pas lancés par la CI.

## 1. Tests instrumentés

```
./gradlew :app:connectedDebugAndroidTest --tests 'com.netpopup.storage.*'
```

Attendu : la base n'a pas d'en-tête SQLite en clair, aucune donnée lisible, l'ouverture sans clé échoue,
la perte de la clé est signalée sans effacement silencieux, `allowBackup` est désactivé.

## 2. Aucune donnée dans `adb backup`

1. Installer l'app debug, la lancer, bloquer un utilisateur, envoyer un message.
2. `adb backup -f backup.ab -noapk com.netpopup.debug`, confirmer sur le téléphone.
3. Attendu : `backup.ab` ne contient aucun fichier de l'app. Le décompresser (par exemple avec `android-backup-extractor`,
   `abe unpack backup.ab backup.tar`) puis `tar tf backup.tar` : aucune entrée `databases/`, `files/` ni `shared_prefs/`.

## 3. Fichier de base inexploitable

1. `adb shell run-as com.netpopup.debug cat databases/mesh.db > mesh.db` (debug seulement).
2. `sqlite3 mesh.db 'select * from blocked_user;'` → « file is not a database ».
3. `strings mesh.db | grep -i <pseudo ou texte envoyé>` → aucune ligne.

## 4. Clé et restauration

- Fichier de la clé enveloppée : `files/` n'est pas utilisé ; elle est dans `no_backup/mesh_db.key`.
- Supprimer ce fichier puis relancer : l'app doit signaler la perte de clé (`DatabaseKeyLostException`)
  et proposer la réinitialisation, sans effacer d'elle-même l'identité.
- Effacer les données de l'app (Paramètres) : base et clé disparaissent ensemble.

## 5. Points à observer sur matériel

- Temps d'ouverture à froid de la base (Keystore + SQLCipher) : doit rester hors du fil principal.
- Appareils sans StrongBox : repli sur le Keystore matériel standard (journaliser le cas).
- Migration : une installation précédente avec une liste de blocage dans le DataStore doit la retrouver dans la base, et le DataStore doit être vidé de cette clé.
