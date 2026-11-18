package app.notify.android.controller

import android.app.Activity
import android.app.Application
import android.os.SystemClock
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.notify.android.account.PasskeyAccount
import app.notify.android.crypto.DeviceIdentity
import app.notify.android.notifications.NotificationSettings
import app.notify.android.notifications.NotificationState
import app.notify.android.remote.*
import java.io.IOException
import java.io.File
import java.security.GeneralSecurityException
import java.security.ProviderException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONException
import org.json.JSONObject

data class ReplyReview(val card: RequestCard, val decision: ReplyDecision, val feedback: String, val preview: ReplyCommand)
data class ControllerState(
    val account: Account? = null, val relationship: Relationship? = null, val busy: Boolean = false,
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
) {
    val visibleSavedHistory get() = savedHistory.takeIf {
        relationship != null && savedScope == ConversationScope.from(relationship) &&
            (account == null || account == relationship.account)
    } ?: ConversationHistory()
    fun clearSavedHistory() = copy(savedHistory = ConversationHistory(), savedScope = null, offlineReader = false)
    fun reconnectable(): Relationship? = relationship?.takeIf { foreground && it.enrolled && it.account == account }
    fun shouldOfferNotificationSetup() = status.state == "connected" && reconnectable() != null &&
        !notifications.setupOffered && !notifications.enabled && !notifications.cleanupPending
    val live get() = foreground && status.state == "connected" && inbox.isLive(now)
    fun receiveRequests(requests: RequestSnapshot, receivedAt: Long): ControllerState {
        val updated = if (requests.connected) inbox.receive(inbox.generation, true, requests.requests, receivedAt)
            else inbox.copy(connected = false)
        return copy(now = receivedAt, inbox = updated, review = review?.takeIf { review ->
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
    fun endAccountSession() = copy(account = null, inbox = RequestInbox(), workspace = WorkspaceState(),
        status = RemoteStatus("disconnected", "Sign in again to reconnect. Your Mac pairing is saved."),
        review = null, controlReview = null, notificationSetupOpen = false)
    fun staleSnapshots() = copy(inbox = inbox.copy(connected = false), workspace = workspace.copy(receivedAt = null),
        review = null, controlReview = null)
    fun retireConnection(): ControllerState {
        val retired = inbox.beginConnection()
        val retained = inbox.cards.map { card ->
            if (card.delivery is Delivery.Pending) card.copy(delivery = Delivery.Unknown) else card
        }
        return staleSnapshots().copy(inbox = retired.copy(cards = retained, selectedTicket = inbox.selectedTicket))
    }
}

class ControllerViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(ControllerState())
    val state = mutable.asStateFlow()
    private val service = ServiceClient(SERVICE_ORIGIN.toHttpUrl())
    private val prefs = application.getSharedPreferences("notify-relationship-v1", Application.MODE_PRIVATE)
    private val appearancePrefs = application.getSharedPreferences("notify-appearance-v1", Application.MODE_PRIVATE)
    private val appearancePreference = AppearancePreference({ appearancePrefs.getString("mode", null) },
        { appearancePrefs.edit().putString("mode", it).commit() })
    private val conversationCache = ConversationCacheStore(
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
    private var connection: RemoteConnection? = null
    private var connectionGeneration = 0L
    private var polling: Job? = null
    private var clock: Job? = null
    private var resumeConnection = false
    private var attentionOpened = false
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationSettings = NotificationSettings(application)
    private val notificationConsent = NotificationConsent()
    private var notificationPermissionWork: Job? = null
    private val connectionTrace = ConnectionTrace(SystemClock::elapsedRealtime)
    private var traceUpdates: Job? = null
    private val notificationListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        viewModelScope.launch {
            refreshNotifications()
            if (key == "tokenHash" && state.value.foreground && !state.value.busy &&
                state.value.notifications.consent && state.value.account != null &&
                state.value.relationship?.account == state.value.account) enableNotifications()
        }
    }

    init {
        change { copy(appearance = appearancePreference.current) }
        notificationSettings.prefs.registerOnSharedPreferenceChangeListener(notificationListener)
        operation {
            val saved = withContext(Dispatchers.IO) { store.read() }
            change { copy(relationship = saved) }
            if (saved != null) restoreConversations(saved)
            refreshNotifications()
        }
    }
    private fun change(block: ControllerState.() -> ControllerState) { mutable.value = mutable.value.block() }
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
        if (clearingConversations || state.value.account != record.account || !snapshot.connected || snapshot.detail == null) return
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
        change { copy(foreground = active, now = SystemClock.elapsedRealtime(), review = null, controlReview = null) }
        if (!active) {
            val reconnect = connection != null
            disconnect()
            resumeConnection = reconnect
        } else if (resumeConnection && state.value.account != null) {
            resumeConnection = false
            retry()
        }
    }
    private fun invalidate() {
        connectionTrace.record(TraceEvent.SNAPSHOT_STALE, category = TraceCategory.SNAPSHOT)
        change { staleSnapshots() }
    }
    fun disconnect() {
        connectionGeneration++
        resumeConnection = false
        polling?.cancel(); polling = null
        clock?.cancel(); clock = null
        val old = connection; connection = null
        cleanup.launch { old?.close() }
        change { retireConnection() }
        change { copy(status = RemoteStatus("disconnected", "Disconnected. Pairing is saved; no actions are queued.")) }
    }
    fun signIn(activity: Activity, register: Boolean, name: String) = operation {
        cancelNotificationPermission()
        disconnect()
        val account = PasskeyAccount(service).authenticate(activity, register, name)
        if (state.value.relationship?.account != account) {
            eraseConversations()
            change { copy(workspace = WorkspaceState(), inbox = RequestInbox()) }
        }
        change { copy(account = account, status = RemoteStatus("disconnected", "Signed in. Mac trust is separate; connect or pair below.")) }
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
    }
    fun signOut() = operation {
        cancelNotificationPermission()
        disconnect()
        val failures = signOutCleanup(
            disableNotificationsLocally = ::disableNotificationsLocally,
            eraseConversations = ::eraseConversations,
            removeNotificationRouting = ::removeNotificationRouting,
            logout = { service.postScoped("/v1/account/logout", JSONObject()); Unit },
            endSession = { disconnect(); change { endAccountSession() } },
        )
        change {
            copy(cacheError = failures.firstOrNull { it.first == SignOutStep.Cache }?.first?.recovery,
                error = failures.filterNot { it.first == SignOutStep.Cache }
                    .joinToString("\n\n") { it.first.recovery }.ifEmpty { null })
        }
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
        change { copy(relationship = record) }
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
        verifyAccount()
        check(record.account == state.value.account) { "Sign in with the account used for this saved Mac. Pairing was kept." }
        check(record.enrolled) {
            "Enrollment was not confirmed. Check the Mac, then explicitly forget and pair again in person; nothing was resent."
        }
        connect(record)
    }
    private suspend fun connect(record: Relationship) {
        disconnect()
        check(state.value.foreground) { "Return to Notify, then tap Retry." }
        val generation = connectionGeneration
        val identity = withContext(Dispatchers.IO) { store.identity(record) }
        if (generation != connectionGeneration || !state.value.foreground) return
        val remote = RemoteConnection(service, identity, record.macID, record.deviceID, decodeKey(record.macPublicKey), connectionTrace) { status ->
            viewModelScope.launch {
                if (generation != connectionGeneration) return@launch
                change { copy(status = status) }
                if (status.state == "connected") startPolling(generation)
                else {
                    polling?.cancel(); polling = null
                    clock?.cancel(); clock = null
                    if (status.state in setOf("connecting", "disconnected", "error")) change { retireConnection() }
                    else invalidate()
                }
            }
        }
        connection = remote
        withContext(Dispatchers.IO) { remote.connect() }
    }
    fun forget() = operation {
        cancelNotificationPermission()
        disconnect()
        eraseConversations()
        removeNotifications()
        disconnect()
        withContext(Dispatchers.IO) { store.forget() }
        change { copy(relationship = null, inbox = RequestInbox(), workspace = WorkspaceState()) }
    }
    fun refreshNotifications() = change { copy(notifications = notificationSettings.state()) }
    fun startConnectionTrace() {
        connectionTrace.start()
        traceUpdates?.cancel()
        traceUpdates = viewModelScope.launch {
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
            notificationConsent.begin(state.value.account, state.value.relationship)
            change { copy(notificationPermissionPending = true) }
            true
        } catch (error: IllegalStateException) { failure(error); false }
    }
    fun notificationPermissionResult(granted: Boolean) {
        val request = notificationConsent.take() ?: return
        notificationPermissionWork = viewModelScope.launch {
            state.first { !it.busy }
            change { copy(notificationPermissionPending = false) }
            if (!notificationConsent.valid(request, state.value.account, state.value.relationship)) {
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
        val account = verifyAccount()
        check(record.account == account && state.value.relationship == record) {
            "Sign in with this pairing's account. Notifications were not registered."
        }
        try {
            val token = notificationSettings.beginRegistration()
            val identity = withContext(Dispatchers.IO) { store.identity(record) }
            check(state.value.account == account && state.value.relationship == record) {
                "Account or pairing changed. Choose Enable alerts again."
            }
            PushRegistration({ path, body -> service.postScoped(path, body) }).update(record, token, identity::sign)
            notificationSettings.registered(token)
        } finally { refreshNotifications() }
    }
    fun disableNotifications() = operation { cancelNotificationPermission(); removeNotifications() }
    private suspend fun removeNotifications() {
        disableNotificationsLocally()
        removeNotificationRouting()
    }
    private fun disableNotificationsLocally() {
        try { notificationSettings.disableLocally() } finally { refreshNotifications() }
    }
    private suspend fun removeNotificationRouting() {
        val notifications = state.value.notifications
        if (!notifications.cleanupPending && !notifications.consent && !notifications.registered) return
        val record = checkNotNull(state.value.relationship) { "Notifications are off locally. Revoke this phone on your Mac to remove routing." }
        check(verifyAccount() == record.account) { "Sign in to the paired account to remove notification routing." }
        try {
            val identity = withContext(Dispatchers.IO) { store.identity(record) }
            PushRegistration({ path, body -> service.postScoped(path, body) }).update(record, null, identity::sign)
            notificationSettings.removed()
        } finally { refreshNotifications() }
    }
    fun openAttention() {
        attentionOpened = true
        disconnect()
        if (state.value.account != null && state.value.relationship != null) {
            if (state.value.foreground) { attentionOpened = false; retry() }
            else resumeConnection = true
        }
    }
    private fun startPolling(generation: Long) {
        val inboxGeneration = state.value.inbox.generation
        polling?.cancel()
        clock?.cancel()
        clock = viewModelScope.launch {
            while (isActive) { change { copy(now = SystemClock.elapsedRealtime()) }; delay(500) }
        }
        polling = viewModelScope.launch {
            while (isActive && generation == connectionGeneration && state.value.foreground) {
                guarded {
                    val remote = checkNotNull(connection)
                    val requests = withContext(Dispatchers.IO) { Snapshots.requests(remote.request("state", JSONObject())) }
                    if (generation != connectionGeneration || inboxGeneration != state.value.inbox.generation) return@guarded
                    val now = SystemClock.elapsedRealtime()
                    change { receiveRequests(requests, now) }
                    val selected = state.value.workspace.sessionID
                    val snapshot = withContext(Dispatchers.IO) {
                        Snapshots.workspace(remote.request("workspace", JSONObject().put("sessionID", selected ?: JSONObject.NULL)))
                    }
                    if (generation != connectionGeneration || inboxGeneration != state.value.inbox.generation ||
                        selected != state.value.workspace.sessionID) return@guarded
                    change { receiveWorkspace(snapshot, SystemClock.elapsedRealtime()) }
                    saveConversations(snapshot)
                }
                delay(2000)
            }
        }
    }
    fun selectRequest(ticket: String) = local { change { copy(inbox = inbox.select(ticket), review = null) } }
    fun answer(ticket: String, questionID: String, draft: AnswerDraft) = local {
        check(state.value.live) { "Wait for a fresh Mac update before editing a reply." }
        change { copy(inbox = inbox.edit(ticket, questionID, draft), review = null) }
    }
    fun reviewReply(ticket: String, decision: ReplyDecision, feedback: String) = local {
        check(state.value.live) { "Wait for a fresh Mac update." }
        val inbox = state.value.inbox
        val prepared = inbox.prepare(ticket, decision, SystemClock.elapsedRealtime(), feedback)
        change { copy(review = ReplyReview(inbox.cards.single { it.request.ticket == ticket }, decision, feedback, prepared.command)) }
    }
    fun cancelReview() = change { copy(review = null, controlReview = null) }
    fun confirmReply() = operation {
        val review = checkNotNull(state.value.review) { "Review the current request again." }
        change { copy(review = null) }
        check(state.value.live && state.value.inbox.cards.any { it == review.card }) { "The request changed. Review it again." }
        val generation = connectionGeneration
        val prepared = state.value.inbox.prepare(review.card.request.ticket, review.decision, SystemClock.elapsedRealtime(), review.feedback)
        change { copy(inbox = prepared.inbox) }
        var acknowledged = false
        try {
            checkNotNull(connection).request("reply", Snapshots.reply(prepared.command), prepared.command.id)
            if (generation == connectionGeneration) {
                change { copy(inbox = inbox.acknowledge(prepared.inbox.generation, prepared.command.id)) }
                acknowledged = true
            }
        } finally {
            if (!acknowledged) change { copy(inbox = inbox.uncertain(prepared.inbox.generation, prepared.command.id)) }
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
        val command = state.value.workspace.prepareManagement(action, SystemClock.elapsedRealtime(), helperID)
        if (action == "create_subagent") sendControl(command)
        else change { copy(controlReview = command) }
    }
    fun prepareControl(action: String) = local {
        check(state.value.live) { "Wait for a fresh Mac update." }
        val command = state.value.workspace.prepare(action, SystemClock.elapsedRealtime())
        if (!command.requiresConfirmation) sendControl(command) else change { copy(controlReview = command) }
    }
    fun confirmControl() { state.value.controlReview?.let(::sendControl) }
    fun prepareSessionAction(action: String, rgb: Int? = null, target: String? = null) = local {
        check(state.value.live) { "Wait for a fresh Mac update." }
        val command = state.value.workspace.prepareSessionAction(action, SystemClock.elapsedRealtime(), rgb, target)
        if (action in setOf("remove_session", "resume_session")) change { copy(controlReview = command) }
        else sendControl(command)
    }
    fun preparePolicy(enabled: Boolean) = local {
        check(state.value.live) { "Wait for a fresh Mac update. Off has not been confirmed." }
        val command = state.value.workspace.select(state.value.workspace.sessionID).preparePolicy(enabled, SystemClock.elapsedRealtime())
        if (enabled) change { copy(controlReview = command) }
        else if (!state.value.policyOffPending) {
            change { copy(policyOffPending = true, controlReview = null) }
            viewModelScope.launch {
                try { guarded { dispatchControl(command) } }
                finally { change { copy(policyOffPending = false) } }
            }
        }
    }
    private fun sendControl(command: ControlCommand) = operation { dispatchControl(command) }
    private suspend fun dispatchControl(command: ControlCommand) {
        change { copy(controlReview = null) }
        check(state.value.live) { "Wait for a fresh Mac update." }
        state.value.workspace.validate(command, SystemClock.elapsedRealtime())
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
            if (error is RemoteRequestFailure && error.notDispatched && command.isPolicy && command.enabled == false &&
                generation == connectionGeneration) {
                change { copy(workspace = workspace.rejectPolicyBeforeDispatch(command, error.message.orEmpty())) }
                acknowledged = true
            }
            if (error is CancellationException || !command.isPolicy ||
                state.value.workspace.policyDeliveries[command.target]?.commandID == command.id) throw error
            acknowledged = true
        } finally {
            if (!acknowledged) change { copy(workspace = workspace.uncertain(command)) }
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
        viewModelScope.launch {
            try { guarded(block) } finally { change { copy(busy = false) } }
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
        if (error is ServiceEndpointFailure) {
            if (error.endsAccountSession) disconnect()
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
        notificationSettings.prefs.unregisterOnSharedPreferenceChangeListener(notificationListener)
        val old = connection
        cleanup.launch { try { old?.close(); service.close() } finally { cleanup.cancel() } }
    }
}
