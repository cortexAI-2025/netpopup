package com.netpopup.engine

import com.netpopup.crypto.room.AdmissionPolicy as CryptoPolicy
import com.netpopup.crypto.room.RoomInvite as CryptoInvite
import com.netpopup.dtn.chat.AdmissionPolicy
import com.netpopup.dtn.chat.ConversationId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Admission aux salons privés, entre téléphones simulés (voir ScenarioTest pour les limites de la simulation). */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomScenarioTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.world() = World(this, tmp.root)

    private suspend fun TestNode.pendingRequests() = repo.observeJoinRequests().first()

    /** [joiner] demande l'accès avec [link] ; [via] accepte la première demande reçue. */
    private suspend fun admit(link: String, joiner: TestNode, via: TestNode) {
        joiner.repo.requestToJoin(link)
        awaitEquals(true, "${via.name} reçoit la demande de ${joiner.name}") { via.pendingRequests().any { it.displayName == joiner.name } }
        via.repo.approveJoinRequest(via.pendingRequests().single { it.displayName == joiner.name }.id)
        awaitEquals(true, "${joiner.name} reçoit la réponse") { joiner.roomNamed(CryptoInvite.fromUri(link).name) != null }
    }

    private suspend fun TestNode.says(room: ConversationId, text: String) { repo.sendText(room, text) }

    @Test fun anyMemberCanAdmitAndEveryoneReadsEveryone() = runTest {
        val w = world()
        val (a, b, c) = w.nodes("A", "B", "C")
        w.network.connect("A", "B"); w.network.connect("B", "C")           // A et C ne se voient jamais
        val invite = a.repo.createRoom("Voisins", AdmissionPolicy.ANY_MEMBER)

        admit(invite.link, b, via = a)
        val roomOnB = b.roomNamed("Voisins")
        assertNotNull(roomOnB)
        admit(invite.link, c, via = b)                                      // B, simple membre, admet C
        val roomOnC = c.roomNamed("Voisins")
        assertNotNull(roomOnC)
        // Annonces, Sender Keys et mises à jour de membres circulent sans qu'on ait à les attendre une à une :
        // chaque message est réessayé tant que sa Sender Key n'est pas arrivée.
        a.says(invite.conversationId, "bonjour de A")
        b.says(roomOnB!!.id, "bonjour de B")
        c.says(roomOnC!!.id, "bonjour de C")

        for ((node, id) in listOf(a to invite.conversationId, b to roomOnB.id, c to roomOnC.id)) {
            awaitEquals(setOf("bonjour de A", "bonjour de B", "bonjour de C"), "chez ${node.name}") { node.bodies(id).toSet() }
        }
        // Chacun voit ses trois membres et la même empreinte de membres.
        for ((n, id) in listOf(a to invite.conversationId, b to roomOnB.id, c to roomOnC.id)) {
            awaitEquals(3, "membres vus par ${n.name}") { n.repo.observeRoomDetails(id).first()!!.members.size }
        }
        val fingerprints = listOf(a to invite.conversationId, b to roomOnB.id, c to roomOnC.id).map { (n, id) ->
            n.repo.observeRoomDetails(id).first()!!.securityFingerprint
        }
        assertEquals(1, fingerprints.toSet().size)
        // Les noms viennent des annonces signées, pas du hasard.
        assertEquals("B", c.messages(roomOnC.id).first { it.body == "bonjour de B" }.displayName)
    }

    @Test fun creatorOnlyRoomIsAdmittedByTheCreatorAlone() = runTest {
        val w = world()
        val (a, b, c) = w.nodes("A", "B", "C")
        w.network.connect("A", "B"); w.network.connect("B", "C"); w.network.connect("A", "C")
        val invite = a.repo.createRoom("Fermée", AdmissionPolicy.CREATOR_ONLY)
        admit(invite.link, b, via = a)

        c.repo.requestToJoin(invite.link)
        awaitEquals(listOf("C"), "le créateur voit la demande") { a.pendingRequests().map { it.displayName } }
        settle(60)
        assertTrue("un simple membre ne voit pas la demande", b.pendingRequests().isEmpty())

        a.repo.rejectJoinRequest(a.pendingRequests().single().id); settle(30)
        assertTrue(a.pendingRequests().isEmpty())
        assertNull(c.roomNamed("Fermée"))                                    // refusé : aucun accès
        assertEquals(1, c.repo.observePendingJoins().first().size)           // et la demande reste « en attente » côté C
    }

    @Test fun invitationWithATamperedPolicyNeverGivesAccess() = runTest {
        val w = world()
        val (a, c) = w.nodes("A", "C")
        w.network.connect("A", "C")
        val invite = a.repo.createRoom("Fermée", AdmissionPolicy.CREATOR_ONLY)
        val real = CryptoInvite.fromUri(invite.link)
        val tampered = CryptoInvite(real.roomId, real.code, real.creator, CryptoPolicy.ANY_MEMBER, real.name).toUri()

        c.repo.requestToJoin(tampered)
        awaitEquals(true, "la demande arrive (elle est valide pour le créateur)") { a.pendingRequests().isNotEmpty() }
        a.repo.approveJoinRequest(a.pendingRequests().single().id); settle(120)
        // Le ROOM_KEY authentique porte la politique signée par le créateur : elle ne correspond pas à l'invitation.
        assertNull(c.roomNamed("Fermée"))
    }

    @Test fun codeHolderWhoIsNotAcceptedCannotReadAnything() = runTest {
        val w = world()
        val (a, b, spy) = w.nodes("A", "B", "S")
        w.network.connect("A", "B"); w.network.connect("A", "S")
        val invite = a.repo.createRoom("Privée", AdmissionPolicy.ANY_MEMBER)
        admit(invite.link, b, via = a)
        spy.repo.requestToJoin(invite.link)                                  // S a le lien mais personne ne l'accepte
        a.says(invite.conversationId, "secret de salon")
        awaitEquals(listOf("secret de salon"), "B, membre, lit") { b.bodies(b.roomNamed("Privée")!!.id) }
        settle(120)
        assertTrue(spy.conversations().none { it.title == "Privée" })
        assertTrue(spy.messages(invite.conversationId).isEmpty())
    }

    @Test fun removedMemberStopsReadingAndRotationCompletes() = runTest {
        val w = world()
        val (a, b, c) = w.nodes("A", "B", "C")
        w.network.connect("A", "B"); w.network.connect("A", "C"); w.network.connect("B", "C")
        val invite = a.repo.createRoom("Voisins", AdmissionPolicy.ANY_MEMBER)
        admit(invite.link, b, via = a); admit(invite.link, c, via = a)
        val roomB = b.roomNamed("Voisins")!!.id; val roomC = c.roomNamed("Voisins")!!.id
        a.says(invite.conversationId, "avant le retrait")
        awaitEquals(listOf("avant le retrait"), "C lit") { c.bodies(roomC) }
        awaitEquals(listOf("avant le retrait"), "B lit") { b.bodies(roomB) }

        awaitEquals(true, "A connaît le nom de B") { a.repo.observeRoomDetails(invite.conversationId).first()!!.members.any { it.displayName == "B" } }
        val bKey = a.repo.observeRoomDetails(invite.conversationId).first()!!.members.first { it.displayName == "B" }.identityKey
        a.engine.removeMember(invite.conversationId, bKey)
        // ROOM_KEY de l'époque suivante, puis nouvelles Sender Keys de chaque membre restant.
        awaitEquals(false, "rotation terminée") { a.conversations().first { it.id == invite.conversationId }.keyRotationPending }
        awaitEquals(false, "rotation terminée chez C") { c.conversations().first { it.id == roomC }.keyRotationPending }

        a.says(invite.conversationId, "après le retrait")
        awaitEquals(listOf("avant le retrait", "après le retrait"), "C lit la suite") { c.bodies(roomC) }
        settle(120)
        assertEquals(listOf("avant le retrait"), b.bodies(roomB))            // B ne lit plus rien de nouveau
        assertEquals(2, a.repo.observeRoomDetails(invite.conversationId).first()!!.members.size)
    }

    @Test fun roomSurvivesRestartOfAMember() = runTest {
        val w = world()
        val (a, b) = w.nodes("A", "B")
        w.network.connect("A", "B")
        val invite = a.repo.createRoom("Voisins", AdmissionPolicy.ANY_MEMBER)
        admit(invite.link, b, via = a)
        val roomB = b.roomNamed("Voisins")!!.id
        a.says(invite.conversationId, "avant")
        awaitEquals(listOf("avant"), "B lit avant le redémarrage") { b.bodies(roomB) }
        b.restart(); a.restart()
        w.network.connect("A", "B"); settle(30)
        assertEquals(listOf("avant"), b.bodies(b.roomNamed("Voisins")!!.id))  // état de room et Sender Keys relus du disque
        b.says(roomB, "après"); a.says(invite.conversationId, "encore")
        // Deux messages envoyés dans la même seconde par deux appareils : l'ordre entre eux n'est pas garanti.
        awaitEquals(setOf("avant", "après", "encore"), "A") { a.bodies(invite.conversationId).toSet() }
        awaitEquals(setOf("avant", "après", "encore"), "B") { b.bodies(roomB).toSet() }
    }
}
