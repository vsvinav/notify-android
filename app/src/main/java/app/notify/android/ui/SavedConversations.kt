package app.notify.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.notify.android.controller.CachedConversation
import app.notify.android.controller.CachedMessage
import app.notify.android.controller.ConversationHistory
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SavedConversations(history: ConversationHistory, close: () -> Unit) {
    var sessionID by rememberSaveable { mutableStateOf<String?>(null) }
    var agentID by rememberSaveable { mutableStateOf("") }
    val selected = history.conversations.singleOrNull { it.target.sessionID == sessionID && it.target.agentID == agentID }
    BackHandler(selected != null) { sessionID = null }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            TextButton(onClick = if (selected == null) close else { { sessionID = null } }) {
                Text(if (selected == null) "Back to sign-in / live view" else "All saved conversations")
            }
            Heading(selected?.sessionName ?: "Saved conversations")
            Supporting("Cached · read-only · stale. No connection or action authority is restored. " +
                "Only the latest 8 supplied messages per conversation are kept, within phone storage limits. The Mac may supply partial history.")
        }
        if (selected == null) {
            items(history.conversations, key = { "${it.target.sessionID.length}:${it.target.sessionID}${it.target.agentID}" }) { conversation ->
                TextButton(onClick = { sessionID = conversation.target.sessionID; agentID = conversation.target.agentID },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(conversation.sessionName, style = MaterialTheme.typography.titleMedium)
                        Text(conversation.agentName, style = MaterialTheme.typography.bodyMedium)
                        Text(savedConversationLabel(conversation), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            item {
                Text(selected.agentName, style = MaterialTheme.typography.titleMedium)
                Supporting(savedConversationLabel(selected))
                if (selected.messages.isEmpty()) Supporting("No messages were supplied for this conversation.")
            }
            items(selected.messages) { message -> SavedMessage(message) }
        }
    }
}

internal fun savedConversationLabel(conversation: CachedConversation): String =
    "Saved ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(conversation.savedAt))} · " +
        "${conversation.messages.size} messages" + if (conversation.sourcePartial) " · Mac reported partial history" else ""

@Composable
internal fun SavedMessage(message: CachedMessage) {
    Surface(color = if (message.role == "user") MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(start = if (message.role == "user") 32.dp else 0.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${if (message.role == "user") "You" else "Assistant"} · ${message.title}",
                style = MaterialTheme.typography.labelLarge)
            if (message.role == "assistant") MessageMarkdown(message.text)
            else SelectionContainer { Text(message.text) }
            CopyButton(message.text)
        }
    }
}
