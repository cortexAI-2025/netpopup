package com.netpopup.crypto.room

import com.netpopup.crypto.OuterLayer
import com.netpopup.crypto.PairwiseSession
import com.netpopup.crypto.TestDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomServiceTest {
    private val aad = ByteArray(47) { it.toByte() }
    private val hour = 3_600_000L

    private class Member(val dev: TestDevice, var room: RoomState)

    /** Un message de contrôle voyage dans la session 1:1 (X3DH / Double Ratchet) avant d'atteindre le destinataire. */
    private fun viaPair(out: RoomService.Outgoing, from: TestDevice, to: TestDevice): PairwiseSession.DecryptResult {
        val wire = from.session.encrypt(out.recipient, out.payload)
        return to.session.decrypt(wire, from.identity.publicKey)
    }

    private fun deliver(out: RoomService.Outgoing, from: Member, to: Member) = viaPair(out, from.dev, to.dev)

    private fun byKey(all: List<Member>, out: RoomService.Outgoing) = all.first { it.dev.identity.publicKey == out.recipient }

    /** Demande d'accès de [joiner] acceptée par [via] ; les autres membres sont informés. */
    private fun join(invite: RoomInvite, joiner: TestDevice, via: Member, others: List<Member> = emptyList()): Member {
        val sealed = joiner.rooms.sealJoin(invite, aad, joiner.bundle())
        val request = via.dev.rooms.openJoin(via.room, aad, sealed)!!
        val out = via.dev.rooms.accept(via.room, request)
        val received = viaPair(out, via.dev, joiner)
        val member = Member(joiner, joiner.rooms.joinFromRoomKey(invite, received.sender, received.plaintext))
        val everyone = others + via + member
        for (o in via.dev.rooms.membershipUpdate(via.room, except = joiner.identity.publicKey).outgoing) {
            val target = byKey(everyone, o)
            val got = deliver(o, via, target)
            target.dev.rooms.onRoomKey(target.room, got.sender, got.plaintext)
        }
        return member
    }

    /** Chacun annonce son bundle, puis distribue sa Sender Key à tous les autres. */
    private fun syncAll(members: List<Member>) {
        for (m in members) {
            val announce = m.dev.rooms.sealAnnounce(m.room, aad, m.dev.bundle())
            for (o in members) if (o !== m) o.dev.rooms.openAnnounce(o.room, aad, announce)
        }
        for (m in members) {
            val dist = m.dev.rooms.senderKeyDistribution(m.room)
            assertTrue("injoignables : ${dist.unreachable.size}", dist.unreachable.isEmpty())
            for (out in dist.outgoing) {
                val target = byKey(members, out)
                val got = deliver(out, m, target)
                target.dev.rooms.onSenderKey(target.room, got.sender, got.plaintext)
            }
        }
    }

    private fun setup(policy: AdmissionPolicy = AdmissionPolicy.ANY_MEMBER): Pair<RoomInvite, List<Member>> {
        val alice = TestDevice("Alice")
        val (room, invite) = alice.rooms.create("Voisins", policy)
        val a = Member(alice, room)
        val b = join(invite, TestDevice("Bob"), a)
        val c = if (policy == AdmissionPolicy.ANY_MEMBER) join(invite, TestDevice("Carol"), b, listOf(a))
        else join(invite, TestDevice("Carol"), a, listOf(b))
        return invite to listOf(a, b, c)
    }

    @Test fun fullFlowThreeMembersExchangeMessages() {
        val (_, all) = setup()
        val (a, b, c) = all
        all.forEach { assertEquals(3, it.room.members.size) }
        syncAll(all)

        val wire = a.dev.rooms.sealMessage(a.room, aad, "bonjour la room".toByteArray())
        for (m in listOf(b, c)) {
            val got = m.dev.rooms.openMessage(m.room, aad, wire)!!
            assertEquals("bonjour la room", String(got.plaintext))
            assertEquals(a.dev.identity.publicKey, got.sender)
        }
        val reply = c.dev.rooms.sealMessage(c.room, aad, "salut".toByteArray())
        assertEquals("salut", String(a.dev.rooms.openMessage(a.room, aad, reply)!!.plaintext))
        assertEquals(c.dev.identity.publicKey, b.dev.rooms.openMessage(b.room, aad, reply)!!.sender)
    }

    @Test fun messageIsBoundToItsHeaderAndToTheRoom() {
        val (_, all) = setup()
        syncAll(all)
        val (a, b) = all
        val wire = a.dev.rooms.sealMessage(a.room, aad, "x".toByteArray())
        assertNull(b.dev.rooms.openMessage(b.room, aad.copyOf().also { it[0] = 99 }, wire))
        assertNull(b.dev.rooms.openMessage(b.room, aad, wire.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() }))
        val (_, other) = setup()
        assertNull(other[1].dev.rooms.openMessage(other[1].room, aad, wire))
    }

    @Test fun joinCodeGivesNoAccessToMessages() {
        val (invite, all) = setup()
        syncAll(all)
        val wire = all[0].dev.rooms.sealMessage(all[0].room, aad, "secret".toByteArray())
        assertNotEquals(invite.joinKeys.envKey.toList(), all[0].room.keys.envKey.toList())
        assertNull(OuterLayer.open(invite.joinKeys.envKey, aad, wire))
    }

    @Test fun joinRequestWithWrongCodeOrTamperedAadIsIgnored() {
        val alice = TestDevice("Alice")
        val (room, invite) = alice.rooms.create("Privée", AdmissionPolicy.ANY_MEMBER)
        val other = RoomInvite(invite.roomId, ByteArray(16) { 9 }, invite.creator, invite.policy, "x")
        val bob = TestDevice("Bob")
        assertNull(alice.rooms.openJoin(room, aad, bob.rooms.sealJoin(other, aad, bob.bundle())))
        assertNull(alice.rooms.openJoin(room, aad.copyOf().also { it[0] = 77 }, bob.rooms.sealJoin(invite, aad, bob.bundle())))
        assertNotNull(alice.rooms.openJoin(room, aad, bob.rooms.sealJoin(invite, aad, bob.bundle())))
    }

    @Test fun creatorOnlyRefusesAcceptanceByOtherMembers() {
        val (invite, all) = setup(AdmissionPolicy.CREATOR_ONLY)
        val (_, b) = all
        val carol = TestDevice("Dave")
        val request = b.dev.rooms.openJoin(b.room, aad, carol.rooms.sealJoin(invite, aad, carol.bundle()))!!
        val ex = assertThrows(RoomException::class.java) { b.dev.rooms.accept(b.room, request) }
        assertTrue(ex.message!!.contains("creator"))
    }

    @Test fun creatorOnlyJoinerRejectsAnAuthenticRoomKeyRelayedByAMember() {
        val (invite, all) = setup(AdmissionPolicy.CREATOR_ONLY)
        val (_, b) = all
        // Bob contourne son propre contrôle en rejouant l'en-tête authentique avec une politique locale permissive.
        val dave = TestDevice("Dave")
        val tampered = RoomState(
            b.room.roomId, "x", b.room.creator, AdmissionPolicy.ANY_MEMBER, b.room.inviteCode,
            b.room.epoch, b.room.secret, b.room.header, b.room.headerSignature, b.room.members,
        )
        val out = b.dev.rooms.accept(tampered, RoomService.JoinRequest(tampered.roomId, dave.bundle()))
        val got = viaPair(out, b.dev, dave)
        val ex = assertThrows(RoomException::class.java) { dave.rooms.joinFromRoomKey(invite, got.sender, got.plaintext) }
        assertTrue(ex.message!!, ex.message!!.contains("creator"))
    }

    @Test fun forgedRoomKeysAreRejected() {
        val alice = TestDevice("Alice")
        val (room, invite) = alice.rooms.create("Voisins", AdmissionPolicy.ANY_MEMBER)
        val mallory = TestDevice("Mallory")
        val victim = TestDevice("Victim")

        // 1. Mallory (qui connaît le code) fabrique un en-tête cohérent en tout point (room, code, politique,
        //    empreinte de son propre secret) mais signé par elle : seule la signature du créateur manque.
        val secret = ByteArray(32) { 7 }
        val header = RoomHeader(room.roomId, 0, invite.policy, invite.code, RoomKeys.commitment(room.roomId, 0, secret))
        val signature = mallory.identity.keyPair.privateKey.calculateSignature(header.signedBytes())
        val forgedState = RoomState(
            room.roomId, "x", invite.creator, invite.policy, invite.code,
            0, secret, header, signature, listOf(mallory.identity.publicKey),
        )
        val out1 = mallory.rooms.accept(forgedState, RoomService.JoinRequest(room.roomId, victim.bundle()))
        val got1 = viaPair(out1, mallory, victim)
        val ex1 = assertThrows(RoomException::class.java) { victim.rooms.joinFromRoomKey(invite, got1.sender, got1.plaintext) }
        assertTrue(ex1.message!!, ex1.message!!.contains("creator"))

        // 2. Mallory reprend l'en-tête authentique mais met un autre secret : l'empreinte signée ne correspond pas.
        val victim2 = TestDevice("Victim2")
        val swapped = RoomState(
            room.roomId, "x", room.creator, room.policy, room.inviteCode,
            0, ByteArray(32) { 5 }, room.header, room.headerSignature, listOf(mallory.identity.publicKey),
        )
        val out2 = mallory.rooms.accept(swapped, RoomService.JoinRequest(room.roomId, victim2.bundle()))
        val got2 = viaPair(out2, mallory, victim2)
        val ex = assertThrows(RoomException::class.java) { victim2.rooms.joinFromRoomKey(invite, got2.sender, got2.plaintext) }
        assertTrue(ex.message!!, ex.message!!.contains("commitment"))
    }

    @Test fun malformedRoomKeyNeverCrashes() {
        val alice = TestDevice("Alice")
        val (_, invite) = alice.rooms.create("Voisins", AdmissionPolicy.ANY_MEMBER)
        val bob = TestDevice("Bob")
        for (bad in listOf(ByteArray(0), byteArrayOf(2), ByteArray(300), byteArrayOf(9, 1, 2))) {
            assertThrows(RoomException::class.java) { bob.rooms.joinFromRoomKey(invite, alice.identity.publicKey, bad) }
        }
    }

    @Test fun inviteRoundTripAndMalformedInvites() {
        val alice = TestDevice("Alice")
        val (_, invite) = alice.rooms.create("Voisins", AdmissionPolicy.CREATOR_ONLY)
        val back = RoomInvite.fromUri(invite.toUri())
        assertEquals("Voisins", back.name)
        assertEquals(AdmissionPolicy.CREATOR_ONLY, back.policy)
        assertEquals(invite.creator, back.creator)
        assertThrows(RoomException::class.java) { RoomInvite.fromUri("netpopup://room/AAAA") }
        assertThrows(RoomException::class.java) { RoomInvite.decode(invite.encode() + 0) }
        assertThrows(RoomException::class.java) { RoomInvite.fromUri("https://x") }
    }

    @Test fun roomCapsMembers() {
        val alice = TestDevice("Alice")
        val (room, invite) = alice.rooms.create("Pleine", AdmissionPolicy.ANY_MEMBER)
        repeat(RoomState.MAX_MEMBERS - 1) { room.addMember(TestDevice("m$it").identity.publicKey) }
        val extra = TestDevice("Extra")
        val req = alice.rooms.openJoin(room, aad, extra.rooms.sealJoin(invite, aad, extra.bundle()))!!
        assertThrows(RoomException::class.java) { alice.rooms.accept(room, req) }
    }

    // ── Retrait et rotation ──────────────────────────────────────────────────

    @Test fun removalRotatesKeysAndLocksOutTheRemovedMember() {
        val (_, all) = setup()
        val (a, b, c) = all
        syncAll(all)
        val inFlight = c.dev.rooms.sealMessage(c.room, aad, "avant le retrait".toByteArray())
        val oldEpoch = a.room.epoch

        // Alice retire Bob.
        val dist = a.dev.rooms.remove(a.room, b.dev.identity.publicKey)
        assertEquals(oldEpoch + 1, a.room.epoch)
        assertTrue(a.room.rotationInProgress)
        assertEquals(listOf(c.dev.identity.publicKey), dist.outgoing.map { it.recipient })
        val gotC = deliver(dist.outgoing[0], a, c)
        c.dev.rooms.onRoomKey(c.room, gotC.sender, gotC.plaintext)
        assertEquals(oldEpoch + 1, c.room.epoch)
        assertEquals(2, c.room.members.size)
        assertTrue(c.room.rotationInProgress)

        // « Rotation en cours » jusqu'à ce que chacun ait redistribué ses nouvelles Sender Keys.
        for (m in listOf(a, c)) {
            for (out in m.dev.rooms.senderKeyDistribution(m.room).outgoing) {
                val target = byKey(listOf(a, c), out)
                val got = deliver(out, m, target)
                target.dev.rooms.onSenderKey(target.room, got.sender, got.plaintext)
            }
        }
        assertFalse(a.room.rotationInProgress)
        assertFalse(c.room.rotationInProgress)

        // Les nouveaux messages circulent entre Alice et Carol et sont illisibles pour Bob.
        val fresh = a.dev.rooms.sealMessage(a.room, aad, "après le retrait".toByteArray())
        assertEquals("après le retrait", String(c.dev.rooms.openMessage(c.room, aad, fresh)!!.plaintext))
        assertNull(b.dev.rooms.openMessage(b.room, aad, fresh))
        assertNotEquals(b.room.keys.tagKey.toList(), a.room.keys.tagKey.toList())

        // Limite documentée : une enveloppe émise avant le retrait reste lisible par qui avait les clés d'alors.
        assertEquals("avant le retrait", String(b.dev.rooms.openMessage(b.room, aad, inFlight)!!.plaintext))
        // ... et les membres restants lisent encore les messages en vol de l'ancienne époque (fenêtre de 26 h).
        assertEquals("avant le retrait", String(a.dev.rooms.openMessage(a.room, aad, inFlight)!!.plaintext))
    }

    @Test fun removedMembersLaterMessagesAreDropped() {
        val (_, all) = setup()
        val (a, b, c) = all
        syncAll(all)
        val dist = a.dev.rooms.remove(a.room, b.dev.identity.publicKey)
        val got = deliver(dist.outgoing[0], a, c)
        c.dev.rooms.onRoomKey(c.room, got.sender, got.plaintext)
        val fromBob = b.dev.rooms.sealMessage(b.room, aad, "je suis toujours là".toByteArray())
        assertNull(a.dev.rooms.openMessage(a.room, aad, fromBob))
        assertNull(c.dev.rooms.openMessage(c.room, aad, fromBob))
    }

    @Test fun previousEpochExpiresAfter26Hours() {
        val (_, all) = setup()
        val (a, b, c) = all
        syncAll(all)
        val oldFromCarol = c.dev.rooms.sealMessage(c.room, aad, "ancien".toByteArray())
        val dist = a.dev.rooms.remove(a.room, b.dev.identity.publicKey)
        val got = deliver(dist.outgoing[0], a, c)
        c.dev.rooms.onRoomKey(c.room, got.sender, got.plaintext)

        a.dev.now += 25 * hour
        assertEquals("ancien", String(a.dev.rooms.openMessage(a.room, aad, oldFromCarol)!!.plaintext))
        a.dev.now += 2 * hour
        assertNull(a.dev.rooms.openMessage(a.room, aad, oldFromCarol))
    }

    @Test fun aMemberCannotImposeANewEpoch() {
        val (_, all) = setup()
        val (a, b, c) = all
        syncAll(all)
        a.dev.rooms.remove(a.room, c.dev.identity.publicKey) // Alice passe à l'époque 1, Carol est retirée
        // Carol relaie à Bob le ROOM_KEY authentique de l'époque 1 : signé par Alice, mais envoyé par Carol.
        val relayed = c.dev.rooms.membershipUpdate(a.room).outgoing.first { it.recipient == b.dev.identity.publicKey }
        val got = deliver(relayed, c, b)
        val ex = assertThrows(RoomException::class.java) { b.dev.rooms.onRoomKey(b.room, got.sender, got.plaintext) }
        assertTrue(ex.message!!, ex.message!!.contains("creator"))
        assertEquals(0, b.room.epoch)
    }

    @Test fun onlyTheCreatorCanRemoveMembers() {
        val (_, all) = setup()
        val (a, b, c) = all
        assertThrows(RoomException::class.java) { b.dev.rooms.remove(b.room, c.dev.identity.publicKey) }
        assertThrows(RoomException::class.java) { a.dev.rooms.remove(a.room, a.dev.identity.publicKey) }
        assertThrows(RoomException::class.java) { a.dev.rooms.remove(a.room, TestDevice("Inconnu").identity.publicKey) }
    }

    @Test fun staleRoomKeyReplayIsRejected() {
        val (_, all) = setup()
        val (a, b, c) = all
        syncAll(all)
        val epoch0Key = a.dev.rooms.membershipUpdate(a.room).outgoing.first { it.recipient == c.dev.identity.publicKey }
        val dist = a.dev.rooms.remove(a.room, b.dev.identity.publicKey)
        val new = deliver(dist.outgoing[0], a, c)
        c.dev.rooms.onRoomKey(c.room, new.sender, new.plaintext)
        val old = deliver(epoch0Key, a, c)
        val ex = assertThrows(RoomException::class.java) { c.dev.rooms.onRoomKey(c.room, old.sender, old.plaintext) }
        assertTrue(ex.message!!.contains("stale"))
    }

    @Test fun senderKeyFromNonMemberOrWrongEpochIsRejected() {
        val (_, all) = setup()
        val (a, b) = all
        val stranger = TestDevice("Eve")
        val mine = a.dev.rooms.senderKeyDistribution(a.room)
        val out = mine.outgoing.first { it.recipient == b.dev.identity.publicKey }
        val got = deliver(out, a, b)
        assertThrows(RoomException::class.java) { b.dev.rooms.onSenderKey(b.room, stranger.identity.publicKey, got.plaintext) }
        b.dev.rooms.onSenderKey(b.room, got.sender, got.plaintext)
    }

    // ── Clés qui voyagent avec retard ────────────────────────────────────────

    private fun announceAll(members: List<Member>) {
        for (m in members) {
            val announce = m.dev.rooms.sealAnnounce(m.room, aad, m.dev.bundle())
            for (o in members) if (o !== m) o.dev.rooms.openAnnounce(o.room, aad, announce)
        }
    }

    @Test fun aMemberWhoseKeyArrivesLateStillReadsWhatWasWrittenBeforeIt() {
        val (_, all) = setup()
        val (a, b, c) = all
        announceAll(all)
        // Alice ne peut d'abord joindre que Bob ; elle écrit ; la clé de Carol ne part qu'ensuite.
        val first = a.dev.rooms.senderKeyDistribution(a.room, null) { it == b.dev.identity.publicKey }
        for (out in first.outgoing) { val got = deliver(out, a, b); b.dev.rooms.onSenderKey(b.room, got.sender, got.plaintext) }
        val early1 = a.dev.rooms.sealMessage(a.room, aad, "premier".toByteArray())
        val early2 = a.dev.rooms.sealMessage(a.room, aad, "deuxième".toByteArray())

        val late = a.dev.rooms.senderKeyDistribution(a.room, first.snapshot) { it == c.dev.identity.publicKey }
        for (out in late.outgoing) { val got = deliver(out, a, c); c.dev.rooms.onSenderKey(c.room, got.sender, got.plaintext) }

        assertEquals("premier", String(c.dev.rooms.openMessage(c.room, aad, early1)!!.plaintext))
        assertEquals("deuxième", String(c.dev.rooms.openMessage(c.room, aad, early2)!!.plaintext))
        val after = a.dev.rooms.sealMessage(a.room, aad, "après".toByteArray())
        assertEquals("après", String(c.dev.rooms.openMessage(c.room, aad, after)!!.plaintext))
    }

    @Test fun withoutTheSnapshotALateKeyCannotReadEarlierMessages() {
        // Garde-fou : documente pourquoi l'instantané est nécessaire (comportement natif de libsignal).
        val (_, all) = setup()
        val (a, b, c) = all
        announceAll(all)
        val first = a.dev.rooms.senderKeyDistribution(a.room, null) { it == b.dev.identity.publicKey }
        for (out in first.outgoing) { val got = deliver(out, a, b); b.dev.rooms.onSenderKey(b.room, got.sender, got.plaintext) }
        val early = a.dev.rooms.sealMessage(a.room, aad, "avant".toByteArray())

        val fresh = a.dev.rooms.senderKeyDistribution(a.room, null) { it == c.dev.identity.publicKey }
        for (out in fresh.outgoing) { val got = deliver(out, a, c); c.dev.rooms.onSenderKey(c.room, got.sender, got.plaintext) }
        assertNull(c.dev.rooms.openMessage(c.room, aad, early))
    }
}
