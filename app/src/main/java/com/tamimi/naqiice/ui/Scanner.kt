package com.tamimi.naqiice.ui

import android.Manifest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tamimi.naqiice.core.Row
import com.tamimi.naqiice.core.boxNumber
import com.tamimi.naqiice.core.decodeQr
import com.tamimi.naqiice.core.packLuma
import com.tamimi.naqiice.core.parseBoxSerial
import com.tamimi.naqiice.core.str
import com.tamimi.naqiice.core.unexpectedBoxMove
import java.util.UUID
import java.util.concurrent.Executors

/** Screen tests have no camera: they turn the automatic permission request off. */
var scannerAsksForCamera = true

/**
 * A live camera view that reports every QR text it reads (the same text at most once
 * every two seconds). Works offline and without Google services.
 */
@Composable
fun QrScanner(modifier: Modifier = Modifier, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val report by rememberUpdatedState(onCode)
    var granted by remember { mutableStateOf(cameraGranted(context)) }
    var failed by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted && scannerAsksForCamera) runCatching { permission.launch(Manifest.permission.CAMERA) } }

    Box(modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(Radius.xl)).background(Color.Black), contentAlignment = Alignment.Center) {
        if (!granted || failed) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (failed) "تعذّر تشغيل الكاميرا؛ أدخل رقم الصندوق يدوياً" else "اسمح للتطبيق باستخدام الكاميرا لمسح ملصق الصندوق، أو أدخل الرقم يدوياً",
                    style = T.sm.color(Color.White), textAlign = TextAlign.Center,
                )
                if (!failed) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("السماح بالكاميرا", { runCatching { permission.launch(Manifest.permission.CAMERA) } }, variant = Variant.Secondary)
                    Btn("إعدادات التطبيق", { openAppSettings(context) }, variant = Variant.Secondary)
                }
            }
            return@Box
        }
        val view = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER } }
        AndroidView({ view }, Modifier.fillMaxSize())
        DisposableEffect(view, owner) {
            val executor = Executors.newSingleThreadExecutor()
            val main = Handler(Looper.getMainLooper())
            var provider: ProcessCameraProvider? = null
            var disposed = false
            var lastText: String? = null
            var lastAt = 0L
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (disposed) return@addListener
                runCatching {
                    val p = future.get()
                    provider = p
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build(),
                        ).build()
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            val plane = image.planes[0]
                            val text = decodeQr(packLuma(plane.buffer, image.width, image.height, plane.rowStride), image.width, image.height)
                            val now = System.currentTimeMillis()
                            if (text != null && (text != lastText || now - lastAt > 2_000)) {
                                lastText = text; lastAt = now
                                main.post { if (!disposed) report(text) }
                            }
                        } catch (_: Exception) {
                        } finally { image.close() }
                    }
                    p.unbindAll()
                    camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }.onFailure { failed = true }
            }, ContextCompat.getMainExecutor(context))
            onDispose {
                disposed = true
                runCatching { provider?.unbindAll() }
                executor.shutdown()
                camera = null
            }
        }
        // A light for cold rooms and night loading.
        if (camera?.cameraInfo?.hasFlashUnit() == true) Box(
            Modifier.align(Alignment.BottomStart).padding(8.dp).size(40.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
                .tap { torch = !torch; runCatching { camera?.cameraControl?.enableTorch(torch) } },
            contentAlignment = Alignment.Center,
        ) { Icon(Lucide.Flashlight, "إضاءة", Modifier.size(20.dp), tint = if (torch) Ice.warning else Color.White) }
    }
}

/** One box in a scan list; [id] becomes the record id of its move. */
data class ScannedBox(val id: String, val serial: String)

/** "على شاحنة ١" for a truck called "شاحنة ١", "على الشاحنة T1" for one called "T1". */
fun onTruck(name: String?): String = when {
    name.isNullOrBlank() -> "على شاحنة"
    name.startsWith("شاحنة") || name.startsWith("الشاحنة") -> "على $name"
    else -> "على الشاحنة $name"
}

/** Where a box is recorded right now, in words. */
fun boxPlace(box: Row?, truckName: (String?) -> String?, customerName: (String?) -> String?): String = when (box?.str("status")) {
    null -> "غير مسجل"
    "factory" -> "في المصنع"
    "truck" -> onTruck(truckName(box.str("truck_id")))
    "customer" -> "عند العميل ${customerName(box.str("customer_id")) ?: ""}".trim()
    "elsewhere" -> "في مكان آخر"
    "damaged" -> "تالف"
    "disposed" -> "مستبعد"
    else -> "لم يُمسح بعد"
}

/**
 * Scan (or type) a list of boxes for one [action]. Unknown stickers are refused; a box whose
 * recorded place does not fit the action is accepted with a note, because the box in the
 * hand is the truth.
 */
@Composable
fun BoxScanDialog(
    title: String, action: String, truckId: String?, customerId: String?, initial: List<ScannedBox>,
    limit: Int? = null, confirmLabel: String = "تأكيد", allowEmpty: Boolean = false,
    onClose: () -> Unit, onDone: (List<ScannedBox>) -> Unit,
) {
    val repo = LocalRepo.current
    val snap by repo.snapshot.collectAsState()
    val haptic = LocalHapticFeedback.current
    var boxes by remember { mutableStateOf(initial) }
    var typed by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { runCatching { tone?.release() } } }

    fun add(text: String, fromCamera: Boolean) {
        val serial = parseBoxSerial(text)
        // The camera keeps seeing a sticker that is already in the list: nothing to say.
        if (fromCamera && serial != null && boxes.any { it.serial == serial }) return
        val result: Pair<String, Boolean> = when {
            serial == null -> "ليس ملصق صندوق" to false
            boxes.any { it.serial == serial } -> "الصندوق ${boxNumber(serial)} مضاف من قبل" to false
            snap.box(serial) == null -> "الصندوق $serial غير مسجل في النظام" to false
            limit != null && boxes.size >= limit -> "لا يمكن إضافة أكثر من $limit" to false
            else -> {
                boxes = boxes + ScannedBox(UUID.randomUUID().toString(), serial)
                "تمت إضافة الصندوق ${boxNumber(serial)}" to true
            }
        }
        // A sticker the camera cannot accept stays in view: say it once, not every two seconds.
        if (fromCamera && result == message) return
        message = result
        runCatching { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
        runCatching { tone?.startTone(if (result.second) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK, 120) }
    }

    WebDialog(title, onClose) {
        QrScanner { add(it, true) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Input(typed, { typed = it }, Modifier.weight(1f), placeholder = "رقم الصندوق", height = 44.dp, numeric = true, ltr = true)
            Btn("إضافة", { if (typed.isNotBlank()) { add(typed, false); typed = "" } }, variant = Variant.Outline, height = 44.dp, icon = Lucide.Plus)
        }
        message?.let { (text, ok) -> Text(text, style = T.sm.semibold.color(if (ok) Ice.success else Ice.destructive)) }
        if (boxes.isEmpty()) Empty("وجّه الكاميرا إلى رمز QR على ملصق الصندوق")
        else ListCard {
            boxes.forEachIndexed { index, item ->
                if (index > 0) Divider()
                val box = snap.box(item.serial)
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(item.serial, Modifier.fillMaxWidth(), style = T.base.bold.ltr, textAlign = TextAlign.Right)
                        if (box != null && unexpectedBoxMove(box, action, truckId, customerId)) {
                            Text("مسجل: ${boxPlace(box, { snap.truck(it)?.str("name") }, { snap.customer(it)?.str("name") })}", style = T.xs.color(Ice.onWarning))
                        }
                    }
                    Icon(Lucide.X, "إزالة", Modifier.size(20.dp).tap { boxes = boxes - item; message = null }, tint = Ice.mutedText)
                }
            }
        }
        Btn("$confirmLabel (${boxes.size})", { onDone(boxes) }, Modifier.fillMaxWidth(), enabled = allowEmpty || boxes.isNotEmpty(), height = 48.dp, style = T.base.medium)
    }
}
