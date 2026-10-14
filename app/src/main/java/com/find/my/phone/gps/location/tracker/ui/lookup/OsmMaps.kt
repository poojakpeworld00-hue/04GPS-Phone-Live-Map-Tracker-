package com.find.my.phone.gps.location.tracker.ui.lookup

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration as UiConfiguration
import androidx.core.content.ContextCompat
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.data.lookup.AreaPrecision
import com.find.my.phone.gps.location.tracker.data.lookup.GeoPin
import com.find.my.phone.gps.location.tracker.data.lookup.NumberReport
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import kotlin.math.log2

/** The OpenStreetMap setup shared by the result sheet's map card and the Number Details header. */
object OsmMaps {

    private var configured = false

    /** osmdroid needs its config before any MapView is inflated. */
    fun init(context: Context) {
        if (configured) return
        val app = context.applicationContext
        Configuration.getInstance().apply {
            load(app, app.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = app.packageName
            osmdroidBasePath = File(app.cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }
        configured = true
    }

    /** MAPNIK tiles, no zoom buttons; [interactive] false makes it a picture (no gestures). */
    @SuppressLint("ClickableViewAccessibility")
    fun style(map: MapView, interactive: Boolean) = map.apply {
        setTileSource(TileSourceFactory.MAPNIK)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        isTilesScaledToDpi = true
        minZoomLevel = 2.5
        maxZoomLevel = 18.0
        setMultiTouchControls(interactive)
        if (!interactive) setOnTouchListener { _, _ -> true }
        // Dark theme: invert the light OSM tiles so the map doesn't glare.
        val night = resources.configuration.uiMode and UiConfiguration.UI_MODE_NIGHT_MASK
        if (night == UiConfiguration.UI_MODE_NIGHT_YES) overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
    }

    /**
     * Centres [map] on the report's area with the pulsing circle. [coarse] keeps it at state
     * level (the locked preview must not give the city away). Null when the area has no point.
     */
    fun place(map: MapView, report: NumberReport, coarse: Boolean): AreaPulseOverlay? {
        val pin = report.pin ?: return null
        val precision = if (coarse && report.precision.zoom > AreaPrecision.STATE.zoom) AreaPrecision.STATE else report.precision
        return placeAt(map, pin, precision)
    }

    /**
     * The zoom at which [precision]'s circle looks as big as the state circle does at state zoom.
     * For small map cards: at a city's own zoom its circle fills the whole card, which reads as a
     * plain blue page, while this keeps it a ring around the right place.
     */
    fun cardZoom(precision: AreaPrecision): Double {
        if (precision.radiusMeters <= 0.0) return precision.zoom
        val fit = AreaPrecision.STATE.zoom + log2(AreaPrecision.STATE.radiusMeters / precision.radiusMeters)
        return minOf(precision.zoom, fit)
    }

    /** Centres [map] on [pin] at [zoom] (default: [precision]'s own), with its pulsing circle. */
    fun placeAt(map: MapView, pin: GeoPin, precision: AreaPrecision, zoom: Double = precision.zoom): AreaPulseOverlay {
        val point = GeoPoint(pin.lat, pin.lng)
        map.controller.setZoom(zoom)
        map.controller.setCenter(point)
        val context = map.context
        return AreaPulseOverlay(
            map, point, precision.radiusMeters,
            ContextCompat.getColor(context, R.color.ds_primary), context.resources.displayMetrics.density,
        ).also {
            map.overlays.add(it)
            it.start()
        }
    }
}
