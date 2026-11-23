package app.notify.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.notify.android.controller.ControllerState
import app.notify.android.remote.Delivery

@Composable
internal fun PendingAttentionStrip(state: ControllerState, open: () -> Unit, browse: () -> Unit,
    modifier: Modifier = Modifier) {
    val attention = state.visibleAttention
    val current = attention.current ?: return
    val card = state.attentionCard
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Surface(onClick = open, enabled = card != null, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = "Pending request: ${current.requestTitle}, ${current.sessionName}. " +
                        if (card != null) "Open this exact Decision. No answer is sent." else "Status unconfirmed."
                }) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(current.requestTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text(current.sessionName, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val status = when {
                        attention.handover -> "Mac restarted · status unconfirmed. Select the current Mac request in Needs you."
                        card == null -> "Status unconfirmed · wait for an update or check your Mac."
                        card.delivery == Delivery.Unknown -> "Reply outcome unknown · check your Mac."
                        card.delivery is Delivery.Pending || card.request.sending -> "Awaiting acknowledgment"
                        else -> null
                    }
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    attention.additionalCount?.takeIf { it > 0 }?.let {
                        Text("+$it OTHER waiting ${if (it == 1) "request" else "requests"}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (attention.handover) TextButton(onClick = browse, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("Browse decisions")
            }
        }
    }
}
