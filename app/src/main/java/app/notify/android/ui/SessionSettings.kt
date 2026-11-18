package app.notify.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.notify.android.controller.ControllerState

internal fun sessionRgb(rgb: Int) = Color(0xFF000000L or rgb.toLong())

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionSettings(state: ControllerState, close: () -> Unit, action: (String, Int?, String?) -> Unit) {
    val workspace = state.workspace
    val session = workspace.session
    val settings = session?.sessionActions
    fun enabled(name: String, target: String? = null) = state.live && !state.busy &&
        workspace.sessionActionUnavailable(name, state.now, target) == null
    Page {
        TextButton(onClick = close, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to session") }
        Heading("Session settings")
        Text(session?.name ?: "Session unavailable", style = MaterialTheme.typography.titleMedium)
        Supporting("These session changes are saved on your Mac. This phone waits for its acknowledgment and refreshed state.")
        workspace.sessionResults.forEach { Text(it.status, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        workspace.sessionActionUnavailable("set_session_color", state.now)?.let { Supporting(it) }
        HorizontalDivider()
        Text("Session color", style = MaterialTheme.typography.titleMedium)
        val current = settings?.rgb ?: session?.color
        if (current != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(28.dp).background(sessionRgb(current), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                Text((settings?.colors?.firstOrNull { it.rgb == current }?.name ?: "#%06X".format(current)) +
                    if (settings?.overridden == true) " · Custom override" else if (settings != null) " · Automatic" else " · Last reported")
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            settings?.colors?.forEach { color ->
                OutlinedButton(onClick = { action("set_session_color", color.rgb, null) },
                    enabled = enabled("set_session_color"),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { selected = current == color.rgb }) {
                    Box(Modifier.size(20.dp).background(sessionRgb(color.rgb), CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(color.name)
                    if (current == color.rgb) Text(" · Selected")
                }
            }
        }
        OutlinedButton(onClick = { action("reset_session_color", null, null) },
            enabled = enabled("reset_session_color"), modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset automatic") }
        HorizontalDivider()
        Text("Order on Mac & phone", style = MaterialTheme.typography.titleMedium)
        Supporting("Move within the Mac’s current status group, including rows hidden by your phone’s search or provider filters.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { action("move_session", null, settings?.moveUp) },
                enabled = enabled("move_session", settings?.moveUp), modifier = Modifier.heightIn(min = 48.dp)) { Text("Move up") }
            OutlinedButton(onClick = { action("move_session", null, settings?.moveDown) },
                enabled = enabled("move_session", settings?.moveDown), modifier = Modifier.heightIn(min = 48.dp)) { Text("Move down") }
        }
        HorizontalDivider()
        Text("Saved session", style = MaterialTheme.typography.titleMedium)
        Supporting(settings?.resumeDetail?.takeIf { it.isNotBlank() } ?: "Resume requires an original supported saved CLI session on your Mac.")
        settings?.let { Supporting("Mac reports: ${it.resumeStatus.replace('_', ' ')}") }
        OutlinedButton(onClick = { action("resume_session", null, null) },
            enabled = enabled("resume_session"), modifier = Modifier.heightIn(min = 48.dp)) { Text("Resume on Mac…") }
        Supporting("Launch is not connection. Mac sign-in or Automation prompts may need attention there. This launch action sends no approvals.")
        HorizontalDivider()
        OutlinedButton(onClick = { action("remove_session", null, null) },
            enabled = enabled("remove_session"), modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove from Notify…") }
        Supporting("Disconnected cached row only. Does not stop an owner or delete CLI history or project files. Discovery may bring it back.")
    }
}
