package com.find.phone.gps.live.location.tracker.screens.people

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
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.logic.DisplayTeaserProfile
import com.find.phone.gps.live.location.tracker.monetize.render.EmbeddedTeaserRibbon
import com.find.phone.gps.live.location.tracker.foundation.StageActivity
import com.find.phone.gps.live.location.tracker.store.LockerBook
import com.find.phone.gps.live.location.tracker.databinding.ScreenContactPickerBinding
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.screens.craft.KitFlyout
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
class BuddyChooserActivity : StageActivity<ScreenContactPickerBinding>() {

    override val layoutId: Int = R.layout.screen_contact_picker

    private val adapter = BuddyAdapter(onLocate = { pick(it) }, onCall = { dial(it) })
    private val favorites = PinnedAdapter { pick(it) }
    private val prefs by lazy { LockerBook(this) }

    private var all: List<BuddyNum> = emptyList()
    private var account: String? = null // null = all accounts
    private var descending = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onGranted() else presentPermissionState()
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.lvContactsRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.listContacts.layoutManager = LinearLayoutManager(this)
        binding.listContacts.adapter = adapter
        binding.listFavorites.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.listFavorites.adapter = favorites

        binding.huntInput.doAfterTextChanged {
            binding.actClearSearch.isVisible = !it.isNullOrEmpty()
            enactFilter()
        }
        binding.actClearSearch.setOnClickListener { binding.huntInput.setText("") }
        binding.lvAccountChip.setOnClickListener { presentAccountMenu() }
        binding.actTools.setOnClickListener { presentToolsMenu() }
        binding.actAllow.setOnClickListener { queryPermission() }
        listOf(binding.lvAccountChip, binding.actTools, binding.actAllow).forEach { CraftMovement.pressScale(it) }
        setupRail()

        if (hasPermission()) onGranted() else presentPermissionState()
    }

    // ===== permission (8b) =====

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** First ask through the system dialog; once it stops showing (denied twice), app settings. */
    private fun queryPermission() {
        val perm = Manifest.permission.READ_CONTACTS
        when {
            !prefs.hasRequestedPermission(perm) -> {
                prefs.flagPermissionRequested(perm)
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
        if (binding.grantState.isVisible && hasPermission()) onGranted()
    }

    private fun presentPermissionState() {
        binding.grantState.isVisible = true
        binding.lvListGroup.isVisible = false
        binding.lvRailBox.isVisible = false
    }

    private fun onGranted() {
        binding.grantState.isVisible = false
        binding.lvListGroup.isVisible = true
        paintAd()
        load()
    }

    // ===== data =====

    private fun load() {
        binding.spinLoading.isVisible = true
        binding.txtAccountCount.isVisible = false // no count until the phone book is read
        lifecycleScope.launch {
            all = withContext(Dispatchers.IO) { runCatching { GadgetBuddies.load(this@BuddyChooserActivity) }.getOrDefault(emptyList()) }
            binding.spinLoading.isVisible = false
            if (account != null && all.none { it.account == account }) account = null
            wireAccountChip()
            enactFilter()
        }
    }

    private fun wireAccountChip() {
        val scope = all.filter { account == null || it.account == account }
        binding.txtAccount.text = account ?: getString(R.string.ds_contacts_all_accounts)
        binding.txtAccountCount.text = scope.distinctBy { it.contactId }.size.toString()
        binding.txtAccountCount.isVisible = true
    }

    /** Account scope, then name contains the text or — when it has digits — the number does. */
    private fun enactFilter() {
        if (binding.spinLoading.isVisible) return
        val query = binding.huntInput.text?.toString()?.trim().orEmpty()
        val needle = query.lowercase(Locale.getDefault())
        val digits = query.filter(Char::isDigit)
        val scoped = all.filter { account == null || it.account == account }
        var shown = if (query.isEmpty()) scoped else scoped.filter {
            it.name.lowercase(Locale.getDefault()).contains(needle) ||
                (digits.isNotEmpty() && it.number.filter(Char::isDigit).contains(digits))
        }
        if (descending) shown = shown.sortedWith(compareBy<BuddyNum> { it.letter == "#" }.thenByDescending { it.name.lowercase(Locale.getDefault()) })
        adapter.lodgeContacts(shown) {
            binding.listContacts.scrollToPosition(0)
            composeRail()
        }

        // Favorites: the starred contacts of the scope, hidden while searching (design 8a).
        val favs = scoped.filter { it.starred }.distinctBy { it.contactId }
        favorites.submitList(favs)
        binding.lvFavoritesGroup.isVisible = query.isEmpty() && favs.isNotEmpty()
        binding.txtFavoritesCount.text = favs.size.toString()

        when {
            all.isEmpty() -> presentEmpty(R.string.ds_contacts_empty, R.string.ds_contacts_empty_body)
            shown.isEmpty() -> presentEmpty(R.string.ds_contacts_no_match, R.string.ds_contacts_no_match_body)
            else -> binding.blankState.isVisible = false
        }
        binding.lvRailBox.isVisible = shown.isNotEmpty()
    }

    private fun presentEmpty(title: Int, body: Int) {
        binding.txtEmpty.setText(title)
        binding.txtEmptyBody.setText(body)
        binding.blankState.isVisible = true
    }

    // ===== menus =====

    /** "All accounts" plus every account the phone book has, each with its contact count. */
    private fun presentAccountMenu() {
        val people = all.distinctBy { it.contactId }
        val counts = people.groupingBy { it.account }.eachCount()
        val popup = KitFlyout(this)
        popup.add(
            label = getString(R.string.ds_contacts_all_accounts),
            icon = R.drawable.lv_ic_contact_card,
            badge = people.size.toString(),
            selected = account == null,
        ) { highlightAccount(null) }
        counts.keys.sorted().forEach { name ->
            popup.add(
                label = name,
                icon = when (name) {
                    BuddyNum.ACCOUNT_PHONE -> R.drawable.lv_ic_phone
                    BuddyNum.ACCOUNT_SIM -> R.drawable.lv_ic_contact_card
                    else -> R.drawable.lv_ic_globe
                },
                badge = counts.getValue(name).toString(),
                selected = account == name,
            ) { highlightAccount(name) }
        }
        popup.show(binding.lvAccountChip, Gravity.START)
    }

    private fun highlightAccount(name: String?) {
        account = name
        wireAccountChip()
        enactFilter()
    }

    /** The tools button: sort direction and a refresh. */
    private fun presentToolsMenu() {
        KitFlyout(this)
            .add(
                label = getString(if (descending) R.string.ds_contacts_sort_az else R.string.ds_contacts_sort_za),
                icon = R.drawable.lv_ic_grid,
            ) { descending = !descending; enactFilter() }
            .add(label = getString(R.string.ds_contacts_refresh), icon = R.drawable.lv_ic_history) { load() }
            .show(binding.actTools, Gravity.END)
    }

    // ===== actions =====

    /** Locate: the number goes back to Home, which fills the field. */
    private fun pick(contact: BuddyNum) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_NUMBER, contact.number))
        finish()
    }

    private fun dial(contact: BuddyNum) {
        runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.number}"))) }
    }

    // ===== small native ad =====

    /** The small native card under the search field (`ScreenAds.BuddyChooserActivity`). */
    private fun paintAd() {
        val show = SpotsLocker.getInstance(this).getBoolean("IsAdsON") && DisplayTeaserProfile.resolve(this, javaClass.simpleName).show
        binding.spotCard.isVisible = show
        if (show) EmbeddedTeaserRibbon().presentNativeBannerNative(this, binding.spotNativeFrame, binding.spotShimmer)
    }

    // ===== letter rail =====

    private fun composeRail() {
        binding.lvRail.removeAllViews()
        val font = ResourcesCompat.getFont(this, R.font.figtree_bold)
        adapter.letterPositions.keys.forEach { letter ->
            binding.lvRail.addView(TextView(this).apply {
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
        binding.lvRail.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val letters = adapter.letterPositions.keys.toList()
                    if (letters.isNotEmpty() && v.height > 0) {
                        val idx = ((event.y / v.height) * letters.size).toInt().coerceIn(0, letters.lastIndex)
                        adapter.letterPositions[letters[idx]]?.let { pos ->
                            (binding.listContacts.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
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

        fun intent(context: Context): Intent = Intent(context, BuddyChooserActivity::class.java)
    }
}
