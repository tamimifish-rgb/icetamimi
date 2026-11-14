package com.tamimi.naqiice.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamimi.naqiice.core.numericInput
import com.tamimi.naqiice.data.Repo
import com.tamimi.naqiice.data.userMessage
import kotlinx.coroutines.launch
import java.util.UUID

// Brand palette taken from the web app's theme.
object Ice {
    val background = Color(0xFFEFF9FD)
    val foreground = Color(0xFF091F3B)
    val primary = Color(0xFF0C335F)
    val onPrimary = Color(0xFFF2FAFE)
    val secondary = Color(0xFFD0EDFA)
    val muted = Color(0xFFE5F1F7)
    val mutedText = Color(0xFF52657A)
    val accent = Color(0xFF54C9E3)
    val destructive = Color(0xFFDB2B33)
    val success = Color(0xFF009962)
    val warning = Color(0xFFEFA831)
    val onWarning = Color(0xFF331B06)
    val border = Color(0xFFD2E1EA)
    val card = Color.White
    val gradient = Brush.linearGradient(listOf(Color(0xFF0C335F), Color(0xFF1B5E9E), Color(0xFF4FB3D9)))
}

private val scheme = lightColorScheme(
    primary = Ice.primary, onPrimary = Ice.onPrimary, secondary = Ice.accent, onSecondary = Ice.foreground,
    secondaryContainer = Ice.secondary, onSecondaryContainer = Ice.primary,
    background = Ice.background, onBackground = Ice.foreground, surface = Ice.card, onSurface = Ice.foreground,
    surfaceVariant = Ice.muted, onSurfaceVariant = Ice.mutedText, error = Ice.destructive, onError = Color.White, outline = Ice.border,
)

val LocalRepo = staticCompositionLocalOf<Repo> { error("no repo") }
val LocalNav = compositionLocalOf<Nav> { error("no nav") }

/** The whole employee interface is Arabic and right-to-left. */
@Composable
fun PureIceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
    }
}

// ---- navigation -----------------------------------------------------------------------

sealed interface Screen {
    data object Today : Screen
    data object Customers : Screen
    data object NewCustomer : Screen
    data class Customer(val id: String) : Screen
    data class Sale(val customer: String? = null) : Screen
    data class Payment(val customer: String? = null) : Screen
    data class FreezerAction(val customer: String? = null) : Screen
    data object Expenses : Screen
    data object Maintenance : Screen
    data object Closing : Screen
    data object More : Screen
    data class Invoice(val id: String) : Screen
    data object InvoiceChanges : Screen
    data class InvoiceChange(val id: String, val kind: String) : Screen
    data object Pending : Screen
    // Supervisor and administrator screens.
    data object Trips : Screen
    data class Trip(val id: String) : Screen
    data object Closings : Screen
    data object Reports : Screen
    data object RouteMap : Screen
    data object Admin : Screen
    data object AdminSetup : Screen
    data object AdminProducts : Screen
    data object AdminRegions : Screen
    data object AdminTrucks : Screen
    data object AdminCustomers : Screen
    data object AdminFreezers : Screen
    data object AdminUsers : Screen
    data object AdminCompany : Screen
}

class Nav(home: Screen) {
    val stack = mutableStateListOf(home)
    val current: Screen get() = stack.last()
    fun go(screen: Screen) { if (stack.last() != screen) stack.add(screen) }
    /** Replaces the current screen, e.g. after saving a form. */
    fun replace(screen: Screen) { stack.removeAt(stack.lastIndex); go(screen) }
    /** Bottom-bar destinations start a fresh stack. */
    fun home(screen: Screen) { stack.clear(); stack.add(screen) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
}

// ---- operations -----------------------------------------------------------------------

/**
 * One save at a time: the lock is taken before the button can be pressed twice, and the
 * record id is kept across a failed attempt so a retry can never create a duplicate.
 */
class Operation(private val launch: (suspend (String) -> Unit) -> Unit, busyState: androidx.compose.runtime.State<Boolean>) {
    val busy by busyState
    fun run(action: suspend (String) -> Unit) = launch(action)
}

@Composable
fun rememberOperation(): Operation {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val busy = remember { mutableStateOf(false) }
    val holder = remember { arrayOfNulls<String>(1) }
    return remember {
        Operation({ action ->
            if (!busy.value) {
                busy.value = true
                val id = holder[0] ?: UUID.randomUUID().toString().also { holder[0] = it }
                scope.launch {
                    try { action(id); holder[0] = null } catch (e: Exception) {
                        Toast.makeText(context, e.userMessage(), Toast.LENGTH_LONG).show()
                    } finally { busy.value = false }
                }
            }
        }, busy)
    }
}

/** Stops a save with a short message (the record id is kept for the retry). */
class Notice(message: String) : Exception(message)

@Composable
fun rememberToast(): (String) -> Unit {
    val context = LocalContext.current
    return remember { { message: String -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() } }
}

// ---- building blocks ------------------------------------------------------------------

@Composable
fun PageTitle(title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ice.foreground, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun SectionTitle(text: String) = Text(text, fontWeight = FontWeight.Bold, color = Ice.foreground, modifier = Modifier.padding(bottom = 8.dp))

@Composable
fun IceCard(modifier: Modifier = Modifier, borderColor: Color = Ice.border, background: Color = Ice.card, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = background, border = BorderStroke(1.dp, borderColor)) {
        Box(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
fun Stat(label: String, value: String, tone: Color = Ice.foreground, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = Ice.card, border = BorderStroke(1.dp, Ice.border)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, fontSize = 12.sp, color = Ice.mutedText)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = tone, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Two stats per row. */
@Composable
fun StatGrid(items: List<Triple<String, String, Color>>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { Stat(it.first, it.second, it.third, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun Empty(text: String) {
    Box(
        Modifier.fillMaxWidth().border(1.dp, Ice.border, RoundedCornerShape(16.dp)).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 14.sp, color = Ice.mutedText, textAlign = TextAlign.Center) }
}

@Composable
fun Banner(background: Color, modifier: Modifier = Modifier, borderColor: Color = Color.Transparent, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().background(background, RoundedCornerShape(12.dp)).border(1.dp, borderColor, RoundedCornerShape(12.dp)).padding(12.dp)) { content() }
}

@Composable
fun BigButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick, modifier.fillMaxWidth().height(56.dp), enabled = enabled, shape = RoundedCornerShape(12.dp)) { Text(text, fontSize = 18.sp) }
}

@Composable
fun MidButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, outlined: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    if (outlined) {
        OutlinedButton(onClick, modifier.heightIn(min = 46.dp), enabled = enabled, shape = shape, colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) Ice.destructive else Ice.primary)) { Text(text) }
    } else {
        Button(onClick, modifier.heightIn(min = 46.dp), enabled = enabled, shape = shape, colors = if (danger) ButtonDefaults.buttonColors(containerColor = Ice.destructive) else ButtonDefaults.buttonColors()) { Text(text) }
    }
}

/** A row of mutually exclusive choices. */
@Composable
fun <T> Choice(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) -> MidButton(label, Modifier.weight(1f), outlined = value != selected) { onSelect(value) } }
    }
}

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    numeric: Boolean = false, ltr: Boolean = false, large: Boolean = false, keyboard: KeyboardType? = null, singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(if (numeric) numericInput(it) else it) },
        label = { Text(label) },
        singleLine = singleLine,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        textStyle = TextStyle(fontSize = if (large) 20.sp else 16.sp, textDirection = if (ltr || numeric) TextDirection.Ltr else TextDirection.Content),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard ?: if (numeric) KeyboardType.Number else KeyboardType.Text),
    )
}

/** A simple dropdown for short lists (regions, freezers, trucks…). */
@Composable
fun <T> Picker(label: String, options: List<Pair<T, String>>, selected: T?, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        Surface(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { open = true },
            shape = RoundedCornerShape(12.dp), color = Ice.card, border = BorderStroke(1.dp, Ice.border),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(label, fontSize = 12.sp, color = Ice.mutedText)
                Text(options.firstOrNull { it.first == selected }?.second ?: "— اختر —", fontSize = 16.sp)
            }
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(value) }) }
        }
    }
}

@Composable
fun LineItem(start: String, end: String, endColor: Color = Ice.foreground, bold: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(start, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(end, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = endColor)
    }
}

@Composable
fun RowScope.Grow() = Spacer(Modifier.weight(1f))
