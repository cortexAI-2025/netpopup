package com.netpopup.crypto

/** Stable unsigned lexicographic ordering without Android API 33's Arrays.compareUnsigned. */
object UnsignedBytes : Comparator<ByteArray> {
    override fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val difference = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return a.size.compareTo(b.size)
    }
}
