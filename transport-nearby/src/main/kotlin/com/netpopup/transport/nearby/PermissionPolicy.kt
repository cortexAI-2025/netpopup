package com.netpopup.transport.nearby

/**
 * Permissions à demander selon la version d'Android (architecture mesh v0.2, section 1).
 *
 * Source : https://developers.google.com/nearby/connections/android/get-started
 * (page mise à jour le 10 juin 2026). Réponse à la question ouverte de mesh-08 : sur
 * Android 12 (API 31), `ACCESS_FINE_LOCATION` est **encore exigée** ; elle ne l'est
 * plus à partir de l'API 32.
 *
 * Classe pure, sans dépendance Android : les noms de permissions sont des chaînes, le
 * niveau d'API est un paramètre. L'écran d'onboarding (mesh-16) l'utilise pour tout
 * demander en une fois ; le service de premier plan, pour refuser de démarrer sans elles.
 */
object PermissionPolicy {

    const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    const val BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
    const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    private const val ANDROID_10 = 29
    private const val ANDROID_12 = 31
    private const val ANDROID_12L = 32
    private const val ANDROID_13 = 33

    /**
     * Permissions « dangereuses » à demander à l'exécution pour que Nearby accepte de
     * diffuser et de chercher. Sans elles, l'API refuse de démarrer.
     *
     *  - API 29–30 : position précise (le scan Bluetooth l'exige) ;
     *  - API 31 : Bluetooth scan/advertise/connect, et **encore** la position précise ;
     *  - API 32 : Bluetooth scan/advertise/connect ;
     *  - API 33+ : idem, plus `NEARBY_WIFI_DEVICES` (montée en Wi-Fi après connexion).
     *
     * `NEARBY_WIFI_DEVICES` : la page Nearby ne la liste pas parmi les permissions
     * dangereuses, mais Android la classe ainsi depuis l'API 33 ; on la demande, à
     * confirmer sur appareil réel.
     */
    fun runtimePermissions(sdkInt: Int): List<String> = when {
        sdkInt < ANDROID_10 -> throw IllegalArgumentException("minSdk is 29, got $sdkInt")
        sdkInt < ANDROID_12 -> listOf(FINE_LOCATION)
        sdkInt == ANDROID_12 -> bluetooth() + FINE_LOCATION
        sdkInt == ANDROID_12L -> bluetooth()
        else -> bluetooth() + NEARBY_WIFI_DEVICES
    }

    /** Notification du service de premier plan : à partir de l'API 33, elle se demande aussi. */
    fun notificationPermissions(sdkInt: Int): List<String> =
        if (sdkInt >= ANDROID_13) listOf(POST_NOTIFICATIONS) else emptyList()

    /** Tout ce que l'onboarding demande en une fois. */
    fun allRequested(sdkInt: Int): List<String> = runtimePermissions(sdkInt) + notificationPermissions(sdkInt)

    /**
     * Permissions Nearby qu'il manque encore, d'après [granted]. La notification n'en fait
     * pas partie : refusée, le service tourne quand même, sans notification visible.
     */
    fun missing(sdkInt: Int, granted: Collection<String>): List<String> =
        runtimePermissions(sdkInt).filter { it !in granted }

    private fun bluetooth() = listOf(BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT)
}
