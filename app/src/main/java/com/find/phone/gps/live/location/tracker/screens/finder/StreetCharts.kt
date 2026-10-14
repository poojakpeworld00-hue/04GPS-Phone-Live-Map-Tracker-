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

object StreetCharts {
    private var configured = false

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

    @SuppressLint("ClickableViewAccessibility")
    fun style(map: MapView, interactive: Boolean) = map.apply {
        setTileSource(TileSourceFactory.MAPNIK)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        isTilesScaledToDpi = true
        minZoomLevel = 2.5
        maxZoomLevel = 18.0
        setMultiTouchControls(interactive)
        if (!interactive) setOnTouchListener { _, _ -> true }

        val night = resources.configuration.uiMode and UiConfiguration.UI_MODE_NIGHT_MASK
        if (night == UiConfiguration.UI_MODE_NIGHT_YES) overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
    }

    fun place(map: MapView, report: NumDigest, coarse: Boolean): SectorThrobVeil? {
        val pin = report.pin ?: return null
        val precision = if (coarse && report.precision.zoom > SectorExactness.STATE.zoom) SectorExactness.STATE else report.precision
        return siteAt(map, pin, precision)
    }

    fun cardZoom(precision: SectorExactness): Double {
        if (precision.radiusMeters <= 0.0) return precision.zoom
        val fit = SectorExactness.STATE.zoom + log2(SectorExactness.STATE.radiusMeters / precision.radiusMeters)
        return minOf(precision.zoom, fit)
    }

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
