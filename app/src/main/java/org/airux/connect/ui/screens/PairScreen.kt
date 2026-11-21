package org.airux.connect.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.launch
import org.airux.connect.data.PairLink
import org.airux.connect.net.Session
import org.airux.connect.ui.theme.Ax
import org.airux.connect.ui.theme.Card
import org.airux.connect.ui.theme.Muted
import java.util.concurrent.Executors

@Composable
fun PairScreen(incoming: String?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var waiting by remember { mutableStateOf<PairLink?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanning = true else error = "AIROX Connect needs the camera to scan the code. You can paste the link instead."
    }

    fun go(text: String) {
        val link = PairLink.parse(text)
        if (link == null) { error = "That is not an AIROX Connect pairing code."; return }
        scanning = false
        waiting = link
        error = null
        scope.launch {
            try {
                Session.pair(link)
                onDone()
            } catch (e: Exception) {
                error = e.message ?: "Pairing failed."
            }
            waiting = null
        }
    }
    LaunchedEffect(incoming) { if (incoming != null && waiting == null) go(incoming) }

    Column(
        Modifier.fillMaxSize().background(Ax.bg).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("AIROX Connect", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Muted("Talk to AIRA, see what your AIRUX PC is doing, send files both ways and use your phone as a touchpad or " +
            "second screen. AIRA is your Adaptive Intelligent Research Assistant.", size = 15)
        val w = waiting
        if (w != null) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Ax.blue)
                    Spacer(Modifier.size(14.dp))
                    Column {
                        Text("Allow this phone on ${w.pcName}", fontWeight = FontWeight.SemiBold)
                        Muted("Click Allow on the PC (in AIROX Connect or the notification).")
                    }
                }
                Muted("PC certificate ${w.fingerprint.take(16).chunked(4).joinToString(" ")}…", Modifier.padding(top = 10.dp), 12)
            }
        } else if (scanning) {
            Card {
                Scanner(onCode = { go(it) })
                Muted("Point the camera at the code in the AIROX Connect window on your PC.", Modifier.padding(top = 10.dp))
                OutlinedButton(onClick = { scanning = false }, modifier = Modifier.padding(top = 8.dp)) { Text("Cancel") }
            }
        } else {
            Card {
                Text("Pair with your PC", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Muted("On the PC open AIROX Connect, turn on \"Allow phone connections\" and click \"Show pairing code\". " +
                    "The phone and the PC must be on the same network.", Modifier.padding(vertical = 8.dp))
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
                        else camPerm.launch(Manifest.permission.CAMERA)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("scan"),
                    colors = ButtonDefaults.buttonColors(containerColor = Ax.blue),
                ) { Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.size(8.dp)); Text("Scan the pairing code") }
            }
            Card {
                Text("No camera? Paste the link", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("pairLink"),
                    placeholder = { Text("airox://pair?…") }, singleLine = true)
                OutlinedButton(onClick = { go(typed) }, modifier = Modifier.padding(top = 8.dp).testTag("pairGo")) {
                    Icon(Icons.Filled.Link, null); Spacer(Modifier.size(8.dp)); Text("Pair")
                }
            }
        }
        error?.let { Text(it, color = Ax.red, modifier = Modifier.testTag("pairError")) }
        Muted("Only on your local network. The connection is encrypted to your PC's own certificate, and every action " +
            "from this phone goes through AIRA's permission broker on the PC.", size = 12)
    }
}

@Composable
private fun Scanner(onCode: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val exec = remember { Executors.newSingleThreadExecutor() }
    var done by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { exec.shutdown() } }
    AndroidView(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
        factory = { c ->
            val view = PreviewView(c)
            val future = ProcessCameraProvider.getInstance(c)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(exec) { img ->
                    try {
                        if (!done) {
                            val plane = img.planes[0]
                            val buf = plane.buffer
                            val data = ByteArray(buf.remaining()).also { buf.get(it) }
                            val src = PlanarYUVLuminanceSource(data, plane.rowStride, img.height, 0, 0, img.width, img.height, false)
                            val text = try { reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text } catch (e: Exception) { null }
                            if (text != null && text.startsWith("airox://pair")) {
                                done = true
                                ContextCompat.getMainExecutor(ctx).execute { onCode(text) }
                            }
                        }
                    } finally {
                        img.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(c))
            view
        },
    )
    Box {}
}
