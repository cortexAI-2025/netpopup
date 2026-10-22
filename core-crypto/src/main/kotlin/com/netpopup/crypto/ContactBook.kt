package com.netpopup.crypto

import org.signal.libsignal.protocol.IdentityKey

enum class ContactTrust {
    /** Reçu par `ANNOUNCE` dans une room : authentique vis-à-vis de sa clé, mais personne n'a vu la personne. */
    ANNOUNCED,

    /** Reçu par QR en personne, ou numéro de sécurité comparé. */
    VERIFIED,
}

class Contact(val bundle: ContactBundle, val trust: ContactTrust) {
    val identityKey: IdentityKey get() = bundle.identityKey
    val name: String get() = bundle.name
}

/**
 * Carnet de contacts en mémoire (la persistance chiffrée vient avec mesh-13). Un contact
 * est identifié par sa clé d'identité, jamais par son pseudo : deux contacts de même nom
 * restent distincts, et une annonce ne peut ni écraser un contact vérifié ni le rétrograder.
 */
class ContactBook {
    private val contacts = LinkedHashMap<String, Contact>()

    fun all(): List<Contact> = contacts.values.toList()

    fun find(identity: IdentityKey): Contact? = contacts[key(identity)]

    fun addFromAnnounce(bundle: ContactBundle): Contact = put(bundle, ContactTrust.ANNOUNCED)

    fun addFromQr(bundle: ContactBundle): Contact = put(bundle, ContactTrust.VERIFIED)

    fun markVerified(identity: IdentityKey) {
        val c = find(identity) ?: return
        contacts[key(identity)] = Contact(c.bundle, ContactTrust.VERIFIED)
    }

    private fun put(bundle: ContactBundle, trust: ContactTrust): Contact {
        val existing = find(bundle.identityKey)
        val merged = when {
            existing == null -> Contact(bundle, trust)
            // Un bundle plus récent (pré-clé renouvelée) remplace l'ancien, sans baisser la confiance.
            else -> Contact(bundle, maxOf(existing.trust, trust))
        }
        contacts[key(bundle.identityKey)] = merged
        return merged
    }

    private fun key(identity: IdentityKey) = identity.serialize().joinToString("") { "%02x".format(it) }
}
