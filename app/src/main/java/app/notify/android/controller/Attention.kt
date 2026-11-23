package app.notify.android.controller

import app.notify.android.remote.RemoteRequest

data class AttentionItem(val sessionID: String, val requestID: String, val sessionName: String, val requestTitle: String) {
    fun matches(request: RemoteRequest) = sessionID == request.sessionID && requestID == request.prompt.id
}
data class AttentionProjection(val epoch: String, val revision: Long, val current: AttentionItem?, val additionalCount: Int)
data class AttentionState(
    val projection: AttentionProjection? = null,
    val current: AttentionItem? = null,
    val fresh: Boolean = false,
    val handover: Boolean = false,
    private val canRebind: Boolean = false,
) {
    val additionalCount: Int? get() = projection?.additionalCount?.takeIf { fresh }
    fun stale() = copy(fresh = false, canRebind = false)
    fun receive(next: AttentionProjection?, observed: List<RemoteRequest>): AttentionState {
        if (next == null) return stale()
        if (next.epoch == projection?.epoch &&
            (next.revision < projection.revision || next.revision == projection.revision && next != projection)) return stale()
        val changedEpoch = projection != null && next.epoch != projection.epoch
        val waiting = current != null && (handover || changedEpoch)
        val reobserved = next.current?.let { candidate ->
            current?.sessionID == candidate.sessionID && current.requestID == candidate.requestID &&
                observed.any(candidate::matches)
        } == true
        if (waiting && !reobserved) return copy(projection = next, fresh = false, handover = true,
            canRebind = next.current?.let { candidate -> observed.any(candidate::matches) } == true)
        return AttentionState(next, next.current, fresh = true)
    }
    fun select(request: RemoteRequest): AttentionState =
        if (handover && canRebind && projection?.current?.matches(request) == true)
            AttentionState(projection, projection.current, fresh = true)
        else this
}
