package com.netpopup.v2.rooms

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Salon tel que mémorisé : nom d'affichage et clé maîtresse (jamais le mot de passe). */
class StoredRoom(val displayName: String, val master: ByteArray)

/** Où sont gardés les salons entre deux lancements. */
interface RoomStore {
    fun load(): List<StoredRoom>
    fun save(rooms: List<StoredRoom>)
}

class Room(val key: String, val displayName: String)

class RoomMessage(
    val id: String,
    val roomKey: String,
    val sender: String,
    /** Identifiant mesh authentique de l'émetteur (paquet signé), `null` pour nos propres messages. */
    val peerId: String?,
    val text: String,
    val timestampMs: Long,
    val mine: Boolean,
)

/**
 * État des salons : liste, messages, non-lus. Pure Kotlin (testée sur la JVM) ; le branchement Android est dans [RoomRepository].
 *
 * Les paquets d'un salon inconnu, mal formés, d'une autre clé ou altérés sont ignorés sans trace. Un message déjà vu (même
 * identifiant) n'est jamais ajouté deux fois, ce qui coupe les doublons dus aux retransmissions du mesh.
 */
class RoomCore(private val store: RoomStore, private val clock: () -> Long = System::currentTimeMillis) {

    private class Entry(val room: Room, val master: ByteArray, val tagHex: String) {
        val seen = LinkedHashSet<String>()
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    private val _rooms = MutableStateFlow<List<Room>>(emptyList())
    val rooms: StateFlow<List<Room>> = _rooms.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<RoomMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<RoomMessage>>> = _messages.asStateFlow()

    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unread: StateFlow<Map<String, Int>> = _unread.asStateFlow()

    init {
        val saved = try { store.load() } catch (e: Exception) { emptyList() }
        saved.forEach { add(it.displayName, it.master, persist = false) }
    }

    /** Ajoute (ou retrouve) le salon dont la clé maîtresse est [master]. */
    fun add(displayName: String, master: ByteArray, persist: Boolean = true): Room = synchronized(lock) {
        val key = RoomCrypto.normalizeName(displayName)
        val existing = entries[key]
        if (existing != null && existing.master.contentEquals(master)) return existing.room
        val entry = Entry(Room(key, displayName.trim().removePrefix("#").trim()), master, RoomCrypto.tag(master).toHex())
        entries[key] = entry
        publishRooms()
        if (persist) save()
        entry.room
    }

    fun leave(roomKey: String) = synchronized(lock) {
        if (entries.remove(roomKey) != null) {
            publishRooms()
            _messages.value = _messages.value - roomKey
            _unread.value = _unread.value - roomKey
            save()
        }
    }

    fun markRead(roomKey: String) {
        if ((_unread.value[roomKey] ?: 0) != 0) _unread.value = _unread.value - roomKey
    }

    /** Fabrique le paquet à diffuser pour [text] dans le salon, et l'ajoute à notre propre fil. `null` : salon inconnu ou texte vide. */
    fun compose(roomKey: String, nickname: String, text: String): String? = synchronized(lock) {
        val entry = entries[roomKey] ?: return null
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val id = RoomCrypto.newMessageId()
        val now = clock()
        val packet = RoomEnvelope.build(entry.master, nickname, clean, now, id)
        val idHex = id.toHex()
        entry.seen += idHex
        append(RoomMessage(idHex, roomKey, nickname, null, clean, now, mine = true), countUnread = false)
        packet
    }

    /**
     * Traite la charge utile d'un paquet de diffusion reçu de [peerId].
     * Renvoie `true` si c'était une enveloppe de salon (à ne pas afficher dans le fil public), quel qu'en soit le sort.
     */
    fun onPacket(peerId: String, payload: ByteArray): Boolean {
        if (!RoomEnvelope.looksLikeRoomPacket(payload)) return false
        val tagHex = RoomEnvelope.tagOf(payload)?.toHex() ?: return true
        synchronized(lock) {
            val entry = entries.values.firstOrNull { it.tagHex == tagHex } ?: return true
            val plain = RoomEnvelope.open(entry.master, payload) ?: return true
            val idHex = plain.id.toHex()
            if (!entry.seen.add(idHex)) return true
            if (entry.seen.size > MAX_SEEN) entry.seen.remove(entry.seen.first())
            val now = clock()
            val ts = plain.timestampMs.takeIf { it in (now - MAX_AGE_MS)..(now + MAX_SKEW_MS) } ?: now
            append(RoomMessage(idHex, entry.room.key, plain.nickname.ifBlank { peerId.take(4) }, peerId, plain.text, ts, mine = false), countUnread = true)
        }
        return true
    }

    private fun append(message: RoomMessage, countUnread: Boolean) {
        val current = _messages.value[message.roomKey].orEmpty()
        val next = (current + message).sortedBy { it.timestampMs }.takeLast(MAX_MESSAGES)
        _messages.value = _messages.value + (message.roomKey to next)
        if (countUnread) _unread.value = _unread.value + (message.roomKey to ((_unread.value[message.roomKey] ?: 0) + 1))
    }

    private fun publishRooms() { _rooms.value = entries.values.map { it.room } }

    private fun save() {
        try { store.save(entries.values.map { StoredRoom(it.room.displayName, it.master) }) } catch (e: Exception) { /* sans stockage : salons perdus au prochain lancement */ }
    }

    companion object {
        const val MAX_MESSAGES = 500
        private const val MAX_SEEN = 4000
        private const val MAX_AGE_MS = 3L * 24 * 3600 * 1000
        private const val MAX_SKEW_MS = 10L * 60 * 1000
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
