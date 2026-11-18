package app.notify.android.notifications

data class NotificationState(
    val consent: Boolean = false, val permission: Boolean = false,
    val channel: Boolean = false, val registered: Boolean = false,
    val cleanupPending: Boolean = false, val setupOffered: Boolean = false,
) {
    val enabled get() = consent && permission && channel && registered
    val description get() = when {
        cleanupPending -> "Off on this phone. Sign in and finish removing its saved notification routing."
        !consent -> "Off. Choose whether Notify can alert you when your Mac needs attention."
        !permission -> "Blocked by Android. Allow notifications in system settings, then refresh here."
        !channel -> "This notification channel is off. You control sound and vibration in Android settings."
        !registered -> "Not connected for notifications. Sign in to your paired account, then refresh registration."
        else -> "On. Alerts contain no request or chat content. Open Notify to check the current Mac state."
    }
}

object Attention {
    fun accept(data: Map<String, String>, now: Long, seen: Set<String>): String? {
        if (data.keys != setOf("type", "version", "eventID", "issuedAt") ||
            data["type"] != "attention" || data["version"] != "1") return null
        val id = data["eventID"] ?: return null
        if (!Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}").matches(id) || id in seen) return null
        val issued = data["issuedAt"]?.takeIf { Regex("[0-9]{13}").matches(it) }?.toLongOrNull() ?: return null
        return id.takeIf { issued <= now + 5000 && now - issued < 120000 }
    }
}
