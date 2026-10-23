package com.netpopup.crypto

import com.netpopup.crypto.room.AdmissionPolicy
import com.netpopup.crypto.room.RoomException
import com.netpopup.crypto.room.RoomInvite
import com.netpopup.crypto.room.RoomState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Fuzzing des décodeurs de données venues d'un tiers : bundle de contact, invitation de room, état de room.
 * Seules les exceptions *documentées* sont admises ; toute autre (indice, tampon, mémoire, cast) est un défaut.
 * Itérations bornées par `FUZZ_ITERATIONS` (défaut 3 000), graine fixe.
 */
class ParserFuzzTest {
    private val iterations = System.getenv("FUZZ_ITERATIONS")?.toIntOrNull() ?: 3_000

    private fun mutate(bytes: ByteArray, random: Random): ByteArray {
        var b = bytes.copyOf()
        repeat(1 + random.nextInt(4)) {
            when (random.nextInt(4)) {
                0 -> if (b.isNotEmpty()) b[random.nextInt(b.size)] = random.nextInt(256).toByte()
                1 -> if (b.isNotEmpty()) { val i = random.nextInt(b.size); b[i] = (b[i].toInt() xor (1 shl random.nextInt(8))).toByte() }
                2 -> b = b.copyOf(random.nextInt(b.size + 1))
                3 -> b += random.nextBytes(random.nextInt(32))
            }
        }
        return b
    }

    private inline fun fuzz(seed: ByteArray, random: Random, allowed: (Throwable) -> Boolean, decode: (ByteArray) -> Unit) {
        repeat(iterations) {
            val input = if (random.nextInt(6) == 0) random.nextBytes(random.nextInt(4096)) else mutate(seed, random)
            try {
                decode(input)
            } catch (e: Throwable) {
                if (!allowed(e)) throw AssertionError("undocumented ${e::class.simpleName} for ${input.size}-byte input", e)
            }
        }
    }

    @Test fun contactBundleDecoderOnlyThrowsInvalidBundle() {
        val seed = TestDevice("Fuzz").bundle().encode()
        fuzz(seed, Random(3), { it is InvalidBundleException }) { ContactBundle.decode(it) }
    }

    @Test fun contactUriDecoderOnlyThrowsInvalidBundle() {
        val seed = TestDevice("Fuzz").bundle().toUri().toByteArray()
        fuzz(seed, Random(4), { it is InvalidBundleException }) { ContactBundle.fromUri(String(it, Charsets.ISO_8859_1)) }
    }

    @Test fun roomInviteDecoderOnlyThrowsRoomException() {
        val creator = TestDevice("Creator")
        val invite = creator.rooms.create("Fuzz", AdmissionPolicy.ANY_MEMBER).second
        fuzz(invite.encode(), Random(5), { it is RoomException }) { RoomInvite.decode(it) }
    }

    @Test fun roomStateDecoderOnlyThrowsRoomException() {
        val creator = TestDevice("Creator")
        val state = creator.rooms.create("Fuzz", AdmissionPolicy.ANY_MEMBER).first
        fuzz(state.encode(), Random(6), { it is RoomException }) { RoomState.decode(it) }
    }

    @Test fun hmacSha256Rfc4231Vectors() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        // HMAC(key, data) = HKDF-Extract(salt = key, ikm = data)
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            Hkdf.extract(hex("0b".repeat(20)), "Hi There".toByteArray()).joinToString("") { "%02x".format(it) },
        )
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            Hkdf.extract("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray()).joinToString("") { "%02x".format(it) },
        )
    }
}
