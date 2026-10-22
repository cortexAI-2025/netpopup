package com.netpopup.crypto.room

import com.netpopup.crypto.ContactBook
import com.netpopup.crypto.ContactTrust
import com.netpopup.crypto.TestDevice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomStatePersistenceTest {
    private val aad = ByteArray(47)

    @Test fun roomStateSurvivesSerializationIncludingRetiredEpochsAndRotation() {
        val alice = TestDevice("Alice")
        val bob = TestDevice("Bob")
        val (room, invite) = alice.rooms.create("Voisins", AdmissionPolicy.CREATOR_ONLY)
        val request = alice.rooms.openJoin(room, aad, bob.rooms.sealJoin(invite, aad, bob.bundle()))!!
        alice.rooms.accept(room, request)
        alice.rooms.remove(room, bob.identity.publicKey) // époque 1, une époque retirée, rotation en cours (aucun restant)

        val back = RoomState.decode(room.encode())
        assertArrayEquals(room.roomId, back.roomId)
        assertEquals("Voisins", back.name)
        assertEquals(AdmissionPolicy.CREATOR_ONLY, back.policy)
        assertEquals(room.epoch, back.epoch)
        assertEquals(room.members, back.members)
        assertArrayEquals(room.keys.envKey, back.keys.envKey)
        assertEquals(room.acceptedKeys(alice.now).size, back.acceptedKeys(alice.now).size)
        assertEquals(room.rotationInProgress, back.rotationInProgress)
        assertArrayEquals(room.inviteCode, back.inviteCode)
    }

    @Test fun corruptedRoomStateIsReportedNotCrashed() {
        val alice = TestDevice("Alice")
        val bytes = alice.rooms.create("X", AdmissionPolicy.ANY_MEMBER).first.encode()
        assertThrows(RoomException::class.java) { RoomState.decode(bytes.copyOf(bytes.size - 5)) }
        assertThrows(RoomException::class.java) { RoomState.decode(ByteArray(0)) }
        assertThrows(RoomException::class.java) { RoomState.decode(bytes.copyOf().also { it[0] = 9 }) }
    }

    @Test fun replyQueueIsPerRequesterAndNeedsTheCode() {
        val alice = TestDevice("Alice")
        val bob = TestDevice("Bob")
        val carol = TestDevice("Carol")
        val (room, _) = alice.rooms.create("R", AdmissionPolicy.ANY_MEMBER)
        val forBob = room.replyKeys(bob.identity.publicKey)
        assertNotEquals(forBob.tagKey.toList(), room.replyKeys(carol.identity.publicKey).tagKey.toList())
        assertNotEquals(forBob.tagKey.toList(), room.joinKeys.tagKey.toList())
        assertNotEquals(forBob.envKey.toList(), room.keys.envKey.toList())
        // Même dérivation côté demandeur, à partir de l'invitation seule.
        val viaInvite = RoomKeys.reply(room.inviteCode, room.roomId, bob.identity.publicKey.serialize())
        assertArrayEquals(forBob.tagKey, viaInvite.tagKey)
    }

    @Test fun contactBookReportsChangesAndRestoresWithoutWriting() {
        val changes = ArrayList<ContactTrust>()
        val book = ContactBook { changes += it.trust }
        val bob = TestDevice("Bob")
        book.addFromAnnounce(bob.bundle())
        book.addFromQr(bob.bundle())
        assertEquals(listOf(ContactTrust.ANNOUNCED, ContactTrust.VERIFIED), changes)
        val fresh = ContactBook { changes += it.trust }
        fresh.restore(book.find(bob.identity.publicKey)!!)
        assertEquals(2, changes.size) // restaurer n'écrit pas
        assertTrue(fresh.find(bob.identity.publicKey) != null)
    }
}
