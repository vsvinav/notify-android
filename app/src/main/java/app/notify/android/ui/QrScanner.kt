package app.notify.android.ui

import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.notify.android.controller.SERVICE_ORIGIN
import app.notify.android.remote.PairingScan
import app.notify.android.remote.QrFrameDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.HttpUrl.Companion.toHttpUrl

@Composable
internal fun QrScanner(onScanned: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scanned by rememberUpdatedState(onScanned)
    val cancel by rememberUpdatedState(onCancel)
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    BackHandler { onCancel() }
    DisposableEffect(owner, attempt) {
        val active = AtomicBoolean(true)
        val gate = PairingScan(SERVICE_ORIGIN.toHttpUrl())
        val worker = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val preview = Preview.Builder().build()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
            .build()
        val decoder = QrFrameDecoder()
        var provider: ProcessCameraProvider? = null
        var lastInvalid: String? = null
        fun stop() {
            active.set(false)
            gate.stop()
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            worker.shutdown()
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { stop(); cancel() }
        }
        owner.lifecycle.addObserver(observer)
        ready = false
        error = null
        analysis.setAnalyzer(worker) { frame ->
            try {
                if (active.get()) {
                    val plane = frame.planes[0]
                    val buffer = plane.buffer.duplicate()
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    val raw = decoder.decode(bytes, frame.width, frame.height, plane.rowStride, plane.pixelStride)
                    if (raw != null) main.execute {
                        if (active.get() && raw != lastInvalid) {
                            try {
                                if (gate.accept(raw) != null) { stop(); scanned(raw) }
                            } catch (invalid: IllegalArgumentException) {
                                lastInvalid = raw
                                error = "${invalid.message} Point at a fresh Notify QR, or cancel and paste its link."
                            }
                        }
                    }
                }
            } catch (_: IllegalArgumentException) {
                main.execute { if (active.get()) { stop(); error = "Camera frame unavailable. Try again, or paste the pairing link." } }
            } finally { frame.close() }
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (active.get()) try {
                provider = future.get()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                ready = true
            } catch (_: Exception) {
                stop()
                error = "Camera unavailable. Try again, or cancel and paste the pairing link from your Mac."
            }
        }, main)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            stop()
        }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Scan your Mac’s QR", style = MaterialTheme.typography.headlineSmall)
        Text("Open Notify → Internet access on your Mac. Keep the whole QR inside the camera view.",
            style = MaterialTheme.typography.bodyMedium)
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().weight(1f))
        if (!ready && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = { attempt++ }) { Text("Try scanning again") }
        }
        Text("Scanning stays on this phone. No images are saved or uploaded.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel scan") }
    }
}
