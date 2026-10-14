package com.find.phone.gps.live.location.tracker.screens.finder

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration as UiConfiguration
import androidx.core.content.ContextCompat
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.store.inquiry.SectorExactness
import com.find.phone.gps.live.location.tracker.store.inquiry.TerraTack
import com.find.phone.gps.live.location.tracker.store.inquiry.NumDigest
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import kotlin.math.log2

/** The OpenStreetMap setup shared by the result sheet's map card and the Number Details header. */
object StreetCharts {

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
    fun place(map: MapView, report: NumDigest, coarse: Boolean): SectorThrobVeil? {
        val pin = report.pin ?: return null
        val precision = if (coarse && report.precision.zoom > SectorExactness.STATE.zoom) SectorExactness.STATE else report.precision
        return siteAt(map, pin, precision)
    }

    /**
     * The zoom at which [precision]'s circle looks as big as the state circle does at state zoom.
     * For small map cards: at a city's own zoom its circle fills the whole card, which reads as a
     * plain blue page, while this keeps it a ring around the right place.
     */
    fun cardZoom(precision: SectorExactness): Double {
        if (precision.radiusMeters <= 0.0) return precision.zoom
        val fit = SectorExactness.STATE.zoom + log2(SectorExactness.STATE.radiusMeters / precision.radiusMeters)
        return minOf(precision.zoom, fit)
    }

    /** Centres [map] on [pin] at [zoom] (default: [precision]'s own), with its pulsing circle. */
    fun siteAt(map: MapView, pin: TerraTack, precision: SectorExactness, zoom: Double = precision.zoom): SectorThrobVeil {
        val point = GeoPoint(pin.lat, pin.lng)
        map.controller.setZoom(zoom)
        map.controller.setCenter(point)
        val context = map.context
        return SectorThrobVeil(
            map, point, precision.radiusMeters,
            ContextCompat.getColor(context, R.color.ds_primary), context.resources.displayMetrics.density,
        ).also {
            map.overlays.add(it)
            it.start()
        }
    }
}
