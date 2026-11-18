package app.notify.android.controller

class NotificationConsent {
    class Request internal constructor(val generation: Long, val account: Account, val relationship: Relationship)
    private var generation = 0L
    private var pending: Request? = null

    fun begin(account: Account?, relationship: Relationship?) {
        check(pending == null && account != null && relationship?.enrolled == true && relationship.account == account) {
            "Sign in to this phone’s paired account before enabling alerts."
        }
        pending = Request(++generation, account, relationship)
    }

    fun take(): Request? = pending.also { pending = null }
    fun valid(request: Request, account: Account?, relationship: Relationship?) =
        request.generation == generation && request.account == account && request.relationship == relationship
    fun cancel() { generation++; pending = null }
}
