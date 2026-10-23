package com.netpopup.engine

import com.netpopup.dtn.chat.LOCAL_CONVERSATION_ID
import com.netpopup.dtn.envelope.DecodeResult
import com.netpopup.dtn.envelope.DestTags
import com.netpopup.dtn.envelope.EnvelopeCodec
import com.netpopup.dtn.sync.Frame
import com.netpopup.dtn.sync.FrameCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `listeningTags` : ce que le moteur dit écouter doit être exactement ce qu'il sait recevoir. */
@OptIn(ExperimentalCoroutinesApi::class)
class ListeningTagsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val base = 1_700_000_000_000L
    private fun epochNow(virtualMillis: Long) = DestTags.epochOf((base + virtualMillis) / 1000)

    private fun tagsOf(frames: List<ByteArray>, nowSeconds: Long): List<ByteArray> = frames.mapNotNull { FrameCodec.decode(it) }
        .filterIsInstance<Frame.EnvelopeFrame>()
        .mapNotNull { (EnvelopeCodec.decode(it.bytes, nowSeconds) as? DecodeResult.Accepted)?.envelope?.destTag }

    private fun List<ByteArray>.hasTag(t: ByteArray) = any { it.contentEquals(t) }

    @Test fun coversPublicRoomInboxAndDirectConversationsBothWays() = runTest {
        val w = World(this, tmp.root)
        val (a, b) = w.nodes("A", "B")
        a.repo.addContact(b.repo.myContactLink())
        val conv = a.directWith(b) ?: error("conversation 1:1 attendue")
        w.network.connect("A", "B")
        settle(30)
        a.repo.sendText(conv, "bonjour B")
        a.repo.sendText(LOCAL_CONVERSATION_ID, "bonjour tout le monde")
        awaitEquals(true, "B reçoit le 1:1") { b.directWith(a)?.let { b.bodies(it) }?.contains("bonjour B") == true }

        val e = epochNow(currentTime)
        val nowSeconds = (base + currentTime) / 1000
        val emittedByA = tagsOf(a.transport.sentFrames, nowSeconds)
        assertTrue("A a émis des enveloppes", emittedByA.isNotEmpty())

        val heardByA = a.engine.listeningTags(e - 1, e + 1)
        val heardByB = b.engine.listeningTags(e - 1, e + 1)
        // Tout ce que A a émis pour B (1:1) ou pour la room publique est une étiquette que B écoute.
        val publicTag = DestTags.forSending(PublicRoom.keys.tagKey, nowSeconds)
        assertTrue("room publique", heardByB.hasTag(publicTag) && heardByA.hasTag(publicTag))
        assertTrue("toutes les enveloppes de A, hors accusés, sont écoutées par B ou A",
            emittedByA.all { t -> heardByB.hasTag(t) || heardByA.hasTag(t) })
        // Les étiquettes sont distinctes d'une époque à l'autre.
        val oneEpoch = a.engine.listeningTags(e, e)
        val twoEpochs = a.engine.listeningTags(e, e + 1)
        assertEquals(oneEpoch.size * 2, twoEpochs.size)
        assertEquals(twoEpochs.size, twoEpochs.map { it.toList() }.toSet().size)
    }

    @Test fun nothingLeaksTagKeysAndRangeIsBounded() = runTest {
        val w = World(this, tmp.root)
        val a = w.node("A").also { it.start() }
        val e = epochNow(currentTime)
        assertTrue(a.engine.listeningTags(e, e).all { it.size == 16 })              // des étiquettes, pas des clés (32 o)
        val tooMany = runCatching { a.engine.listeningTags(e - 100, e) }.exceptionOrNull()
        assertTrue("plage trop large refusée", tooMany is IllegalArgumentException)
        val inverted = runCatching { a.engine.listeningTags(e + 1, e) }.exceptionOrNull()
        assertTrue(inverted is IllegalArgumentException)
        assertFalse(a.engine.listeningTags(e, e).isEmpty())
    }
}
