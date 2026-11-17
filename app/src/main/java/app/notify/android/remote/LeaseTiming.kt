package app.notify.android.remote

import java.net.ProtocolException
import org.json.JSONObject

internal data class LeaseStart(val wall: Long, val mono: Long)
internal data class LeaseTiming(val expiresAt: Long, val deadline: Long, val remaining: Long)

internal fun leaseTiming(value: JSONObject, start: LeaseStart, now: LeaseStart): LeaseTiming {
    fun invalid(): Nothing = throw ProtocolException("Invalid or expired channel authorization lifetime.")
    fun integer(value: Any?): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value.takeIf { it in 0..9_007_199_254_740_991L } ?: invalid()
        else -> invalid()
    }
    val serverExpiry = integer(value.opt("expiresAt"))
    if (serverExpiry <= 0 || start.wall < 0 || start.mono < 0 ||
        now.wall < start.wall || now.mono < start.mono ||
        start.wall > 9_007_199_254_710_991L || start.mono > Long.MAX_VALUE - 30000) invalid()
    val expiresAt = if (value.has("validForMs")) {
        val duration = integer(value.opt("validForMs"))
        if (duration !in 1..30000) invalid()
        start.wall + duration
    } else {
        if (serverExpiry <= now.wall || serverExpiry - now.wall > 30000) invalid()
        minOf(serverExpiry, start.wall + 30000)
    }
    val deadline = start.mono + (expiresAt - start.wall)
    val remaining = minOf(expiresAt - now.wall, deadline - now.mono)
    if (remaining !in 1..30000) invalid()
    return LeaseTiming(expiresAt, deadline, remaining)
}
