package com.tamimi.naqiice.ui

import android.graphics.Paint
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.Typography
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamimi.naqiice.R
import com.tamimi.naqiice.core.numericInput
import com.tamimi.naqiice.data.Repo
import com.tamimi.naqiice.data.userMessage
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// The look is a 1:1 port of the web app's theme (src/styles.css and its components):
// same palette, Cairo type scale, corner radii, shadows, buttons and inputs.

object Ice {
    val background = Color(0xFFEFF9FD)
    val foreground = Color(0xFF091F3B)
    val primary = Color(0xFF0C335F)
    val onPrimary = Color(0xFFF2FAFE)
    val secondary = Color(0xFFD0EDFA)
    val onSecondary = Color(0xFF0D2E55)
    val muted = Color(0xFFE5F1F7)
    val mutedText = Color(0xFF52657A)
    val accent = Color(0xFF54C9E3)
    val destructive = Color(0xFFDB2B33)
    val onDestructive = Color(0xFFF8F8F8)
    val success = Color(0xFF009962)
    val warning = Color(0xFFEFA831)
    val onWarning = Color(0xFF331B06)
    val border = Color(0xFFD2E1EA)
    val input = Color(0xFFC8DBE6)
    val card = Color.White
    /** `bg-ice`: linear-gradient(150deg, primary 0%, 55%, 100%). */
    val gradient: Brush = cssGradient(150f, 0f to Color(0xFF0C335F), 0.55f to Color(0xFF005B90), 1f to Color(0xFF18B1CD))
}

/** A CSS `linear-gradient(<angle>deg, …)`; angles are clockwise from "to top". */
fun cssGradient(angle: Float, vararg stops: Pair<Float, Color>): Brush = object : ShaderBrush() {
    override fun createShader(size: Size) = Math.toRadians(angle.toDouble()).let { a ->
        val dx = sin(a).toFloat()
        val dy = -cos(a).toFloat()
        val half = (abs(size.width * dx) + abs(size.height * dy)) / 2
        val center = Offset(size.width / 2, size.height / 2)
        LinearGradientShader(
            Offset(center.x - dx * half, center.y - dy * half), Offset(center.x + dx * half, center.y + dy * half),
            stops.map { it.second }, stops.map { it.first },
        )
    }
}

val Cairo = FontFamily(
    Font(R.font.cairo_regular, FontWeight.Normal), Font(R.font.cairo_medium, FontWeight.Medium),
    Font(R.font.cairo_semibold, FontWeight.SemiBold), Font(R.font.cairo_bold, FontWeight.Bold),
)

private fun type(size: Int, line: Int) = TextStyle(
    fontFamily = Cairo, fontSize = size.sp, lineHeight = line.sp, platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** Tailwind's type scale (font size / line height). */
object T {
    val xs = type(12, 16)
    val sm = type(14, 20)
    val base = type(16, 24)
    val lg = type(18, 28)
    val xl = type(20, 28)
    val xl2 = type(24, 32)
    val xl3 = type(30, 36)
}

val TextStyle.medium get() = copy(fontWeight = FontWeight.Medium)
val TextStyle.semibold get() = copy(fontWeight = FontWeight.SemiBold)
val TextStyle.bold get() = copy(fontWeight = FontWeight.Bold)
fun TextStyle.color(color: Color) = copy(color = color)
val TextStyle.muted get() = copy(color = Ice.mutedText)
val TextStyle.ltr get() = copy(textDirection = TextDirection.Ltr)

/** Corner radii derived from `--radius: 0.9rem`. */
object Radius {
    val md = 12.4.dp
    val lg = 14.4.dp
    val xl = 18.4.dp
    val xl2 = 22.4.dp
    val xl3 = 26.4.dp
}

private val scheme = lightColorScheme(
    primary = Ice.primary, onPrimary = Ice.onPrimary, secondary = Ice.accent, onSecondary = Ice.foreground,
    secondaryContainer = Ice.secondary, onSecondaryContainer = Ice.onSecondary,
    background = Ice.background, onBackground = Ice.foreground, surface = Ice.card, onSurface = Ice.foreground,
    surfaceVariant = Ice.muted, onSurfaceVariant = Ice.mutedText, error = Ice.destructive, onError = Color.White, outline = Ice.border,
    surfaceContainer = Ice.card, surfaceContainerHigh = Ice.background, surfaceContainerHighest = Ice.background, surfaceContainerLow = Ice.card,
    surfaceTint = Color.Transparent,
)

private val typography = Typography().run {
    fun TextStyle.cairo() = copy(fontFamily = Cairo, platformStyle = PlatformTextStyle(includeFontPadding = false))
    copy(
        displayLarge.cairo(), displayMedium.cairo(), displaySmall.cairo(), headlineLarge.cairo(), headlineMedium.cairo(), headlineSmall.cairo(),
        titleLarge.cairo(), titleMedium.cairo(), titleSmall.cairo(), T.base, T.base, T.sm, labelLarge.cairo(), labelMedium.cairo(), labelSmall.cairo(),
    )
}

val LocalRepo = staticCompositionLocalOf<Repo> { error("no repo") }
val LocalNav = compositionLocalOf<Nav> { error("no nav") }

/** The whole employee interface is Arabic and right-to-left. */
@Composable
fun PureIceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalContentColor provides Ice.foreground) {
            ProvideTextStyle(T.base, content)
        }
    }
}

// ---- text ------------------------------------------------------------------------------

/** The Tailwind line height that goes with a font size given on its own. */
private fun TextStyle.sized(size: TextUnit): TextStyle = when (size.value.toInt()) {
    12 -> T.xs; 14 -> T.sm; 16 -> T.base; 18 -> T.lg; 20 -> T.xl; 24 -> T.xl2; 30 -> T.xl3
    else -> type(size.value.toInt(), (size.value * 1.4f).toInt())
}.let { merge(TextStyle(fontSize = it.fontSize, lineHeight = it.lineHeight)) }

private val lineOverflow = HashMap<String, Int>()

/**
 * Text with the web's line boxes. Cairo's own line is taller than the CSS line heights,
 * and Android keeps that extra above the first and below the last line; a browser lets it
 * overflow instead. This takes the extra back out so blocks have exactly the web's height.
 */
@Composable
fun Text(
    text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null, fontWeight: FontWeight? = null, fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null, textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true, maxLines: Int = Int.MAX_VALUE, minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null, style: TextStyle = LocalTextStyle.current,
) {
    var merged = if (fontSize.isSpecified && !lineHeight.isSpecified) style.sized(fontSize) else style
    merged = merged.merge(TextStyle(fontSize = fontSize, fontWeight = fontWeight, fontFamily = fontFamily, lineHeight = lineHeight, fontStyle = fontStyle))
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(0)
    val key = "${merged.fontSize}|${merged.lineHeight}|${merged.fontWeight}|${merged.fontFamily.hashCode()}|${density.density}|${density.fontScale}"
    val extra = if (!merged.lineHeight.isSpecified) 0 else lineOverflow.getOrPut(key) {
        (measurer.measure("ا", merged, density = density).size.height - with(density) { merged.lineHeight.roundToPx() }).coerceAtLeast(0)
    }
    val trimmed = if (extra == 0) modifier else modifier.layout { measurable, constraints ->
        // Measured without a height limit: inside a fixed-height button the untrimmed line is
        // taller than the room it is given, and a clipped measure would shift the glyphs down.
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
        layout(placeable.width, (placeable.height - extra).coerceAtLeast(0)) { placeable.place(0, -(extra / 2)) }
    }
    M3Text(
        text, trimmed, color, TextUnit.Unspecified, null, null, null, letterSpacing, textDecoration, textAlign, TextUnit.Unspecified,
        overflow, softWrap, maxLines, minLines, onTextLayout, merged,
    )
}

// ---- shadows and borders --------------------------------------------------------------

/** A CSS box-shadow (`0 <dy> <blur> <spread> <color>`) behind a rounded box. */
fun Modifier.cssShadow(radius: Dp, blur: Dp, dy: Dp, spread: Dp = 0.dp, color: Color): Modifier = drawBehind {
    val paint = Paint().apply {
        isAntiAlias = true
        this.color = android.graphics.Color.TRANSPARENT
        // CSS blur is twice the Gaussian deviation; Android's radius maps to 0.577·r + 0.5.
        setShadowLayer(((blur.toPx() / 2 - 0.5f) / 0.57735f).coerceAtLeast(0.1f), 0f, dy.toPx(), color.toArgb())
    }
    val s = spread.toPx()
    val r = (radius.toPx() + s).coerceAtLeast(0f)
    // As in CSS, the shadow is only painted outside the box, never under a transparent fill.
    val inside = androidx.compose.ui.graphics.Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius.toPx())))
    }
    clipPath(inside, androidx.compose.ui.graphics.ClipOp.Difference) {
        drawIntoCanvas { it.nativeCanvas.drawRoundRect(-s, -s, size.width + s, size.height + s, r, r, paint) }
    }
}

/** `shadow-soft`: 0 8px 24px -12px primary/35%. */
fun Modifier.softShadow(radius: Dp) = cssShadow(radius, 24.dp, 8.dp, (-12).dp, Ice.primary.copy(alpha = 0.35f))

/** `shadow` / `shadow-sm`: the small drop shadow under buttons and inputs. */
fun Modifier.smallShadow(radius: Dp) = cssShadow(radius, 3.dp, 1.dp, 0.dp, Color.Black.copy(alpha = 0.1f))

/** `shadow-lg` for dialogs. */
fun Modifier.largeShadow(radius: Dp) = cssShadow(radius, 15.dp, 10.dp, (-3).dp, Color.Black.copy(alpha = 0.1f))

/** A rounded box: optional soft shadow, fill and 1px (or wider) border. */
fun Modifier.box(radius: Dp, background: Color? = Ice.card, border: Color? = Ice.border, borderWidth: Dp = 1.dp, shadow: Boolean = false): Modifier {
    var m = if (shadow) softShadow(radius) else this
    val shape = RoundedCornerShape(radius)
    if (background != null) m = m.background(background, shape)
    // As in CSS, a border takes its own space: content starts inside it.
    if (border != null) m = m.border(borderWidth, border, shape).padding(borderWidth)
    return m
}

fun Modifier.dashedBorder(radius: Dp, color: Color = Ice.border): Modifier = drawBehind {
    val w = 1.dp.toPx()
    drawRoundRect(
        color, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(radius.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
    )
}

/** Web links and rows have no ripple; a press gives no visual change there either. */
@Composable
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)

// ---- navigation -----------------------------------------------------------------------

sealed interface Screen {
    data object Today : Screen
    data object Customers : Screen
    /** The distributor's customers on a map, marking those invoiced in this campaign. */
    data object CustomerMap : Screen
    /** One employee's sales figures and account (charged shortages, salary deductions). */
    data class StaffProfile(val id: String) : Screen
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
    /** The route map; [trip] opens it on one campaign (the web's `/map?trip=<id>`). */
    data class RouteMap(val trip: String? = null) : Screen
    data object Admin : Screen
    data object AdminSetup : Screen
    data object AdminProducts : Screen
    data object AdminRegions : Screen
    data object AdminTrucks : Screen
    data object AdminCustomers : Screen
    data object AdminFreezers : Screen
    data object AdminUsers : Screen
    data object AdminCompany : Screen

    // Returnable boxes (shown only when the server has the feature).
    data object Boxes : Screen
    data class Box(val id: String) : Screen
    data object AdminBoxes : Screen
}

class Nav(home: Screen) {
    val stack = mutableStateListOf(home)
    val current: Screen get() = stack.last()
    fun go(screen: Screen) { if (stack.last() != screen) stack.add(screen) }
    /** Replaces the current screen, e.g. after saving a form. */
    fun replace(screen: Screen) {
        // The current screen may be the only one (opened from the bottom bar).
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        if (stack.lastOrNull() != screen) stack.add(screen)
    }
    /** Bottom-bar destinations start a fresh stack. */
    fun home(screen: Screen) { stack.clear(); stack.add(screen) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
}

/**
 * Applies loaded data on the main thread. On a phone a screen's effects already run there,
 * so this is free; it matters where the caller's dispatcher is unconfined (the screen
 * tests), where the write would otherwise happen on a network thread and could be missed.
 */
suspend fun <T> onMain(block: () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { block() }

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
                // Always continues on the main thread: the action updates the screen after saving.
                scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
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
    return remember {
        { message: String ->
            val show = { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) show() else { android.os.Handler(android.os.Looper.getMainLooper()).post { show() }; Unit }
        }
    }
}

// ---- buttons --------------------------------------------------------------------------

enum class Variant { Default, Destructive, Outline, Secondary, Ghost, Link }

/**
 * The web `Button`: rounded-md, text-sm medium, 16px icons, half opacity when disabled.
 * [height] is the Tailwind `h-*` the screen uses (h-9 by default); [minHeight] is `min-h-*`.
 */
@Composable
fun Btn(
    onClick: () -> Unit, modifier: Modifier = Modifier, variant: Variant = Variant.Default, enabled: Boolean = true,
    height: Dp? = 36.dp, minHeight: Dp = 0.dp, style: TextStyle = T.sm.medium, padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    radius: Dp = Radius.md, content: @Composable RowScope.() -> Unit,
) {
    val (background, foreground) = when (variant) {
        Variant.Default -> Ice.primary to Ice.onPrimary
        Variant.Destructive -> Ice.destructive to Ice.onDestructive
        Variant.Outline -> Ice.background to LocalContentColor.current
        Variant.Secondary -> Ice.secondary to Ice.onSecondary
        Variant.Ghost -> Color.Transparent to LocalContentColor.current
        Variant.Link -> Color.Transparent to Ice.primary
    }
    var m = modifier.alpha(if (enabled) 1f else 0.5f)
    m = if (height != null) m.height(height) else m.heightIn(min = minHeight)
    if (minHeight > 0.dp && height != null) m = m.heightIn(min = minHeight)
    if (variant != Variant.Ghost && variant != Variant.Link) m = m.smallShadow(radius)
    m = m.clip(RoundedCornerShape(radius)).background(background)
    if (variant == Variant.Outline) m = m.border(1.dp, Ice.input, RoundedCornerShape(radius)).padding(horizontal = 1.dp)
    Row(m.clickable(enabled = enabled, onClick = onClick).padding(padding), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides foreground) { ProvideTextStyle(style.copy(color = foreground)) { content() } }
    }
}

@Composable
fun Btn(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, variant: Variant = Variant.Default, enabled: Boolean = true,
    height: Dp? = 36.dp, minHeight: Dp = 0.dp, style: TextStyle = T.sm.medium, icon: ImageVector? = null,
) = Btn(onClick, modifier, variant, enabled, height, minHeight, style) {
    if (icon != null) Icon(icon, null, Modifier.size(16.dp))
    Text(text, maxLines = 1)
}

/** `h-14 w-full text-lg`: the main action at the bottom of a form. */
@Composable
fun BigButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Btn(text, onClick, modifier.fillMaxWidth(), enabled = enabled, height = 56.dp, style = T.lg.medium)

/** A secondary action; `h-11` unless the screen says otherwise. */
@Composable
fun MidButton(
    text: String, modifier: Modifier = Modifier, enabled: Boolean = true, outlined: Boolean = false, danger: Boolean = false,
    height: Dp = 44.dp, icon: ImageVector? = null, onClick: () -> Unit,
) {
    val variant = if (outlined) Variant.Outline else if (danger) Variant.Destructive else Variant.Default
    Btn(onClick, modifier, variant, enabled, height) {
        val color = if (outlined && danger) Ice.destructive else LocalContentColor.current
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = color)
        Text(text, maxLines = 1, color = color)
    }
}

/** A row of mutually exclusive choices (`flex gap-2`, each `h-11 flex-1`). */
@Composable
fun <T> Choice(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, fill: Boolean = true, onSelect: (T) -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) -> MidButton(label, if (fill) Modifier.weight(1f) else Modifier, outlined = value != selected) { onSelect(value) } }
    }
}

// ---- inputs ---------------------------------------------------------------------------

/** The web `Input`: rounded-md, 1px input-coloured border, 12px side padding, small shadow. */
@Composable
fun Input(
    value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "", height: Dp = 36.dp,
    style: TextStyle = T.base, numeric: Boolean = false, ltr: Boolean = false, keyboard: KeyboardType? = null, center: Boolean = false,
    background: Color = Color.Transparent, password: Boolean = false, leading: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Radius.md)
    val text = style.copy(color = Ice.foreground, textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content, textAlign = if (center) TextAlign.Center else TextAlign.Start)
    BasicTextField(
        value = value,
        onValueChange = { onChange(if (numeric) numericInput(it) else it) },
        singleLine = true,
        textStyle = text,
        cursorBrush = SolidColor(Ice.foreground),
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard ?: if (numeric) KeyboardType.Number else KeyboardType.Text),
        modifier = modifier.fillMaxWidth().height(height).smallShadow(Radius.md).background(background, shape).border(1.dp, Ice.input, shape),
        decorationBox = { inner ->
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                leading?.invoke()
                Box(Modifier.weight(1f), contentAlignment = if (center) Alignment.Center else Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = text.copy(color = Ice.mutedText), maxLines = 1)
                    inner()
                }
            }
        },
    )
}

/** A form field as the web forms use it: an `h-12` input (or `h-14 text-xl`) with a placeholder. */
@Composable
fun Field(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    numeric: Boolean = false, ltr: Boolean = false, large: Boolean = false, keyboard: KeyboardType? = null, singleLine: Boolean = true,
) = Input(value, onChange, modifier, label, if (large) 56.dp else 48.dp, if (large) T.xl else T.base, numeric, ltr, keyboard)

/** `Label`: text-sm medium, placed above its control. */
@Composable
fun Labeled(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // On the web the inline label takes a 24px line and its `space-y-1.5` margin has no
    // effect on an inline element, so the control follows directly.
    Column(modifier) {
        Text(label, style = T.sm.medium.copy(lineHeight = 24.sp))
        content()
    }
}

/** The web's native `<select class="h-12 w-full rounded-md border border-input bg-background px-3">`. */
@Composable
fun <T> Select(options: List<Pair<T, String>>, selected: T?, modifier: Modifier = Modifier, height: Dp = 48.dp, placeholder: String = "", onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.md)
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(height).background(Ice.background, shape).border(1.dp, Ice.input, shape).clip(shape).clickable { open = true }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.firstOrNull { it.first == selected }?.second ?: placeholder, Modifier.weight(1f), style = T.base, maxLines = 1)
            Icon(Lucide.ChevronDown, null, Modifier.size(16.dp), tint = Ice.foreground)
        }
        DropdownMenu(open, { open = false }, containerColor = Ice.card) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text, style = T.base) }, onClick = { open = false; onSelect(value) }) }
        }
    }
}

/** A select with its caption above (`mb-1 text-sm text-muted-foreground`). */
@Composable
fun <T> Picker(label: String, options: List<Pair<T, String>>, selected: T?, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    Column(modifier) {
        if (label.isNotEmpty()) Text(label, style = T.sm.muted, modifier = Modifier.padding(bottom = 4.dp))
        Select(options, selected, placeholder = "— اختر —", onSelect = onSelect)
    }
}

/**
 * The web `Switch`: a 36×20 track with a 16px thumb. In the web's right-to-left layout the
 * thumb of a switched-on control slides out past the track's right edge; kept identical here.
 */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.size(36.dp, 20.dp).smallShadow(10.dp).background(if (checked) Ice.primary else Ice.input, CircleShape).tap { onChange(!checked) }.padding(2.dp)) {
        Box(Modifier.offset(x = if (checked) (-16).dp else 0.dp).size(16.dp).cssShadow(8.dp, 15.dp, 10.dp, (-3).dp, Color.Black.copy(alpha = 0.1f)).background(Ice.background, CircleShape))
    }
}

// ---- layout blocks --------------------------------------------------------------------

/**
 * `mb-4 flex items-center justify-between gap-2` with an `h1.text-xl.font-bold`. On the web
 * the title's own 16px bottom margin replaces the parent's `space-y-*`, so pass the parent
 * column's [spacing] when it is not 16dp and the gap below the title stays 16dp.
 */
@Composable
fun PageTitle(title: String, spacing: Dp = 16.dp, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, (placeable.height - (spacing - 16.dp).roundToPx()).coerceAtLeast(0)) { placeable.place(0, 0) }
        },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = T.xl.bold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** `h2.mb-2.font-bold`. */
@Composable
fun SectionTitle(text: String) = Text(text, style = T.base.bold, modifier = Modifier.padding(bottom = 8.dp))

/** `rounded-2xl bg-card p-4 shadow-soft`; pass [border] for the bordered list cards. */
@Composable
fun IceCard(
    modifier: Modifier = Modifier, borderColor: Color? = null, background: Color = Ice.card, radius: Dp = Radius.xl2,
    padding: Dp = 16.dp, shadow: Boolean = true, content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth().box(radius, background, borderColor, shadow = shadow).padding(padding)) { content() }
}

/** `rounded-2xl border bg-card p-3 shadow-soft` with a `text-xs` caption and a `text-lg` bold value. */
@Composable
fun Stat(label: String, value: String, tone: Color = Ice.foreground, modifier: Modifier = Modifier) {
    Column(modifier.box(Radius.xl2, shadow = true).padding(12.dp)) {
        Text(label, style = T.xs.muted)
        Text(value, style = T.lg.bold.color(tone), modifier = Modifier.padding(top = 4.dp))
    }
}

/** `grid grid-cols-2 gap-3`. */
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

/** `rounded-2xl border border-dashed p-6 text-center text-sm text-muted-foreground`. */
@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().dashedBorder(Radius.xl2).padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = T.sm.muted, textAlign = TextAlign.Center)
    }
}

/** A tinted notice box (`rounded-xl p-3`). */
@Composable
fun Banner(background: Color, modifier: Modifier = Modifier, borderColor: Color = Color.Transparent, radius: Dp = Radius.xl, padding: Dp = 12.dp, borderWidth: Dp = 1.dp, content: @Composable () -> Unit) {
    val inset = if (borderColor == Color.Transparent) padding else padding + borderWidth
    Box(modifier.fillMaxWidth().background(background, RoundedCornerShape(radius)).border(borderWidth, borderColor, RoundedCornerShape(radius)).padding(inset)) {
        ProvideTextStyle(T.sm, content)
    }
}

/** One `flex justify-between` row of a bordered list. */
@Composable
fun LineItem(start: String, end: String, endColor: Color = Ice.foreground, bold: Boolean = true, style: TextStyle = T.base, vertical: Dp = 6.dp) {
    Row(Modifier.fillMaxWidth().padding(vertical = vertical), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(start, Modifier.weight(1f), style = style)
        Spacer(Modifier.width(8.dp))
        Text(end, style = if (bold) style.bold.color(endColor) else style.color(endColor))
    }
}

/** `divide-y divide-border rounded-2xl border border-border bg-card`. */
@Composable
fun ListCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().box(Radius.xl2)) { content() }
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Ice.border))

@Composable
fun RowScope.Grow() = Spacer(Modifier.weight(1f))
