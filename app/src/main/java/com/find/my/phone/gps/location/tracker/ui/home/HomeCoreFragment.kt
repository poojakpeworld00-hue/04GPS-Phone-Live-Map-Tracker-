package com.find.my.phone.gps.location.tracker.ui.home

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telephony.PhoneNumberFormattingTextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ContextThemeWrapper
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.LinearLayoutManager
import com.facebook.shimmer.ShimmerFrameLayout
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.data.lookup.NumberFactsReader
import com.find.my.phone.gps.location.tracker.data.lookup.NumberReport
import com.find.my.phone.gps.location.tracker.ui.lookup.LocatingActivity
import com.find.my.phone.gps.location.tracker.ui.lookup.NumberDetailActivity
import com.find.my.phone.gps.location.tracker.util.LocationLog
import com.find.my.phone.gps.location.tracker.util.openActivity
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault
import com.find.my.phone.gps.location.tracker.adcast.domain.LauncherAdsConfig
import com.find.my.phone.gps.location.tracker.adcast.domain.ScreenPromoConfig
import com.find.my.phone.gps.location.tracker.data.VaultRegistry
import com.find.my.phone.gps.location.tracker.adcast.domain.logKeyEvent
import com.find.my.phone.gps.location.tracker.data.premium.PremiumBilling
import com.find.my.phone.gps.location.tracker.data.premium.PremiumConfig
import com.find.my.phone.gps.location.tracker.data.premium.SearchQuota
import com.find.my.phone.gps.location.tracker.databinding.FragmentHomeCoreBinding
import com.find.my.phone.gps.location.tracker.ui.premium.PremiumActivity
import com.find.my.phone.gps.location.tracker.data.contactsync.ContactSync
import com.find.my.phone.gps.location.tracker.ui.contacts.ContactPickerActivity
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.find.my.phone.gps.location.tracker.ui.lookup.Territories
import com.find.my.phone.gps.location.tracker.util.followAdContainer

/**
 * The app's home content (design 3a–3e): "one task up front — number in, search out". The live
 * radar hero, the raised search card, the native mid slot under the fold, then recent searches;
 * the adaptive banner docks at the bottom once recents exist.
 *
 * One fragment, two hosts: the standalone home ([com.find.my.phone.gps.location.tracker.ui.AppCoreActivity])
 * and the launcher's swipe-in panel ([com.find.my.phone.gps.location.tracker.launcher.LauncherShellFragment]).
 * Ads and anything else that must not run unseen wait for [setOnScreen]: the launcher commits its
 * panel at `onCreate` and parks it off screen. The Activity-bound flows (RC permission sheet,
 * in-app update) stay with the hosts through [HomeShellController].
 */
class HomeCoreFragment : Fragment() {

    /** The host that owns the menu button (the standalone home's drawer). */
    interface MenuHost {
        fun openHomeMenu()
    }

    private var _binding: FragmentHomeCoreBinding? = null
    private val binding get() = _binding!!

    private val store by lazy { RecentSearchStore(requireContext()) }
    private val prefs by lazy { VaultRegistry(requireContext()) }

    private lateinit var recentsAdapter: RecentSearchAdapter
    private val animators = mutableListOf<Animator>()
    private var glowAnimator: Animator? = null
    private var proShine: Animator? = null
    private var heroAnimator: ValueAnimator? = null

    /** The country the dial code belongs to; the user's home country, else the US. */
    private var countryIso: String = "US"

    /** Groups the digits as the user types ("415 555 0134"), following the chosen country. */
    private var formatter: PhoneNumberFormattingTextWatcher? = null

    /** Whether the user can see this content right now (always in the standalone home). */
    private var onScreen = false

    /** The native slot has been requested once; later opens refresh instead. */
    private var nativeRequested = false

    /** A banner load is in flight or on screen; cleared when recents empty and the frame hides. */
    private var bannerRequested = false

    /** The contact button asks for Contacts first; once granted it opens the system picker. */
    private val contactPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openContactPicker()
        else Toast.makeText(requireContext(), R.string.ds_contacts_denied, Toast.LENGTH_SHORT).show()
    }

    /** The history page hands a row back; it reopens exactly as a tap on the Home list would. */
    private val historyPage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) { refreshRecents(); return@registerForActivityResult }
        val digits = result.data?.getStringExtra(HistoryActivity.EXTRA_DIGITS).orEmpty()
        val dial = result.data?.getStringExtra(HistoryActivity.EXTRA_DIAL).orEmpty()
        refreshRecents()
        store.find(digits, dial)?.let { openRecent(it) }
    }

    /**
     * A recent search tapped (on Home or on the history page): an unlocked result reopens as it
     * was; a locked one goes back to its blurred preview (no new lookup); rows saved before
     * results were kept just refill the search box.
     */
    private fun openRecent(recent: RecentSearch) {
        val report = recent.report
        if (report != null) {
            if (recent.unlocked) requireContext().openActivity(NumberDetailActivity.intent(requireContext(), report))
            else openLocating(recent, saved = report)
            return
        }
        countryIso = recent.iso2
        bindCountry()
        binding.phoneInput.setText(recent.digits)
        binding.phoneInput.setSelection(recent.digits.length)
    }

    private val contactPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val number = result.data?.getStringExtra(ContactPickerActivity.EXTRA_NUMBER)
        if (result.resultCode == Activity.RESULT_OK && !number.isNullOrBlank()) fillFromContact(number)
    }

    /**
     * Search Location: location permission first, then contacts permission, then the result —
     * the search runs whatever either answer is. The data use is disclosed on the LightHouse
     * disclosure screen after the splash, so neither step has an in-app dialog of its own; the
     * one-time phonebook upload runs silently once contacts is granted (gated inside
     * [ContactSync.uploadOnceIfDue]: release only, Remote Config switch, once per install).
     *
     * Everything after a permission result is null-safe on purpose: the result can land while
     * the fragment's view is gone (the launcher panel closes and reopens under the system
     * dialog), and a throw here takes the whole app down.
     */
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) context?.let(LocationLog::log)
        askContactsThenSearch()
    }

    /** Result of the contacts permission asked in the search chain (not the picker button). */
    private val uploadContactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startUploadIfDue()
        openPendingSearch()
    }

    /**
     * After location settles: ask for contacts once (the system shows its dialog at most twice;
     * after that the search just goes ahead), then open the search. Already granted → straight to
     * the search, with the upload kicked off in the background.
     */
    private fun askContactsThenSearch() {
        val context = context ?: return
        val permission = Manifest.permission.READ_CONTACTS
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        when {
            granted -> {
                startUploadIfDue()
                openPendingSearch()
            }
            !prefs.hasRequestedPermission(permission) -> {
                prefs.markPermissionRequested(permission)
                uploadContactsPermission.launch(permission)
            }
            shouldShowRequestPermissionRationale(permission) -> uploadContactsPermission.launch(permission)
            // Denied for good: the system would not show a dialog, so don't hold the search.
            else -> openPendingSearch()
        }
    }

    /**
     * The one-time upload, off the main thread and never fatal. Uses the application context and
     * the view's scope only if the view is still there, so a late permission result cannot throw.
     */
    private fun startUploadIfDue() {
        val app = context?.applicationContext ?: return
        val owner = viewLifecycleOwnerLiveData.value ?: return
        owner.lifecycleScope.launch {
            runCatching { ContactSync.uploadOnceIfDue(app) }
                .onFailure { android.util.Log.w("ContactSync", "upload chain failed", it) }
        }
    }

    /** Opens the search that was waiting on the permission answers, if one is still pending. */
    private fun openPendingSearch() {
        val search = pendingSearch ?: return
        pendingSearch = null
        openLocating(search, saved = null)
    }

    /** The search waiting on the location answer. */
    private var pendingSearch: RecentSearch? = null

    /** The next search was bought with a 9b rewarded ad: its result opens without another ad. */
    private var nextSearchPrepaid = false

    /** Back from the paywall opened by the limit sheet: bought Premium → the search goes ahead. */
    private val limitPremiumLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bindQuota()
        if (PremiumBilling.isPremium(requireContext())) startSearch()
    }

    /**
     * Back from the Locating page: it saved the search to Recents itself; once its lookup has
     * answered the search box is cleared. Premium bought from it shows here too.
     */
    private val locatingPage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.data?.getBooleanExtra(LocatingActivity.EXTRA_FOUND, false) == true) _binding?.phoneInput?.setText("")
        refreshRecents()
        bindQuota()
        bindProChip()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // Inflated under the app theme in both hosts: the launcher Activity carries the module's
        // own theme, whose default font and text style would otherwise restyle this content.
        val themed = inflater.cloneInContext(ContextThemeWrapper(inflater.context, R.style.Theme_GpsTracker))
        return FragmentHomeCoreBinding.inflate(themed, container, false).also { _binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // The hero gradient runs under the status bar; the header takes the top inset as
        // padding, the root the sides and the bottom (the banner slot sits above the nav bar).
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.header.updatePadding(top = bars.top)
            binding.header.layoutParams.height = dp(56) + bars.top
            navBarBottom = bars.bottom
            v.updatePadding(left = bars.left, right = bars.right)
            applyBottomInset(v)
            insets
        }
        // The bottom inset is only padded where the navigation bar really covers this view. In the
        // standalone (edge-to-edge) home that is the whole bar; in the launcher panel with 3-button
        // navigation the host already lays out above the bar, and padding again left a bar-high
        // empty strip under the banner.
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> applyBottomInset(v) }

        countryIso = prefs.homeCountryIso.takeIf { it.length == 2 && Territories.byIso(it) != null } ?: "US"
        bindCountry()

        // The menu only exists where there is a drawer to open (the standalone home).
        val menuHost = (parentFragment as? MenuHost) ?: (activity as? MenuHost)
        binding.btnMenu.isVisible = menuHost != null
        binding.btnMenu.setOnClickListener { menuHost?.openHomeMenu() }
        binding.btnHistory.setOnClickListener { historyPage.launch(HistoryActivity.intent(requireContext())) }

        // Free searches today (design 9a); "Unlimited" → the Premium paywall.
        binding.btnUnlimited.setOnClickListener {
            requireContext().logKeyEvent("home_quota_unlimited")
            startActivity(PremiumActivity.intent(requireContext()))
        }
        bindQuota()

        // PRO chip (design 9a) → the Premium paywall.
        DesignMotion.pressScale(binding.btnPro)
        binding.btnPro.clipToOutline = true
        binding.btnPro.setOnClickListener {
            requireContext().logKeyEvent("home_pro_chip")
            startActivity(PremiumActivity.intent(requireContext()))
        }
        bindProChip()
        binding.countryChip.setOnClickListener {
            CountryPickerSheet.newInstance(countryIso).show(childFragmentManager, "country")
        }
        childFragmentManager.setFragmentResultListener(CountryPickerSheet.RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            bundle.getString(CountryPickerSheet.ARG_ISO)?.let { iso ->
                countryIso = iso
                bindCountry()
            }
        }

        // Field: rest → focus (white, 2dp primary, 4dp halo) and the hero shrinks (3b).
        binding.phoneInput.setOnFocusChangeListener { _, focused -> setTypingState(focused) }
        binding.phoneInput.doAfterTextChanged { updateInputState() }
        binding.phoneInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { startSearch(); true } else false
        }
        binding.btnContact.setOnClickListener { pickContact() }
        binding.btnClear.setOnClickListener { binding.phoneInput.setText("") }
        DesignMotion.pressScale(binding.btnSearch)
        binding.btnSearch.setOnClickListener { startSearch() }
        updateInputState()

        recentsAdapter = RecentSearchAdapter { openRecent(it) }
        binding.rvRecents.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecents.adapter = recentsAdapter
        binding.btnClearAll.setOnClickListener {
            store.clear()
            refreshRecents()
        }
        refreshRecents()

        // The slot frame and the bottom banner are hidden until the content is on screen, so an
        // off-screen panel never requests an ad; the standalone home calls setOnScreen(true)
        // straight away. The ad layer shows the banner frame itself once a load starts.
        binding.adNativeCard.isVisible = false
        binding.root.findViewById<View>(R.id.bannerAdFrame)?.isVisible = false
        if (onScreen) renderAds()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the paywall with a purchase hides the chip and the counter; a new day
        // refills the counter.
        bindProChip()
        bindQuota()
    }

    /** The chip shows while Premium is on offer and the user doesn't have it; its shine runs meanwhile. */
    private fun bindProChip() {
        val b = _binding ?: return
        val context = requireContext()
        val show = PremiumConfig.read(context).enabled && !PremiumBilling.isPremium(context)
        b.btnPro.isVisible = show
        if (!show) {
            proShine?.cancel()
            proShine = null
            return
        }
        if (proShine != null || DesignMotion.animationsDisabled(context)) {
            b.proShine.isVisible = proShine != null
            return
        }
        b.btnPro.post {
            val chip = _binding?.btnPro ?: return@post
            val w = chip.width.toFloat()
            proShine = ObjectAnimator.ofFloat(_binding?.proShine, View.TRANSLATION_X, -0.4f * w, 1.2f * w).apply {
                duration = 1_800L
                startDelay = 1_200L
                repeatCount = ValueAnimator.INFINITE
                interpolator = DesignMotion.EASE_IN_OUT
                start()
            }.also { animators += it }
        }
    }

    override fun onDestroyView() {
        proShine = null
        heroAnimator?.cancel()
        DesignMotion.cancelAll(animators)
        glowAnimator = null
        _binding = null
        super.onDestroyView()
    }

    /**
     * Whether the user can see this content. The launcher calls it as its panel slides in and
     * out; the standalone home once, on create. Ad requests only ever happen while it is true.
     */
    fun setOnScreen(visible: Boolean) {
        onScreen = visible
        if (visible && _binding != null) renderAds()
    }

    /** The native mid slot under the search card, and the bottom banner once recents exist. */
    private fun renderAds() {
        val activity = activity ?: return
        val slot = LauncherAdsConfig.rightPanelNativeSlot(activity)
        val adsOn = AdsVault.getInstance(activity).getBoolean("IsAdsON")
        binding.adNativeCard.isVisible = adsOn && slot.visible
        if (adsOn && slot.visible) {
            // `launcher_ads.right_panel.native`: requested once, then refreshed only when a
            // fresh native is actually in hand (see LauncherAdsConfig.refreshSlot).
            if (!nativeRequested) {
                nativeRequested = true
                LauncherAdsConfig.showSlot(activity, slot, binding.adNativeFrame, binding.adShimmer)
            } else {
                LauncherAdsConfig.refreshSlot(activity, slot, binding.adNativeFrame, binding.adShimmer)
            }
        }
        renderBanner()
    }

    /**
     * Design 3b: the adaptive banner docks at the bottom once recents exist. Requested here for
     * both hosts (`ScreenAds.AppCoreActivity`): CanvasActivity's automatic bottom banner runs
     * before this fragment's view is in the hierarchy, so it never finds the frame.
     */
    private fun renderBanner() {
        val activity = activity ?: return
        val container = binding.root.findViewById<FrameLayout>(R.id.bannerAdFrame) ?: return
        val shimmer = binding.root.findViewById<ShimmerFrameLayout>(R.id.bannerShimmer)
        if (store.all().isEmpty()) return
        if (bannerRequested || bannerHoldsAd(container)) return
        bannerRequested = true
        ScreenPromoConfig.showAd(BANNER_SCREEN_KEY, activity, container, shimmer)
        binding.adBannerDivider.followAdContainer(container)
    }

    /** The shimmer skeleton is a child too; only a real ad view counts. */
    private fun bannerHoldsAd(container: FrameLayout): Boolean {
        val shimmer = container.findViewById<View>(R.id.bannerShimmer)
        return (0 until container.childCount).any { container.getChildAt(it) !== shimmer }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** The navigation bar's height from the last insets pass. */
    private var navBarBottom = 0

    /**
     * Pads the bottom by how far the navigation bar actually overlaps [view] in its window:
     * the bar's full height when the view runs under it, nothing when the host already stops
     * above it. Padding never changes the view's own bounds, so the layout listener cannot loop.
     */
    private fun applyBottomInset(view: View) {
        if (view.height == 0) return
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val barTop = view.rootView.height - navBarBottom
        val overlap = (loc[1] + view.height - barTop).coerceIn(0, navBarBottom)
        if (view.paddingBottom != overlap) view.updatePadding(bottom = overlap)
    }

    private fun bindCountry() {
        binding.tvFlag.text = Territories.flag(countryIso)
        binding.tvDial.text = "+" + (Territories.dialOf(countryIso) ?: "1")
        formatter?.let { binding.phoneInput.removeTextChangedListener(it) }
        formatter = PhoneNumberFormattingTextWatcher(countryIso).also {
            binding.phoneInput.addTextChangedListener(it)
        }
    }

    /** Contacts ↔ clear swap on input; the Search button glows only when the number is valid. */
    private fun updateInputState() {
        val digits = binding.phoneInput.text?.filter { it.isDigit() }?.length ?: 0
        binding.btnContact.isVisible = digits == 0
        binding.btnClear.isVisible = digits > 0
        val valid = digits >= MIN_DIGITS
        if (valid && glowAnimator == null && !DesignMotion.animationsDisabled(requireContext())) {
            binding.searchGlow.isInvisible = false
            glowAnimator = DesignMotion.glow(binding.searchGlow, 2400L).also { animators += it }
        } else if (!valid && glowAnimator != null) {
            glowAnimator?.cancel()
            animators.remove(glowAnimator)
            glowAnimator = null
            binding.searchGlow.isInvisible = true
        }
    }

    /** 3a ↔ 3b: focus turns the field white with the primary ring and halo, hero 300 → 230dp. */
    private fun setTypingState(typing: Boolean) {
        binding.phoneField.setBackgroundResource(if (typing) R.drawable.ds_field_focus_r16 else R.drawable.ds_field_rest_r16)
        binding.fieldHalo.isInvisible = !typing
        binding.pillGps.isVisible = !typing
        binding.tvHeadline.textSize = if (typing) 24f else 26f
        val from = binding.heroBox.layoutParams.height
        val to = dp(if (typing) 230 else 300)
        heroAnimator?.cancel()
        heroAnimator = ValueAnimator.ofInt(from, to).apply {
            duration = 250L
            interpolator = DesignMotion.EASE_IN_OUT
            addUpdateListener {
                val b = _binding ?: return@addUpdateListener
                b.heroBox.layoutParams.height = it.animatedValue as Int
                b.heroBox.requestLayout()
            }
            start()
        }
    }

    // ── Contacts ──────────────────────────────────────────────────────────

    /**
     * Picks a number from the phone book. Contacts permission first; once the system has stopped
     * showing its dialog (denied twice), the button opens this app's settings instead.
     */
    private fun pickContact() {
        val permission = Manifest.permission.READ_CONTACTS
        val context = requireContext()
        when {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED -> openContactPicker()
            !prefs.hasRequestedPermission(permission) -> {
                prefs.markPermissionRequested(permission)
                contactPermission.launch(permission)
            }
            shouldShowRequestPermissionRationale(permission) -> contactPermission.launch(permission)
            else -> {
                Toast.makeText(context, R.string.ds_contacts_denied, Toast.LENGTH_SHORT).show()
                runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }
            }
        }
    }

    /** The app's own contact list (ContactPickerActivity), not the system picker. */
    private fun openContactPicker() {
        val context = context ?: return
        contactPicker.launch(ContactPickerActivity.intent(context))
        // Contacts are granted here (this runs after the grant): upload silently if the switch is
        // on. Gated inside — debug/off/already-uploaded are all no-ops. Disclosed on the LightHouse
        // screen, so no dialog here.
        startUploadIfDue()
    }

    /** The picked number goes into the field; an international one also sets the country. */
    private fun fillFromContact(raw: String) {
        val (iso, digits) = NumberFactsReader.split(raw, countryIso)
        if (iso != countryIso && Territories.byIso(iso) != null) {
            countryIso = iso
            bindCountry()
        }
        binding.phoneInput.setText(digits)
        binding.phoneInput.setSelection(binding.phoneInput.text?.length ?: 0)
    }

    // ── Search ────────────────────────────────────────────────────────────

    private fun startSearch() {
        val typed = binding.phoneInput.text?.filter { it.isDigit() }?.toString().orEmpty()
        if (typed.length < MIN_DIGITS || !NumberFactsReader.isValid(typed, countryIso)) {
            showInvalid(typed)
            return
        }
        // Keep the national digits only: "91 70460 24874" typed under +91 is 7046024874, so
        // Recents doesn't show "+91 917 046 024874".
        val digits = NumberFactsReader.split(typed, countryIso)
            .takeIf { (iso, _) -> iso.equals(countryIso, ignoreCase = true) }?.second ?: typed
        hideKeyboard()
        // Out of free searches today → design 9b: watch an ad for one more, or go Premium.
        if (!SearchQuota.canSearch(requireContext())) {
            requireContext().logKeyEvent("search_limit_reached")
            SearchLimitSheet(
                requireActivity(),
                onExtraSearch = {
                    bindQuota()
                    nextSearchPrepaid = true
                    startSearch()
                },
                onPremium = { limitPremiumLauncher.launch(PremiumActivity.intent(requireContext())) },
            ).show()
            return
        }
        val search = RecentSearch(digits, countryIso, Territories.dialOf(countryIso) ?: "1", System.currentTimeMillis())
        val context = requireContext()
        val located = LOCATION_PERMISSIONS.any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (located) {
            openLocating(search, saved = null)
        } else {
            pendingSearch = search
            locationPermission.launch(LOCATION_PERMISSIONS)
        }
    }

    /**
     * The Locating page: a fresh search runs its map search there and is saved to Recents
     * (locked) once it finds its result; watching the ad saves it unlocked and opens the full
     * detail screen. [saved] reopens a locked recent search straight at its result.
     */
    private fun openLocating(search: RecentSearch, saved: NumberReport?) {
        val activity = activity ?: return
        // A new lookup uses one of today's free searches (reopening a saved result doesn't).
        if (saved == null) {
            SearchQuota.consume(activity)
            bindQuota()
        }
        // A number unlocked before stays unlocked when searched again.
        val unlockedBefore = store.all().any { it.digits == search.digits && it.dial == search.dial && it.unlocked } ||
            // One ad per search: a search bought with the 9b ad skips the 11a/11b unlock.
            (saved == null && nextSearchPrepaid)
        if (saved == null) nextSearchPrepaid = false
        locatingPage.launch(LocatingActivity.intent(activity, search, saved, unlockedBefore))
    }

    /** Design 9a: "Free searches today · n of 3 left" with its bar; hidden when there is no limit. */
    private fun bindQuota() {
        val b = _binding ?: return
        val context = requireContext()
        val unlimited = SearchQuota.isUnlimited(context)
        b.quotaRow.isVisible = !unlimited
        if (unlimited) return
        val limit = SearchQuota.limit(context)
        val left = SearchQuota.remaining(context)
        b.tvQuota.text = getString(R.string.ds_quota_left, left, limit)
        b.quotaBar.progress = (100 * left.coerceAtMost(limit) / limit.coerceAtLeast(1))
    }

    /** Design 11d: what's wrong with the number, with Edit number and Change country. */
    private fun showInvalid(digits: String) {
        val context = requireContext()
        val shown = binding.phoneInput.text?.toString()?.trim().orEmpty().ifEmpty { digits }
        InvalidNumberSheet(
            requireActivity(),
            number = "+" + (Territories.dialOf(countryIso) ?: "") + " " + shown,
            country = Territories.byIso(countryIso)?.name ?: countryIso,
            onEdit = {
                binding.phoneInput.requestFocus()
                binding.phoneInput.setSelection(binding.phoneInput.text?.length ?: 0)
                ViewCompat.getWindowInsetsController(binding.phoneInput)?.show(WindowInsetsCompat.Type.ime())
            },
            onChangeCountry = { CountryPickerSheet.newInstance(countryIso).show(childFragmentManager, "country") },
        ).show()
        context.logKeyEvent("search_invalid_number")
    }

    private fun hideKeyboard() {
        binding.phoneInput.clearFocus()
        ViewCompat.getWindowInsetsController(binding.phoneInput)?.hide(WindowInsetsCompat.Type.ime())
    }

    private fun refreshRecents() {
        val b = _binding ?: return
        val recents = store.all()
        recentsAdapter.submitList(recents)
        b.emptyState.isVisible = recents.isEmpty()
        b.rvRecents.isVisible = recents.isNotEmpty()
        b.btnClearAll.isVisible = recents.isNotEmpty()
        // Design 3b: the adaptive banner docks at the bottom once recents exist. The frame is only
        // forced on when it holds a real ad; otherwise the ad layer's own state stands (it hides
        // the frame when there is nothing to show, and forcing it on exposed the loading skeleton).
        val banner = b.root.findViewById<FrameLayout>(R.id.bannerAdFrame)
        val holdsAd = banner != null && bannerHoldsAd(banner)
        if (recents.isEmpty()) {
            banner?.isVisible = false
            bannerRequested = false
        } else if (holdsAd) {
            banner?.isVisible = true
        }
        b.adBannerDivider.isVisible = recents.isNotEmpty() && holdsAd && banner?.isVisible == true
        if (recents.isNotEmpty() && onScreen) renderBanner()
    }

    private companion object {
        const val MIN_DIGITS = 6

        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        /** The same ScreenAds entry the standalone home uses, so one switch covers both. */
        const val BANNER_SCREEN_KEY = "AppCoreActivity"
    }
}
