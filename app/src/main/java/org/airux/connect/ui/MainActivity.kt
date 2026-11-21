package org.airux.connect.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay
import org.airux.connect.net.Confirm
import org.airux.connect.net.Conn
import org.airux.connect.net.Session
import org.airux.connect.ui.screens.AiraScreen
import org.airux.connect.ui.screens.FilesScreen
import org.airux.connect.ui.screens.HomeScreen
import org.airux.connect.ui.screens.MoreScreen
import org.airux.connect.ui.screens.PairScreen
import org.airux.connect.ui.screens.TasksScreen
import org.airux.connect.ui.theme.AiroxTheme
import org.airux.connect.ui.theme.Ax

class MainActivity : FragmentActivity() {
    private val link = mutableStateOf<String?>(null)
    private val tab = mutableStateOf("home")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { AiroxTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i?.data?.takeIf { it.scheme == "airox" }?.let { link.value = it.toString() }
        i?.getStringExtra("tab")?.let { if (it in TABS.map { t -> t.first }) tab.value = it }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Root() {
        val paired by Session.paired.collectAsState()
        // test tags double as resource ids, so UI tests (UiAutomator) can find the controls
        Box(Modifier.fillMaxSize().background(Ax.bg).semantics { testTagsAsResourceId = true }) {
            if (paired == null || link.value != null) {
                PairScreen(link.value) { link.value = null }
            } else {
                Main()
            }
            ConfirmHost()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Main() {
        val conn by Session.conn.collectAsState()
        val p by Session.paired.collectAsState()
        val current by tab
        Scaffold(
            containerColor = Ax.bg,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Ax.bg),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(
                                when (conn) { is Conn.Online -> Ax.green; is Conn.Connecting -> Ax.amber; else -> Ax.red }, CircleShape))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(p?.pcName ?: "AIROX Connect", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.testTag("pcName"))
                                Text(when (val c = conn) {
                                    is Conn.Online -> "Connected · ${c.host}"
                                    is Conn.Connecting -> "Connecting…"
                                    is Conn.Offline -> c.why
                                    Conn.Unpaired -> "Not paired"
                                }, fontSize = 12.sp, color = Ax.muted, maxLines = 1)
                            }
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Ax.panel) {
                    TABS.forEach { (id, label, icon) ->
                        NavigationBarItem(
                            selected = current == id, onClick = { tab.value = id },
                            icon = { Icon(icon, label) }, label = { Text(label) },
                            modifier = Modifier.testTag("tab_$id"),
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Ax.line),
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (current) {
                    "home" -> HomeScreen()
                    "aira" -> AiraScreen()
                    "tasks" -> TasksScreen()
                    "files" -> FilesScreen()
                    else -> MoreScreen()
                }
            }
        }
    }

    // ---------------------------------------------------------------- approvals from the PC
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun ConfirmHost() {
        val list by Session.confirms.collectAsState()
        val c = list.firstOrNull() ?: return
        val left = remember(c.id) { mutableIntStateOf(((c.expires - System.currentTimeMillis()) / 1000).toInt()) }
        LaunchedEffect(c.id) {
            while (left.intValue > 0) { delay(1000); left.intValue-- }
            Session.confirms.value = Session.confirms.value.filter { it.id != c.id }   // the PC denies it by itself
        }
        AlertDialog(
            onDismissRequest = {},
            modifier = Modifier.semantics { testTagsAsResourceId = true },   // a dialog is a window of its own
            containerColor = Ax.panel,
            title = { Text(if (c.src == "aira") "AIRA asks" else "Are you sure?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(c.summary, modifier = Modifier.testTag("confirmText"))
                    if (c.risks.isNotEmpty()) Text(c.risks.joinToString(" · ") { RISK[it] ?: it }, color = Ax.amber, fontSize = 12.sp)
                    if (c.strong) Text("You will confirm with your screen lock.", color = Ax.muted, fontSize = 12.sp)
                    Text("Denied automatically in ${left.intValue} s", color = Ax.muted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { approve(c) }, modifier = Modifier.testTag("confirmYes")) { Text("Yes", color = Ax.green) }
            },
            dismissButton = {
                TextButton(onClick = { Session.answer(c, false) }, modifier = Modifier.testTag("confirmNo")) { Text("No") }
            },
            shape = RoundedCornerShape(16.dp),
        )
    }

    private fun approve(c: Confirm) {
        if (!c.strong) return Session.answer(c, true)
        val bm = BiometricManager.from(this)
        if (bm.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "Set a screen lock on this phone to approve this kind of action from it.", Toast.LENGTH_LONG).show()
            return
        }
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = Session.answer(c, true)
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Approve on your PC").setSubtitle(c.summary.take(80))
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build()
        )
    }

    companion object {
        val TABS: List<Triple<String, String, ImageVector>> = listOf(
            Triple("home", "Home", Icons.Filled.Home),
            Triple("aira", "AIRA", Icons.Filled.RecordVoiceOver),
            Triple("tasks", "Tasks", Icons.Filled.Memory),
            Triple("files", "Files", Icons.Filled.Folder),
            Triple("more", "More", Icons.Filled.Apps),
        )
        val RISK = mapOf(
            "stop" to "Ends a running program or service", "root" to "Needs an administrator", "delete" to "Deletes something",
            "overwrite" to "Overwrites something", "install" to "Installs software", "send_off_machine" to "Data leaves the PC",
            "network_change" to "Changes the network", "exec" to "Runs a command", "write" to "Changes something",
        )
    }
}
