package com.tamimi.naqiice.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamimi.naqiice.core.numericInput
import com.tamimi.naqiice.data.Backend
import com.tamimi.naqiice.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Shared pieces of the supervisor screens and the invoice-change screens.

/** Result of an online-only read: [data] stays visible while a refresh is running. */
class Loaded<T>(val data: T?, val error: String?, val reload: () -> Unit)

/**
 * Loads from the server when the screen opens, when a key changes, on [Loaded.reload],
 * and every [every] milliseconds when set. One bearer serves the whole read.
 */
@Composable
fun <T> rememberLoaded(vararg keys: Any?, every: Long = 0L, fetch: suspend (bearer: String) -> T): Loaded<T> {
    val repo = LocalRepo.current
    var data by remember(*keys) { mutableStateOf<T?>(null) }
    var error by remember(*keys) { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val latest by rememberUpdatedState(fetch)
    LaunchedEffect(*keys, tick) {
        while (true) {
            try {
                data = latest(repo.api.token())
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage("تعذّر التحميل")
            }
            if (every <= 0) break
            delay(every)
        }
    }
    return Loaded(data, error) { tick++ }
}

@Composable
fun LoadError(text: String, retry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Empty(text)
        MidButton("إعادة المحاولة", Modifier.fillMaxWidth(), outlined = true, onClick = retry)
    }
}

/** Replaces the web app's prompt(): a required free-text reason. */
@Composable
fun ReasonDialog(title: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = { Field(text, { text = it }, "السبب", singleLine = false) },
        confirmButton = { TextButton({ onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("إلغاء") } },
    )
}

/** Opens the deposit slip photo through a short-lived signed link. */
@Composable
fun DepositSlipButton(path: String?) {
    if (path.isNullOrEmpty()) return
    val repo = LocalRepo.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    MidButton("عرض إيصال الإيداع", outlined = true) {
        scope.launch {
            try {
                if (!openUrl(context, repo.api.signedUrl(Backend.RECEIPT_BUCKET, path, 300, repo.api.token()))) toast("تعذّر فتح الصورة")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.userMessage("تعذّر فتح الصورة"))
            }
        }
    }
}

@Composable
fun Chip(text: String, background: Color, color: Color = Ice.foreground) {
    Text(text, Modifier.background(background, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 12.sp, color = color)
}

/** A tappable list row that leads to another screen. */
@Composable
fun LinkRow(title: String, subtitle: String? = null, end: String? = null, highlighted: Boolean = false, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(12.dp),
        color = if (highlighted) Ice.primary.copy(alpha = 0.05f) else Ice.card,
        border = BorderStroke(1.dp, if (highlighted) Ice.primary.copy(alpha = 0.4f) else Ice.border),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = Ice.mutedText)
            }
            if (end != null) Text(end, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = Ice.mutedText)
        }
    }
}

/** − [quantity] + as on the sale screen. */
@Composable
fun QtyStepper(n: Double, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StepButton("−", Ice.secondary, Ice.primary) { onChange(maxOf(0.0, n - 1)) }
        BasicTextField(
            value = if (n == 0.0) "" else plainNumber(n),
            onValueChange = { onChange(maxOf(0.0, numericInput(it).toDoubleOrNull() ?: 0.0)) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = Ice.foreground),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(56.dp).background(Ice.background, RoundedCornerShape(8.dp)).padding(vertical = 10.dp),
            decorationBox = { inner -> Box(contentAlignment = Alignment.Center) { if (n == 0.0) Text("0", color = Ice.mutedText, fontSize = 18.sp); inner() } },
        )
        StepButton("+", Ice.primary, Ice.onPrimary) { onChange(n + 1) }
    }
}

@Composable
private fun StepButton(symbol: String, background: Color, content: Color, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).background(background, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(symbol, color = content, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

/** Whole numbers without a trailing ".0", for editable fields. */
fun plainNumber(n: Double): String = if (n == Math.rint(n) && kotlin.math.abs(n) < 9e15) n.toLong().toString() else n.toString()
