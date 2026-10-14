package com.find.my.phone.gps.location.tracker.ui.lookup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.base.CanvasActivity
import com.find.my.phone.gps.location.tracker.data.lookup.NumberReport
import com.find.my.phone.gps.location.tracker.databinding.ViewMapFullBinding
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import kotlin.math.roundToInt

/**
 * Full view of a number's registered area, opened from Number Details (the header's Full view,
 * a tap on the header map, or the Map action). The same OpenStreetMap and pulsing area as the
 * header, but interactive, with zoom, recenter and Directions (hands the place to a maps app).
 */
class MapFullActivity : CanvasActivity<ViewMapFullBinding>() {

    override val layoutId: Int = R.layout.view_map_full

    private lateinit var report: NumberReport
    private var overlay: AreaPulseOverlay? = null
    private var topInset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // osmdroid must be configured before the MapView is inflated (in super.onCreate).
        OsmMaps.init(this)
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        val given = IntentCompat.getSerializableExtra(intent, EXTRA_REPORT, NumberReport::class.java)
        if (given == null) {
            finish()
            return
        }
        report = given

        // The map runs under the status bar; the top bar sits below it.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        val topPad = binding.mapTopBar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.mapRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.mapTopBar.updatePadding(top = topPad + bars.top)
            if (topInset != bars.top) {
                topInset = bars.top
                if (::report.isInitialized) centerPin()
            }
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }

        val r = report
        val place = listOfNotNull(r.city, r.state).distinct().joinToString(", ")
        binding.tvTitle.text = place.ifEmpty { r.countryName }
        binding.tvIso.text = r.iso2
        binding.tvNumber.text = r.international
        binding.tvPlace.text = r.placeLabel.ifEmpty { r.countryName }

        binding.btnBack.setOnClickListener { goBack() }
        binding.btnZoomIn.setOnClickListener { binding.mapView.controller.zoomIn() }
        binding.btnZoomOut.setOnClickListener { binding.mapView.controller.zoomOut() }
        binding.btnRecenter.setOnClickListener { recenter() }
        binding.btnDirections.setOnClickListener { openInMaps() }
        listOf(binding.btnZoomIn, binding.btnZoomOut, binding.btnRecenter, binding.btnDirections)
            .forEach { DesignMotion.pressScale(it) }

        OsmMaps.style(binding.mapView, interactive = true)
        overlay = OsmMaps.place(binding.mapView, r, coarse = false)
        // No point for the area (country-only result): the whole country's name is all we have.
        binding.btnRecenter.isEnabled = r.pin != null
        binding.btnRecenter.alpha = if (r.pin != null) 1f else 0.4f
        centerPin()
    }

    /** Back to the area at its own zoom, after the user panned or zoomed away. */
    private fun recenter() {
        val pin = report.pin ?: return
        binding.mapView.controller.animateTo(
            org.osmdroid.util.GeoPoint(pin.lat, pin.lng), report.precision.zoom, RECENTER_MS,
        )
    }

    /**
     * The pin goes in the middle of the map left visible between the top bar and the place card
     * over its bottom — half the difference below centre.
     */
    private fun centerPin() {
        binding.placeCard.post {
            val d = resources.displayMetrics.density
            val above = topInset + (52 * d).roundToInt()
            val below = binding.placeCard.height + (16 * d).roundToInt()
            binding.mapView.setMapCenterOffset(0, (above - below) / 2)
            binding.mapView.invalidate()
        }
    }

    private fun openInMaps() {
        val pin = report.pin
        val label = Uri.encode(report.placeLabel.ifEmpty { report.countryName })
        val uri = if (pin != null) Uri.parse("geo:${pin.lat},${pin.lng}?q=${pin.lat},${pin.lng}($label)")
        else Uri.parse("geo:0,0?q=$label")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.ds_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::report.isInitialized) binding.mapView.onResume()
    }

    override fun onPause() {
        if (::report.isInitialized) binding.mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        overlay?.stop()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_REPORT = "report"
        private const val RECENTER_MS = 600L

        fun intent(context: Context, report: NumberReport): Intent =
            Intent(context, MapFullActivity::class.java).putExtra(EXTRA_REPORT, report)
    }
}
