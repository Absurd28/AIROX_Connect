package org.airux.connect.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.airux.connect.net.Session
import org.airux.connect.net.orEmptyList
import org.airux.connect.ui.theme.Ax
import org.airux.connect.ui.theme.Card
import org.airux.connect.ui.theme.Meter
import org.airux.connect.ui.theme.Muted
import org.airux.connect.ui.theme.SectionTitle
import org.airux.connect.ui.theme.bytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen() {
    val act by polled(2500) { Session.get("/v1/activity") }
    val airaUp by Session.airaUp.collectAsState()
    val busy by Session.busy.collectAsState()
    val transcript by Session.transcript.collectAsState()
    val a = act
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("home"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card {
                SectionTitle("Your PC")
                val s = a?.optJSONObject("summary")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Meter("CPU", s?.optDoubleOrNull("cpu"), Ax.blue, modifier = Modifier.weight(1f))
                    Meter("Memory", s?.optDoubleOrNull("memory"), Ax.violet,
                        if (s != null) "${bytes(s.optLong("memory_used"))} of ${bytes(s.optLong("memory_total"))}" else "", Modifier.weight(1f))
                    Meter("GPU", s?.optDoubleOrNull("gpu"), Ax.red, s?.optString("gpu_name")?.take(22).orEmpty(), Modifier.weight(1f))
                }
                s?.optJSONObject("battery")?.let { Muted("PC battery ${it.optInt("percent")}% (${it.optString("status")})", Modifier.padding(top = 8.dp)) }
                s?.optDouble("uptime")?.takeIf { !it.isNaN() }?.let { Muted("Up ${uptime(it)}", Modifier.padding(top = 2.dp)) }
            }
        }
        item {
            Card {
                SectionTitle("AIRA")
                Text(when { !airaUp -> "AIRA isn't running on the PC"; busy -> "Working on something…"; else -> "Ready" },
                    color = if (airaUp) Ax.green else Ax.amber)
                transcript.lastOrNull { it.who == "aira" }?.let {
                    Muted("Last: ${it.text}", Modifier.padding(top = 6.dp))
                }
            }
        }
        item {
            Card {
                SectionTitle("Running apps")
                val apps = a?.optJSONArray("apps").orEmptyList().sortedByDescending { it.optDouble("cpu") }
                if (apps.isEmpty()) Muted(if (a == null) "Loading…" else "No apps open on the desktop.")
                apps.take(10).forEach { app ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(app.optString("name"), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted("${"%.1f".format(app.optDouble("cpu"))}%  ${bytes(app.optLong("memory"))}")
                    }
                }
            }
        }
        a?.optJSONObject("phagos")?.let { ph ->
            item {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Shield, null, tint = if (ph.optBoolean("protection", true)) Ax.green else Ax.amber)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Phagos antivirus", fontWeight = FontWeight.SemiBold)
                            Muted(if (ph.optBoolean("protection", true)) "Real-time protection is on" else "Real-time protection is off")
                        }
                    }
                }
            }
        }
        item {
            Card {
                SectionTitle("Recent actions")
                val acts = a?.optJSONArray("actions").orEmptyList().reversed()
                if (acts.isEmpty()) Muted("Nothing yet.")
                acts.take(12).forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val ok = e.optBoolean("ok") && e.optBoolean("allowed", true)
                        Icon(if (!e.optBoolean("allowed", true)) Icons.Filled.Block else if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                            null, Modifier.size(16.dp), tint = if (ok) Ax.green else if (!e.optBoolean("allowed", true)) Ax.muted else Ax.red)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.optString("tool"), fontSize = 14.sp)
                            Muted(e.optString("via").ifEmpty { e.optString("decision") }, size = 12)
                        }
                        Muted(time(e.optDouble("ts")), size = 12)
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}

private fun org.json.JSONObject.optDoubleOrNull(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }

private fun uptime(s: Double): String {
    val d = (s / 86400).toInt(); val h = ((s % 86400) / 3600).toInt(); val m = ((s % 3600) / 60).toInt()
    return if (d > 0) "${d}d ${h}h" else "${h}h ${m}m"
}

private fun time(ts: Double): String = if (ts.isNaN() || ts <= 0) "" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date((ts * 1000).toLong()))
