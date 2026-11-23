package app.notify.android.controller

import app.notify.android.notifications.NotificationState
import app.notify.android.remote.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import java.io.Closeable
import java.util.UUID
import org.json.JSONObject

interface ControllerConnection : Closeable {
    fun connect()
    suspend fun request(path: String, data: JSONObject, id: String = UUID.randomUUID().toString(),
        dispatchAllowed: (() -> Boolean)? = null): JSONObject
}

/** Platform effects are injectable; controller transitions and callbacks stay in ControllerViewModel. */
internal interface ControllerRuntime {
    val scope: CoroutineScope
    val main: CoroutineDispatcher
    val initialState: ControllerState
    val remembered: RememberedStore
    val cache: ConversationCacheStore
    fun service(): ServiceClient
    suspend fun authenticate(client: ServiceClient, register: Boolean, name: String): Account
    fun elapsed(): Long
    suspend fun connection(client: ServiceClient, record: Relationship, authorization: RememberAuthorization?,
        current: () -> Boolean, authorized: (RememberAuthorization, PushMetadata) -> Unit,
        status: (RemoteStatus) -> Unit): ControllerConnection
    fun signer(record: Relationship): (ByteArray) -> ByteArray
    fun notifications(): NotificationState
    suspend fun notificationToken(): String
    fun registeredNotifications(token: String)
    fun routing(record: Relationship): PushMetadata?
    fun routing(record: Relationship, metadata: PushMetadata)
    fun disableNotifications()
    fun removedNotifications()
    fun stopBackground()
}
