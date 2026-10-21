package com.netpopup.dtn.sync

/**
 * Seau à jetons (architecture mesh v0.1, section 3) : rafale de [capacity]
 * envois, puis [refillPerSecond] par seconde. Plein au départ.
 */
class TokenBucket(
    private val capacity: Int,
    private val refillPerSecond: Double
) {
    init {
        require(capacity >= 0) { "capacity must be >= 0" }
        require(refillPerSecond >= 0.0) { "refillPerSecond must be >= 0" }
    }

    private var tokens = capacity.toDouble()
    private var lastMillis: Long? = null

    fun tryTake(nowMillis: Long): Boolean {
        refill(nowMillis)
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }

    private fun refill(nowMillis: Long) {
        val last = lastMillis
        if (last != null && nowMillis > last) {
            tokens = (tokens + (nowMillis - last) / 1000.0 * refillPerSecond).coerceAtMost(capacity.toDouble())
        }
        if (last == null || nowMillis > last) lastMillis = nowMillis
    }
}
