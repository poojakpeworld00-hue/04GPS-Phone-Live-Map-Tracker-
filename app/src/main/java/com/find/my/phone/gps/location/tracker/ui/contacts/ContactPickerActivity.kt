package com.find.my.phone.gps.location.tracker.ui.contacts

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault
import com.find.my.phone.gps.location.tracker.adcast.domain.ScreenPromoConfig
import com.find.my.phone.gps.location.tracker.adcast.presentation.NativePromoBanner
import com.find.my.phone.gps.location.tracker.base.CanvasActivity
import com.find.my.phone.gps.location.tracker.data.VaultRegistry
import com.find.my.phone.gps.location.tracker.databinding.ViewContactPickerBinding
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.find.my.phone.gps.location.tracker.ui.design.DsPopup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The contact list (design 8a / 8b): account filter with count, tools and add, search, a small
 * native ad, the Favorites row, A–Z cards with Locate and Call, and a letter rail. Every number
 * in the phone book, searchable by name or digits.
 *
 * Opened from Home as a picker: Locate hands the number back as [EXTRA_NUMBER]. Without the
 * Contacts permission the 8b state asks for it in place.
 */
class ContactPickerActivity : CanvasActivity<ViewContactPickerBinding>() {

    override val layoutId: Int = R.layout.view_contact_picker

    private val adapter = ContactAdapter(onLocate = { pick(it) }, onCall = { dial(it) })
    private val favorites = FavoriteAdapter { pick(it) }
    private val prefs by lazy { VaultRegistry(this) }

    private var all: List<ContactNumber> = emptyList()
    private var account: String? = null // null = all accounts
    private var descending = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onGranted() else showPermissionState()
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.contactsRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.rvContacts.layoutManager = LinearLayoutManager(this)
        binding.rvContacts.adapter = adapter
        binding.rvFavorites.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvFavorites.adapter = favorites

        binding.searchInput.doAfterTextChanged {
            binding.btnClearSearch.isVisible = !it.isNullOrEmpty()
            applyFilter()
        }
        binding.btnClearSearch.setOnClickListener { binding.searchInput.setText("") }
        binding.accountChip.setOnClickListener { showAccountMenu() }
        binding.btnTools.setOnClickListener { showToolsMenu() }
        binding.btnAllow.setOnClickListener { askPermission() }
        listOf(binding.accountChip, binding.btnTools, binding.btnAllow).forEach { DesignMotion.pressScale(it) }
        setupRail()

        if (hasPermission()) onGranted() else showPermissionState()
    }

    // ===== permission (8b) =====

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** First ask through the system dialog; once it stops showing (denied twice), app settings. */
    private fun askPermission() {
        val perm = Manifest.permission.READ_CONTACTS
        when {
            !prefs.hasRequestedPermission(perm) -> {
                prefs.markPermissionRequested(perm)
                permission.launch(perm)
            }
            shouldShowRequestPermissionRationale(perm) -> permission.launch(perm)
            else -> runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from app settings with the grant in hand.
        if (binding.permissionState.isVisible && hasPermission()) onGranted()
    }

    private fun showPermissionState() {
        binding.permissionState.isVisible = true
        binding.listGroup.isVisible = false
        binding.railBox.isVisible = false
    }

    private fun onGranted() {
        binding.permissionState.isVisible = false
        binding.listGroup.isVisible = true
        renderAd()
        load()
    }

    // ===== data =====

    private fun load() {
        binding.pbLoading.isVisible = true
        binding.tvAccountCount.isVisible = false // no count until the phone book is read
        lifecycleScope.launch {
            all = withContext(Dispatchers.IO) { runCatching { DeviceContacts.load(this@ContactPickerActivity) }.getOrDefault(emptyList()) }
            binding.pbLoading.isVisible = false
            if (account != null && all.none { it.account == account }) account = null
            bindAccountChip()
            applyFilter()
        }
    }

    private fun bindAccountChip() {
        val scope = all.filter { account == null || it.account == account }
        binding.tvAccount.text = account ?: getString(R.string.ds_contacts_all_accounts)
        binding.tvAccountCount.text = scope.distinctBy { it.contactId }.size.toString()
        binding.tvAccountCount.isVisible = true
    }

    /** Account scope, then name contains the text or — when it has digits — the number does. */
    private fun applyFilter() {
        if (binding.pbLoading.isVisible) return
        val query = binding.searchInput.text?.toString()?.trim().orEmpty()
        val needle = query.lowercase(Locale.getDefault())
        val digits = query.filter(Char::isDigit)
        val scoped = all.filter { account == null || it.account == account }
        var shown = if (query.isEmpty()) scoped else scoped.filter {
            it.name.lowercase(Locale.getDefault()).contains(needle) ||
                (digits.isNotEmpty() && it.number.filter(Char::isDigit).contains(digits))
        }
        if (descending) shown = shown.sortedWith(compareBy<ContactNumber> { it.letter == "#" }.thenByDescending { it.name.lowercase(Locale.getDefault()) })
        adapter.submitContacts(shown) {
            binding.rvContacts.scrollToPosition(0)
            buildRail()
        }

        // Favorites: the starred contacts of the scope, hidden while searching (design 8a).
        val favs = scoped.filter { it.starred }.distinctBy { it.contactId }
        favorites.submitList(favs)
        binding.favoritesGroup.isVisible = query.isEmpty() && favs.isNotEmpty()
        binding.tvFavoritesCount.text = favs.size.toString()

        when {
            all.isEmpty() -> showEmpty(R.string.ds_contacts_empty, R.string.ds_contacts_empty_body)
            shown.isEmpty() -> showEmpty(R.string.ds_contacts_no_match, R.string.ds_contacts_no_match_body)
            else -> binding.emptyState.isVisible = false
        }
        binding.railBox.isVisible = shown.isNotEmpty()
    }

    private fun showEmpty(title: Int, body: Int) {
        binding.tvEmpty.setText(title)
        binding.tvEmptyBody.setText(body)
        binding.emptyState.isVisible = true
    }

    // ===== menus =====

    /** "All accounts" plus every account the phone book has, each with its contact count. */
    private fun showAccountMenu() {
        val people = all.distinctBy { it.contactId }
        val counts = people.groupingBy { it.account }.eachCount()
        val popup = DsPopup(this)
        popup.add(
            label = getString(R.string.ds_contacts_all_accounts),
            icon = R.drawable.ds_ic_contact_card,
            badge = people.size.toString(),
            selected = account == null,
        ) { selectAccount(null) }
        counts.keys.sorted().forEach { name ->
            popup.add(
                label = name,
                icon = when (name) {
                    ContactNumber.ACCOUNT_PHONE -> R.drawable.ds_ic_phone
                    ContactNumber.ACCOUNT_SIM -> R.drawable.ds_ic_contact_card
                    else -> R.drawable.ds_ic_globe
                },
                badge = counts.getValue(name).toString(),
                selected = account == name,
            ) { selectAccount(name) }
        }
        popup.show(binding.accountChip, Gravity.START)
    }

    private fun selectAccount(name: String?) {
        account = name
        bindAccountChip()
        applyFilter()
    }

    /** The tools button: sort direction and a refresh. */
    private fun showToolsMenu() {
        DsPopup(this)
            .add(
                label = getString(if (descending) R.string.ds_contacts_sort_az else R.string.ds_contacts_sort_za),
                icon = R.drawable.ds_ic_grid,
            ) { descending = !descending; applyFilter() }
            .add(label = getString(R.string.ds_contacts_refresh), icon = R.drawable.ds_ic_history) { load() }
            .show(binding.btnTools, Gravity.END)
    }

    // ===== actions =====

    /** Locate: the number goes back to Home, which fills the field. */
    private fun pick(contact: ContactNumber) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_NUMBER, contact.number))
        finish()
    }

    private fun dial(contact: ContactNumber) {
        runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.number}"))) }
    }

    // ===== small native ad =====

    /** The small native card under the search field (`ScreenAds.ContactPickerActivity`). */
    private fun renderAd() {
        val show = AdsVault.getInstance(this).getBoolean("IsAdsON") && ScreenPromoConfig.resolve(this, javaClass.simpleName).show
        binding.adCard.isVisible = show
        if (show) NativePromoBanner().showNativeBannerNative(this, binding.adNativeFrame, binding.adShimmer)
    }

    // ===== letter rail =====

    private fun buildRail() {
        binding.rail.removeAllViews()
        val font = ResourcesCompat.getFont(this, R.font.figtree_bold)
        adapter.letterPositions.keys.forEach { letter ->
            binding.rail.addView(TextView(this).apply {
                text = letter
                gravity = Gravity.CENTER
                typeface = font
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(ContextCompat.getColor(context, R.color.ds_primary))
                setPadding(dp(4), dp(1), dp(4), dp(1))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
    }

    /** Touch or drag along the rail scrolls the list to that letter. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupRail() {
        binding.rail.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val letters = adapter.letterPositions.keys.toList()
                    if (letters.isNotEmpty() && v.height > 0) {
                        val idx = ((event.y / v.height) * letters.size).toInt().coerceIn(0, letters.lastIndex)
                        adapter.letterPositions[letters[idx]]?.let { pos ->
                            (binding.rvContacts.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.performClick(); true }
                else -> false
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_NUMBER = "number"

        fun intent(context: Context): Intent = Intent(context, ContactPickerActivity::class.java)
    }
}
