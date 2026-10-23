package com.netpopup.engine

import com.netpopup.dtn.chat.DeliveryState
import com.netpopup.dtn.chat.LOCAL_CONVERSATION_ID
import com.netpopup.transport.internet.InMemoryRelayBackend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Le relais Internet de bout en bout, avec de vrais moteurs et de vraies bases : des téléphones qui ne se voient
 * jamais (aucune radio entre eux) échangent par un relais muet. Relais **simulé** en mémoire : le vrai Firestore n'est
 * pas atteint ici (voir docs/internet-relay.md pour ce qui reste à vérifier).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayScenarioTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.world() = World(this, tmp.root)

    private fun InMemoryRelayBackend.containsBytes(needle: String) =
        everything().any { d -> String(d.envelope, Charsets.ISO_8859_1).contains(String(needle.toByteArray(), Charsets.ISO_8859_1)) }

    @Test fun deferredDirectMessageAndAckThroughTheRelayAlone() = runTest {
        val relay = InMemoryRelayBackend()
        val w = world()
        val a = w.relayNode("Alice", relay).also { it.start() }
        val c = w.relayNode("Carol", relay)                      // pas encore démarrée : hors ligne
        val conv = a.repo.addContact(c.repo.myContactLink())

        a.repo.sendText(conv, "rendez-vous à 18h")
        awaitEquals(DeliveryState.RELAYED, "le message est « relayé » parce que le relais l'a rangé") { a.state(conv, "rendez-vous à 18h") }
        assertTrue("le relais contient des enveloppes", relay.count() > 0)

        settle(1800)                                             // Carol se connecte une demi-heure plus tard
        c.start()
        awaitEquals(listOf("rendez-vous à 18h"), "Carol reçoit par le relais") { c.bodiesWith(a) }
        awaitEquals(DeliveryState.ACKNOWLEDGED, "l'accusé revient par le relais") { a.state(conv, "rendez-vous à 18h") }

        assertFalse("le texte n'est jamais lisible côté relais", relay.containsBytes("rendez-vous"))
        assertFalse(relay.containsBytes("Alice") || relay.containsBytes("Carol"))
    }

    @Test fun publicRoomMessagesCrossTheRelay() = runTest {
        val relay = InMemoryRelayBackend()
        val w = world()
        val a = w.relayNode("Alice", relay).also { it.start() }
        val c = w.relayNode("Carol", relay).also { it.start() }
        a.repo.sendText(LOCAL_CONVERSATION_ID, "salut le monde")
        awaitEquals(listOf("salut le monde"), "la room publique passe aussi par le relais") { c.bodies(LOCAL_CONVERSATION_ID) }
    }

    @Test fun relayOutageKeepsMessagesQueuedThenDeliversOnRecovery() = runTest {
        val relay = InMemoryRelayBackend()
        val w = world()
        val a = w.relayNode("Alice", relay).also { it.start() }
        val c = w.relayNode("Carol", relay).also { it.start() }
        val conv = a.repo.addContact(c.repo.myContactLink())
        settle(120)

        relay.offline = true
        a.repo.sendText(conv, "pendant la panne")
        settle(300)
        assertEquals("rien n'est annoncé « relayé » quand le relais est coupé", DeliveryState.QUEUED, a.state(conv, "pendant la panne"))
        assertEquals(emptyList<String>(), c.bodiesWith(a))

        relay.offline = false
        awaitEquals(listOf("pendant la panne"), "livré après la reprise, sans intervention") { c.bodiesWith(a) }
        awaitEquals(DeliveryState.ACKNOWLEDGED) { a.state(conv, "pendant la panne") }
    }

    @Test fun aPhoneWithBothRadioAndRelayBridgesTwoWorlds() = runTest {
        val relay = InMemoryRelayBackend()
        val w = world()
        val a = w.nodes("Alice").single()                         // radio seulement
        val b = w.relayNode("Bob", relay, alsoRadio = true).also { it.start() }
        val c = w.relayNode("Carol", relay).also { it.start() }   // relais seulement
        w.network.connect("Alice", "Bob")
        a.repo.sendText(LOCAL_CONVERSATION_ID, "d'Alice à Carol, via Bob")
        awaitEquals(listOf("d'Alice à Carol, via Bob"), "Bob relaie la radio vers Internet") { c.bodies(LOCAL_CONVERSATION_ID) }
        assertTrue(b.bodies(LOCAL_CONVERSATION_ID).isNotEmpty())
    }

    @Test fun relayStoresNothingIntelligibleAboutTheParticipants() = runTest {
        val relay = InMemoryRelayBackend()
        val w = world()
        val a = w.relayNode("Alice", relay).also { it.start() }
        val c = w.relayNode("Carol", relay).also { it.start() }
        val conv = a.repo.addContact(c.repo.myContactLink())
        a.repo.sendText(conv, "secret absolu")
        awaitEquals(listOf("secret absolu"), "Carol reçoit") { c.bodiesWith(a) }
        for (d in relay.everything()) {
            assertEquals(16, d.id.size); assertEquals(16, d.destTag.size)
            assertTrue("fin de vie bornée à 24 h", d.expiresAtSeconds - (1_700_000_000L + currentTime / 1000) <= 24 * 3600 + 7200)
        }
        assertFalse(relay.containsBytes("secret absolu"))
        assertFalse(relay.containsBytes("Alice") || relay.containsBytes("Carol"))
    }
}
