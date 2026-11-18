package app.notify.android.remote

import okhttp3.HttpUrl

class PairingScan(private val origin: HttpUrl) {
    private var stopped = false

    @Synchronized fun accept(value: String): PairingLink? {
        if (stopped) return null
        val pairing = PairingLink.parse(value, origin)
        stopped = true
        return pairing
    }

    @Synchronized fun stop() { stopped = true }
}
