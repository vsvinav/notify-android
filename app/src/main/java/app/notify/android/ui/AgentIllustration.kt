package app.notify.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform

@Composable
internal fun AgentIllustration(
    presence: AgentPresence, modifier: Modifier, isLead: Boolean = false, portrait: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.clipToBounds()) {
        withTransform({
            scale(size.width / if (portrait) 58f else 180f,
                size.height / if (portrait) 58f else 136f, pivot = Offset.Zero)
            if (portrait) translate(-32f, -17f)
        }) {
            val ink = colors.onSurface
            val accent = colors.primary
            fun line(vararg points: Float, color: Color = ink, width: Float = 2f) {
                val path = Path().apply {
                    moveTo(points[0], points[1])
                    for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
                }
                drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            fun stroke(path: Path, color: Color = ink) =
                drawPath(path, color, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            val lean = if (presence == AgentPresence.Working) 7f else 0f
            val head = Offset(43f + lean, 26f)
            drawOval(colors.surfaceContainerHighest, head, Size(33f, 34f))
            drawOval(ink, head, Size(33f, 34f), style = Stroke(2f))
            if (isLead) {
                drawPath(Path().apply {
                    moveTo(45f + lean, 29f); lineTo(47f + lean, 8f)
                    lineTo(72f + lean, 8f); lineTo(75f + lean, 29f); close()
                }, ink)
                line(46f + lean, 23f, 73f + lean, 23f, color = accent, width = 4f)
                line(38f + lean, 29f, 81f + lean, 29f, width = 4f)
            }
            when (presence) {
                AgentPresence.Stopped -> for (x in listOf(54f, 67f)) {
                    line(x + lean - 2f, 39f, x + lean + 2f, 44f)
                    line(x + lean - 2f, 44f, x + lean + 2f, 39f)
                }
                AgentPresence.Disconnected -> {
                    line(52f + lean, 41f, 56f + lean, 42f)
                    line(65f + lean, 42f, 69f + lean, 41f)
                }
                else -> for (x in listOf(54f, 67f))
                    drawOval(ink, Offset(x + lean, 40f), Size(2.5f, 3f))
            }
            stroke(Path().apply {
                moveTo(57f + lean, 50f)
                quadraticTo(61f + lean, 54f, 65f + lean, 50f)
            })
            stroke(Path().apply {
                moveTo(50f, 66f); quadraticTo(40f, 74f, 39f, 99f)
                lineTo(80f, 99f); quadraticTo(80f, 74f, 73f, 66f)
            })
            line(54f, 65f, 61f, 69f, 68f, 65f, 68f, 73f, 61f, 69f, 54f, 73f, 54f, 65f, color = accent)
            val handsOff = presence in setOf(AgentPresence.Stopped, AgentPresence.Resting, AgentPresence.Completed,
                AgentPresence.Disconnected, AgentPresence.Unknown)
            line(48f, 80f, 59f, 91f, if (handsOff) 67f else 96f, if (handsOff) 104f else 93f)
            if (presence == AgentPresence.Waiting) {
                line(76f, 79f, 89f, 63f, 90f, 36f, color = accent)
                line(85f, 42f, 90f, 49f, 96f, 38f, color = accent)
            } else line(76f, 82f, 82f, 90f, if (handsOff) 78f else 108f, if (handsOff) 103f else 94f)
            line(30f, 81f, 30f, 106f, 68f, 106f, color = colors.outline)
            line(48f, 107f, 48f, 127f, color = colors.outline)
            line(65f, 103f, 73f, 124f, 86f, 124f)
            line(29f, 100f, 165f, 100f, color = colors.outline)
            line(149f, 100f, 149f, 127f, color = colors.outline)
            val laptop = Path().apply {
                moveTo(113f, 65f); lineTo(160f, 65f); lineTo(150f, 90f)
                lineTo(105f, 90f); close()
            }
            drawPath(laptop, colors.surface)
            stroke(laptop, accent)
            line(87f, 98f, 144f, 98f, color = accent)
            for (row in 0..1) for (key in 0..6) {
                val x = (96 + key * 6 - row * 2).toFloat()
                val y = (92 + row * 3).toFloat()
                line(x, y, x + 3f, y, color = colors.onSurfaceVariant, width = 1f)
            }
            when (presence) {
                AgentPresence.Working -> {
                    line(128f, 33f, 134f, 39f, 128f, 45f, color = accent)
                    line(138f, 45f, 147f, 45f, color = accent)
                }
                AgentPresence.Completed -> line(128f, 40f, 134f, 46f, 146f, 33f, color = accent)
                AgentPresence.Stopped -> drawRect(accent, Offset(130f, 33f), Size(14f, 14f))
                AgentPresence.Waiting, AgentPresence.Failed -> {
                    val color = if (presence == AgentPresence.Failed) colors.error else colors.tertiary
                    line(137f, 31f, 137f, 41f, color = color)
                    drawCircle(color, 1.5f, Offset(137f, 47f))
                }
                else -> Unit
            }
        }
    }
}
