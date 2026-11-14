package com.tamimi.naqiice.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tamimi.naqiice.core.numericInput
import com.tamimi.naqiice.data.Backend
import com.tamimi.naqiice.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

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

/**
 * The web `Button` at its default `h-9`. `Btn` pads its label by 8dp above and below, which
 * leaves a 36dp button only 20dp for a line that Cairo draws taller: the label is cut and sits
 * 3dp low. Without the vertical padding the label is whole and centred, as on the web.
 */
@Composable
fun SmallBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, variant: Variant = Variant.Default, enabled: Boolean = true) =
    Btn(onClick, modifier, variant, enabled, padding = PaddingValues(horizontal = 16.dp)) { Text(text, maxLines = 1) }

/** `h2.mb-2.font-bold` of a section; over an empty list its margin collapses into the gap that follows. */
@Composable
fun SectionHead(text: String, empty: Boolean) = if (empty) Text(text, style = T.base.bold) else SectionTitle(text)

/** The web's error state: the message and an outline retry button inside the dashed `Empty` box. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoadError(text: String, retry: () -> Unit) {
    Box(Modifier.fillMaxWidth().dashedBorder(Radius.xl2).padding(24.dp), contentAlignment = Alignment.Center) {
        FlowRow(horizontalArrangement = Arrangement.Center) {
            Text(text, Modifier.align(Alignment.CenterVertically), style = T.sm.muted, textAlign = TextAlign.Center)
            // The outline button has no colour of its own: it takes the box's muted text colour.
            CompositionLocalProvider(LocalContentColor provides Ice.mutedText) {
                SmallBtn("إعادة المحاولة", retry, Modifier.align(Alignment.CenterVertically), Variant.Outline)
            }
        }
    }
}

/** `RequireRole`'s refusal: rounded-xl bg-muted p-6 text-center text-muted-foreground. */
@Composable
fun NotAllowed() {
    Banner(Ice.muted, padding = 24.dp) { Text("ليست لديك صلاحية لهذه الصفحة.", Modifier.fillMaxWidth(), style = T.base.muted, textAlign = TextAlign.Center) }
}

/** The browser's `prompt()`: a message, a free-text answer, OK and Cancel. */
@Composable
fun ReasonDialog(title: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title)
                Input(text, { text = it })
            }
        },
        confirmButton = { TextButton({ onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("إلغاء") } },
    )
}

/** `DepositSlip`: an outline button that opens the slip photo through a short-lived signed link. */
@Composable
fun DepositSlipButton(path: String?) {
    if (path.isNullOrEmpty()) return
    val repo = LocalRepo.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    SmallBtn("عرض إيصال الإيداع", {
        scope.launch {
            try {
                if (!openUrl(context, repo.api.signedUrl(Backend.RECEIPT_BUCKET, path, 300, repo.api.token()))) toast("تعذّر فتح الصورة")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.userMessage("تعذّر فتح الصورة"))
            }
        }
    }, variant = Variant.Outline)
}

/** `span.rounded-full.px-2.py-0.5.text-xs`; as a stretched flex item its text stays at the top. */
@Composable
fun Chip(text: String, background: Color, color: Color = Ice.foreground, modifier: Modifier = Modifier) {
    Box(modifier.background(background, CircleShape)) {
        // Measured without the chip's height limit, so a stretched chip never cuts its line.
        Text(text, Modifier.wrapContentHeight(Alignment.Top, unbounded = true).padding(horizontal = 8.dp, vertical = 2.dp), style = T.xs.color(color), maxLines = 1)
    }
}

/**
 * Text followed on the same line by a smaller inline `<span>` (a code, a date, a state),
 * sitting on the same baseline as on the web. Put the separating space at the end of [text].
 */
@Composable
fun RowScope.Inline(text: String, style: TextStyle, small: String?, smallStyle: TextStyle) {
    Text(text, Modifier.alignByBaseline(), style = style)
    if (small != null) Text(small, Modifier.alignByBaseline(), style = smallStyle)
}

/**
 * Text ending in the web's "←". Cairo has no arrow, and the font a browser falls back to
 * draws a full-size one where Android's draws a hairline; this draws the browser's.
 */
@Composable
fun ArrowText(text: String, style: TextStyle, modifier: Modifier = Modifier, center: Boolean = false) {
    Row(modifier, horizontalArrangement = if (center) Arrangement.Center else Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
        Text("$text ", Modifier.weight(1f, fill = false), style = style)
        Icon(Lucide.ArrowRight, null, Modifier.size(16.dp).scale(-1f, 1f), tint = style.color)
    }
}

/**
 * Takes Cairo's extra line height back out of an editable text, exactly as the package's
 * `Text` does for static text, so typed text sits where the browser puts it.
 */
@Composable
fun Modifier.trimLine(style: TextStyle): Modifier {
    val measurer = rememberTextMeasurer(0)
    val density = LocalDensity.current
    val extra = remember(style, density) {
        (measurer.measure("ا", style, density = density).size.height - with(density) { style.lineHeight.roundToPx() }).coerceAtLeast(0)
    }
    return if (extra == 0) this else layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, (placeable.height - extra).coerceAtLeast(0)) { placeable.place(0, -(extra / 2)) }
    }
}

/** The web `Textarea`: rounded-md, input-coloured border, px-3 py-2, text-base, small shadow, fixed `min-h-*`. */
@Composable
fun TextArea(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "", height: Dp = 60.dp) {
    val shape = RoundedCornerShape(Radius.md)
    val style = T.base.copy(color = Ice.foreground)
    BasicTextField(
        value = value, onValueChange = onChange, textStyle = style, cursorBrush = SolidColor(Ice.foreground),
        modifier = modifier.fillMaxWidth().height(height).smallShadow(Radius.md).border(1.dp, Ice.input, shape),
        decorationBox = { inner ->
            Box(Modifier.clip(shape).padding(horizontal = 13.dp, vertical = 9.dp)) {
                if (value.isEmpty()) Text(placeholder, style = style.muted)
                Box(Modifier.trimLine(style)) { inner() }
            }
        },
    )
}

/**
 * The web's `<input type="date" class="h-10 w-40">`. A browser lays it out left-to-right
 * whatever the page direction: the date at the left, the calendar glyph at the right.
 */
@Composable
fun DateInput(value: String, modifier: Modifier = Modifier, height: Dp = 40.dp, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val parts = value.split('-').mapNotNull { it.toIntOrNull() }
    Box(
        modifier.height(height).smallShadow(Radius.md).box(Radius.md, null, Ice.input).semantics { contentDescription = "التاريخ" }.tap {
            if (parts.size == 3) DatePickerDialog(context, { _, y, m, d -> onChange(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)) }, parts[0], parts[1] - 1, parts[2]).show()
        },
    ) {
        // Physical left is the end edge in this right-to-left layout.
        Text(
            if (parts.size == 3) "%02d/%02d/%04d".format(Locale.US, parts[2], parts[1], parts[0]) else "",
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), style = T.base.ltr, maxLines = 1,
        )
        CalendarGlyph(Modifier.align(Alignment.CenterStart).padding(start = 6.5.dp, bottom = 1.5.dp))
    }
}

/** Chromium's date-picker indicator: a black calendar with a filled header and two rings. */
@Composable
private fun CalendarGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier.size(11.5.dp, 12.5.dp)) {
        val stroke = 1.5.dp.toPx()
        val top = 1.5.dp.toPx()
        drawRoundRect(
            Color.Black, Offset(stroke / 2, top + stroke / 2), Size(size.width - stroke, size.height - top - stroke),
            CornerRadius(1.dp.toPx()), style = Stroke(stroke),
        )
        drawRect(Color.Black, Offset(0.75.dp.toPx(), top), Size(size.width - 1.5.dp.toPx(), 3.dp.toPx()))
        val ring = Size(1.5.dp.toPx(), top + 0.5.dp.toPx())
        drawRect(Color.Black, Offset(2.25.dp.toPx(), 0f), ring)
        drawRect(Color.Black, Offset(size.width - 3.75.dp.toPx(), 0f), ring)
    }
}

/**
 * The web's native `<select class="h-10 rounded-md border border-input bg-background px-2 text-sm">`:
 * the chosen text at the start, the browser's arrow tight against the end edge.
 */
@Composable
fun <T> SmallSelect(options: List<Pair<T, String>>, selected: T?, modifier: Modifier = Modifier, placeholder: String = "", onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.md)
    Box(modifier) {
        Box(Modifier.fillMaxWidth().height(40.dp).background(Ice.background, shape).border(1.dp, Ice.input, shape).clip(shape).clickable { open = true }) {
            Text(
                options.firstOrNull { it.first == selected }?.second ?: placeholder,
                Modifier.align(Alignment.CenterStart).padding(start = 13.dp, end = 20.dp), style = T.sm, maxLines = 1,
            )
            // Chromium's menu arrow: a short, heavy chevron 3dp inside the end edge.
            Canvas(Modifier.align(Alignment.CenterEnd).padding(end = 3.5.dp, top = 1.dp).size(8.dp, 4.5.dp)) {
                val path = androidx.compose.ui.graphics.Path().apply { moveTo(0f, 0f); lineTo(size.width / 2, size.height); lineTo(size.width, 0f) }
                drawPath(path, Ice.foreground, style = Stroke(2.dp.toPx()))
            }
        }
        DropdownMenu(open, { open = false }, containerColor = Ice.card) {
            if (placeholder.isNotEmpty()) DropdownMenuItem(text = { Text(placeholder, style = T.sm) }, onClick = { open = false })
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text, style = T.sm) }, onClick = { open = false; onSelect(value) }) }
        }
    }
}

/** `flex size-11 items-center justify-center rounded-full` with a `size-5` icon. */
@Composable
fun RoundIconButton(icon: ImageVector, label: String, background: Color, content: Color, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(background).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(20.dp), tint = content)
    }
}

/** − [quantity] + : round buttons around `input.h-11.w-14.rounded-lg.border-input.bg-background.text-lg.font-bold`. */
@Composable
fun RowScope.QtyStepper(n: Double, onChange: (Double) -> Unit) {
    RoundIconButton(Lucide.Minus, "إنقاص", Ice.secondary, Ice.foreground) { onChange(maxOf(0.0, n - 1)) }
    val shape = RoundedCornerShape(Radius.lg)
    val style = T.lg.bold.copy(color = Ice.foreground, textAlign = TextAlign.Center)
    BasicTextField(
        value = if (n == 0.0) "" else plainNumber(n),
        onValueChange = { onChange(maxOf(0.0, numericInput(it).toDoubleOrNull() ?: 0.0)) },
        singleLine = true, textStyle = style, cursorBrush = SolidColor(Ice.foreground),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.size(56.dp, 44.dp).background(Ice.background, shape).border(1.dp, Ice.input, shape),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.Center) {
                // A bare input's placeholder is the text colour at half strength.
                if (n == 0.0) Text("0", style = style.color(Ice.foreground.copy(alpha = 0.5f)))
                inner()
            }
        },
    )
    RoundIconButton(Lucide.Plus, "زيادة", Ice.primary, Ice.onPrimary) { onChange(n + 1) }
}

/** Whole numbers without a trailing ".0", for editable fields. */
fun plainNumber(n: Double): String = if (n == Math.rint(n) && kotlin.math.abs(n) < 9e15) n.toLong().toString() else n.toString()
