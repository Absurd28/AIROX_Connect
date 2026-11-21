package org.airux.connect.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Ax {
    val bg = Color(0xFF0B1016)
    val panel = Color(0xFF131B25)
    val line = Color(0xFF223040)
    val text = Color(0xFFE6E9EE)
    val muted = Color(0xFF8B95A3)
    val blue = Color(0xFF7CC4FF)
    val green = Color(0xFF59C38A)
    val amber = Color(0xFFF0B45A)
    val red = Color(0xFFFF7A7A)
    val violet = Color(0xFFBE8CFF)
    val accent = Brush.horizontalGradient(listOf(Color(0xFF2F6F9C), Color(0xFF2F8A5F)))
}

@Composable
fun AiroxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ax.blue, onPrimary = Color(0xFF06131D), secondary = Ax.green, background = Ax.bg,
            surface = Ax.panel, surfaceVariant = Color(0xFF172230), onSurface = Ax.text, onBackground = Ax.text,
            onSurfaceVariant = Ax.muted, outline = Ax.line, error = Ax.red, surfaceContainer = Ax.panel,
            surfaceContainerHigh = Color(0xFF172230), surfaceContainerHighest = Color(0xFF1B2836),
        ),
        content = content,
    )
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Ax.panel, RoundedCornerShape(16.dp)).border(1.dp, Ax.line, RoundedCornerShape(16.dp))
            .padding(16.dp), content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(bottom = 6.dp), color = Ax.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, size: Int = 13) = Text(text, modifier, color = Ax.muted, fontSize = size.sp)

/** A small line graph of the last values (0..max). */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier.fillMaxWidth().height(48.dp), max: Double = 100.0) {
    Box(modifier.background(Color(0xFF0D141C), RoundedCornerShape(8.dp))) {
        Canvas(Modifier.fillMaxWidth().height(48.dp).padding(2.dp)) {
            if (values.size < 2) return@Canvas
            val n = 60
            val pts = values.takeLast(n)
            val step = size.width / (n - 1)
            val off = n - pts.size
            val path = Path()
            pts.forEachIndexed { i, v ->
                val x = (i + off) * step
                val y = size.height - (v / max).coerceIn(0.0, 1.0).toFloat() * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val fill = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(off * step, size.height); close() }
            drawPath(fill, color.copy(alpha = 0.18f))
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
            drawLine(Ax.line, Offset(0f, size.height), Offset(size.width, size.height))
        }
    }
}

@Composable
fun Meter(label: String, value: Double?, color: Color, sub: String = "", modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(if (value == null) "—" else "${value.toInt()}%", color = Ax.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Muted(label)
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(5.dp).background(Ax.line, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(((value ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)).height(5.dp).background(color, RoundedCornerShape(3.dp)))
        }
        if (sub.isNotEmpty()) Muted(sub, Modifier.padding(top = 4.dp), 12)
    }
}

fun bytes(b: Number?): String {
    if (b == null) return "—"
    var v = b.toDouble()
    val u = listOf("B", "KB", "MB", "GB", "TB")
    var i = 0
    while (v >= 1024 && i < 4) { v /= 1024; i++ }
    return if (i == 0) "${v.toInt()} B" else String.format(if (v < 10) "%.1f %s" else "%.0f %s", v, u[i])
}

fun rate(b: Number?): String = bytes(b) + "/s"
