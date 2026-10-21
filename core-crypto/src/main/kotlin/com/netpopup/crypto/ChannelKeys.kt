package com.netpopup.crypto

/**
 * Clés d'une conversation dérivées de son secret partagé (secret de room de 256 bits,
 * ou `S = DH(IK_A, IK_B)` en 1:1) par HKDF-SHA256 avec séparation de domaine.
 *
 * `tagKey` sert aux étiquettes de destination tournantes, `envKey` à la couche
 * extérieure : les deux ne se déduisent pas l'une de l'autre.
 */
class ChannelKeys private constructor(val tagKey: ByteArray, val envKey: ByteArray) {
    companion object {
        private const val SALT = "netpopup/v0/channel"

        fun derive(secret: ByteArray, scope: String): ChannelKeys {
            require(secret.size >= 32) { "channel secret must be at least 32 bytes" }
            val salt = SALT.toByteArray()
            fun key(label: String) = Hkdf.derive(secret, salt, "$scope/$label".toByteArray(), 32)
            return ChannelKeys(tagKey = key("tag"), envKey = key("env"))
        }
    }
}
