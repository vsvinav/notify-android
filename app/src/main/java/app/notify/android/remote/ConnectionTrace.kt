package app.notify.android.remote

import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.TimeoutCancellationException

enum class TraceEvent {
    CAPTURE_STARTED, CAPTURE_STOPPED, CAPTURE_TIMEOUT, FOREGROUND, BACKGROUND,
    ATTEMPT_STARTED, GRANT_BEGIN, GRANT_FINISHED, RELAY_OPENED, RELAY_CLOSED, RELAY_FAILURE,
    WELCOME_RECEIVED, AWAITING_READY, CONNECTED, RENEW_BEGIN, RENEW_SUCCESS, RENEW_FAILURE,
    HANDSHAKE_TIMEOUT, LEASE_EXPIRED, STATE_SENT, WORKSPACE_SENT, RPC_RESULT, RPC_TIMEOUT,
    SNAPSHOT_STALE, CHANNEL_FAILURE, RETRY_SCHEDULED, CHANNEL_RETIRED,
}
enum class TracePhase {
    NONE, CONNECTING, GREETING, WELCOME, APPROVAL, READY;
    companion object {
        fun from(stage: String) = when (stage) {
            "connecting" -> CONNECTING
            "greeting" -> GREETING
            "welcome" -> WELCOME
            "approval" -> APPROVAL
            "ready" -> READY
            else -> NONE
        }
    }
}
enum class TraceCategory {
    NONE, IO, TIMEOUT, HTTP_RETRYABLE, HTTP_TERMINAL, RELAY_CONFLICT, RENEWAL_REJECTED,
    PEER_CLOSE, PROTOCOL, CRYPTO, TLS, SNAPSHOT, OTHER;
    companion object {
        fun from(error: Throwable): TraceCategory = when (error) {
            is TimeoutCancellationException, is SocketTimeoutException -> TIMEOUT
            is SSLException -> TLS
            is HttpFailure -> if (error.status == 429 || error.status >= 500) HTTP_RETRYABLE else HTTP_TERMINAL
            is ProtocolException -> if (error.cause != null) CRYPTO else PROTOCOL
            is IOException -> IO
            else -> OTHER
        }
    }
}
data class TraceEntry(
    val elapsedMs: Long, val attempt: Int, val event: TraceEvent, val phase: TracePhase,
    val category: TraceCategory, val httpCode: Int?, val wsCode: Int?, val retryMs: Long?, val leaseMs: Long?,
)
data class TraceSnapshot(val capturing: Boolean = false, val entries: List<TraceEntry> = emptyList()) {
    val text: String get() = buildString {
        append("Notify connection trace v1 · ").append(if (capturing) "capturing" else "stopped")
        append("\nRelative milliseconds only; attempt 0 means no attempt started during capture.\n")
        for (entry in entries) with(entry) {
            append("+").append(elapsedMs).append("ms attempt=").append(attempt)
            append(" event=").append(event.name).append(" phase=").append(phase.name)
            append(" category=").append(category.name)
            httpCode?.let { append(" http=").append(it) }
            wsCode?.let { append(" ws=").append(it) }
            retryMs?.let { append(" retryMs=").append(it) }
            leaseMs?.let { append(" leaseMs=").append(it) }
            append('\n')
        }
    }
}

class ConnectionTrace(private val elapsed: () -> Long) {
    private val entries = ArrayDeque<TraceEntry>(64)
    private var capturing = false
    private var started = 0L
    private var attempt = 0
    private var firstAttempt = 0

    @Synchronized fun start() {
        entries.clear()
        started = elapsed()
        firstAttempt = attempt
        capturing = true
        record(TraceEvent.CAPTURE_STARTED)
    }
    @Synchronized fun stop() {
        if (active()) {
            record(TraceEvent.CAPTURE_STOPPED)
            capturing = false
        }
    }
    @Synchronized fun newAttempt(): Int {
        if (!active()) return 0
        record(TraceEvent.ATTEMPT_STARTED, ++attempt, TracePhase.CONNECTING)
        return attempt
    }
    @Synchronized fun record(
        event: TraceEvent, attempt: Int = 0, phase: TracePhase = TracePhase.NONE,
        category: TraceCategory = TraceCategory.NONE, httpCode: Int? = null, wsCode: Int? = null,
        retryMs: Long? = null, leaseMs: Long? = null,
    ) {
        if (!active()) return
        append(TraceEntry((elapsed() - started).coerceIn(0, 60_000), (attempt - firstAttempt).coerceAtLeast(0), event, phase, category,
            httpCode?.takeIf { it in 100..599 }, wsCode?.takeIf { it in 1000..4999 },
            retryMs?.takeIf { it in 0..30_000 }, leaseMs?.coerceIn(0, 30_000)))
    }
    @Synchronized fun snapshot(): TraceSnapshot {
        active()
        return TraceSnapshot(capturing, entries.toList())
    }
    private fun active(): Boolean {
        if (capturing && elapsed() - started >= 60_000) {
            append(TraceEntry(60_000, 0, TraceEvent.CAPTURE_TIMEOUT, TracePhase.NONE, TraceCategory.NONE, null, null, null, null))
            capturing = false
        }
        return capturing
    }
    private fun append(entry: TraceEntry) {
        if (entries.size == 64) entries.removeFirst()
        entries.addLast(entry)
    }
}
