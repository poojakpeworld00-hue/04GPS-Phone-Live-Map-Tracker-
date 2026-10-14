package com.find.my.phone.gps.location.tracker.ui.sim

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.adcast.domain.logKeyEvent
import com.find.my.phone.gps.location.tracker.adcast.presentation.AppOpenAdRegistry
import com.find.my.phone.gps.location.tracker.base.CanvasActivity
import com.find.my.phone.gps.location.tracker.databinding.ViewShareNumberBinding
import com.find.my.phone.gps.location.tracker.util.isNightMode
import java.io.File
import java.io.FileOutputStream

/**
 * SIM Tools "Share + QR code" (design 1a). Shows a QR encoding `tel:<number>` for one SIM, a
 * Send-as selector (plain text / vCard contact card / the QR image), and Copy / Save QR / Share.
 * Generation only — no scanner, no extra permission (saving uses MediaStore on API 29+).
 */
class ShareNumberActivity : CanvasActivity<ViewShareNumberBinding>() {

    override val layoutId: Int = R.layout.view_share_number

    private enum class SendAs { TEXT, CONTACT, QR }

    private lateinit var e164: String
    private lateinit var display: String
    private var name: String? = null
    private var selected = SendAs.TEXT
    private var qr: Bitmap? = null

    override fun initView() {
        e164 = intent.getStringExtra(EXTRA_E164).orEmpty()
        display = intent.getStringExtra(EXTRA_NUMBER)?.ifBlank { e164 } ?: e164
        name = intent.getStringExtra(EXTRA_NAME)?.ifBlank { null }
        val slot = intent.getIntExtra(EXTRA_SLOT, 1)
        val carrier = intent.getStringExtra(EXTRA_CARRIER).orEmpty()

        // Dark icons on the light canvas, light icons in dark mode.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isNightMode(this)
        ViewCompat.setOnApplyWindowInsetsListener(binding.shareRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.tvNumber.text = display
        binding.tvSubtitle.text = getString(R.string.share_number_scan_fmt, slot, carrier)

        qr = runCatching { QrGen.encode("tel:$e164", QR_PX) }.getOrNull()
        binding.imgQr.setImageBitmap(qr)

        binding.btnBack.setOnClickListener { goBack() }
        binding.segText.setOnClickListener { pick(SendAs.TEXT) }
        binding.segContact.setOnClickListener { pick(SendAs.CONTACT) }
        binding.segQr.setOnClickListener { pick(SendAs.QR) }
        binding.btnCopy.setOnClickListener { copy() }
        binding.btnSaveQr.setOnClickListener { saveQr() }
        binding.btnShare.setOnClickListener { share() }
        pick(SendAs.TEXT)
    }

    private fun pick(which: SendAs) {
        selected = which
        style(binding.segText, which == SendAs.TEXT)
        style(binding.segContact, which == SendAs.CONTACT)
        style(binding.segQr, which == SendAs.QR)
    }

    private fun style(view: TextView, on: Boolean) {
        val color = ContextCompat.getColor(this, if (on) R.color.ds_primary else R.color.ds_muted)
        view.setTextColor(color)
        view.typeface = ResourcesCompat.getFont(this, if (on) R.font.figtree_bold else R.font.figtree_semibold)
        view.background = if (on) ContextCompat.getDrawable(this, R.drawable.ds_seg_thumb_r10) else null
        view.elevation = if (on) 2f * resources.displayMetrics.density else 0f
    }

    private fun copy() {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.my_number_title), display))
        Toast.makeText(this, R.string.my_number_copied, Toast.LENGTH_SHORT).show()
        logKeyEvent("ShareNumber_Copy")
    }

    private fun share() {
        logKeyEvent("ShareNumber_Share_${selected.name.lowercase()}")
        AppOpenAdRegistry.skipNextAppOpenAd = true
        val intent = when (selected) {
            SendAs.TEXT -> Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, name?.let { "$it\n$display" } ?: display)

            SendAs.CONTACT -> fileShareIntent(writeVcard(), "text/x-vcard")
            SendAs.QR -> fileShareIntent(writeQrPng(), "image/png")
        } ?: return
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.share_number_share))) }
    }

    /** ACTION_SEND for a file under cacheDir/shared via the app FileProvider. */
    private fun fileShareIntent(file: File?, mime: String): Intent? {
        if (file == null) return null
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        return Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun sharedDir(): File = File(cacheDir, "shared").apply { mkdirs() }

    private fun writeVcard(): File? = runCatching {
        val vcard = buildString {
            append("BEGIN:VCARD\r\n")
            append("VERSION:3.0\r\n")
            append("FN:").append(name ?: getString(R.string.share_number_vcard_name)).append("\r\n")
            append("TEL;TYPE=CELL:").append(e164).append("\r\n")
            append("END:VCARD\r\n")
        }
        File(sharedDir(), "my_number.vcf").apply { writeText(vcard) }
    }.getOrNull()

    private fun writeQrPng(): File? = runCatching {
        val bmp = qr ?: return null
        File(sharedDir(), "my_number_qr.png").apply {
            FileOutputStream(this).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }.getOrNull()

    private fun saveQr() {
        val bmp = qr
        if (bmp == null) {
            Toast.makeText(this, R.string.share_number_save_failed, Toast.LENGTH_SHORT).show()
            return
        }
        logKeyEvent("ShareNumber_SaveQr")
        val ok = runCatching { saveBitmapToGallery(bmp) }.getOrDefault(false)
        Toast.makeText(this, if (ok) R.string.share_number_saved else R.string.share_number_save_failed, Toast.LENGTH_SHORT).show()
    }

    /** MediaStore on API 29+ (no permission); app external Pictures dir on older. */
    private fun saveBitmapToGallery(bmp: Bitmap): Boolean {
        val fileName = "GPS_MyNumber_QR_${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return false
            return true
        }
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return false
        File(dir, fileName).apply {
            FileOutputStream(this).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return true
    }

    companion object {
        private const val QR_PX = 640
        private const val EXTRA_E164 = "e164"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_SLOT = "slot"
        private const val EXTRA_CARRIER = "carrier"

        fun intent(context: Context, e164: String, number: String, name: String?, slot: Int, carrier: String): Intent =
            Intent(context, ShareNumberActivity::class.java)
                .putExtra(EXTRA_E164, e164)
                .putExtra(EXTRA_NUMBER, number)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_SLOT, slot)
                .putExtra(EXTRA_CARRIER, carrier)
    }
}
