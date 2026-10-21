package com.netpopup.dtn.sync

import java.nio.ByteBuffer

/**
 * Trames d'une session de synchronisation (architecture mesh v0.1, section 4).
 *
 * | Octet 0 | Trame       | Contenu                                              |
 * |---------|-------------|------------------------------------------------------|
 * | 0x02    | `INVENTORY` | filtre de Bloom des IDs connus (taille, nb hachages, sel, bits) |
 * | 0x04    | `ENVELOPE`  | une enveloppe sérialisée ([com.netpopup.dtn.envelope.EnvelopeCodec]) |
 *
 * Les types 0x01 (`HELLO`) et 0x03 (`REQUEST`) restent réservés : voir [SyncSession].
 * Toute trame reçue est hostile : [FrameCodec.decode] ne lève jamais d'exception.
 */
sealed interface Frame {
    class Inventory(val filter: BloomFilter) : Frame
    class EnvelopeFrame(val bytes: ByteArray) : Frame
}

object FrameCodec {

    /** Taille maximale d'une trame : charge utile `BYTES` de Nearby Connections. */
    const val MAX_FRAME_SIZE = 32_768

    private const val TYPE_INVENTORY: Byte = 0x02
    private const val TYPE_ENVELOPE: Byte = 0x04
    private const val INVENTORY_HEADER = 1 + 4 + 1 + BloomFilter.SALT_SIZE

    fun encodeInventory(filter: BloomFilter): ByteArray {
        val bits = filter.bits
        return ByteBuffer.allocate(INVENTORY_HEADER + bits.size)
            .put(TYPE_INVENTORY)
            .putInt(filter.bitCount)
            .put(filter.hashCount.toByte())
            .put(filter.salt)
            .put(bits)
            .array()
            .also { require(it.size <= MAX_FRAME_SIZE) { "inventory exceeds $MAX_FRAME_SIZE bytes" } }
    }

    fun encodeEnvelope(envelopeBytes: ByteArray): ByteArray {
        require(envelopeBytes.size + 1 <= MAX_FRAME_SIZE) { "envelope frame exceeds $MAX_FRAME_SIZE bytes" }
        return ByteArray(envelopeBytes.size + 1).also {
            it[0] = TYPE_ENVELOPE
            envelopeBytes.copyInto(it, 1)
        }
    }

    /** Null si la trame est vide, trop grande, de type inconnu ou mal formée. */
    fun decode(frame: ByteArray): Frame? {
        if (frame.isEmpty() || frame.size > MAX_FRAME_SIZE) return null
        return when (frame[0]) {
            TYPE_ENVELOPE -> if (frame.size > 1) Frame.EnvelopeFrame(frame.copyOfRange(1, frame.size)) else null
            TYPE_INVENTORY -> decodeInventory(frame)
            else -> null
        }
    }

    private fun decodeInventory(frame: ByteArray): Frame? {
        if (frame.size < INVENTORY_HEADER) return null
        val buf = ByteBuffer.wrap(frame)
        buf.get()
        val bitCount = buf.getInt()
        val hashCount = buf.get().toInt() and 0xFF
        val salt = ByteArray(BloomFilter.SALT_SIZE).also { buf.get(it) }
        // bitCount hostile : on borne avant d'allouer quoi que ce soit
        if (bitCount !in 1..(MAX_FRAME_SIZE * 8)) return null
        if (buf.remaining() != BloomFilter.byteCountFor(bitCount)) return null
        val bits = ByteArray(buf.remaining()).also { buf.get(it) }
        return BloomFilter.fromWire(bitCount, hashCount, salt, bits)?.let(Frame::Inventory)
    }
}
