package app.notify.android.controller

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.notify.android.account.PasskeyAccount
import app.notify.android.crypto.DeviceIdentity
import app.notify.android.connection.BackgroundConnectionNotification
import app.notify.android.connection.BackgroundConnectionService
import app.notify.android.notifications.NotificationSettings
import app.notify.android.notifications.NotificationState
import app.notify.android.remote.*
import java.io.IOException
import java.io.File
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONException
import org.json.JSONObject

data class ReplyReview(val card: RequestCard, val decision: ReplyDecision, val feedback: String, val preview: ReplyCommand)
data class ControllerState(
    val account: Account? = null, val relationship: Relationship? = null, val busy: Boolean = false,
    val remembered: RememberedState = RememberedState(),
    val rememberMessage: String? = null,
    val rememberedStorageUnconfirmed: Boolean = false,
    val status: RemoteStatus = RemoteStatus("disconnected", "Sign in, then connect your saved Mac or pair in person."),
    val error: String? = null, val inbox: RequestInbox = RequestInbox(), val workspace: WorkspaceState = WorkspaceState(),
    val foreground: Boolean = false, val now: Long = 0, val review: ReplyReview? = null,
    val controlReview: ControlCommand? = null, val notices: String? = null,
    val policyOffPending: Boolean = false,
    val notifications: NotificationState = NotificationState(),
    val notificationSetupOpen: Boolean = false,
    val notificationPermissionPending: Boolean = false,
    val connectionTrace: TraceSnapshot = TraceSnapshot(),
    val appearance: PhoneAppearance = PhoneAppearance.System,
    val savedHistory: ConversationHistory = ConversationHistory(),
    val savedScope: ConversationScope? = null,
    val offlineReader: Boolean = false,
    val cacheError: String? = null,
    val panel: PanelState = PanelState(),
    val dashboardEnabled: Boolean = false,
    val dashboardGeneration: Long = 0,
    val backgroundStarting: Boolean = false,
    val backgroundRunning: Boolean = false,
    val backgroundPermissionPending: Boolean = false,
    val visibilityGeneration: Long = 0,
    val refreshingVisibleState: Boolean = false,
    val attention: AttentionState = AttentionState(),
    val attentionOwner: Relationship? = null,
    val decisionFocus: Long = 0,
) {
    val deviceAccess get() = remembered.committed?.takeIf {
        it.relationship == relationship && it.expiresAt > System.currentTimeMillis() &&
            (account == null || account == it.relationship.account)
    }
    val connectionAccount get() = relationship?.takeIf {
        it.enrolled && (account == it.account || account == null && deviceAccess != null)
    }?.account
    val visibleAttention get(): AttentionState {
        if (connectionAccount == null ||
            attentionOwner != relationship) return AttentionState()
        return if (live) attention else attention.stale()
    }
    val attentionCard: RequestCard? get() = visibleAttention.takeIf { it.fresh }?.current?.let { current ->
        inbox.visibleCards.singleOrNull { current.matches(it.request) }
    }
    fun openAttention(): ControllerState {
        val card = attentionCard ?: return this
        return selectDecision(card.request.ticket).copy(workspace = workspace.select(card.request.sessionID),
            decisionFocus = decisionFocus + 1, offlineReader = false, notices = null, notificationSetupOpen = false,
            controlReview = null, panel = panel.copy(confirm = null))
    }
    fun selectDecision(ticket: String): ControllerState {
        val selected = inbox.select(ticket)
        val request = selected.visibleCards.single { it.request.ticket == ticket }.request
        return copy(inbox = selected, review = null,
            attention = if (live) visibleAttention.select(request) else attention)
    }
    val keepTransportWhenHidden get() = backgroundRunning && connectionAccount != null
    val pollVisibleState get() = foreground && status.state == "connected"
    val panelActionsEnabled get() = dashboardEnabled && live && panel.fresh && panel.pending == null
    fun panelReadDelay(visible: Boolean): Long? =
        if (dashboardEnabled && pollVisibleState) panelPollDelay(panel.available, visible) else null
    fun dashboardSetting(enabled: Boolean) = copy(dashboardEnabled = enabled,
        dashboardGeneration = dashboardGeneration + 1,
        panel = if (enabled) panel else panel.disable())
    fun panelMayDispatch(id: String, consent: Long) = dashboardEnabled && dashboardGeneration == consent &&
        live && panel.fresh && panel.pending?.id == id
    fun panelDraftKey(tabId: String, actionId: String): PanelDraftKey? = relationship?.takeIf {
        it.enrolled && it.account == connectionAccount
    }?.let { PanelDraftKey(ConversationScope.from(it), tabId, actionId) }
    fun visibility(active: Boolean, now: Long) = staleSnapshots().copy(foreground = active, now = now,
        visibilityGeneration = visibilityGeneration + 1, refreshingVisibleState = true,
        panel = panel.copy(fresh = false, confirm = null, pending = panel.pending?.takeIf { it.sending }))
    fun stopBackground() = retireConnection().copy(backgroundStarting = false, backgroundRunning = false,
        backgroundPermissionPending = false)
    val visibleSavedHistory get() = savedHistory.takeIf {
        relationship != null && savedScope == ConversationScope.from(relationship) &&
            (account == null || account == relationship.account)
    } ?: ConversationHistory()
    fun clearSavedHistory() = copy(savedHistory = ConversationHistory(), savedScope = null, offlineReader = false)
    fun reconnectable(): Relationship? = relationship?.takeIf { foreground && it.enrolled && it.account == connectionAccount }
    fun shouldOfferNotificationSetup() = status.state == "connected" && reconnectable() != null &&
        !notifications.setupOffered && !notifications.enabled && !notifications.cleanupPending
    val live get() = foreground && !refreshingVisibleState && status.state == "connected" && inbox.isLive(now)
    fun receiveRequests(requests: RequestSnapshot, receivedAt: Long): ControllerState {
        val updated = if (requests.connected) inbox.receive(inbox.generation, true, requests.requests, receivedAt)
            else inbox.copy(connected = false)
        val paired = connectionAccount != null
        val previous = attention.takeIf { paired && attentionOwner == relationship } ?: AttentionState()
        val next = if (paired && foreground && status.state == "connected" && requests.connected)
            previous.receive(requests.attention, requests.requests) else previous.stale()
        return copy(now = receivedAt, inbox = updated, attention = next, attentionOwner = relationship.takeIf { paired },
            review = review?.takeIf { review ->
            requests.connected && updated.cards.any { it == review.card }
        })
    }
    fun receiveWorkspace(snapshot: WorkspaceSnapshot, receivedAt: Long): ControllerState {
        val updated = workspace.receive(snapshot, receivedAt)
        return copy(now = receivedAt, workspace = updated, offlineReader = offlineReader && !snapshot.connected,
            controlReview = controlReview?.takeIf {
            if (it.isPolicy) updated.sessionID == it.sessionID && updated.session?.ticket == it.ticket &&
                it.agentID.isEmpty() && updated.select(updated.sessionID).policyUnavailable(receivedAt, it.enabled == true) == null &&
                updated.session?.dangerousAutoYes?.ticket == it.policyTicket
            else if (it.isManagement || it.isSessionAction) runCatching { updated.validate(it, receivedAt) }.isSuccess
            else updated.target == it.target && updated.unavailable(it.action, receivedAt) == null
        })
    }
    fun expireAccountSession() = if (deviceAccess != null) copy(account = null)
        else endAccountSession().copy(remembered = remembered)
    fun endAccountSession() = copy(account = null, remembered = RememberedState(operation = remembered.operation),
        inbox = RequestInbox(), workspace = WorkspaceState(), panel = PanelState(),
        attention = AttentionState(), attentionOwner = null,
        status = RemoteStatus("disconnected", "Sign in again to reconnect. Your Mac pairing is saved."),
        review = null, controlReview = null, notificationSetupOpen = false,
        backgroundStarting = false, backgroundRunning = false, backgroundPermissionPending = false)
    fun staleSnapshots() = copy(inbox = inbox.copy(connected = false), workspace = workspace.copy(receivedAt = null),
        review = null, controlReview = null, panel = panel.copy(fresh = false, confirm = null), attention = attention.stale())
    fun retireConnection(): ControllerState {
        val retired = inbox.beginConnection()
        val retained = inbox.cards.map { card ->
            if (card.delivery is Delivery.Pending) card.copy(delivery = Delivery.Unknown) else card
        }
        return staleSnapshots().copy(refreshingVisibleState = true,
            inbox = retired.copy(cards = retained, selectedTicket = inbox.selectedTicket),
            workspace = workspace.retirePending().copy(receivedAt = null), panel = panel.retire())
    }
}

class ControllerViewModel internal constructor(application: Application, private val runtime: ControllerRuntime?) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, null)
    private val mainScope get() = runtime?.scope ?: viewModelScope
    private val mainDispatcher get() = runtime?.main ?: Dispatchers.Main
    private fun elapsed() = runtime?.elapsed() ?: SystemClock.elapsedRealtime()
    private fun newService() = runtime?.service() ?: ServiceClient(SERVICE_ORIGIN.toHttpUrl())
    private val mutable = MutableStateFlow(runtime?.initialState ?: ControllerState())
    val state = mutable.asStateFlow()
    @Volatile private var service = newService()
    @Volatile private var userGeneration = 0L
    private var operationJob: Job? = null
    private var rememberPolling: Job? = null
    private var authorizationClock: Job? = null
    @Volatile private var pushGeneration = 0L
    private var signingOut: Job? = null
    private var cacheCleanup: Deferred<Exception?>? = null
    private var accountClock: Job? = null
    private val rememberPrefs by lazy { application.getSharedPreferences("notify-remembered-v1", Application.MODE_PRIVATE) }
    private val rememberStore = runtime?.remembered ?: RememberedStore(object : RelationshipStorage {
        override fun read() = rememberPrefs.getString("state", null)
        override fun write(value: String?) {
            check(rememberPrefs.edit().putString("state", value).commit()) { "Could not save remembered access." }
        }
    })
    private val prefs by lazy { application.getSharedPreferences("notify-relationship-v1", Application.MODE_PRIVATE) }
    private val appearancePrefs by lazy { application.getSharedPreferences("notify-appearance-v1", Application.MODE_PRIVATE) }
    private val appearancePreference by lazy { AppearancePreference({ appearancePrefs.getString("mode", null) },
        { appearancePrefs.edit().putString("mode", it).commit() }) }
    private val panelPrefs by lazy { application.getSharedPreferences("notify-dashboard-v1", Application.MODE_PRIVATE) }
    private val panelPreference by lazy { PanelPreference({ panelPrefs.getBoolean("enabled", false) },
        { panelPrefs.edit().putBoolean("enabled", it).commit() }) }
    private val conversationCache = runtime?.cache ?: ConversationCacheStore(
        EncryptedConversationStorage(File(application.noBackupFilesDir, "conversations-v1.enc")))
    private var conversationLease: ConversationLease? = null
    private var clearingConversations = false
    private val store = RelationshipStore(object : RelationshipStorage {
        override fun read() = prefs.getString("relationship", null)
        override fun write(value: String?) {
            check(prefs.edit().apply { if (value == null) remove("relationship") else putString("relationship", value) }.commit()) {
                "Could not save pairing metadata. Check device storage before continuing."
            }
        }
    }, object : IdentityKeys<DeviceIdentity> {
        override fun create(id: String) = DeviceIdentity.create(id).publicKey
        override fun load(id: String, expected: ByteArray) = DeviceIdentity.load(id, expected)
        override fun delete(id: String) = DeviceIdentity.delete(id)
    })
    private var connection: ControllerConnection? = null
    private var connectionAuthorization: RememberAuthorization? = null
    private var closingConnection: Job? = null
    @Volatile private var connectionGeneration = 0L
    private var polling: Job? = null
    private var panelPolling: Job? = null
    private val panelRefresh = Mutex()
    private var dashboardOpen = false
    private var clock: Job? = null
    private var resumeConnection = false
    private val backgroundConsent = BackgroundConnectionConsent()
    private val backgroundNotification by lazy { BackgroundConnectionNotification(application) }
    private var backgroundPermissionID: String? = null
    private var backgroundToken: String? = null
    private var attentionOpened = false
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationSettings by lazy { NotificationSettings(application) }
    private val notificationConsent = NotificationConsent()
    private var notificationPermissionWork: Job? = null
    private val connectionTrace = ConnectionTrace(::elapsed)
    private var traceUpdates: Job? = null
    private val notificationListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        mainScope.launch {
            refreshNotifications()
            if (key == "tokenHash" && state.value.foreground && !state.value.busy &&
                state.value.notifications.consent && state.value.connectionAccount != null) enableNotifications()
        }
    }

    init {
        if (runtime == null) {
            change { copy(appearance = appearancePreference.current, dashboardEnabled = panelPreference.enabled) }
            notificationSettings.prefs.registerOnSharedPreferenceChangeListener(notificationListener)
        }
        operation {
            val saved = if (runtime != null) runtime.initialState.relationship else withContext(Dispatchers.IO) { store.read() }
            change { copy(relationship = saved, remembered = rememberStore.state,
                rememberedStorageUnconfirmed = rememberStore.persistenceFailed,
                rememberMessage = if (rememberStore.persistenceFailed) "Remembered access storage is unavailable. Sign out to repair it." else null) }
            if (saved != null) restoreConversations(saved)
            refreshNotifications()
            watchAuthorization()
            if (saved != null && rememberStore.state.matching(saved)) {
                resumeConnection = true
                if (state.value.foreground) connect(saved)
            }
        }
    }
    private fun change(block: ControllerState.() -> ControllerState) {
        val previous = mutable.value
        val next = previous.block()
        mutable.value = if (previous.connectionAccount != next.connectionAccount || previous.relationship != next.relationship)
            next.copy(panel = PanelState()) else next
    }
    fun clearError() = change { copy(error = null) }
    fun readSavedConversations(open: Boolean) = change {
        copy(offlineReader = open && visibleSavedHistory.conversations.isNotEmpty(), review = null, controlReview = null)
    }
    fun retrySavedConversations() = operation {
        state.value.relationship?.let { restoreConversations(it) }
    }
    fun clearSavedConversations() = operation { eraseConversations() }
    private suspend fun restoreConversations(record: Relationship) {
        val lease = conversationCache.open(ConversationScope.from(record))
        conversationLease = lease
        try {
            val restored = withContext(Dispatchers.IO) { conversationCache.restore(lease) }
            if (conversationCache.current(lease)) change {
                copy(savedScope = lease.scope, savedHistory = restored, cacheError = null)
            }
        } catch (error: ConversationCacheException) {
            change { clearSavedHistory().copy(cacheError = error.message) }
        }
    }
    private suspend fun saveConversations(snapshot: WorkspaceSnapshot) {
        val record = state.value.relationship ?: return
        if (clearingConversations || state.value.connectionAccount != record.account || !snapshot.connected || snapshot.detail == null) return
        val lease = conversationLease ?: conversationCache.open(ConversationScope.from(record)).also { conversationLease = it }
        if (lease.scope != ConversationScope.from(record)) return
        // A transient disconnect must not cancel an in-flight local save. Revocation still fences it.
        withContext(NonCancellable) {
            try {
                val saved = withContext(Dispatchers.IO) { conversationCache.save(lease, snapshot, System.currentTimeMillis()) }
                if (saved != null && conversationCache.current(lease)) change {
                    copy(savedScope = lease.scope, savedHistory = saved, cacheError = null)
                }
            } catch (error: ConversationCacheException) {
                if (conversationCache.current(lease)) change { copy(cacheError = error.message) }
            }
        }
    }
    private suspend fun eraseConversations() {
        clearingConversations = true
        conversationCache.revoke()
        conversationLease = null
        change { clearSavedHistory() }
        try {
            withContext(NonCancellable + Dispatchers.IO) { conversationCache.clear() }
            change { copy(cacheError = null) }
        } finally { clearingConversations = false }
    }
    fun appearance(value: PhoneAppearance) = local {
        appearancePreference.set(value)
        change { copy(appearance = appearancePreference.current) }
    }
    fun foreground(active: Boolean) {
        connectionTrace.record(if (active) TraceEvent.FOREGROUND else TraceEvent.BACKGROUND)
        NotificationSettings.foreground = active
        refreshNotifications()
        stopVisibleJobs()
        change { visibility(active, elapsed()) }
        if (!active) {
            stopConnectionTrace()
            if (state.value.keepTransportWhenHidden) return
            val reconnect = connection != null
            disconnect()
            resumeConnection = reconnect
        } else if (state.value.backgroundRunning && backgroundNotification.unavailableReason() != null) {
            val reason = backgroundNotification.unavailableReason()
            disconnect()
            change { copy(error = reason) }
        } else if (connection != null && state.value.status.state == "connected") {
            startPolling(connectionGeneration)
        } else if (resumeConnection && (state.value.connectionAccount != null ||
                state.value.account == null && rememberStore.state.matching(state.value.relationship))) {
            resumeConnection = false
            retry()
        }
        if (active) watchRememberProposal() else { rememberPolling?.cancel(); rememberPolling = null }
    }
    fun requestBackgroundPermission(): String? {
        if (state.value.busy || state.value.backgroundStarting || state.value.backgroundPermissionPending) return null
        return try {
            backgroundConsent.begin(state.value).also { id ->
                backgroundPermissionID = id
                change { copy(backgroundPermissionPending = true, error = null) }
            }
        } catch (error: IllegalStateException) { failure(error); null }
    }
    fun backgroundPermissionResult(id: String, granted: Boolean) {
        if (id != backgroundPermissionID) return
        backgroundPermissionID = null
        change { copy(backgroundPermissionPending = false) }
        val token = backgroundConsent.permissionResult(id, granted, state.value)
        if (token == null) {
            change { copy(error = if (!granted)
                "Notifications were not allowed. Background connection is off. Allow notifications in Android settings, then try again; Mac attention alerts are unchanged."
                else "Connection, account or pairing changed. Return to your connected Mac and enable background connection again.") }
            return
        }
        backgroundNotification.createChannel()
        val unavailable = backgroundNotification.unavailableReason()
        if (unavailable != null) {
            backgroundConsent.cancel()
            change { copy(error = unavailable) }
            return
        }
        backgroundToken = token
        change { copy(backgroundStarting = true) }
        try {
            getApplication<Application>().startForegroundService(
                Intent(getApplication(), BackgroundConnectionService::class.java)
                    .setAction(BackgroundConnectionService.START).putExtra(BackgroundConnectionService.TOKEN, token))
        } catch (_: RuntimeException) {
            backgroundServiceFailed(token, "Android could not start the background connection. Keep Notify open and try again.")
        }
    }
    fun consumeBackgroundStart(token: String): Boolean {
        if (token != backgroundToken) return false
        if (backgroundConsent.consumeStart(token, state.value)) return true
        backgroundServiceFailed(token, "Background connection was not started. Return to your connected Mac and try again.")
        return false
    }
    fun isBackgroundSession(token: String) = backgroundToken == token
    fun backgroundServiceStarted(token: String) {
        if (isBackgroundSession(token)) change { copy(backgroundStarting = false, backgroundRunning = true) }
    }
    fun backgroundServiceFailed(token: String, message: String) {
        if (!isBackgroundSession(token)) return
        disconnect()
        change { copy(error = message) }
    }
    fun backgroundServiceStopped(token: String) {
        if (isBackgroundSession(token)) disconnect()
    }
    fun stopBackgroundConnection(token: String? = null) {
        if (token == null || isBackgroundSession(token)) disconnect()
    }
    fun openBackgroundSettings() = backgroundNotification.openSettings()
    private fun stopBackgroundService() {
        backgroundConsent.cancel()
        backgroundPermissionID = null
        backgroundToken = null
        if (runtime != null) runtime.stopBackground()
        else getApplication<Application>().stopService(Intent(getApplication(), BackgroundConnectionService::class.java))
    }
    private fun stopVisibleJobs() {
        polling?.cancel(); polling = null
        panelPolling?.cancel(); panelPolling = null
        clock?.cancel(); clock = null
    }
    private fun invalidate() {
        connectionTrace.record(TraceEvent.SNAPSHOT_STALE, category = TraceCategory.SNAPSHOT)
        change { staleSnapshots() }
    }
    fun disconnect() {
        stopBackgroundService()
        retireTransport(stoppingBackground = true)
    }
    private fun retireTransport(stoppingBackground: Boolean = false) {
        connectionGeneration++
        resumeConnection = false
        stopVisibleJobs()
        val old = connection; connection = null
        connectionAuthorization = null
        if (old != null) closingConnection = cleanup.launch { old.close() }
        change { if (stoppingBackground) stopBackground() else retireConnection() }
        change { copy(status = RemoteStatus("disconnected", "Disconnected. Pairing is saved; no actions are queued.")) }
    }
    fun signIn(activity: Activity, register: Boolean, name: String) = operation {
        userGeneration++
        val user = userGeneration
        rememberPolling?.cancel()
        accountClock?.cancel()
        cancelNotificationPermission()
        disconnect()
        cacheCleanup?.await()?.let { change { copy(cacheError = SignOutStep.Cache.recovery) } }
        val previous = service
        val client = newService().also { service = it }
        cleanup.launch { previous.close() }
        val account = runtime?.authenticate(client, register, name)
            ?: PasskeyAccount(client).authenticate(activity, register, name)
        check(user == userGeneration && client === service)
        if (state.value.relationship?.account != account) {
            eraseConversations()
            change { copy(workspace = WorkspaceState(), inbox = RequestInbox(), panel = PanelState(),
                attention = AttentionState(), attentionOwner = null) }
        }
        change { copy(account = account, status = RemoteStatus("disconnected", "Signed in. Mac trust is separate; connect or pair below.")) }
        watchAccountSession(client)
        refreshNotifications()
        if (state.value.notifications.consent && state.value.relationship?.account != account) {
            notificationSettings.disableLocally()
            refreshNotifications()
        } else if (state.value.notifications.consent && !state.value.notifications.registered) registerNotifications()
        val record = state.value.relationship
        if (record?.enrolled == true && record.account == account) {
            attentionOpened = false
            if (state.value.reconnectable() != null) connect(record) else resumeConnection = true
        }
        watchRememberProposal()
    }
    fun signOut() {
        val user = ++userGeneration
        pushGeneration++
        NotificationSettings.alertsBlocked = true
        operationJob?.cancel()
        rememberPolling?.cancel()
        authorizationClock?.cancel()
        accountClock?.cancel()
        cancelNotificationPermission()
        disconnect()
        val old = service
        val record = state.value.relationship
        val remembered = rememberStore.state
        val routing = record?.let { runCatching { routing(it) }.getOrNull() }
        val oldAccount = state.value.account
        val sign = try { record?.let { signer(it) } } catch (_: Exception) { null }
        val routingPossible = routing?.enabled == true || state.value.notifications.cleanupPending ||
            state.value.notifications.consent || state.value.notifications.registered
        val errors = mutableListOf<String>()
        try { rememberStore.off() } catch (error: Exception) { errors += error.message.orEmpty() }
        try { disableNotificationsLocally() } catch (_: Exception) { errors += SignOutStep.LocalNotifications.recovery }
        service = newService()
        conversationCache.revoke()
        conversationLease = null
        change { endAccountSession().clearSavedHistory().copy(busy = false, remembered = rememberStore.state,
            rememberedStorageUnconfirmed = rememberStore.persistenceFailed,
            rememberMessage = if (rememberStore.persistenceFailed) "Stopped in this process; durable Off could not be confirmed. Repair storage and retry sign-out."
                else "Off on this phone.", error = errors.joinToString("\n\n").ifEmpty { null }) }
        val clear = cleanup.async {
            try { conversationCache.clear(); null } catch (error: Exception) { error }
        }.also { cacheCleanup = it }
        // All cleanup owns the retired cookie jar and captured CAS guards, never the next login's client.
        signingOut = cleanup.launch {
            try {
                var revokedCommitted: RememberAuthorization? = null
                val failures = signOutCleanup(
                    disableNotificationsLocally = {},
                    eraseConversations = { clear.await()?.let { throw it } },
                    revokeAuthorizations = remembered.authorizations.map { authorization -> suspend {
                        check(sign != null)
                        val revoked = RememberApi(old::post, ::elapsed).revoke(authorization, { true }, sign)
                        if (!revoked.cancelledPending) revokedCommitted = authorization
                    } },
                    removeNotificationRouting = {
                        if (routingPossible) {
                            check(record != null && sign != null)
                            val removed = if (oldAccount == record.account)
                                PushRegistration(old::post, ::elapsed).update(record, null, routing?.registrationGeneration, sign = sign)
                            else {
                                val authorization = checkNotNull(revokedCommitted) {
                                    "The server's committed captured generation could not be confirmed."
                                }
                                RememberApi(old::post, ::elapsed).push(authorization, null,
                                    routing?.registrationGeneration, { true }, sign)
                            }
                            withContext(mainDispatcher) {
                                if (user == userGeneration) {
                                    saveRouting(record, removed)
                                    if (runtime != null) runtime.removedNotifications() else notificationSettings.removed()
                                    refreshNotifications()
                                }
                            }
                        }
                    },
                    logout = { old.post("/v1/account/logout", JSONObject()); Unit },
                    endSession = {},
                )
                errors += failures.map { if (it.first == SignOutStep.NotificationRouting)
                    "Push routing may separately remain active. Revoke this phone from your Mac to remove it."
                    else it.first.recovery }.distinct()
            } finally {
                old.close()
                withContext(mainDispatcher) {
                    if (user == userGeneration) change { copy(error = errors.joinToString("\n\n").ifEmpty { null }) }
                }
            }
        }
    }
    fun rememberPhone(activity: Activity) = operation {
        val record = checkNotNull(state.value.relationship).also { check(it.enrolled) }
        check(state.value.account == null || state.value.account == record.account) { "Sign in to this pairing's account first." }
        val user = ++userGeneration
        pushGeneration++
        cancelNotificationPermission()
        rememberPolling?.cancel()
        accountClock?.cancel()
        disconnect()
        val client = service
        val account = runtime?.authenticate(client, false, "")
            ?: PasskeyAccount(client).authenticate(activity, false, "")
        check(user == userGeneration && client === service && state.value.relationship == record)
        change { copy(account = account) }
        watchAccountSession(client)
        if (account != record.account) {
            disconnect()
            disableNotificationsLocally()
        }
        check(account == record.account) { "The passkey belongs to another account. This phone was not remembered." }
        verifyAccount(fresh = true)
        if (rememberStore.state.matching(record)) reconcileRemembered()
        check(rememberStore.state.pending == null) { "The existing Mac confirmation is still pending. Confirm it or sign out to cancel it first." }
        val previousOperation = rememberStore.state.operation
        val operation = rememberStore.begin(record)
        notificationSettings.advanceAuthority(previousOperation)
        change { copy(remembered = rememberStore.state, rememberMessage = "Preparing a new Mac confirmation…") }
        val identity = withContext(Dispatchers.IO) { store.identity(record) }
        val current = { user == userGeneration && client === service && state.value.relationship == record &&
            state.value.account == account && rememberStore.current(operation, record) }
        try {
            RememberApi(client::post, ::elapsed).enable(record, current, {
                rememberStore.propose(operation, it)
                change { copy(remembered = rememberStore.state, rememberMessage = "Pending · confirm this new request on your Mac.") }
            }, identity::sign)
        } finally {
            if (current()) {
                change { copy(remembered = rememberStore.state,
                    rememberMessage = if (rememberStore.state.pending != null)
                        "Pending or unconfirmed · check your Mac. No new authorization is active yet." else rememberMessage) }
                watchAuthorization()
                watchRememberProposal()
                if (state.value.foreground) connect(record)
            }
        }
    }
    fun checkRemembered() = operation {
        val record = checkNotNull(state.value.relationship)
        if (state.value.account == record.account) reconcileRemembered() else connect(record)
    }
    private suspend fun reconcileRemembered() {
        val record = state.value.relationship ?: return
        if (state.value.account != record.account || !rememberStore.state.matching(record)) return
        val user = userGeneration
        val client = service
        val operation = rememberStore.state.operation
        val previousAuthorization = rememberStore.state.committed
        val result = client.postScoped("/v1/remote/remember/status", RememberApi.ids(record))
        if (user != userGeneration || client !== service || state.value.relationship != record) return
        rememberStore.reconcile(operation, record, result)
        if (connectionAuthorization != null && connectionAuthorization !in rememberStore.state.authorizations) disconnect()
        if (previousAuthorization != null && rememberStore.state.committed == null) disableNotificationsLocally()
        saveRouting(record, PushMetadata.parse(result.getJSONObject("push")))
        change { copy(remembered = rememberStore.state, rememberMessage = when {
            rememberStore.state.pending != null -> "Pending · a new confirmation is required on your Mac."
            rememberStore.state.committed != null -> "On · approved by your Mac. Expiry does not extend automatically."
            else -> "Not active · the request was denied, cancelled, expired or is unavailable. Start a new opt-in to try again."
        }) }
        watchAuthorization()
    }
    private fun watchRememberProposal() {
        rememberPolling?.cancel()
        if (!state.value.foreground || state.value.account != state.value.relationship?.account ||
            rememberStore.state.pending == null) return
        rememberPolling = mainScope.launch {
            while (isActive && state.value.foreground && rememberStore.state.pending != null) {
                delay(3000)
                guarded {
                    reconcileRemembered()
                    if (rememberStore.state.pending?.expiresAt?.let { it <= System.currentTimeMillis() } == true)
                        change { copy(rememberMessage = "Confirmation window ended. Check the Mac result; sign out to cancel unconfirmed access before a new request.") }
                }
            }
        }
    }
    private fun watchAuthorization() {
        authorizationClock?.cancel()
        authorizationClock = mainScope.launch {
            while (isActive) {
                val authorization = rememberStore.state.committed ?: break
                val remaining = authorization.expiresAt - System.currentTimeMillis()
                if (remaining <= 0) {
                    disconnect()
                    local { rememberStore.reject(rememberStore.state.operation, authorization) }
                    local { disableNotificationsLocally() }
                    change { copy(remembered = rememberStore.state, rememberMessage = "Expired · start a new opt-in with a fresh passkey and Mac confirmation.") }
                    break
                }
                delay(minOf(remaining, 60_000L))
            }
        }
    }
    private fun watchAccountSession(client: ServiceClient) {
        accountClock?.cancel()
        val user = userGeneration
        val expiry = client.accountExpiresAt ?: return
        accountClock = mainScope.launch {
            delay((expiry - System.currentTimeMillis()).coerceIn(0, 3_600_000L))
            expireAccount(client, user)
        }
    }
    private suspend fun expireAccount(client: ServiceClient, user: Long) {
        if (client !== service || user != userGeneration) return
        val device = state.value.deviceAccess
        val accountTransport = connection != null && connectionAuthorization == null
        val backgroundOwner = backgroundToken?.takeIf { state.value.keepTransportWhenHidden }
        val reconnect = accountTransport && device != null && (state.value.foreground || backgroundOwner != null)
        if (device == null) disconnect()
        else if (accountTransport) retireTransport()
        change { expireAccountSession() }
        rememberPolling?.cancel()
        if (reconnect) guarded { connect(device!!.relationship, backgroundOwner) }
    }
    private suspend fun verifyAccount(fresh: Boolean = false): Account {
        val session = service.postScoped("/v1/account/session", JSONObject())
        val account = Account.parse(session.getJSONObject("account"))
        check(session.opt("scope") == "account" && account == state.value.account) { "Your account changed. Sign in again; pairing was kept." }
        check(!fresh || session.opt("fresh") == true) { "Pairing requires a fresh passkey sign-in. Sign in again first." }
        return account
    }
    fun pair(link: String, name: String) = operation {
        val setup = PairingLink.parse(link, service.origin)
        val account = verifyAccount(fresh = true)
        if (state.value.relationship == null) eraseConversations()
        val record = withContext(Dispatchers.IO) { store.create(account, setup, name.trim()) }
        change { copy(relationship = record, attention = AttentionState(), attentionOwner = null) }
        val pending = service.postScoped("/v1/remote/pair", JSONObject().put("setupID", setup.setupID)
            .put("deviceID", record.deviceID).put("publicKey", record.publicKey).put("name", record.name))
        check(pending.opt("macID") == record.macID && pending.opt("macPublicKey") == record.macPublicKey &&
            Account.parse(pending.getJSONObject("account")) == account) {
            "Pairing response does not match your pinned Mac and account. Do not approve. Forget this pairing and check your Mac."
        }
        val enrolled = withContext(Dispatchers.IO) { store.enrolled(record) }
        change { copy(relationship = enrolled) }
        connect(enrolled)
    }
    fun retry() = operation {
        val record = checkNotNull(state.value.relationship) { "Pair with your Mac first." }
        check(state.value.account == null || record.account == state.value.account) {
            "Sign in with the account used for this saved Mac. Pairing was kept."
        }
        if (state.value.account != null && state.value.deviceAccess == null) {
            try { verifyAccount() } catch (error: ServiceEndpointFailure) {
                if (!error.endsAccountSession || !rememberStore.state.matching(record)) throw error
                change { expireAccountSession() }
            }
        }
        check(state.value.account == record.account || rememberStore.state.matching(record)) { "Sign in or explicitly remember this phone first." }
        check(record.enrolled) {
            "Enrollment was not confirmed. Check the Mac, then explicitly forget and pair again in person; nothing was resent."
        }
        connect(record)
    }
    private suspend fun connect(record: Relationship, backgroundOwner: String? = null) {
        retireTransport()
        fun owned() = state.value.foreground || backgroundOwner != null && backgroundToken == backgroundOwner &&
            state.value.keepTransportWhenHidden
        check(owned()) { "Return to Notify, then tap Retry." }
        val generation = connectionGeneration
        val user = userGeneration
        val client = service
        val authorization = rememberStore.state.committed?.takeIf {
            it.relationship == record && it.expiresAt > System.currentTimeMillis()
        } ?: rememberStore.state.pending?.authorization?.takeIf {
            state.value.account == null && it.relationship == record && it.expiresAt > System.currentTimeMillis()
        }
        check(state.value.account == record.account || state.value.account == null && authorization != null) {
            "This account cannot access the saved pairing."
        }
        check(authorization == null || authorization.expiresAt > System.currentTimeMillis()) { "Remembered authorization expired. Start a new opt-in." }
        closingConnection?.join()
        val identity = if (runtime == null) withContext(Dispatchers.IO) { store.identity(record) } else null
        if (generation != connectionGeneration || user != userGeneration || client !== service || !owned()) return
        val current = {
                generation == connectionGeneration && user == userGeneration && client === service &&
                    state.value.relationship == record && (state.value.account == null || state.value.account == record.account) &&
                    (authorization == null && state.value.account == record.account ||
                        authorization != null && !rememberStore.persistenceFailed &&
                        authorization in rememberStore.state.authorizations)
        }
        val authorized: (RememberAuthorization, PushMetadata) -> Unit = { proof, push ->
                try {
                    check(user == userGeneration && generation == connectionGeneration &&
                        rememberStore.confirm(rememberStore.state.operation, proof)) { "Remembered access changed before grant completion." }
                } catch (error: Exception) {
                    val failedOperation = rememberStore.state.operation
                    if (rememberStore.persistenceFailed) mainScope.launch {
                        // The failed write invalidates authorityCurrent, so transport status cannot deliver this error.
                        if (user == userGeneration && client === service && state.value.relationship == record &&
                            rememberStore.persistenceFailed && rememberStore.state.operation == failedOperation) {
                            NotificationSettings.alertsBlocked = true
                            if (generation == connectionGeneration) disconnect()
                            val alerts = try { disableNotificationsLocally(); null }
                                catch (_: Exception) { SignOutStep.LocalNotifications.recovery }
                            change { copy(remembered = rememberStore.state, rememberedStorageUnconfirmed = true,
                                rememberMessage = "Stopped on this phone. Durable authorization could not be saved; repair storage and retry saving sign-out.",
                                error = listOfNotNull(error.message, alerts).joinToString("\n\n")) }
                        }
                    }
                    throw error
                }
                mainScope.launch {
                    if (user == userGeneration && generation == connectionGeneration) {
                        change { copy(remembered = rememberStore.state,
                            rememberMessage = "On · this device has Mac-approved access, not an account session.") }
                        local { saveRouting(record, push) }
                        watchAuthorization()
                    }
                }
        }
        val onStatus: (RemoteStatus) -> Unit = { status ->
            mainScope.launch {
                if (generation != connectionGeneration) return@launch
                change { copy(status = status) }
                if (status.state == "connected") startPolling(generation)
                else {
                    stopVisibleJobs()
                    if (status.state in setOf("connecting", "disconnected", "error")) change { retireConnection() }
                    else invalidate()
                    if (status.state == "error") {
                        if (authorization != null && rememberStore.state.committed == authorization) {
                            local { rememberStore.reject(rememberStore.state.operation, authorization) }
                            change { copy(remembered = rememberStore.state, rememberedStorageUnconfirmed = rememberStore.persistenceFailed) }
                            try { disableNotificationsLocally() } catch (_: Exception) { /* Authority is already retired. */ }
                        }
                        disconnect()
                        change { copy(status = status, error = "${status.message} Background connection is off. " +
                            "Choose Reconnect to check your sign-in; expired sessions require signing in again. " +
                            "Also check Remote Access on your Mac. Pairing is saved.") }
                    }
                }
            }
        }
        val remote = if (runtime != null) runtime.connection(client, record, authorization, current, authorized, onStatus)
            else RemoteConnection(client, checkNotNull(identity), record.macID, record.deviceID, decodeKey(record.macPublicKey),
                connectionTrace, authorization, current, authorized, onStatus)
        connection = remote
        connectionAuthorization = authorization
        withContext(Dispatchers.IO) { remote.connect() }
    }
    fun forget() {
        signOut()
        val user = userGeneration
        operation {
            signingOut?.join()
            check(user == userGeneration)
            withContext(Dispatchers.IO) { store.forget() }
            change { copy(relationship = null, inbox = RequestInbox(), workspace = WorkspaceState(), panel = PanelState(),
                attention = AttentionState(), attentionOwner = null) }
        }
    }
    private fun routing(record: Relationship) = if (runtime != null) runtime.routing(record) else notificationSettings.routing(record)
    private fun saveRouting(record: Relationship, metadata: PushMetadata) {
        if (runtime != null) runtime.routing(record, metadata) else notificationSettings.routing(record, metadata)
    }
    private fun signer(record: Relationship): (ByteArray) -> ByteArray =
        runtime?.signer(record) ?: store.identity(record)::sign
    fun refreshNotifications() = change { copy(notifications = runtime?.notifications() ?: notificationSettings.state()) }
    fun startConnectionTrace() {
        connectionTrace.start()
        traceUpdates?.cancel()
        traceUpdates = mainScope.launch {
            do {
                val snapshot = connectionTrace.snapshot()
                change { copy(connectionTrace = snapshot) }
                if (!snapshot.capturing) break
                delay(500)
            } while (true)
        }
    }
    fun stopConnectionTrace() {
        connectionTrace.stop()
        traceUpdates?.cancel()
        traceUpdates = null
        change { copy(connectionTrace = this@ControllerViewModel.connectionTrace.snapshot()) }
    }
    fun showNotificationSetup() = local {
        if (state.value.shouldOfferNotificationSetup()) {
            notificationSettings.markSetupOffered()
            change { copy(notificationSetupOpen = true, notifications = notificationSettings.state()) }
        }
    }
    fun finishNotificationSetup() = change { copy(notificationSetupOpen = false) }
    fun requestNotificationPermission(): Boolean {
        if (state.value.busy || state.value.notificationPermissionPending) return false
        return try {
            notificationConsent.begin(state.value.connectionAccount, state.value.relationship)
            change { copy(notificationPermissionPending = true) }
            true
        } catch (error: IllegalStateException) { failure(error); false }
    }
    fun notificationPermissionResult(granted: Boolean) {
        val request = notificationConsent.take() ?: return
        notificationPermissionWork = mainScope.launch {
            state.first { !it.busy }
            change { copy(notificationPermissionPending = false) }
            if (!notificationConsent.valid(request, state.value.connectionAccount, state.value.relationship)) {
                failure(IllegalStateException("Account or pairing changed. Alerts were not enabled; choose Enable alerts again."))
            } else if (!granted) notificationPermissionDenied()
            else operation { registerNotifications(request.relationship) }
        }
    }
    private fun cancelNotificationPermission() {
        notificationConsent.cancel()
        notificationPermissionWork?.cancel()
        notificationPermissionWork = null
        change { copy(notificationPermissionPending = false) }
    }
    fun notificationPermissionDenied() {
        refreshNotifications()
        change { copy(error = "Notifications were not allowed. They are not enabled; you can change this in Android settings.") }
    }
    fun openNotificationSettings() { notificationSettings.createChannel(); notificationSettings.openSettings() }
    fun useLongerVibration() = local {
        notificationSettings.useLongerVibration()
        refreshNotifications()
    }
    fun enableNotifications() = operation { registerNotifications() }
    private suspend fun registerNotifications(expected: Relationship? = null) {
        val record = checkNotNull(state.value.relationship) { "Pair with your Mac before enabling notifications." }
        check(expected == null || expected == record) { "Pairing changed. Choose Enable alerts again." }
        check(state.value.connectionAccount == record.account)
        val user = userGeneration
        val push = ++pushGeneration
        val client = service
        val authorization = state.value.deviceAccess
        val current = { user == userGeneration && push == pushGeneration && client === service &&
            state.value.relationship == record && state.value.connectionAccount == record.account &&
            (authorization == null || state.value.deviceAccess == authorization) }
        try {
            if (authorization == null) verifyAccount()
            // Current On registration may reconcile metadata; cleanup always uses its old captured guard.
            if (authorization == null) {
                val status = client.postScoped("/v1/remote/remember/status", RememberApi.ids(record))
                check(current())
                require(status.opt("macID") == record.macID && status.opt("deviceID") == record.deviceID)
                saveRouting(record, PushMetadata.parse(status.getJSONObject("push")))
            }
            val guard = routing(record)?.registrationGeneration
            val token = runtime?.notificationToken() ?: notificationSettings.beginRegistration()
            val sign = withContext(Dispatchers.IO) { signer(record) }
            check(current())
            val result = if (authorization == null)
                PushRegistration(client::postScoped, ::elapsed).update(record, token, guard, current, sign)
            else RememberApi(client::post, ::elapsed).push(authorization, token, guard, current, sign)
            check(current())
            saveRouting(record, result)
            if (runtime != null) runtime.registeredNotifications(token) else notificationSettings.registered(token)
        } finally { refreshNotifications() }
    }
    fun disableNotifications() = operation { cancelNotificationPermission(); removeNotifications() }
    private suspend fun removeNotifications() {
        pushGeneration++
        disableNotificationsLocally()
        removeNotificationRouting()
    }
    private fun disableNotificationsLocally() {
        try { if (runtime != null) runtime.disableNotifications() else notificationSettings.disableLocally() }
        finally { refreshNotifications() }
    }
    private suspend fun removeNotificationRouting() {
        val notifications = state.value.notifications
        if (!notifications.cleanupPending && !notifications.consent && !notifications.registered) return
        val record = checkNotNull(state.value.relationship) { "Notifications are off locally. Revoke this phone on your Mac to remove routing." }
        val user = userGeneration
        val push = pushGeneration
        val client = service
        val authorization = state.value.deviceAccess
        val guard = routing(record)?.registrationGeneration
        val current = { user == userGeneration && push == pushGeneration && client === service &&
            state.value.relationship == record && state.value.connectionAccount == record.account }
        if (authorization == null) check(verifyAccount() == record.account)
        try {
            val sign = withContext(Dispatchers.IO) { signer(record) }
            val result = if (authorization == null)
                PushRegistration(client::postScoped, ::elapsed).update(record, null, guard, current, sign)
            else RememberApi(client::post, ::elapsed).push(authorization, null, guard, current, sign)
            check(current())
            saveRouting(record, result)
            if (runtime != null) runtime.removedNotifications() else notificationSettings.removed()
        } finally { refreshNotifications() }
    }
    fun openAttention() {
        attentionOpened = true
        if (state.value.backgroundRunning && connection != null) {
            if (state.value.foreground && state.value.status.state == "connected") {
                invalidate()
                startPolling(connectionGeneration)
            }
            return
        }
        disconnect()
        if (state.value.connectionAccount != null) {
            if (state.value.foreground) { attentionOpened = false; retry() }
            else resumeConnection = true
        }
    }
    private fun startPolling(generation: Long) {
        if (!state.value.pollVisibleState) return
        val inboxGeneration = state.value.inbox.generation
        val visibility = state.value.visibilityGeneration
        polling?.cancel()
        clock?.cancel()
        clock = mainScope.launch {
            while (isActive && state.value.pollVisibleState) { change { copy(now = elapsed()) }; delay(500) }
        }
        polling = mainScope.launch {
            while (isActive && generation == connectionGeneration && state.value.foreground) {
                guarded {
                    val remote = checkNotNull(connection)
                    val requests = withContext(Dispatchers.IO) { Snapshots.requests(remote.request("state", JSONObject())) }
                    if (generation != connectionGeneration || inboxGeneration != state.value.inbox.generation ||
                        visibility != state.value.visibilityGeneration || !state.value.pollVisibleState) return@guarded
                    val now = elapsed()
                    change { receiveRequests(requests, now) }
                    val selected = state.value.workspace.sessionID
                    val snapshot = withContext(Dispatchers.IO) {
                        Snapshots.workspace(remote.request("workspace", JSONObject().put("sessionID", selected ?: JSONObject.NULL)))
                    }
                    if (generation != connectionGeneration || inboxGeneration != state.value.inbox.generation ||
                        selected != state.value.workspace.sessionID || visibility != state.value.visibilityGeneration ||
                        !state.value.pollVisibleState) return@guarded
                    change { receiveWorkspace(snapshot, elapsed()).copy(refreshingVisibleState = false) }
                    saveConversations(snapshot)
                }
                delay(2000)
            }
        }
        startPanel(generation)
    }
    fun dashboardVisible(visible: Boolean) {
        if (visible == dashboardOpen) return
        dashboardOpen = visible
        if (!visible) { if (state.value.panel.available) { panelPolling?.cancel(); panelPolling = null } }
        else if (connection != null && state.value.status.state == "connected") startPanel(connectionGeneration)
    }
    fun customDashboard(enabled: Boolean) = local {
        panelPreference.set(enabled)
        panelPolling?.cancel(); panelPolling = null
        change { dashboardSetting(enabled) }
        if (enabled) startPanel(connectionGeneration)
    }
    private fun startPanel(generation: Long) {
        if (state.value.panelReadDelay(dashboardOpen) == null) return
        panelPolling?.cancel()
        panelPolling = mainScope.launch {
            refreshPanel(generation)
            while (isActive && generation == connectionGeneration) {
                delay(state.value.panelReadDelay(dashboardOpen) ?: break)
                refreshPanel(generation)
            }
        }
    }
    private suspend fun refreshPanel(generation: Long) = panelRefresh.withLock {
        val remote = connection ?: return@withLock
        if (generation != connectionGeneration || state.value.panelReadDelay(dashboardOpen) == null) return@withLock
        val readGeneration = state.value.dashboardGeneration
        val inboxGeneration = state.value.inbox.generation
        val visibility = state.value.visibilityGeneration
        try {
            // Finish an already-started encrypted read without cancelling midway through MLS protection.
            val reply = withContext(Dispatchers.IO + NonCancellable) {
                if (generation != connectionGeneration || readGeneration != state.value.dashboardGeneration ||
                    visibility != state.value.visibilityGeneration || state.value.panelReadDelay(dashboardOpen) == null)
                    return@withContext null
                Snapshots.panel(remote.request("panel", JSONObject()))
            } ?: return@withLock
            if (generation == connectionGeneration && inboxGeneration == state.value.inbox.generation &&
                visibility == state.value.visibilityGeneration && readGeneration == state.value.dashboardGeneration &&
                state.value.dashboardEnabled && state.value.pollVisibleState)
                change { copy(panel = panel.receive(reply)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (generation == connectionGeneration && inboxGeneration == state.value.inbox.generation &&
                visibility == state.value.visibilityGeneration && readGeneration == state.value.dashboardGeneration &&
                state.value.dashboardEnabled && state.value.pollVisibleState)
                change { copy(panel = panel.copy(error = panelFailure(error, "Dashboard could not refresh."))) }
        }
    }
    fun previewPanelAction(action: PanelAction) = panelAction(action)
    fun editPanelDraft(tabId: String, actionId: String, text: String) {
        val key = state.value.panelDraftKey(tabId, actionId) ?: return
        if (text.length > 300) return
        change { copy(panel = panel.edit(key, text)) }
    }
    fun previewPanelForm(tabId: String) {
        val form = state.value.panel.feed?.tabs?.singleOrNull { it.id == tabId }?.form ?: return
        val key = state.value.panelDraftKey(tabId, form.actionId) ?: return
        val submission = state.value.panel.submission(key)
        val error = form.error(submission?.text.orEmpty())
        if (error != null) {
            change { copy(panel = panel.copy(message = error)) }
            return
        }
        panelAction(PanelAction(form.actionId, form.submitLabel), submission = submission)
    }
    fun sendPanelAction() {
        val confirm = state.value.panel.confirm ?: return
        if (confirm.submission != null && state.value.panel.submission(confirm.submission.key) != confirm.submission) {
            cancelPanelAction()
            return
        }
        change { copy(panel = panel.copy(confirm = null)) }
        panelAction(confirm.action, confirm, confirm.submission)
    }
    fun cancelPanelAction() = change { copy(panel = panel.copy(confirm = null)) }
    fun panelMessageShown(message: String) = change {
        if (panel.message == message) copy(panel = panel.copy(message = null)) else this
    }
    // Each tap is one request; a failed or uncertain send is reported, never retried.
    private fun panelAction(action: PanelAction, confirmation: PanelConfirm? = null, submission: PanelSubmission? = null) {
        val preview = confirmation == null
        val remote = connection
        if (state.value.panel.pending != null) return
        if (remote == null || !state.value.panelActionsEnabled) {
            change { copy(panel = panel.copy(message = "Wait for fresh Mac and Dashboard updates. Nothing was sent.")) }
            return
        }
        val generation = connectionGeneration
        val consent = state.value.dashboardGeneration
        val requestID = UUID.randomUUID().toString()
        val payload = if (confirmation == null) Snapshots.panelPreviewRequest(action, submission)
            else Snapshots.panelAction(confirmation)
        change { copy(panel = panel.copy(pending = PanelPending(requestID, action.id, sending = !preview, submission), confirm = null, message = null)) }
        mainScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    remote.request("panel_action", payload, requestID, dispatchAllowed = {
                        generation == connectionGeneration && state.value.panelMayDispatch(requestID, consent)
                    })
                }
                if (generation != connectionGeneration || state.value.panel.pending?.id != requestID) return@launch
                if (!preview) {
                    change { copy(panel = panel.complete(requestID, Snapshots.panelResult(result))) }
                    if (dashboardOpen) launch { refreshPanel(generation) }
                } else {
                    if (!state.value.dashboardEnabled || !state.value.live || !state.value.panel.fresh) {
                        change { copy(panel = panel.finish(requestID)) }
                        return@launch
                    }
                    val confirm = Snapshots.panelPreview(action, result, submission)
                    change { copy(panel = panel.finish(requestID, confirm = confirm, message = if (confirm == null) "Demo mode: nothing sent" else null)) }
                }
            } catch (cancelled: CancellationException) {
                if (generation == connectionGeneration) change { copy(panel = panel.finish(requestID, message = if (preview) null
                    else "Delivery is uncertain. Check the session on your Mac. Nothing was resent.")) }
                throw cancelled
            } catch (error: Exception) {
                if (generation == connectionGeneration) change { copy(panel = panel.finish(requestID, message = panelFailure(error, if (preview) "Could not prepare this action. Nothing was sent."
                    else "Delivery is uncertain. Check the session on your Mac. Nothing was resent."))) }
            }
        }
    }
    private fun panelFailure(error: Exception, fallback: String) = when (error) {
        is RemoteRequestFailure, is IllegalArgumentException -> error.message?.takeIf { it.isNotBlank() } ?: fallback
        else -> fallback
    }
    fun selectRequest(ticket: String) = local { change { selectDecision(ticket) } }
    fun openCurrentAttention(): Boolean {
        val current = state.value.copy(now = elapsed())
        val opened = current.openAttention()
        if (opened === current) return false
        change { opened }
        return true
    }
    fun answer(ticket: String, questionID: String, draft: AnswerDraft) = local {
        check(state.value.live) { "Wait for a fresh Mac update before editing a reply." }
        change { copy(inbox = inbox.edit(ticket, questionID, draft), review = null) }
    }
    fun reviewReply(ticket: String, decision: ReplyDecision, feedback: String) = local {
        check(state.value.live) { "Wait for a fresh Mac update." }
        val inbox = state.value.inbox
        val prepared = inbox.prepare(ticket, decision, elapsed(), feedback)
        change { copy(review = ReplyReview(inbox.cards.single { it.request.ticket == ticket }, decision, feedback, prepared.command)) }
    }
    fun cancelReview() = change { copy(review = null, controlReview = null) }
    fun confirmReply() = operation {
        val review = checkNotNull(state.value.review) { "Review the current request again." }
        change { copy(review = null) }
        check(state.value.live && state.value.inbox.cards.any { it == review.card }) { "The request changed. Review it again." }
        val generation = connectionGeneration
        val prepared = state.value.inbox.prepare(review.card.request.ticket, review.decision, elapsed(), review.feedback)
        change { copy(inbox = prepared.inbox) }
        var acknowledged = false
        try {
            checkNotNull(connection).request("reply", Snapshots.reply(prepared.command), prepared.command.id)
            if (generation == connectionGeneration) {
                change { copy(inbox = inbox.acknowledge(prepared.inbox.generation, prepared.command.id)) }
                acknowledged = true
            }
        } catch (error: Exception) {
            if (generation == connectionGeneration || error is CancellationException) throw error
        } finally {
            if (!acknowledged && generation == connectionGeneration)
                change { copy(inbox = inbox.uncertain(prepared.inbox.generation, prepared.command.id)) }
        }
    }
    fun selectSession(id: String?, agentID: String = "") {
        change { copy(workspace = workspace.select(id, agentID), controlReview = null) }
    }
    fun draft(text: String) = local { change { copy(workspace = workspace.edit(text), controlReview = null) } }
    fun creationDraft(value: SubagentCreation) = local {
        change { copy(workspace = workspace.editCreation(value), controlReview = null) }
    }
    fun prepareManagement(action: String, helperID: String = "") = local {
        check(state.value.live) { "Wait for a fresh Mac update." }
        val command = state.value.workspace.prepareManagement(action, elapsed(), helperID)
        if (action == "create_subagent") sendControl(command)
        else change { copy(controlReview = command) }
    }
    fun prepareControl(action: String) = local {
        check(state.value.live) { "Wait for a fresh Mac update." }
        val command = state.value.workspace.prepare(action, elapsed())
        if (!command.requiresConfirmation) sendControl(command) else change { copy(controlReview = command) }
    }
    fun confirmControl() { state.value.controlReview?.let(::sendControl) }
    fun prepareSessionAction(action: String, rgb: Int? = null, target: String? = null) = local {
        check(state.value.live) { "Wait for a fresh Mac update." }
        val command = state.value.workspace.prepareSessionAction(action, elapsed(), rgb, target)
        if (action in setOf("remove_session", "resume_session")) change { copy(controlReview = command) }
        else sendControl(command)
    }
    fun preparePolicy(enabled: Boolean) = local {
        check(state.value.live) { "Wait for a fresh Mac update. Off has not been confirmed." }
        val command = state.value.workspace.select(state.value.workspace.sessionID).preparePolicy(enabled, elapsed())
        if (enabled) change { copy(controlReview = command) }
        else if (!state.value.policyOffPending) {
            change { copy(policyOffPending = true, controlReview = null) }
            mainScope.launch {
                try { guarded { dispatchControl(command) } }
                finally { change { copy(policyOffPending = false) } }
            }
        }
    }
    private fun sendControl(command: ControlCommand) = operation { dispatchControl(command) }
    private suspend fun dispatchControl(command: ControlCommand) {
        change { copy(controlReview = null) }
        check(state.value.live) { "Wait for a fresh Mac update." }
        state.value.workspace.validate(command, elapsed())
        val generation = connectionGeneration
        change { copy(workspace = workspace.sending(command)) }
        var acknowledged = false
        try {
            val result = checkNotNull(connection).request("control", command.json(), command.id)
            if (generation == connectionGeneration) {
                if (command.isPolicy && state.value.workspace.policyDeliveries[command.target]?.commandID != command.id) {
                    acknowledged = true
                    return
                }
                if (command.isPolicy) {
                    val ok = result.bool("ok")
                    check(result.text("commandID", 128, true).isNotBlank() &&
                        result.text("status", 64, true) == if (ok) "applied" else "failed") {
                        "Invalid Mac policy acknowledgment. Check this session on Mac."
                    }
                    val policy = Snapshots.policy(result.getJSONObject("dangerousAutoYes"))
                    change { copy(workspace = workspace.acknowledgePolicy(command, policy, ok, result.optionalText("error")),
                        error = if (ok) error else result.optionalText("error")) }
                } else if (command.isSessionAction) {
                    change { copy(workspace = workspace.acknowledgeSessionAction(command, result)) }
                } else if (command.isManagement) {
                    change { copy(workspace = workspace.acknowledgeManagement(command, result)) }
                } else {
                    check(result.bool("ok")) { "The Mac did not acknowledge this control. Check the exact target; nothing was resent." }
                    change { copy(workspace = workspace.acknowledge(command, result.text("commandID", 256, true), result.text("status", 64, true))) }
                }
                acknowledged = true
            }
        } catch (error: Exception) {
            if (generation != connectionGeneration) {
                if (error is CancellationException) throw error
                return
            }
            if (error is RemoteRequestFailure && error.notDispatched && command.isPolicy && command.enabled == false &&
                generation == connectionGeneration) {
                change { copy(workspace = workspace.rejectPolicyBeforeDispatch(command, error.message.orEmpty())) }
                acknowledged = true
            }
            if (error is CancellationException || !command.isPolicy ||
                state.value.workspace.policyDeliveries[command.target]?.commandID == command.id) throw error
            acknowledged = true
        } finally {
            if (!acknowledged && generation == connectionGeneration) change { copy(workspace = workspace.uncertain(command)) }
        }
    }
    fun notices() = operation {
        val text = withContext(Dispatchers.IO) {
            listOf("notify-camera.txt", "notify-notifications.txt", "notify-crypto.txt").joinToString("\n\n") { name ->
                getApplication<Application>().assets.open("third-party/$name").bufferedReader().use { it.readText() }
            }
        }
        change { copy(notices = text) }
    }
    fun closeNotices() = change { copy(notices = null) }
    private fun local(block: () -> Unit) {
        try { block() }
        catch (error: IllegalArgumentException) { failure(error) }
        catch (error: IllegalStateException) { failure(error) }
    }
    private fun operation(block: suspend () -> Unit) {
        if (state.value.busy) return
        change { copy(busy = true, error = null) }
        val user = userGeneration
        operationJob = mainScope.launch {
            try { guarded(block) } finally {
                if (user == userGeneration || currentCoroutineContext()[Job] === operationJob) change { copy(busy = false) }
            }
        }
    }
    private suspend fun guarded(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: ConversationCacheException) { change { copy(cacheError = error.message) } }
        catch (_: GetCredentialCancellationException) { change { copy(error = "Passkey sign-in cancelled. Nothing changed.") } }
        catch (_: CreateCredentialCancellationException) { change { copy(error = "Passkey creation cancelled. Nothing changed.") } }
        catch (error: GetCredentialException) { change { copy(error = "Passkey sign-in unavailable: ${error.message}. Check your credential provider and try again.") } }
        catch (error: CreateCredentialException) { change { copy(error = "Passkey creation unavailable: ${error.message}. Check your credential provider and try again.") } }
        catch (error: IOException) { failure(error); invalidate() }
        catch (error: GeneralSecurityException) { failure(error) }
        catch (error: ProviderException) { failure(IllegalStateException("AndroidKeyStore could not use this phone identity. No replacement was created. Check device security settings.", error)) }
        catch (error: JSONException) { failure(IllegalArgumentException("Unsupported Mac or service response. Check on your Mac.", error)); invalidate() }
        catch (error: IllegalArgumentException) { failure(error); invalidate() }
        catch (error: IllegalStateException) { failure(error) }
    }
    private fun failure(error: Exception) {
        if (rememberStore.persistenceFailed) {
            NotificationSettings.alertsBlocked = true
            disconnect()
            change { copy(remembered = rememberStore.state, rememberedStorageUnconfirmed = true) }
        }
        if (error is ServiceEndpointFailure) {
            if (error.endsAccountSession) {
                val client = service
                val user = userGeneration
                mainScope.launch { expireAccount(client, user) }
            }
            change { withServiceFailure(error) }
        } else change { copy(error = when {
            error is HttpFailure && error.status >= 500 -> "Notify service is temporarily unavailable. Retry without re-pairing. ${error.message}"
            error is RemoteRequestFailure && error.status == 401 -> "Mac pairing authorization expired or was revoked. Check Remote Access on your Mac. Account sign-in is separate; pairing was kept. ${error.message}"
            error is IOException && error !is HttpFailure && error !is RemoteRequestFailure ->
                "Connection could not be confirmed. Check internet and keep your Mac awake. ${error.message} No action was retried."
            else -> error.message ?: "Could not complete this operation. Check your Mac and retry."
        }) }
    }

    override fun onCleared() {
        connectionTrace.stop()
        if (runtime == null) notificationSettings.prefs.unregisterOnSharedPreferenceChangeListener(notificationListener)
        val old = connection
        cleanup.launch { try { old?.close(); service.close() } finally { cleanup.cancel() } }
    }
}
