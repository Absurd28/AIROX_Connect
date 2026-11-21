package org.airux.connect.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.airux.connect.net.Session
import org.airux.connect.net.orEmptyList
import org.airux.connect.transfer.Transfers
import org.airux.connect.ui.theme.Ax
import org.airux.connect.ui.theme.Card
import org.airux.connect.ui.theme.Muted
import org.airux.connect.ui.theme.bytes
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

@Composable
fun FilesScreen() {
    val ctx = LocalContext.current
    var path by remember { mutableStateOf("~") }
    var reload by remember { mutableIntStateOf(0) }
    val listing by polled(30_000, path to reload) { Session.get("/v1/files/list?path=" + URLEncoder.encode(path, "UTF-8")) }
    val transfers by Transfers.list.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { Transfers.upload(ctx, it) }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val parent = listing?.optString("parent")?.takeIf { it.isNotEmpty() && it != "null" }
                IconButton(onClick = { parent?.let { path = it } }, enabled = parent != null) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up") }
                Text(listing?.optString("path") ?: path, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("filesPath"))
            }
            if (transfers.isNotEmpty()) {
                Card(Modifier.padding(bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Transfers", Modifier.weight(1f))
                        TextButton({ Transfers.clearFinished() }) { Text("Clear finished") }
                    }
                    transfers.takeLast(5).forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)
                            .clickable(enabled = t.uri != null) {
                                t.uri?.let { u -> try { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (e: Exception) { } }
                            }) {
                            Icon(if (t.up) Icons.Filled.Upload else Icons.Filled.Download, null, Modifier.size(18.dp), tint = Ax.blue)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                when (t.state) {
                                    "done" -> Muted(if (t.up) "Saved on the PC in Downloads/AIROX Connect" else "Saved in Downloads/AIROX Connect · tap to open", size = 12)
                                    "failed" -> Text(t.error ?: "failed", color = Ax.red, fontSize = 12.sp)
                                    else -> LinearProgressIndicator(progress = { if (t.size > 0) t.done.toFloat() / t.size else 0f },
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = Ax.blue)
                                }
                            }
                        }
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize().testTag("fileList")) {
                items(listing?.optJSONArray("items").orEmptyList(), key = { it.optString("path") }) { f ->
                    FileRow(f, onOpen = { path = f.optString("path") }, onGet = {
                        act(ctx, "connect", "send_files", JSONObject().put("paths", JSONArray(listOf(f.optString("path")))), quiet = true) { r ->
                            r.optJSONObject("data")?.optJSONArray("offers").orEmptyList().forEach { o ->
                                Transfers.download(ctx, o.getString("tid"), o.getString("name"), o.getLong("size"), o.optString("mime"))
                            }
                        }
                    })
                }
                if (listing == null) item { Muted("Loading…", Modifier.padding(12.dp)) }
                item { Spacer(Modifier.size(90.dp)) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { pick.launch(arrayOf("*/*")) },
            icon = { Icon(Icons.Filled.Upload, null) }, text = { Text("Send to PC") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("sendToPc"),
            containerColor = Ax.blue,
        )
    }
}

@Composable
private fun FileRow(f: JSONObject, onOpen: () -> Unit, onGet: () -> Unit) {
    val dir = f.optBoolean("dir")
    Row(Modifier.fillMaxWidth().clickable { if (dir) onOpen() else onGet() }.padding(vertical = 8.dp).testTag("file_${f.optString("name")}"),
        verticalAlignment = Alignment.CenterVertically) {
        val icon = when (f.optString("kind")) {
            "folder" -> Icons.Filled.Folder; "image" -> Icons.Filled.Image; "video" -> Icons.Filled.Movie
            "audio" -> Icons.Filled.Audiotrack; "document" -> Icons.Filled.Description; "archive" -> Icons.Filled.Archive
            else -> Icons.AutoMirrored.Filled.InsertDriveFile
        }
        Icon(icon, null, tint = if (dir) Ax.amber else Ax.blue)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(f.optString("name"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!dir) Muted(bytes(f.optLong("size")), size = 12)
        }
        if (!dir) Icon(Icons.Filled.Download, "Get", tint = Ax.muted)
    }
}
