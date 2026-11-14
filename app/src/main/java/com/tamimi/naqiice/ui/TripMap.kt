package com.tamimi.naqiice.ui

import android.graphics.Color as AndroidColor
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tamimi.naqiice.core.TrackPoint
import com.tamimi.naqiice.core.adenTime
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.infowindow.InfoWindow
import java.io.File

data class MapStop(val lat: Double, val lng: Double, val label: String)

// The web map's --chart-1 (route) and --success (stops, last position) colours.
private val ROUTE = androidx.compose.ui.graphics.Color(0xFF1B7BB5).toArgb()
private val STOP = Ice.success.toArgb()

/** Esri serves tiles as z/y/x, not the usual z/x/y. */
private fun esri(name: String, service: String, copyright: String): ITileSource = object : OnlineTileSourceBase(
    name, 0, 19, 256, "", arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/$service/MapServer/tile/"), copyright,
) {
    override fun getTileURLString(index: Long): String =
        "$baseUrl${MapTileIndex.getZoom(index)}/${MapTileIndex.getY(index)}/${MapTileIndex.getX(index)}"
}

private val TILE_SOURCES: List<Pair<String, ITileSource>> = listOf(
    "خريطة الشوارع" to TileSourceFactory.MAPNIK,
    "شوارع بديلة" to esri("EsriStreets", "World_Street_Map", "Tiles © Esri — sources: Esri, HERE, Garmin, USGS"),
    "صور الأقمار الصناعية" to esri("EsriImagery", "World_Imagery", "Imagery © Esri, Maxar, Earthstar Geographics"),
)

private fun dot(radius: Float, color: Int, opacity: Float, density: Float): Drawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(AndroidColor.argb((opacity * 255).toInt(), AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color)))
    setStroke((1.5f * density).toInt().coerceAtLeast(1), color)
    val size = (radius * 2 * density).toInt()
    setSize(size, size)
}

/** Route line, a dot per tracking point (large for the first and last) and a dot per located invoice. */
@Composable
fun TripMap(points: List<TrackPoint>, sales: List<MapStop>, open: Boolean) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    var source by remember { mutableIntStateOf(0) }
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
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            isTilesScaledToDpi = true
            maxZoomLevel = 19.0
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(14.54, 49.12))
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    LaunchedEffect(source) { map.setTileSource(TILE_SOURCES[source].second) }
    LaunchedEffect(points, sales, open) {
        InfoWindow.closeAllInfoWindowsOn(map)
        map.overlays.clear()
        // Lowest overlay: a tap that hits no marker closes the open label.
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean { InfoWindow.closeAllInfoWindowsOn(map); return false }
            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }))
        map.overlays.add(CopyrightOverlay(context))
        val coords = points.map { GeoPoint(it.lat, it.lng) }
        if (coords.size > 1) map.overlays.add(Polyline(map).apply {
            outlinePaint.color = ROUTE
            outlinePaint.strokeWidth = 4 * density
            setPoints(coords)
            infoWindow = null
        })
        val small = dot(3f, ROUTE, 0.85f, density)
        val first = dot(9f, ROUTE, 0.85f, density)
        val last = dot(9f, STOP, 0.85f, density)
        val sale = dot(7f, STOP, 0.9f, density)
        fun marker(at: GeoPoint, icon: Drawable, label: String) = map.overlays.add(Marker(map).apply {
            position = at
            this.icon = icon
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = label
        })
        points.forEachIndexed { i, p ->
            val isFirst = i == 0
            val isLast = i == points.lastIndex
            val label = (if (isFirst) "بداية المسار · " else "") + (if (isLast) if (open) "آخر موقع للشاحنة · " else "نهاية المسار · " else "") + adenTime(p.recordedAt)
            marker(coords[i], if (isLast) last else if (isFirst) first else small, label)
        }
        sales.forEach { marker(GeoPoint(it.lat, it.lng), sale, it.label) }
        val all = coords + sales.map { GeoPoint(it.lat, it.lng) }
        fun fit() {
            if (all.distinct().size == 1) { map.controller.setZoom(16.0); map.controller.setCenter(all[0]) }
            else map.zoomToBoundingBox(BoundingBox.fromGeoPoints(all), false, (30 * density).toInt(), 16.0, null)
        }
        if (all.isNotEmpty()) { if (map.width > 0 && map.height > 0) fit() else map.addOnFirstLayoutListener { _, _, _, _, _ -> fit() } }
        map.invalidate()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(TILE_SOURCES.mapIndexed { i, s -> i to s.first }, source) { source = it }
        val shape = RoundedCornerShape(12.dp)
        AndroidView(factory = { map }, modifier = Modifier.fillMaxWidth().height(420.dp).clip(shape).border(1.dp, Ice.border, shape))
    }
}
