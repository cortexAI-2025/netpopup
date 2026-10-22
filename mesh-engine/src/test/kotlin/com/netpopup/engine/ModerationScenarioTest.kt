package com.netpopup.engine

import com.netpopup.crypto.PairwiseSession
import com.netpopup.dtn.chat.ChatMessage
import com.netpopup.dtn.chat.LOCAL_CONVERSATION_ID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Blocage local, événements de réception, durée de vie réglable et soupçon d'horloge décalée (mesh-17/18/19). */
@OptIn(ExperimentalCoroutinesApi::class)
class ModerationScenarioTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.world() = World(this, tmp.root)

    private fun keyOf(n: TestNode) = PairwiseSession.addressOf(n.engine.identity.publicKey).name

    @Test fun blockingHidesTheSenderButTheBlockerStillRelays() = runTest {
        val w = world()
        val (a, b, c) = w.nodes("A", "B", "C")
        w.network.connect("A", "B")
        w.network.connect("B", "C")

        b.repo.block(keyOf(a))                       // B bloque A
        a.repo.sendText(LOCAL_CONVERSATION_ID, "bonjour de A")

        awaitEquals(listOf("bonjour de A"), "C reçoit via B : le relais est aveugle") { c.bodies(LOCAL_CONVERSATION_ID) }
        assertEquals("B ne montre pas le message d'un bloqué", emptyList<String>(), b.bodies(LOCAL_CONVERSATION_ID))
        assertEquals(setOf(keyOf(a)), b.repo.observeBlocked().first())

        b.repo.unblock(keyOf(a))
        assertEquals(listOf("bonjour de A"), b.bodies(LOCAL_CONVERSATION_ID))
    }

    @Test fun blockIsPersistentAndCannotTargetSelf() = runTest {
        val w = world()
        val a = w.node("A").also { it.start() }
        a.repo.block(keyOf(a))
        assertTrue("on ne se bloque pas soi-même", a.repo.observeBlocked().first().isEmpty())
        a.repo.block("peer-x")
        val again = a.restart()
        assertEquals(setOf("peer-x"), again.repo.observeBlocked().first())
    }

    @Test fun incomingEventsCoverOnlyOthersMessagesAndSkipBlockedSenders() = runTest {
        val w = world()
        val (a, b, c) = w.nodes("A", "B", "C")
        val seen = mutableListOf<ChatMessage>()
        backgroundScope.launch { b.repo.observeIncoming().collect { seen += it } }
        w.network.connect("A", "B")
        w.network.connect("B", "C")
        settle(5)

        b.repo.sendText(LOCAL_CONVERSATION_ID, "écrit par B")
        a.repo.sendText(LOCAL_CONVERSATION_ID, "écrit par A")
        awaitEquals(true, "B reçoit A") { seen.any { it.body == "écrit par A" } }
        assertFalse("jamais ses propres messages", seen.any { it.isOwn || it.body == "écrit par B" })

        b.repo.block(keyOf(c))
        val before = seen.size
        c.repo.sendText(LOCAL_CONVERSATION_ID, "écrit par C")
        awaitEquals(true, "le message de C est reçu mais masqué") {
            b.engine.messages.value.any { it.body == "écrit par C" }
        }
        assertEquals("pas de notification pour un bloqué", before, seen.size)
    }

    @Test fun lifetimeFollowsSettingsAndIsClamped() = runTest {
        val w = world()
        val short = w.node("S", lifetimeSeconds = 2 * 3600).also { it.start() }
        val huge = w.node("H", lifetimeSeconds = 100 * 3600).also { it.start() }
        val tiny = w.node("T", lifetimeSeconds = 10).also { it.start() }
        suspend fun ms(n: TestNode) = n.repo.sendText(LOCAL_CONVERSATION_ID, "x").let { it.expiresAt - it.sentAt }
        assertEquals(2 * 3600_000L, ms(short))
        assertEquals("plafonné à 24 h", 24 * 3600_000L, ms(huge))
        assertEquals("plancher d'1 h", 3600_000L, ms(tiny))
    }

    @Test fun skewedClockIsSuspectedOnlyAfterRepeatedTimeRejections() = runTest {
        val w = world()
        val a = w.node("A", skewMillis = 3 * 3600_000L).also { it.start() }   // 3 h d'avance
        val b = w.node("B").also { it.start() }
        val c = w.node("C").also { it.start() }
        w.network.connect("A", "B")
        w.network.connect("B", "C")
        settle(5)
        assertFalse(b.repo.observeMeshInfo().first().clockSkewSuspected)

        repeat(6) { a.repo.sendText(LOCAL_CONVERSATION_ID, "m$it"); settle(1) }
        awaitEquals(true, "B voit des enveloppes datées du futur") { b.repo.observeMeshInfo().first().clockSkewSuspected }
        assertTrue(b.repo.observeMeshInfo().first().storedEnvelopes >= 0)
        assertFalse("C, sans lien avec A, n'a rien à soupçonner", c.repo.observeMeshInfo().first().clockSkewSuspected)
        assertEquals("rien d'accepté venant d'une horloge fausse", emptyList<String>(), b.bodies(LOCAL_CONVERSATION_ID))
    }
}
