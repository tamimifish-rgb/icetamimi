package com.tamimi.naqiice.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.tamimi.naqiice.core.LocationDecision
import com.tamimi.naqiice.core.locationDecision
import com.tamimi.naqiice.tracking.Locations
import com.tamimi.naqiice.tracking.TrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

// ---- location gate --------------------------------------------------------------------

/** A position for a record; both null means "saved without location" (invoices only). */
data class Position(val lat: Double?, val lng: Double?, val accuracy: Float? = null, val time: Long? = null)

fun com.tamimi.naqiice.tracking.Fix?.toPosition() =
    Position(this?.lat, this?.lng, this?.accuracy?.takeIf { it != Float.MAX_VALUE }, this?.time)

class LocationGate(
    /** Only staff who are distributors (and not managers) must share their location. */
    val required: Boolean,
    val blocked: Boolean,
    val prompt: @Composable () -> Unit,
    /** Re-checks with a fresh fix right before a write; null means the write is blocked. */
    val check: suspend () -> Position?,
)

/**
 * Distributor writes need a current position. Invoices alone may be saved without one
 * when permission is granted but no fix arrived for two minutes ([allowNoFix]).
 */
@Composable
fun rememberLocationGate(allowNoFix: Boolean = false): LocationGate {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by repo.me.collectAsState()
    val required = me?.locationRequired == true
    var decision by remember { mutableStateOf(LocationDecision.BLOCKED) }
    var denied by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val missing = remember { arrayOfNulls<Long>(1) }

    val check: suspend () -> Position? = remember(required, me?.id) {
        suspend {
            if (!required) Position(null, null) else {
                busy = true
                val granted = Locations.granted(context)
                val fix = if (granted) Locations.current(context, 10_000, 15_000) else null
                if (fix != null) missing[0] = null
                else if (granted) { if (missing[0] == null) missing[0] = System.currentTimeMillis() }
                else missing[0] = null
                val next = locationDecision(granted, fix != null, missing[0], System.currentTimeMillis(), allowNoFix)
                decision = next; denied = !granted; busy = false
                if (granted) TrackingService.update(context, repo)
                if (next == LocationDecision.BLOCKED) null else fix.toPosition()
            }
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { scope.launch { check() } }

    LaunchedEffect(required, me?.id) {
        if (!required) return@LaunchedEffect
        missing[0] = null; decision = LocationDecision.BLOCKED
        while (true) { check(); delay(20_000) }
    }

    val blocked = required && decision == LocationDecision.BLOCKED
    val prompt: @Composable () -> Unit = {
        // section[role=alert].space-y-3.border.border-destructive.rounded-lg.p-4
        if (blocked) Column(
            Modifier.fillMaxWidth().border(1.dp, Ice.destructive, RoundedCornerShape(Radius.lg)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("الموقع مطلوب للموزع", style = T.base.bold)
            Text(if (denied) "اسمح لهذا التطبيق باستخدام الموقع من إعدادات الهاتف." else "شغّل موقع الهاتف (GPS) واسمح للتطبيق باستخدامه، ثم أعد المحاولة.")
            if (denied) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("السماح بالموقع الدقيق", {
                    val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
                    permissions.launch(wanted.toTypedArray())
                }, variant = Variant.Outline)
                Btn("إعدادات التطبيق", { openAppSettings(context) }, variant = Variant.Outline)
            }
            if (allowNoFix && !denied) Text("إذا كان الإذن مسموحاً وتعذّر تحديد الموقع لدقيقتين، يمكن حفظ الفاتورة بعلامة «بدون موقع».", style = T.sm)
            Row { Btn("إعادة المحاولة", { scope.launch { check() } }, enabled = !busy) }
        }
    }
    return LocationGate(required, blocked, prompt, check)
}

fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// ---- photos ---------------------------------------------------------------------------

fun cameraGranted(context: Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED

object Photos {
    private fun dir(context: Context) = File(context.filesDir, "photos").apply { mkdirs() }

    fun newCaptureFile(context: Context) = File(dir(context), "capture-${UUID.randomUUID()}.jpg")

    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /**
     * Copies a captured or picked image into app storage as an upright JPEG no larger
     * than 1600px, so receipts upload over slow connections. Returns the saved path.
     */
    suspend fun import(context: Context, source: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
            val decoded = resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return@runCatching null
            val rotation = resolver.openInputStream(source)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            val scale = minOf(1f, 1600f / maxOf(decoded.width, decoded.height))
            val matrix = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
            val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val out = File(dir(context), "${UUID.randomUUID()}.jpg")
            out.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            out.absolutePath
        }.getOrNull()
    }

    /** What happened to a picked document. */
    sealed interface Imported {
        data class Saved(val path: String) : Imported
        data object TooLarge : Imported
        data object Failed : Imported
    }

    const val MAX_DOCUMENT_BYTES = 10 * 1024 * 1024

    /** Copies a picked PDF into app storage unchanged, refusing one larger than 10 MB. */
    suspend fun importPdf(context: Context, source: Uri): Imported = withContext(Dispatchers.IO) {
        runCatching {
            val out = File(dir(context), "${UUID.randomUUID()}.pdf")
            val copied = context.contentResolver.openInputStream(source)?.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_DOCUMENT_BYTES) return@use -1L
                        output.write(buffer, 0, n)
                    }
                    total
                }
            }
            when {
                copied == null -> { out.delete(); Imported.Failed }
                copied < 0 -> { out.delete(); Imported.TooLarge }
                else -> Imported.Saved(out.absolutePath)
            }
        }.getOrElse { Imported.Failed }
    }
}

/** Saves a camera capture or a gallery pick on the phone and reports its path. */
@Composable
private fun rememberPhotoSource(onChange: (String?) -> Unit): Pair<() -> Unit, () -> Unit> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var capture by remember { mutableStateOf<File?>(null) }
    fun accept(uri: Uri?, cleanup: File? = null) {
        if (uri == null) return
        scope.launch {
            val saved = Photos.import(context, uri)
            cleanup?.delete()
            if (saved == null) toast("تعذّر حفظ الصورة") else onChange(saved)
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = capture
        capture = null
        if (taken && file != null) accept(Photos.uriFor(context, file), file) else file?.delete()
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { accept(it) }
    val launchCamera = {
        val file = Photos.newCaptureFile(context)
        capture = file
        runCatching { camera.launch(Photos.uriFor(context, file)) }.onFailure { capture = null; file.delete(); toast("تعذّر فتح الكاميرا") }
        Unit
    }
    // The app declares CAMERA (box stickers), so the system camera needs it granted as well.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else toast("اسمح للتطبيق باستخدام الكاميرا من إعدادات الهاتف")
    }
    val openCamera = {
        if (cameraGranted(context)) launchCamera() else runCatching { cameraPermission.launch(Manifest.permission.CAMERA) }
        Unit
    }
    val openGallery = { runCatching { gallery.launch("image/*") }; Unit }
    return openCamera to openGallery
}

/** The web's dashed "take a photo" box; like there, a tap opens the camera. */
@Composable
fun PhotoPick(label: String, path: String?, onChange: (String?) -> Unit) {
    val (openCamera, _) = rememberPhotoSource(onChange)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(Ice.card, RoundedCornerShape(Radius.md)).dashedBorder(Radius.md, Ice.input).tap(onClick = openCamera).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.Camera, null, Modifier.size(20.dp), tint = Ice.accent)
            Text(if (path != null) "${File(path).name}" else label, style = T.sm, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (path != null) Text("إزالة الصورة", style = T.sm.color(Ice.destructive), modifier = Modifier.tap { onChange(null) })
    }
}

/** Deposit slips may come from the camera or the gallery. */
@Composable
fun DepositPhoto(path: String?, onChange: (String?) -> Unit) {
    val (openCamera, openGallery) = rememberPhotoSource(onChange)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("صورة إيصال الإيداع (مطلوبة)", style = T.sm)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("الكاميرا" to openCamera, "معرض الصور" to openGallery).forEach { (label, open) ->
                Box(Modifier.heightIn(min = 44.dp).border(1.dp, Ice.input, RoundedCornerShape(Radius.md)).tap(onClick = open).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = T.sm)
                }
            }
        }
        if (path != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(File(path).name, style = T.sm, modifier = Modifier.weight(1f, fill = false))
            Btn("إزالة", { onChange(null) }, variant = Variant.Ghost, height = 32.dp, style = T.xs.medium)
        }
    }
}

/** A deposit or transfer slip: a photo from the camera or the gallery, or a PDF file. */
@Composable
fun SlipPick(path: String?, onChange: (String?) -> Unit) {
    val (openCamera, openGallery) = rememberPhotoSource(onChange)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            when (val result = Photos.importPdf(context, uri)) {
                is Photos.Imported.Saved -> onChange(result.path)
                Photos.Imported.TooLarge -> toast("الملف أكبر من 10 ميجابايت")
                Photos.Imported.Failed -> toast("تعذّر حفظ الملف")
            }
        }
    }
    val openPdf = { runCatching { pdf.launch(arrayOf("application/pdf")) }; Unit }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("إشعار الإيداع أو الحوالة: صورة أو ملف PDF (مطلوب)", style = T.sm)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("الكاميرا" to openCamera, "معرض الصور" to openGallery, "ملف PDF" to openPdf).forEach { (label, open) ->
                Box(Modifier.heightIn(min = 44.dp).border(1.dp, Ice.input, RoundedCornerShape(Radius.md)).tap(onClick = open).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = T.sm)
                }
            }
        }
        if (path != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(File(path).name, style = T.sm, modifier = Modifier.weight(1f, fill = false))
            Btn("إزالة", { onChange(null) }, variant = Variant.Ghost, height = 32.dp, style = T.xs.medium)
        }
    }
}

// ---- external apps --------------------------------------------------------------------

fun openUrl(context: Context, url: String): Boolean =
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

fun dial(context: Context, phone: String): Boolean =
    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
