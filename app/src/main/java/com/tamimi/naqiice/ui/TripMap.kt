package com.tamimi.naqiice.ui

import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tamimi.naqiice.core.TrackPoint
import com.tamimi.naqiice.core.adenTime
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.infowindow.InfoWindow
import java.io.File
import kotlin.math.floor

/** How a stop is drawn: an invoice on a route, or a customer not yet / already invoiced. */
enum class StopStyle { Sale, Customer, Visited }

data class MapStop(val lat: Double, val lng: Double, val label: String, val style: StopStyle = StopStyle.Sale, val customerId: String? = null)

// The web map's --chart-1 (route) and --success (stops, last position) colours.
private val ROUTE = Color(0xFF007BB2).toArgb()
private val STOP = Ice.success.toArgb()
/** A customer not yet invoiced in this campaign: the muted text colour. */
private val CUSTOMER = Ice.mutedText.toArgb()

/** Leaflet's own colours: container background, control frame and control text. */
private val MAP_BACKGROUND = Color(0xFFDDDDDD)
private val CONTROL_FRAME = Color.Black.copy(alpha = 0.2f)
private val CONTROL_TEXT = Color(0xFF333333)

/** Esri serves tiles as z/y/x, not the usual z/x/y. */
private fun esri(name: String, service: String, copyright: String): ITileSource = object : OnlineTileSourceBase(
    name, 0, 19, 256, "", arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/$service/MapServer/tile/"), copyright,
) {
    override fun getTileURLString(index: Long): String =
        "$baseUrl${MapTileIndex.getZoom(index)}/${MapTileIndex.getY(index)}/${MapTileIndex.getX(index)}"
}

private class TileLayer(val name: String, val source: ITileSource, val credit: String)

private val TILE_SOURCES = listOf(
    TileLayer("خريطة الشوارع", TileSourceFactory.MAPNIK, "© OpenStreetMap contributors"),
    TileLayer("شوارع بديلة", esri("EsriStreets", "World_Street_Map", "Tiles © Esri — sources: Esri, HERE, Garmin, USGS"), "Tiles © Esri — sources: Esri, HERE, Garmin, USGS"),
    TileLayer("صور الأقمار الصناعية", esri("EsriImagery", "World_Imagery", "Imagery © Esri, Maxar, Earthstar Geographics"), "Imagery © Esri, Maxar, Earthstar Geographics"),
)

/** A Leaflet `circleMarker`: a 3px outline centred on [radius] around a translucent fill. */
private fun dot(radius: Float, color: Int, opacity: Float, density: Float): Drawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(AndroidColor.argb((opacity * 255).toInt(), AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color)))
    setStroke((3 * density).toInt().coerceAtLeast(1), color)
    val size = ((radius * 2 + 3) * density).toInt()
    setSize(size, size)
}

/**
 * The web `TripMap`: route line, a dot per tracking point (large for the first and last) and
 * a dot per located invoice inside an `h-[55vh] min-h-80 rounded-lg border` frame carrying
 * Leaflet's controls — zoom at the top left, the layer list at the top right, the tile credit
 * at the bottom right — and the notice shown while map tiles cannot be loaded.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripMap(
    points: List<TrackPoint>,
    sales: List<MapStop>,
    open: Boolean,
    description: String = "خريطة مسار الحملة",
    /** When set, tapping a stop calls this instead of showing its label. */
    onStop: ((MapStop) -> Unit)? = null,
) {
    val tapStop by rememberUpdatedState(onStop)
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    var source by remember { mutableIntStateOf(0) }
    var tileError by remember { mutableStateOf(false) }
    var layersOpen by remember { mutableStateOf(false) }
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            // App-private cache: no storage permission, and nothing left behind on uninstall.
            osmdroidBasePath = File(context.cacheDir, "osmdroid").apply { mkdirs() }
            osmdroidTileCache = File(osmdroidBasePath, "tiles").apply { mkdirs() }
        }
        object : MapView(context) {
            // The page scrolls vertically; a gesture that starts on the map belongs to the map.
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            // Like the web map (dir="ltr"): map panning must not be mirrored by the Arabic layout.
            layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR
            setMultiTouchControls(true)
            // The zoom buttons are drawn over the map in the web's place and style.
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            // Tiles that have not arrived show Leaflet's plain grey, not a grid.
            overlayManager.tilesOverlay.loadingBackgroundColor = MAP_BACKGROUND.toArgb()
            overlayManager.tilesOverlay.loadingLineColor = MAP_BACKGROUND.toArgb()
            isTilesScaledToDpi = true
            maxZoomLevel = 19.0
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(14.54, 49.12))
        }
    }
    DisposableEffect(map) {
        // As on the web: a loaded tile clears the notice, a failed one raises it, and after
        // three failed street tiles the alternate street layer takes over.
        var failures = 0
        val listener = Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                MapTileProviderBase.MAPTILE_SUCCESS_ID -> tileError = false
                MapTileProviderBase.MAPTILE_FAIL_ID -> {
                    tileError = true
                    if (source == 0 && ++failures >= 3) source = 1
                }
            }
            true
        }
        map.tileProvider.tileRequestCompleteHandlers.add(listener)
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    LaunchedEffect(source) { map.setTileSource(TILE_SOURCES[source].source) }
    LaunchedEffect(points, sales, open) {
        InfoWindow.closeAllInfoWindowsOn(map)
        map.overlays.clear()
        // Lowest overlay: a tap that hits no marker closes the open label and the layer list.
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean { InfoWindow.closeAllInfoWindowsOn(map); layersOpen = false; return false }
            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }))
        val coords = points.map { GeoPoint(it.lat, it.lng) }
        if (coords.size > 1) map.overlays.add(Polyline(map).apply {
            outlinePaint.color = ROUTE
            outlinePaint.strokeWidth = 4 * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            setPoints(coords)
            infoWindow = null
        })
        val small = dot(3f, ROUTE, 0.85f, density)
        val first = dot(9f, ROUTE, 0.85f, density)
        val last = dot(9f, STOP, 0.85f, density)
        val sale = dot(7f, STOP, 0.9f, density)
        val customer = dot(8f, CUSTOMER, 0.75f, density)
        val visited = dot(8f, STOP, 0.9f, density)
        fun marker(at: GeoPoint, icon: Drawable, label: String, stop: MapStop? = null) = map.overlays.add(Marker(map).apply {
            position = at
            this.icon = icon
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = label
            if (stop != null) setOnMarkerClickListener { m, _ ->
                val tap = tapStop
                if (tap != null) tap(stop) else m.showInfoWindow()
                true
            }
        })
        points.forEachIndexed { i, p ->
            val isFirst = i == 0
            val isLast = i == points.lastIndex
            val label = (if (isFirst) "بداية المسار · " else "") + (if (isLast) if (open) "آخر موقع للشاحنة · " else "نهاية المسار · " else "") + adenTime(p.recordedAt)
            marker(coords[i], if (isLast) last else if (isFirst) first else small, label)
        }
        sales.forEach { marker(GeoPoint(it.lat, it.lng), when (it.style) { StopStyle.Sale -> sale; StopStyle.Customer -> customer; StopStyle.Visited -> visited }, it.label, it) }
        val all = coords + sales.map { GeoPoint(it.lat, it.lng) }
        // Leaflet's fitBounds({ padding: [30, 30], maxZoom: 16 }): the deepest whole zoom level
        // that shows every point 30px clear of the frame, centred on them.
        fun fit() {
            val box = BoundingBox.fromGeoPoints(all)
            val inset = (60 * density).toInt()
            val zoom = if (all.distinct().size == 1) 16.0 else MapView.getTileSystem().getBoundingBoxZoom(box, map.width - inset, map.height - inset)
            map.controller.setZoom(floor(zoom.coerceIn(map.minZoomLevel, 16.0)))
            map.controller.setCenter(GeoPoint(box.centerLatitude, box.centerLongitude))
        }
        if (all.isNotEmpty()) { if (map.width > 0 && map.height > 0) fit() else map.addOnFirstLayoutListener { _, _, _, _, _ -> fit() } }
        map.invalidate()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // h-[55vh] min-h-80 w-full overflow-hidden rounded-lg border border-border, dir="ltr"
        val height = maxOf(LocalConfiguration.current.screenHeightDp * 0.55f, 320f).dp
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(
                Modifier.fillMaxWidth().height(height).semantics { contentDescription = description }
                    .box(Radius.lg, MAP_BACKGROUND).clip(RoundedCornerShape(Radius.lg - 1.dp)),
            ) {
                AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
                ZoomControl(Modifier.align(Alignment.TopStart).padding(10.dp), { map.controller.zoomIn() }, { map.controller.zoomOut() })
                LayersControl(Modifier.align(Alignment.TopEnd).padding(10.dp), layersOpen, source, { layersOpen = true }) { source = it }
                // .leaflet-control-attribution: the credit of the layer on show
                Box(Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 5.dp)) {
                    Text(TILE_SOURCES[source].credit, Modifier.offset(y = (-1.5).dp), style = LEAFLET.copy(fontSize = 12.sp, lineHeight = 16.8.sp), maxLines = 1)
                }
            }
        }
        // flex flex-wrap items-center gap-2 text-sm text-destructive
        if (tileError) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("تعذّر تحميل تفاصيل الخريطة؛ تحقق من الإنترنت أو اختر الشوارع البديلة.", Modifier.align(Alignment.CenterVertically), style = T.sm.color(Ice.destructive))
            CompositionLocalProvider(LocalContentColor provides Ice.destructive) {
                OutlineBtn("إعادة المحاولة", Modifier.align(Alignment.CenterVertically), small = true) { map.tileProvider.clearTileCache(); map.invalidate() }
            }
        }
    }
}

/** Leaflet sets its controls in the browser's plain sans-serif, not in the page's Cairo. */
private val LEAFLET = TextStyle(fontFamily = FontFamily.SansSerif, color = CONTROL_TEXT)

/** `.leaflet-touch .leaflet-bar`: two 30px buttons in a 2px translucent frame. */
@Composable
private fun ZoomControl(modifier: Modifier, zoomIn: () -> Unit, zoomOut: () -> Unit) {
    @Composable
    fun button(label: String, centre: Float, plus: Boolean, onClick: () -> Unit) {
        Canvas(Modifier.size(30.dp).background(Color.White).clickable(onClickLabel = label, role = Role.Button, onClick = onClick).semantics { contentDescription = label }) {
            // The bold 22px "+" / "−" of the control: 12px strokes, 2.2px thick.
            val half = 6.dp.toPx()
            val x = 15.5.dp.toPx()
            val y = centre.dp.toPx()
            drawLine(Color.Black, Offset(x - half, y), Offset(x + half, y), 2.2.dp.toPx())
            if (plus) drawLine(Color.Black, Offset(x, y - half), Offset(x, y + half), 2.2.dp.toPx())
        }
    }
    Column(modifier.background(CONTROL_FRAME, RoundedCornerShape(4.dp)).padding(2.dp).clip(RoundedCornerShape(2.dp))) {
        button("تكبير", 14.25f, true, zoomIn)
        Box(Modifier.size(30.dp, 1.dp).background(Color(0xFFCCCCCC)))
        button("تصغير", 11.75f, false, zoomOut)
    }
}

/** `.leaflet-control-layers`: a 44px toggle that opens the list of base layers. */
@Composable
private fun LayersControl(modifier: Modifier, expanded: Boolean, selected: Int, expand: () -> Unit, select: (Int) -> Unit) {
    val frame = modifier.background(CONTROL_FRAME, RoundedCornerShape(5.dp)).padding(2.dp).background(Color.White, RoundedCornerShape(3.dp))
    if (!expanded) {
        Box(frame.size(44.dp).clip(RoundedCornerShape(3.dp)).clickable(onClickLabel = "طبقات الخريطة", role = Role.Button, onClick = expand).semantics { contentDescription = "طبقات الخريطة" }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(26.dp)) { layersIcon() }
        }
        return
    }
    // .leaflet-control-layers-expanded: padding 6px 10px 6px 6px; a radio button and a 13px name per layer
    Column(frame.width(IntrinsicSize.Max).padding(start = 6.dp, top = 6.dp, end = 10.dp, bottom = 6.dp)) {
        TILE_SOURCES.forEachIndexed { index, layer ->
            Row(Modifier.fillMaxWidth().height(21.5.dp).clickable(role = Role.RadioButton) { select(index) }, verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.padding(end = 4.dp).size(13.dp)) {
                    val on = index == selected
                    drawCircle(if (on) Color(0xFF0075FF) else Color(0xFF767676), size.minDimension / 2 - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
                    if (on) drawCircle(Color(0xFF0075FF), 3.25.dp.toPx())
                }
                Text(layer.name, style = LEAFLET.copy(fontSize = 13.sp, lineHeight = 19.5.sp), maxLines = 1)
            }
        }
    }
}

/** Leaflet's `layers.png`: three stacked sheets on a 26px square. */
private fun DrawScope.layersIcon() {
    val unit = size.width / 26f
    fun shape(color: Color, vararg points: Float): Path = Path().apply {
        moveTo(points[0] * unit, points[1] * unit)
        for (i in 2 until points.size step 2) lineTo(points[i] * unit, points[i + 1] * unit)
        close()
    }.also { drawPath(it, color) }
    val edge = Color(0xFF737373)
    shape(Color(0xFFB9B9B9), 0f, 17.056f, 13f, 9.056f, 26f, 17.056f, 13f, 25.056f)
    shape(edge, 0f, 17.056f, 0f, 17.986f, 13f, 25.986f, 26f, 17.986f, 26f, 17.056f, 13f, 25.056f)
    shape(Color(0xFFCDCDCD), 0f, 13.076f, 13f, 5.076f, 26f, 13.076f, 13f, 21.076f)
    shape(edge, 0f, 13.076f, 0f, 13.972f, 13f, 21.972f, 26f, 13.972f, 26f, 13.076f, 13f, 21.076f)
    val top = shape(Color(0xFFF1F1F1), 0f, 8.986f, 13f, 0.986f, 26f, 8.986f, 13f, 16.986f)
    drawPath(top, Color(0xFF797979), style = Stroke(0.1f * unit))
    shape(edge, 0f, 8.986f, 0f, 9.986f, 13f, 17.986f, 26f, 9.986f, 26f, 8.986f, 13f, 16.986f)
}
