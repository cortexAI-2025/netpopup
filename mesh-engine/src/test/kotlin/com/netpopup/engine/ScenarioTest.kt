package com.netpopup.engine

import com.netpopup.dtn.chat.DeliveryState
import com.netpopup.dtn.chat.InvalidInviteException
import com.netpopup.dtn.chat.LOCAL_CONVERSATION_ID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Parcours complets entre appareils **simulés** : vrais moteurs, vraie crypto, vraie base SQLite sur disque,
 * mais radio factice (aucune latence, aucune perte, aucun appairage Bluetooth). Ils prouvent la logique
 * d'ensemble ; ils ne remplacent pas les essais sur téléphones (docs/field-test-protocol.md).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioTest {
    @get:Rule val tmp = TemporaryFolder()
    private val base = 1_700_000_000_000L

    private fun TestScope.world() = World(this, tmp.root)

    private suspend fun assertRefused(what: String, block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("$what devrait être refusé", failure is InvalidInviteException)
    }

    private suspend fun TestNode.introduce(other: TestNode) = repo.addContact(other.repo.myContactLink())

    // ── Échange direct ───────────────────────────────────────────────────────

    @Test fun directExchangeWithDeliveryStates() = runTest {
        val w = world()
        val (a, b) = w.nodes("A", "B")
        val conv = a.introduce(b)

        val sent = a.repo.sendText(conv, "salut B")
        assertEquals(DeliveryState.QUEUED, sent.deliveryState)           // personne à portée
        settle(60)
        assertEquals(DeliveryState.QUEUED, a.state(conv, "salut B"))     // toujours pas transmis : rien n'est inventé

        w.network.connect("A", "B"); settle(30)
        assertEquals(0, b.bodies(b.directWith(a)!!).count { it != "salut B" })
        assertEquals(listOf("salut B"), b.bodies(b.directWith(a)!!))
        assertEquals(DeliveryState.ACKNOWLEDGED, a.state(conv, "salut B")) // l'ACK vient de B, pas d'un compteur local

        val back = b.repo.sendText(b.directWith(a)!!, "bien reçu")
        settle(30)
        assertEquals(listOf("salut B", "bien reçu"), a.bodies(conv))
        assertEquals(DeliveryState.ACKNOWLEDGED, b.state(back.conversationId, "bien reçu"))
        assertEquals("A", b.messages(b.directWith(a)!!).first().displayName)
    }

    @Test fun oneWayScanStillLetsTheOtherSideAnswer() = runTest {
        val w = world()
        val (a, b) = w.nodes("A", "B")
        w.network.connect("A", "B")
        a.introduce(b); settle(30)
        // B n'a rien scanné : l'introduction déposée dans sa boîte lui a appris A.
        val convOnB = b.directWith(a)
        assertNotNull(convOnB)
        b.repo.sendText(convOnB!!, "je te réponds"); settle(30)
        assertEquals(listOf("je te réponds"), a.bodies(a.directWith(b)!!))
    }

    // ── Relais, livraison différée ───────────────────────────────────────────

    @Test fun relayThroughAnIntermediateDevice() = runTest {
        val w = world()
        val (a, b, c) = w.nodes("A", "B", "C")
        w.network.connect("A", "B"); w.network.connect("B", "C")     // A et C ne se voient jamais
        val conv = a.introduce(c); settle(30)
        a.repo.sendText(conv, "via B"); settle(60)

        assertEquals(listOf("via B"), c.bodies(c.directWith(a)!!))
        assertEquals(DeliveryState.ACKNOWLEDGED, a.state(conv, "via B"))  // l'ACK a refait le chemin C → B → A
        assertTrue("le relais ne voit aucun texte", b.conversations().none { it.kind.name == "DIRECT" })
    }

    @Test fun deliveryDeferredUntilADeviceCarriesTheMessage() = runTest {
        val w = world()
        val (a, b, c) = w.nodes("A", "B", "C")
        w.network.connect("A", "B")
        val conv = a.introduce(c); settle(20)
        a.repo.sendText(conv, "porté par B"); settle(30)
        w.network.disconnect("A", "B"); settle(30)       // B s'éloigne en emportant le message
        assertEquals(DeliveryState.RELAYED, a.state(conv, "porté par B"))  // transmis à un pair, pas encore reçu

        w.network.connect("B", "C"); settle(60)          // B croise C
        assertEquals(listOf("porté par B"), c.bodies(c.directWith(a)!!))
        assertEquals(DeliveryState.RELAYED, a.state(conv, "porté par B"))  // A ne sait toujours rien

        w.network.disconnect("B", "C"); w.network.connect("A", "B"); settle(60)  // B repasse voir A avec l'ACK
        assertEquals(DeliveryState.ACKNOWLEDGED, a.state(conv, "porté par B"))
    }

    // ── Redémarrage ──────────────────────────────────────────────────────────

    @Test fun restartKeepsMessagesSessionsAndPendingAcknowledgements() = runTest {
        val w = world()
        val (a, b) = w.nodes("A", "B")
        val conv = a.introduce(b)
        a.repo.sendText(conv, "avant le redémarrage")
        a.restart()                                       // le téléphone redémarre avec un message en attente
        assertEquals(DeliveryState.QUEUED, a.state(conv, "avant le redémarrage"))

        w.network.connect("A", "B"); settle(30)
        assertEquals(listOf("avant le redémarrage"), b.bodies(b.directWith(a)!!))
        assertEquals(DeliveryState.ACKNOWLEDGED, a.state(conv, "avant le redémarrage"))

        b.restart(); a.restart()                          // les deux redémarrent : session et messages conservés
        assertEquals(listOf("avant le redémarrage"), b.bodies(b.directWith(a)!!))
        w.network.connect("A", "B"); settle(10)
        b.repo.sendText(b.directWith(a)!!, "après"); settle(30)
        assertEquals(listOf("avant le redémarrage", "après"), a.bodies(conv))
    }

    @Test fun acknowledgementArrivingAfterTheSenderRestarted() = runTest {
        val w = world()
        val (a, b) = w.nodes("A", "B")
        val conv = a.introduce(b)
        w.network.connect("A", "B"); settle(5)
        a.repo.sendText(conv, "x")
        w.network.disconnect("A", "B")                    // coupé avant que l'ACK ne revienne ?
        settle(1)
        a.restart()
        w.network.connect("A", "B"); settle(40)
        assertEquals(DeliveryState.ACKNOWLEDGED, a.state(conv, "x"))
    }

    // ── Expiration ───────────────────────────────────────────────────────────

    @Test fun expiredMessageIsNeverDeliveredAndDisappears() = runTest {
        val w = world()
        val (a, b) = w.nodes("A", "B")
        val conv = a.introduce(b)
        a.repo.sendText(conv, "éphémère")
        settleHours(9)                                    // 6 h de vie + 2 h de tolérance + marge
        assertTrue(a.bodies(conv).isEmpty())              // plus affiché
        w.network.connect("A", "B"); settle(60)
        assertNull(b.directWith(a)?.let { b.bodies(it).firstOrNull() })   // jamais livré
    }

    // ── Statut du mesh ───────────────────────────────────────────────────────

    @Test fun peersAreCountedOnlyAfterAWellFormedInventory() = runTest {
        val w = world()
        val a = w.node("A")
        a.start()
        val stranger = LoopbackTransport("X", w.network)   // un appareil qui n'est pas un moteur NetPopUp
        w.network.connect("A", "X"); settle(5)
        assertEquals(0, a.engine.meshStatus.value.peersInRange)
        w.network.deliver("X", "A", byteArrayOf(0x7f, 1, 2, 3)); settle(2)   // trame invalide
        assertEquals(0, a.engine.meshStatus.value.peersInRange)
        assertTrue(a.engine.meshStatus.value.isRunning)

        val b = w.node("B"); b.start()
        w.network.connect("A", "B"); settle(5)
        assertEquals(1, a.engine.meshStatus.value.peersInRange)   // seul B a parlé le protocole
        w.network.disconnect("A", "B"); settle(2)
        assertEquals(0, a.engine.meshStatus.value.peersInRange)
        assertNotNull(stranger)
    }

    @Test fun stoppedEngineReportsNotRunning() = runTest {
        val w = world()
        val a = w.node("A"); a.start()
        assertTrue(a.engine.meshStatus.value.isRunning)
        a.engine.stop()
        assertEquals(false, a.engine.meshStatus.value.isRunning)
    }

    // ── Room locale publique ─────────────────────────────────────────────────

    @Test fun publicLocalRoomCarriesSignedMessagesBetweenNeighbours() = runTest {
        val w = world()
        val (a, b) = w.nodes("A", "B")
        w.network.connect("A", "B"); settle(5)
        a.repo.sendText(LOCAL_CONVERSATION_ID, "bonjour le quartier"); settle(20)
        val received = b.messages(LOCAL_CONVERSATION_ID).single()
        assertEquals("bonjour le quartier", received.body)
        assertEquals("A", received.displayName)
        assertEquals(false, received.isOwn)
        assertEquals(DeliveryState.RELAYED, a.state(LOCAL_CONVERSATION_ID, "bonjour le quartier")) // jamais « acquitté »
    }

    // ── Liens d'invitation ───────────────────────────────────────────────────

    @Test fun malformedInvitationsAreRefusedBeforeAnythingIsSent() = runTest {
        val w = world()
        val a = w.node("A").also { it.start() }
        val owner = w.node("B").also { it.start() }
        val link = owner.repo.createRoom("Privée", com.netpopup.dtn.chat.AdmissionPolicy.ANY_MEMBER).link
        for (bad in listOf("XK7B2Q", link.dropLast(10), link.replace("netpopup://room/", "netpopup://contact/"), link + "AAAA", "https://exemple.org/" + "A".repeat(100))) {
            assertRefused(bad) { a.repo.requestToJoin(bad) }
        }
        assertTrue(a.repo.observePendingJoins().first().isEmpty())
        assertRefused("sa propre room") { owner.repo.requestToJoin(link) }
    }
}
