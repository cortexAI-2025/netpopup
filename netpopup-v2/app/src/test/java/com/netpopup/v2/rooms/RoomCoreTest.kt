package com.netpopup.v2.rooms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomCoreTest {

    private class MemoryStore : RoomStore {
        var saved: List<StoredRoom> = emptyList()
        override fun load() = saved
        override fun save(rooms: List<StoredRoom>) { saved = rooms }
    }

    private var now = 1_800_000_000_000L
    private fun core(store: RoomStore = MemoryStore()) = RoomCore(store) { now }

    private val keyA = RoomCrypto.deriveMasterKey("Secours", "correct horse battery staple")
    private val keyB = RoomCrypto.deriveMasterKey("Secours", "un autre mot de passe")

    // ── Cryptographie ────────────────────────────────────────────────────────

    @Test fun `key derivation is deterministic and depends on name and password`() {
        assertArrayEquals(keyA, RoomCrypto.deriveMasterKey("  SECOURS ", "correct horse battery staple"))
        assertArrayEquals(keyA, RoomCrypto.deriveMasterKey("#secours", "correct horse battery staple"))
        assertFalse(keyA.contentEquals(keyB))
        assertFalse(keyA.contentEquals(RoomCrypto.deriveMasterKey("Autre", "correct horse battery staple")))
        assertEquals(32, keyA.size)
    }

    @Test fun `the room tag reveals neither the name nor the password`() {
        val tag = RoomCrypto.tag(keyA)
        assertEquals(8, tag.size)
        val text = String(tag, Charsets.ISO_8859_1)
        assertFalse(text.contains("secours", ignoreCase = true))
        assertFalse(keyA.contentEquals(tag.copyOf(32)))
    }

    @Test fun `sealing round-trips and a wrong key or a tampered message cannot open`() {
        val sealed = RoomCrypto.seal(keyA, "bonjour".toByteArray())
        assertArrayEquals("bonjour".toByteArray(), RoomCrypto.open(keyA, sealed))
        assertNull(RoomCrypto.open(keyB, sealed))
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(RoomCrypto.open(keyA, tampered))
        assertNull(RoomCrypto.open(keyA, ByteArray(5)))
    }

    @Test fun `every seal uses a fresh nonce`() {
        assertFalse(RoomCrypto.seal(keyA, "x".toByteArray()).contentEquals(RoomCrypto.seal(keyA, "x".toByteArray())))
    }

    @Test fun `the ciphertext does not contain the plaintext`() {
        val packet = RoomEnvelope.build(keyA, "Alice", "rendez-vous au pont", now)
        assertTrue(packet.startsWith("NPR1:"))
        assertFalse(packet.contains("pont"))
        assertFalse(packet.contains("Alice"))
    }

    // ── Salons ───────────────────────────────────────────────────────────────

    @Test fun `two phones with the same name and password exchange messages both ways`() {
        val a = core(); val b = core()
        a.add("Secours", keyA); b.add("secours", keyA)
        val toB = a.compose("secours", "Alice", "Je suis au point A")!!
        assertTrue(b.onPacket("peerA", toB.toByteArray()))
        val toA = b.compose("secours", "Bob", "Reçu, j'arrive")!!
        assertTrue(a.onPacket("peerB", toA.toByteArray()))
        assertEquals(listOf("Je suis au point A", "Reçu, j'arrive"), a.messages.value["secours"]!!.map { it.text })
        assertEquals(listOf("Je suis au point A", "Reçu, j'arrive"), b.messages.value["secours"]!!.map { it.text })
        assertEquals("Alice", b.messages.value["secours"]!![0].sender)
        assertEquals("peerA", b.messages.value["secours"]!![0].peerId)
        assertTrue(a.messages.value["secours"]!![0].mine)
    }

    @Test fun `a phone with the wrong password reads nothing`() {
        val a = core(); val intruder = core()
        a.add("Secours", keyA); intruder.add("Secours", keyB)
        val packet = a.compose("secours", "Alice", "secret")!!
        assertTrue(intruder.onPacket("peerA", packet.toByteArray()))     // reconnu comme paquet de salon, non affiché
        assertTrue(intruder.messages.value.isEmpty())
    }

    @Test fun `a phone outside the room ignores the packet but still treats it as a room packet`() {
        val a = core(); val outsider = core()
        a.add("Secours", keyA)
        val packet = a.compose("secours", "Alice", "secret")!!
        assertTrue(outsider.onPacket("peerA", packet.toByteArray()))
        assertTrue(outsider.rooms.value.isEmpty() && outsider.messages.value.isEmpty())
    }

    @Test fun `rooms are isolated from each other`() {
        val keyOther = RoomCrypto.deriveMasterKey("Famille", "correct horse battery staple")
        val a = core(); val b = core()
        a.add("Secours", keyA); a.add("Famille", keyOther)
        b.add("Secours", keyA); b.add("Famille", keyOther)
        b.onPacket("p", a.compose("famille", "Alice", "pour la famille")!!.toByteArray())
        assertEquals(listOf("pour la famille"), b.messages.value["famille"]!!.map { it.text })
        assertNull(b.messages.value["secours"])
    }

    @Test fun `a retransmitted message appears once`() {
        val a = core(); val b = core()
        a.add("Secours", keyA); b.add("Secours", keyA)
        val packet = a.compose("secours", "Alice", "une seule fois")!!.toByteArray()
        repeat(5) { b.onPacket("relay$it", packet) }
        assertEquals(1, b.messages.value["secours"]!!.size)
        assertEquals(1, b.unread.value["secours"])
    }

    @Test fun `our own message echoed back by the mesh is not duplicated`() {
        val a = core(); a.add("Secours", keyA)
        val packet = a.compose("secours", "Alice", "écho")!!.toByteArray()
        a.onPacket("relayX", packet)
        assertEquals(1, a.messages.value["secours"]!!.size)
    }

    @Test fun `garbage and non room payloads are not consumed or crash`() {
        val a = core(); a.add("Secours", keyA)
        assertFalse(a.onPacket("p", "bonjour tout le monde".toByteArray()))
        assertTrue(a.onPacket("p", "NPR1:!!!pas-du-base64".toByteArray()))
        assertTrue(a.onPacket("p", "NPR1:AAAA".toByteArray()))
        assertTrue(a.messages.value.isEmpty())
    }

    @Test fun `empty text is not sent and unknown rooms cannot send`() {
        val a = core(); a.add("Secours", keyA)
        assertNull(a.compose("secours", "Alice", "   "))
        assertNull(a.compose("inconnu", "Alice", "x"))
    }

    @Test fun `unread counts rise on incoming messages and clear when read`() {
        val a = core(); val b = core()
        a.add("Secours", keyA); b.add("Secours", keyA)
        b.onPacket("p", a.compose("secours", "Alice", "1")!!.toByteArray())
        b.onPacket("p", a.compose("secours", "Alice", "2")!!.toByteArray())
        assertEquals(2, b.unread.value["secours"])
        b.markRead("secours")
        assertNull(b.unread.value["secours"])
    }

    @Test fun `rooms survive a restart without storing the password`() {
        val store = MemoryStore()
        core(store).add("Secours", keyA)
        val reloaded = core(store)
        assertEquals(listOf("secours"), reloaded.rooms.value.map { it.key })
        assertEquals("Secours", reloaded.rooms.value[0].displayName)
        assertArrayEquals(keyA, store.saved[0].master)
        assertNotEquals("correct horse battery staple", String(store.saved[0].master, Charsets.ISO_8859_1))
    }

    @Test fun `leaving a room forgets it and its messages`() {
        val store = MemoryStore()
        val a = core(store); a.add("Secours", keyA)
        a.compose("secours", "Alice", "x")
        a.leave("secours")
        assertTrue(a.rooms.value.isEmpty() && a.messages.value.isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test fun `an absurd sender timestamp is replaced by the reception time`() {
        val a = core(); val b = core()
        a.add("Secours", keyA); b.add("Secours", keyA)
        val forged = RoomEnvelope.build(keyA, "Mallory", "demain", now + 10L * 24 * 3600 * 1000)
        b.onPacket("p", forged.toByteArray())
        assertEquals(now, b.messages.value["secours"]!![0].timestampMs)
    }

    @Test fun `long texts are truncated to the maximum`() {
        val a = core(); val b = core()
        a.add("Secours", keyA); b.add("Secours", keyA)
        b.onPacket("p", a.compose("secours", "Alice", "é".repeat(1000))!!.toByteArray())
        assertTrue(b.messages.value["secours"]!![0].text.toByteArray().size <= RoomEnvelope.MAX_TEXT_BYTES)
    }
}
