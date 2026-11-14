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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
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
data class Position(val lat: Double?, val lng: Double?)

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
                if (next == LocationDecision.BLOCKED) null else Position(fix?.lat, fix?.lng)
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
        if (blocked) Banner(Ice.card, borderColor = Ice.destructive) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("الموقع مطلوب للموزع", fontWeight = FontWeight.Bold)
                Text(if (denied) "اسمح لهذا التطبيق باستخدام الموقع الدقيق لإكمال الرحلة." else "شغّل موقع الهاتف (GPS) ثم أعد المحاولة.", fontSize = 14.sp)
                if (allowNoFix && !denied) Text("إذا كان الإذن مسموحاً وتعذّر تحديد الموقع لدقيقتين، يمكن حفظ الفاتورة بعلامة «بدون موقع».", fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (denied) MidButton("السماح بالموقع", outlined = true) {
                        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
                        permissions.launch(wanted.toTypedArray())
                    }
                    if (denied) MidButton("إعدادات التطبيق", outlined = true) { openAppSettings(context) }
                    MidButton("إعادة المحاولة", enabled = !busy) { scope.launch { check() } }
                }
            }
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
}

/** Camera and gallery buttons; [path] is the saved photo on the phone, if any. */
@Composable
fun PhotoPick(label: String, path: String?, onChange: (String?) -> Unit) {
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (path != null) "$label ✓" else label, fontSize = 14.sp, color = if (path != null) Ice.success else Ice.foreground)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MidButton("الكاميرا", Modifier.weight(1f), outlined = true) {
                val file = Photos.newCaptureFile(context)
                capture = file
                runCatching { camera.launch(Photos.uriFor(context, file)) }.onFailure { toast("تعذّر فتح الكاميرا") }
            }
            MidButton("معرض الصور", Modifier.weight(1f), outlined = true) { runCatching { gallery.launch("image/*") } }
            if (path != null) MidButton("إزالة", outlined = true, danger = true) { onChange(null) }
        }
    }
}

// ---- external apps --------------------------------------------------------------------

fun openUrl(context: Context, url: String): Boolean =
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

fun dial(context: Context, phone: String): Boolean =
    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
