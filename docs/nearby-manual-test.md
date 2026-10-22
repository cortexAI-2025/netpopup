# Test manuel du transport Nearby (mesh-08, #9)

La logique du transport est testée sur la JVM (`NearbyTransportTest`, `PermissionPolicyTest`).
**Ce qui ne peut se vérifier que sur téléphones** : la radio, les permissions réelles, le service de
premier plan, la batterie. Ce protocole sert à le faire, à 2 puis à 3 appareils.

À ce stade l'app n'échange aucun message : elle ouvre des liens Nearby et affiche leur nombre dans la
notification du service. L'échange de messages arrive avec la migration de l'UI (mesh-15, #16).

## Matériel

- 3 téléphones Android avec les services Google Play, **de marques différentes** si possible, dont un
  sous Android 10 ou 11 (API 29-30), un sous Android 12 (API 31) et un sous Android 13 ou plus.
- Le même APK debug installé sur chacun (artefact `netpopup-debug-apk` de la CI).
- Wi-Fi d'infrastructure et données mobiles **coupés** sur au moins deux appareils (le but est de ne
  dépendre d'aucun réseau). Bluetooth et localisation activés.

## 1. Permissions

Au premier lancement, l'app demande les permissions en une fois.

| Android | Permissions attendues |
|---|---|
| 10-11 (API 29-30) | Position précise |
| 12 (API 31) | Bluetooth (appareils à proximité) **et position précise** |
| 12L (API 32) | Bluetooth (appareils à proximité) |
| 13+ (API 33+) | Bluetooth, appareils Wi-Fi à proximité, notifications |

- [ ] La liste demandée correspond à la ligne de l'appareil.
- [ ] **À vérifier sur l'Android 12 (API 31)** : sans la position précise, la découverte échoue-t-elle ?
      (La documentation l'exige encore sur cette version.)
- [ ] **À vérifier sur Android 13+** : `NEARBY_WIFI_DEVICES` est-elle bien demandée et nécessaire ?
- [ ] Refuser une permission : le service ne démarre pas et l'app ne plante pas.
- [ ] Android 14+ : accorder les permissions Bluetooth puis démarrer ne lève pas d'exception.

## 2. Service de premier plan

- [ ] Une notification « NetPopUp - Connected phones: 0 » apparaît, sans rien montrer d'un message.
- [ ] Elle est masquée sur l'écran verrouillé.
- [ ] Passer l'app en arrière-plan, écran éteint 10 minutes : la notification reste, le service tourne.
- [ ] Constructeurs agressifs (Xiaomi, Huawei, Oppo) : le service est-il tué en arrière-plan ? Noter
      les réglages d'économie d'énergie nécessaires.

## 3. Deux appareils

Poser A et B à 1 m l'un de l'autre.

- [ ] En moins de 2 minutes, la notification passe à « Connected phones: 1 » sur les deux.
- [ ] Éloigner B hors de portée : le nombre retombe à 0 sur A (noter le délai).
- [ ] Rapprocher : le lien se rétablit.
- [ ] **Rotation** : laisser A et B ensemble 12 minutes. Le lien doit se fermer entre 5 et 10 minutes
      puis se rouvrir après 1 minute de délai.

## 4. Trois appareils

- [ ] A, B et C à 1 m : chacun finit par voir 2 liens.
- [ ] Les trois s'accordent sans connexion en double ni boucle de reconnexion (regarder `adb logcat`).

## 5. Mesures à relever pour calibrer

À reporter dans le rapport du test terrain (mesh-23, #24).

| Mesure | Valeur par défaut | Relevé |
|---|---|---|
| Délai de découverte entre deux appareils immobiles | | |
| Cycle de diffusion (20 s actif / 40 s pause) | 20 s / 40 s | |
| Consommation de batterie par heure, écran éteint | | |
| Portée utile (mètres) en intérieur | | |
| Délai de détection d'un départ | | |
