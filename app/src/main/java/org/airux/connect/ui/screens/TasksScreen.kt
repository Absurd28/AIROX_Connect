package org.airux.connect.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.airux.connect.net.Session
import org.airux.connect.net.orEmptyList
import org.airux.connect.ui.theme.Ax
import org.airux.connect.ui.theme.Card
import org.airux.connect.ui.theme.Muted
import org.airux.connect.ui.theme.SectionTitle
import org.airux.connect.ui.theme.Sparkline
import org.airux.connect.ui.theme.bytes
import org.airux.connect.ui.theme.rate
import org.json.JSONObject

private val TABS = listOf("Processes", "Performance", "Startup", "Services", "Users")

@Composable
fun TasksScreen() {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(tab, containerColor = Ax.bg, edgePadding = 8.dp) {
            TABS.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }, modifier = Modifier.testTag("tasks_$t")) }
        }
        when (tab) {
            0 -> Processes()
            1 -> Performance()
            2 -> Startup()
            3 -> Services()
            else -> Users()
        }
    }
}

@Composable
private fun Processes() {
    val ctx = LocalContext.current
    val data by polled(2000) { Session.get("/v1/tasks/processes") }
    var q by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(setOf<String>()) }
    var sortMem by remember { mutableStateOf(false) }
    val groups = data?.optJSONArray("groups").orEmptyList()
        .filter { g -> q.isBlank() || g.optString("name").contains(q, true) || g.optJSONArray("procs").orEmptyList().any { it.optInt("pid").toString() == q } }
        .sortedByDescending { if (sortMem) it.optLong("memory").toDouble() else it.optDouble("cpu") }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f).padding(vertical = 6.dp).testTag("procSearch"), singleLine = true,
                placeholder = { Text("Search name or process id") })
            FilterChip(sortMem, { sortMem = !sortMem }, label = { Text(if (sortMem) "Memory" else "CPU") }, modifier = Modifier.padding(start = 8.dp))
        }
        LazyColumn(Modifier.fillMaxSize().testTag("procList")) {
            for ((kind, label) in listOf("app" to "Apps", "background" to "Background processes", "system" to "System processes")) {
                val gs = groups.filter { it.optString("kind") == kind }
                if (gs.isEmpty()) continue
                item(key = "h$kind") { Text("$label (${gs.size})", color = Ax.blue, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                items(gs, key = { it.optString("key") }) { g ->
                    val key = g.optString("key")
                    val procs = g.optJSONArray("procs").orEmptyList()
                    GroupRow(g, open.contains(key), onToggle = { open = if (open.contains(key)) open - key else open + key },
                        onEnd = { force ->
                            if (procs.size == 1) act(ctx, "taskmgr", "end_task", JSONObject().put("pid", procs[0].optInt("pid"))
                                .put("start", procs[0].optLong("start")).put("force", force))
                            else act(ctx, "taskmgr", "end_app", JSONObject().put("key", key).put("force", force))
                        })
                    if (open.contains(key) && procs.size > 1) procs.forEach { p -> ProcRow(p) }
                }
            }
            if (data == null) item { Muted("Loading…", Modifier.padding(12.dp)) }
        }
    }
}

@Composable
private fun GroupRow(g: JSONObject, isOpen: Boolean, onToggle: () -> Unit, onEnd: (Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp).testTag("group_${g.optString("name")}"),
        verticalAlignment = Alignment.CenterVertically) {
        if (g.optInt("count") > 1) Icon(if (isOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(18.dp), tint = Ax.muted)
        else Spacer(Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(g.optString("name") + if (g.optInt("count") > 1) "  (${g.optInt("count")})" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Muted("${"%.1f".format(g.optDouble("cpu"))}% CPU · ${bytes(g.optLong("memory"))} · disk ${rate(g.optDouble("read_bps") + g.optDouble("write_bps"))}" +
                (if (!g.isNull("gpu")) " · GPU ${g.optDouble("gpu").toInt()}%" else ""), size = 12)
        }
        TextButton(onClick = { onEnd(false) }, modifier = Modifier.testTag("end_${g.optString("name")}")) { Text("End task", color = Ax.red) }
        Column {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Kill (no saving)") }, { menu = false; onEnd(true) })
            }
        }
    }
}

@Composable
private fun ProcRow(p: JSONObject) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 30.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${p.optString("name")}  ${p.optInt("pid")}", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Muted("${"%.1f".format(p.optDouble("cpu"))}% · ${bytes(p.optLong("memory"))} · ${p.optString("user")} · priority ${p.optInt("nice")}", size = 12)
        }
        Column {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions") }
            DropdownMenu(menu, { menu = false }) {
                val base = { JSONObject().put("pid", p.optInt("pid")).put("start", p.optLong("start")) }
                DropdownMenuItem({ Text("End") }, { menu = false; act(ctx, "taskmgr", "end_task", base().put("force", false)) })
                DropdownMenuItem({ Text("Kill") }, { menu = false; act(ctx, "taskmgr", "end_task", base().put("force", true)) })
                DropdownMenuItem({ Text("Lower priority") }, { menu = false; act(ctx, "taskmgr", "set_priority", base().put("nice", 10)) })
                DropdownMenuItem({ Text("Higher priority (administrator)") }, { menu = false; act(ctx, "taskmgr", "set_priority", base().put("nice", -5)) })
            }
        }
    }
}

@Composable
private fun Performance() {
    val p by polled(1500) { Session.get("/v1/tasks/performance") }
    val d = p
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("perf"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (d == null) { item { Muted("Measuring…", Modifier.padding(12.dp)) }; return@LazyColumn }
        val cpu = d.optJSONObject("cpu") ?: JSONObject()
        item {
            Card {
                Row { SectionTitle("CPU", Modifier.weight(1f)); Text("${cpu.optDouble("util").toInt()}%", color = Ax.blue) }
                Muted(cpu.optString("model"), size = 12)
                Sparkline(cpu.optJSONArray("history").doubles(), Ax.blue, Modifier.fillMaxWidth().padding(top = 8.dp))
                Muted("${cpu.optInt("count")} logical processors · ${cpu.optInt("mhz").takeIf { it > 0 }?.let { "%.2f GHz".format(it / 1000.0) } ?: ""} · " +
                    "${cpu.optInt("procs")} processes", Modifier.padding(top = 6.dp), 12)
            }
        }
        val mem = d.optJSONObject("memory") ?: JSONObject()
        item {
            Card {
                Row { SectionTitle("Memory", Modifier.weight(1f)); Text("${bytes(mem.optLong("used"))} / ${bytes(mem.optLong("total"))}", color = Ax.violet) }
                Sparkline(mem.optJSONArray("history").doubles(), Ax.violet, Modifier.fillMaxWidth().padding(top = 8.dp))
                Muted("Available ${bytes(mem.optLong("available"))} · cached ${bytes(mem.optLong("cached"))} · swap ${bytes(mem.optLong("swap_used"))} of ${bytes(mem.optLong("swap_total"))}",
                    Modifier.padding(top = 6.dp), 12)
            }
        }
        d.optJSONArray("gpus").orEmptyList().forEach { g ->
            item {
                Card {
                    Row { SectionTitle(g.optString("name"), Modifier.weight(1f)); Text(if (g.isNull("util")) "—" else "${g.optDouble("util").toInt()}%", color = Ax.red) }
                    Sparkline(g.optJSONArray("history").doubles(), Ax.red, Modifier.fillMaxWidth().padding(top = 8.dp))
                    Muted(listOfNotNull(
                        if (!g.isNull("mem_total")) "${bytes(g.optLong("mem_used"))} / ${bytes(g.optLong("mem_total"))}" else null,
                        if (!g.isNull("temp")) "${g.optDouble("temp").toInt()} °C" else null,
                        if (!g.isNull("power")) "${g.optDouble("power")} W" else null).joinToString(" · "), Modifier.padding(top = 6.dp), 12)
                }
            }
        }
        d.optJSONArray("disks").orEmptyList().forEach { k ->
            item {
                Card {
                    Row { SectionTitle("Disk ${k.optString("name")}", Modifier.weight(1f)); Text("${k.optDouble("active").toInt()}%", color = Ax.green) }
                    Muted("${k.optString("model")} ${if (k.optBoolean("ssd")) "SSD" else "HDD"} · ${bytes(k.optLong("size"))}", size = 12)
                    Sparkline(k.optJSONArray("history").doubles(), Ax.green, Modifier.fillMaxWidth().padding(top = 8.dp))
                    Muted("Read ${rate(k.optDouble("read_bps"))} · write ${rate(k.optDouble("write_bps"))}", Modifier.padding(top = 6.dp), 12)
                }
            }
        }
        d.optJSONArray("network").orEmptyList().forEach { n ->
            item {
                Card {
                    Row { SectionTitle("${n.optString("kind")} ${n.optString("name")}", Modifier.weight(1f)); Muted(if (n.optBoolean("up")) "Connected" else "Not connected") }
                    val h = n.optJSONArray("history").doubles()
                    Sparkline(h, Ax.amber, Modifier.fillMaxWidth().padding(top = 8.dp), max = maxOf(100.0, h.maxOrNull() ?: 0.0))
                    Muted("Send ${rate(n.optDouble("tx_bps"))} · receive ${rate(n.optDouble("rx_bps"))}", Modifier.padding(top = 6.dp), 12)
                }
            }
        }
        item { Spacer(Modifier.size(8.dp)) }
    }
}

@Composable
private fun Startup() {
    val ctx = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val d by polled(60_000, reload) { Session.get("/v1/tasks/startup") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(d?.optJSONArray("apps").orEmptyList(), key = { it.optString("id") }) { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.optString("name"))
                    Muted(a.optString("command"), size = 12)
                }
                Switch(a.optBoolean("enabled"), { on ->
                    act(ctx, "taskmgr", "startup_app", JSONObject().put("entry", a.optString("id")).put("enabled", on)) { reload++ }
                })
            }
        }
        if (d == null) item { Muted("Loading…", Modifier.padding(12.dp)) }
    }
}

@Composable
private fun Services() {
    val ctx = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    var scope by remember { mutableStateOf("system") }
    var q by remember { mutableStateOf("") }
    val d by polled(15_000, reload) { Session.get("/v1/tasks/services") }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(scope == "system", { scope = "system" }, label = { Text("System") })
            Spacer(Modifier.size(8.dp))
            FilterChip(scope == "user", { scope = "user" }, label = { Text("Mine") })
            OutlinedTextField(q, { q = it }, Modifier.weight(1f).padding(start = 8.dp, top = 4.dp, bottom = 4.dp), singleLine = true,
                placeholder = { Text("Search") })
        }
        LazyColumn(Modifier.fillMaxSize()) {
            val list = d?.optJSONArray(scope).orEmptyList().filter { q.isBlank() || it.optString("unit").contains(q, true) || it.optString("description").contains(q, true) }
            items(list, key = { it.optString("unit") }) { s ->
                val on = s.optString("active") == "active"
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.optString("unit"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        Muted("${if (on) "Running" else if (s.optString("active") == "failed") "Failed" else "Stopped"} · ${s.optString("description")}", size = 12)
                    }
                    val args = { a: String -> JSONObject().put("name", s.optString("unit")).put("action", a).put("scope", scope) }
                    if (on) {
                        TextButton({ act(ctx, "taskmgr", "service", args("restart")) { reload++ } }) { Text("Restart") }
                        TextButton({ act(ctx, "taskmgr", "service", args("stop")) { reload++ } }) { Text("Stop", color = Ax.red) }
                    } else TextButton({ act(ctx, "taskmgr", "service", args("start")) { reload++ } }) { Text("Start") }
                }
            }
        }
    }
}

@Composable
private fun Users() {
    val d by polled(5000) { Session.get("/v1/tasks/users") }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        val usage = d?.optJSONObject("usage") ?: JSONObject()
        items(usage.keys().asSequence().toList()) { u ->
            val x = usage.getJSONObject(u)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(u, Modifier.weight(1f))
                Muted("${"%.1f".format(x.optDouble("cpu"))}% · ${bytes(x.optLong("memory"))} · ${x.optInt("count")} processes")
            }
        }
    }
}

private fun org.json.JSONArray?.doubles(): List<Double> = if (this == null) emptyList() else (0 until length()).map { optDouble(it) }
