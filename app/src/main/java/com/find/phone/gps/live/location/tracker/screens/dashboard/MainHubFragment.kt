package com.find.phone.gps.live.location.tracker.screens.dashboard

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.Manifest
import android.content.Context
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
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.store.inquiry.NumNotesScanner
import com.find.phone.gps.live.location.tracker.store.inquiry.NumDigest
import com.find.phone.gps.live.location.tracker.screens.finder.PinpointingActivity
import com.find.phone.gps.live.location.tracker.screens.finder.NumProfile2Activity
import com.find.phone.gps.live.location.tracker.helpers.PlaceDiary
import com.find.phone.gps.live.location.tracker.helpers.enterActivity
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile
import com.find.phone.gps.live.location.tracker.monetize.logic.DisplayTeaserProfile
import com.find.phone.gps.live.location.tracker.store.LockerBook
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.store.plus.PlusCheckout
import com.find.phone.gps.live.location.tracker.store.plus.PlusProfile
import com.find.phone.gps.live.location.tracker.store.plus.HuntRation
import com.find.phone.gps.live.location.tracker.databinding.PaneHomeCoreBinding
import com.find.phone.gps.live.location.tracker.screens.plus.PlusActivity
import com.find.phone.gps.live.location.tracker.store.peoplesync.BuddyRelay
import com.find.phone.gps.live.location.tracker.screens.people.BuddyChooserActivity
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.screens.finder.Realms
import com.find.phone.gps.live.location.tracker.helpers.followAdContainer

/**
 * Home content: a radar hero, the number search card, a native slot below it and the list of
 * recent searches, with a bottom banner once there are recents.
 *
 * Hosted both by the standalone home and by the launcher's swipe-in panel. Anything that must
 * not run while unseen (ads) waits for [setOnScreen], because the launcher attaches this panel
 * early and keeps it off screen. Activity-level flows stay with the hosts.
 */
class MainHubFragment : Fragment() {

    /** The host that owns the menu button (the standalone home's drawer). */
    interface MenuHost {
        fun openHomeMenu()
    }

    private var _binding: PaneHomeCoreBinding? = null
    private val binding get() = _binding!!

    private val book by lazy { LockerBook(requireContext()) }
    private val recentShelf by lazy { FreshHuntShelf(requireContext()) }

    private lateinit var recentsListAdapter: FreshHuntAdapter
    private val liveAnims = mutableListOf<Animator>()
    private var heroResize: ValueAnimator? = null
    private var searchGlow: Animator? = null
    private var chipShine: Animator? = null

    /** ISO code of the selected dialing country (home country, else US). */
    private var pickedIso: String = "US"

    /** Formats the digits while typing according to [pickedIso]. */
    private var digitGrouper: PhoneNumberFormattingTextWatcher? = null

    /** True while this content is actually visible (always true in the standalone home). */
    private var visibleToUser = false

    /** First native request done; afterwards only refreshes happen. */
    private var nativeLoadedOnce = false

    /** A banner is loading or shown; reset when the recents list empties. */
    private var bannerInFlight = false

    /** Navigation bar height from the latest insets dispatch. */
    private var navInset = 0

    /** Search waiting for the permission answers. */
    private var heldSearch: FreshHunt? = null

    /** The upcoming search was paid for with the limit sheet's rewarded ad. */
    private var prepaidByAd = false

    // Result registrations (kept in a fixed order).

    private val pickerContactsAsk = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        when (ok) {
            true -> launchBuddyPicker()
            false -> Toast.makeText(requireContext(), R.string.ds_contacts_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val historyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val data = res.data
            val digits = data?.getStringExtra(LiveHistoryActivity.EXTRA_DIGITS).orEmpty()
            val dial = data?.getStringExtra(LiveHistoryActivity.EXTRA_DIAL).orEmpty()
            reloadRecents()
            recentShelf.find(digits, dial)?.let { reopenRecent(it) }
        } else {
            reloadRecents()
        }
    }

    private val buddyPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val picked = res.data?.getStringExtra(BuddyChooserActivity.EXTRA_NUMBER)
        picked?.takeIf { res.resultCode == Activity.RESULT_OK && it.isNotBlank() }?.let { fillFromContact(it) }
    }

    /**
     * Location first, then contacts, then the search goes ahead regardless of the answers. Both
     * steps are disclosed earlier in the app, so there is no extra dialog here. All follow-up is
     * null-safe: the answer can arrive after the view is gone.
     */
    private val locationAsk = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
        if (true in answers.values) context?.let(PlaceDiary::log)
        askContactsThenProceed()
    }

    /** Contacts asked as part of the search chain (not the picker button). */
    private val searchContactsAsk = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        kickPhonebookUpload()
        releaseHeldSearch()
    }

    /** Paywall opened from the limit sheet: a purchase lets the search continue. */
    private val ceilingPaywall = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshQuota()
        if (PlusCheckout.isPremium(requireContext())) submitSearch()
    }

    /** Back from Locating: it saved the recent itself; clear the box once the lookup answered. */
    private val locatingLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val answered = res.data?.getBooleanExtra(PinpointingActivity.EXTRA_FOUND, false) == true
        if (answered) _binding?.lvPhoneInput?.setText("")
        reloadRecents()
        refreshQuota()
        refreshProChip()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // Always inflate with the app theme: the launcher host uses its own module theme.
        val appThemed = ContextThemeWrapper(inflater.context, R.style.Theme_GpsTracker)
        val fresh = PaneHomeCoreBinding.inflate(inflater.cloneInContext(appThemed), container, false)
        _binding = fresh
        return fresh.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        installInsets(view)

        pickedIso = book.homeCountryIso.takeIf { it.length == 2 && Realms.byIso(it) != null } ?: "US"
        applyCountry()

        bindHeader()
        bindSearchCard()
        bindRecents()

        // Ad frames stay hidden until the content is on screen, so a parked panel never loads one.
        binding.spotNativeCard.isVisible = false
        binding.root.findViewById<View>(R.id.ribbonAdFrame)?.isVisible = false
        if (visibleToUser) loadAdSlots()
    }

    override fun onResume() {
        super.onResume()
        // A purchase or a new day may have changed the chip / counter.
        refreshProChip()
        refreshQuota()
    }

    override fun onDestroyView() {
        chipShine = null
        heroResize?.cancel()
        CraftMovement.abortAll(liveAnims)
        searchGlow = null
        _binding = null
        super.onDestroyView()
    }

    /**
     * Called by the launcher as its panel enters/leaves, and once by the standalone home.
     * Ads are only requested while this is true.
     */
    fun setOnScreen(visible: Boolean) {
        visibleToUser = visible
        if (!visible || _binding == null) return
        loadAdSlots()
    }

    // ── View setup ────────────────────────────────────────────────────────

    private fun installInsets(view: View) {
        // The hero runs under the status bar: the header pads by the top inset, the root by the
        // sides, and the bottom only by the part of the nav bar that really overlaps it.
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.crest.updatePadding(top = sb.top)
            binding.crest.layoutParams.height = px(56) + sb.top
            navInset = sb.bottom
            v.updatePadding(left = sb.left, right = sb.right)
            padForNavBar(v)
            insets
        }
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> padForNavBar(v) }
    }

    private fun openPaywall(event: String) {
        requireContext().recordKeyEvent(event)
        startActivity(PlusActivity.intent(requireContext()))
    }

    private fun showCountryPicker() {
        LandChooserDrawer.newInstance(pickedIso).show(childFragmentManager, "country")
    }

    private fun bindHeader() {
        // Only the standalone home has a drawer to open.
        val host = (parentFragment as? MenuHost) ?: (activity as? MenuHost)
        binding.actMenu.isVisible = host != null
        binding.actMenu.setOnClickListener { host?.openHomeMenu() }
        binding.actHistory.setOnClickListener { historyLauncher.launch(LiveHistoryActivity.intent(requireContext())) }

        binding.actUnlimited.setOnClickListener { openPaywall("home_quota_unlimited") }
        refreshQuota()

        CraftMovement.pressScale(binding.actPro)
        binding.actPro.clipToOutline = true
        binding.actPro.setOnClickListener { openPaywall("home_pro_chip") }
        refreshProChip()

        binding.landChip.setOnClickListener { showCountryPicker() }
        childFragmentManager.setFragmentResultListener(LandChooserDrawer.RESULT_KEY, viewLifecycleOwner) { _, result ->
            val iso = result.getString(LandChooserDrawer.ARG_ISO) ?: return@setFragmentResultListener
            pickedIso = iso
            applyCountry()
        }
    }

    private fun bindSearchCard() {
        val input = binding.lvPhoneInput
        input.setOnFocusChangeListener { _, hasFocus -> toggleTypingLook(hasFocus) }
        input.doAfterTextChanged { syncInputUi() }
        input.setOnEditorActionListener { _, actionId, _ ->
            val isSearch = actionId == EditorInfo.IME_ACTION_SEARCH
            if (isSearch) submitSearch()
            isSearch
        }
        binding.actContact.setOnClickListener { onContactTapped() }
        binding.actClear.setOnClickListener { input.setText("") }
        CraftMovement.pressScale(binding.actSearch)
        binding.actSearch.setOnClickListener { submitSearch() }
        syncInputUi()
    }

    private fun bindRecents() {
        recentsListAdapter = FreshHuntAdapter { reopenRecent(it) }
        binding.listRecents.layoutManager = LinearLayoutManager(requireContext())
        binding.listRecents.adapter = recentsListAdapter
        binding.actClearAll.setOnClickListener {
            recentShelf.clear()
            reloadRecents()
        }
        reloadRecents()
    }

    /** Shown while Premium is offered and not owned; the shine runs meanwhile. */
    private fun refreshProChip() {
        val b = _binding ?: return
        val ctx = requireContext()
        val offerVisible = PlusProfile.read(ctx).enabled && !PlusCheckout.isPremium(ctx)
        b.actPro.isVisible = offerVisible
        when {
            !offerVisible -> {
                chipShine?.cancel()
                chipShine = null
            }
            chipShine != null || CraftMovement.animationsDisabled(ctx) -> b.lvProShine.isVisible = chipShine != null
            else -> b.actPro.post { startChipShine() }
        }
    }

    private fun startChipShine() {
        val chip = _binding?.actPro ?: return
        val width = chip.width.toFloat()
        chipShine = ObjectAnimator.ofFloat(_binding?.lvProShine, View.TRANSLATION_X, -0.4f * width, 1.2f * width).apply {
            duration = 1_800L
            startDelay = 1_200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = CraftMovement.EASE_IN_OUT
            start()
        }.also { liveAnims += it }
    }

    // ── Ads ───────────────────────────────────────────────────────────────

    /** The native mid slot under the search card, plus the bottom banner once recents exist. */
    private fun loadAdSlots() {
        val host = activity ?: return
        val slot = HomeSpotsProfile.rightPanelNativeSlot(host)
        val showNative = SpotsLocker.getInstance(host).getBoolean("IsAdsON") && slot.visible
        binding.spotNativeCard.isVisible = showNative
        if (showNative) {
            // Requested once; later visits only refresh when a new native is ready.
            if (nativeLoadedOnce) {
                HomeSpotsProfile.renewSlot(host, slot, binding.spotNativeFrame, binding.spotShimmer)
            } else {
                nativeLoadedOnce = true
                HomeSpotsProfile.presentSlot(host, slot, binding.spotNativeFrame, binding.spotShimmer)
            }
        }
        requestBanner()
    }

    /**
     * Bottom banner for both hosts (`ScreenAds.CoreHubActivity`). The host Activity's automatic
     * banner runs before this view is attached, so it is requested from here.
     */
    private fun requestBanner() {
        val host = activity ?: return
        val frame = binding.root.findViewById<FrameLayout>(R.id.ribbonAdFrame) ?: return
        val shimmer = binding.root.findViewById<ShimmerFrameLayout>(R.id.ribbonShimmer)
        val skip = recentShelf.all().isEmpty() || bannerInFlight || frameHasAd(frame)
        if (skip) return
        bannerInFlight = true
        DisplayTeaserProfile.showAd(HOME_BANNER_KEY, host, frame, shimmer)
        binding.spotBannerDivider.followAdContainer(frame)
    }

    /** True only when the frame holds something other than its shimmer placeholder. */
    private fun frameHasAd(frame: FrameLayout): Boolean {
        val placeholder = frame.findViewById<View>(R.id.ribbonShimmer)
        for (i in 0 until frame.childCount) {
            if (frame.getChildAt(i) !== placeholder) return true
        }
        return false
    }

    private fun px(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Bottom padding equal to how much the navigation bar overlaps [view]: all of it when the
     * view runs underneath, none when the host already stops above it. Padding doesn't move the
     * bounds, so the layout listener can't loop.
     */
    private fun padForNavBar(view: View) {
        if (view.height == 0) return
        val pos = IntArray(2).also { view.getLocationInWindow(it) }
        val barTop = view.rootView.height - navInset
        val overlap = (pos[1] + view.height - barTop).coerceIn(0, navInset)
        if (view.paddingBottom == overlap) return
        view.updatePadding(bottom = overlap)
    }

    // ── Input ─────────────────────────────────────────────────────────────

    private fun applyCountry() {
        val input = binding.lvPhoneInput
        binding.txtFlag.text = Realms.flag(pickedIso)
        binding.txtDial.text = "+" + (Realms.dialOf(pickedIso) ?: "1")
        digitGrouper?.let(input::removeTextChangedListener)
        val grouper = PhoneNumberFormattingTextWatcher(pickedIso)
        digitGrouper = grouper
        input.addTextChangedListener(grouper)
    }

    private fun typedDigitCount(): Int = binding.lvPhoneInput.text?.count { it.isDigit() } ?: 0

    /** Contact ↔ clear swap; the search glow only runs while the number is long enough. */
    private fun syncInputUi() {
        val count = typedDigitCount()
        binding.actContact.isVisible = count == 0
        binding.actClear.isVisible = count > 0
        val longEnough = count >= SHORTEST_NUMBER
        when {
            longEnough && searchGlow == null && !CraftMovement.animationsDisabled(requireContext()) -> {
                binding.huntGlow.isInvisible = false
                searchGlow = CraftMovement.glow(binding.huntGlow, 2400L).also { liveAnims += it }
            }
            !longEnough && searchGlow != null -> {
                searchGlow?.cancel()
                liveAnims.remove(searchGlow)
                searchGlow = null
                binding.huntGlow.isInvisible = true
            }
        }
    }

    /** Focused: white field with ring and halo, hero shrinks 300 → 230dp. */
    private fun toggleTypingLook(typing: Boolean) {
        val fieldBg = if (typing) R.drawable.lv_field_focus_r16 else R.drawable.lv_field_rest_r16
        binding.lvPhoneField.setBackgroundResource(fieldBg)
        binding.lvFieldHalo.isInvisible = !typing
        binding.lvPillGps.isVisible = !typing
        binding.txtHeadline.textSize = if (typing) 24f else 26f
        val startH = binding.leadBox.layoutParams.height
        val endH = px(if (typing) 230 else 300)
        heroResize?.cancel()
        val anim = ValueAnimator.ofInt(startH, endH)
        anim.duration = 250L
        anim.interpolator = CraftMovement.EASE_IN_OUT
        anim.addUpdateListener { va ->
            val b = _binding ?: return@addUpdateListener
            b.leadBox.layoutParams.height = va.animatedValue as Int
            b.leadBox.requestLayout()
        }
        anim.start()
        heroResize = anim
    }

    private fun placeCursorAtEnd() {
        binding.lvPhoneInput.setSelection(binding.lvPhoneInput.text?.length ?: 0)
    }

    // ── Contacts ──────────────────────────────────────────────────────────

    private fun hasContacts(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /**
     * Contacts button: ask first; once the system no longer shows its dialog, go to the app's
     * settings page instead.
     */
    private fun onContactTapped() {
        val perm = Manifest.permission.READ_CONTACTS
        val ctx = requireContext()
        if (hasContacts(ctx)) {
            launchBuddyPicker()
        } else if (!book.hasRequestedPermission(perm)) {
            book.flagPermissionRequested(perm)
            pickerContactsAsk.launch(perm)
        } else if (shouldShowRequestPermissionRationale(perm)) {
            pickerContactsAsk.launch(perm)
        } else {
            Toast.makeText(ctx, R.string.ds_contacts_denied, Toast.LENGTH_SHORT).show()
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            }
        }
    }

    /** Opens the in-app contact list; contacts are granted at this point, so the upload may run. */
    private fun launchBuddyPicker() {
        val ctx = context ?: return
        buddyPickerLauncher.launch(BuddyChooserActivity.intent(ctx))
        kickPhonebookUpload()
    }

    /** The chosen number fills the field; an international one switches the country too. */
    private fun fillFromContact(raw: String) {
        val (iso, digits) = NumNotesScanner.split(raw, pickedIso)
        val switchCountry = iso != pickedIso && Realms.byIso(iso) != null
        if (switchCountry) {
            pickedIso = iso
            applyCountry()
        }
        binding.lvPhoneInput.setText(digits)
        placeCursorAtEnd()
    }

    /**
     * After the location answer: ask for contacts once (the system dialog appears at most twice,
     * then the search simply continues). Already granted → upload in the background and search.
     */
    private fun askContactsThenProceed() {
        val ctx = context ?: return
        val perm = Manifest.permission.READ_CONTACTS
        if (hasContacts(ctx)) {
            kickPhonebookUpload()
            releaseHeldSearch()
            return
        }
        if (!book.hasRequestedPermission(perm)) {
            book.flagPermissionRequested(perm)
            searchContactsAsk.launch(perm)
            return
        }
        if (shouldShowRequestPermissionRationale(perm)) {
            searchContactsAsk.launch(perm)
            return
        }
        // Permanently denied: no dialog would appear, so don't block the search.
        releaseHeldSearch()
    }

    /** One-time phonebook upload in the background; never fatal, skipped when the view is gone. */
    private fun kickPhonebookUpload() {
        val appCtx = context?.applicationContext ?: return
        val owner = viewLifecycleOwnerLiveData.value ?: return
        owner.lifecycleScope.launch {
            runCatching { BuddyRelay.pushOnceIfDue(appCtx) }
                .onFailure { android.util.Log.w("BuddyRelay", "upload chain failed", it) }
        }
    }

    private fun releaseHeldSearch() {
        val waiting = heldSearch ?: return
        heldSearch = null
        openLocating(waiting, saved = null)
    }

    // ── Search ────────────────────────────────────────────────────────────

    /**
     * Recents row tapped (here or from history): an unlocked result opens directly, a locked one
     * returns to its preview without a new lookup, and old rows without a result refill the box.
     */
    private fun reopenRecent(recent: FreshHunt) {
        val stored = recent.report
        when {
            stored == null -> {
                pickedIso = recent.iso2
                applyCountry()
                binding.lvPhoneInput.setText(recent.digits)
                binding.lvPhoneInput.setSelection(recent.digits.length)
            }
            recent.unlocked -> requireContext().enterActivity(NumProfile2Activity.intent(requireContext(), stored))
            else -> openLocating(recent, saved = stored)
        }
    }

    private fun nationalDigits(typed: String): String {
        // "91 70460 24874" under +91 becomes 7046024874, so Recents doesn't double the code.
        val (iso, national) = NumNotesScanner.split(typed, pickedIso)
        return if (iso.equals(pickedIso, ignoreCase = true)) national else typed
    }

    private fun showLimitSheet() {
        requireContext().recordKeyEvent("search_limit_reached")
        HuntCeilingDrawer(
            requireActivity(),
            onExtraSearch = {
                refreshQuota()
                prepaidByAd = true
                submitSearch()
            },
            onPremium = { ceilingPaywall.launch(PlusActivity.intent(requireContext())) },
        ).show()
    }

    private fun submitSearch() {
        val typed = binding.lvPhoneInput.text?.filter { it.isDigit() }?.toString().orEmpty()
        val acceptable = typed.length >= SHORTEST_NUMBER && NumNotesScanner.isValid(typed, pickedIso)
        if (!acceptable) return warnInvalid(typed)
        val digits = nationalDigits(typed)
        dismissIme()
        // No free searches left today → rewarded ad for one more, or Premium.
        if (!HuntRation.canSearch(requireContext())) return showLimitSheet()

        val query = FreshHunt(digits, pickedIso, Realms.dialOf(pickedIso) ?: "1", System.currentTimeMillis())
        val ctx = requireContext()
        val locationOk = GEO_PERMS.any {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!locationOk) {
            heldSearch = query
            locationAsk.launch(GEO_PERMS)
            return
        }
        openLocating(query, saved = null)
    }

    /**
     * Opens Locating. A new search uses a free search and runs the lookup there; [saved]
     * reopens a locked recent one at its result.
     */
    private fun openLocating(query: FreshHunt, saved: NumDigest?) {
        val host = activity ?: return
        val isNew = saved == null
        if (isNew) {
            HuntRation.consume(host)
            refreshQuota()
        }
        // Previously unlocked numbers stay unlocked; an ad-bought search skips the unlock ad.
        val unlockedEarlier = recentShelf.all().any { it.digits == query.digits && it.dial == query.dial && it.unlocked }
        val skipUnlock = unlockedEarlier || (isNew && prepaidByAd)
        if (isNew) prepaidByAd = false
        locatingLauncher.launch(PinpointingActivity.intent(host, query, saved, skipUnlock))
    }

    /** "Free searches today · n of 3 left" with its bar; hidden for unlimited users. */
    private fun refreshQuota() {
        val b = _binding ?: return
        val ctx = requireContext()
        val unlimited = HuntRation.isUnlimited(ctx)
        b.rationRow.isVisible = !unlimited
        if (unlimited) return
        val cap = HuntRation.limit(ctx)
        val remaining = HuntRation.remaining(ctx)
        b.txtQuota.text = getString(R.string.ds_quota_left, remaining, cap)
        b.rationBar.progress = 100 * remaining.coerceAtMost(cap) / cap.coerceAtLeast(1)
    }

    /** Explains the invalid number, with Edit number and Change country actions. */
    private fun warnInvalid(digits: String) {
        val ctx = requireContext()
        val input = binding.lvPhoneInput
        val shown = input.text?.toString()?.trim().orEmpty().ifEmpty { digits }
        BrokenNumDrawer(
            requireActivity(),
            number = "+" + (Realms.dialOf(pickedIso) ?: "") + " " + shown,
            country = Realms.byIso(pickedIso)?.name ?: pickedIso,
            onEdit = {
                input.requestFocus()
                placeCursorAtEnd()
                ViewCompat.getWindowInsetsController(input)?.show(WindowInsetsCompat.Type.ime())
            },
            onChangeCountry = { showCountryPicker() },
        ).show()
        ctx.recordKeyEvent("search_invalid_number")
    }

    private fun dismissIme() {
        val input = binding.lvPhoneInput
        input.clearFocus()
        ViewCompat.getWindowInsetsController(input)?.hide(WindowInsetsCompat.Type.ime())
    }

    private fun reloadRecents() {
        val b = _binding ?: return
        val items = recentShelf.all()
        val hasItems = items.isNotEmpty()
        recentsListAdapter.submitList(items)
        b.blankState.isVisible = !hasItems
        b.listRecents.isVisible = hasItems
        b.actClearAll.isVisible = hasItems
        // The banner frame is forced visible only when it holds a real ad; otherwise the ad
        // layer decides (forcing it would expose the loading skeleton).
        val frame = b.root.findViewById<FrameLayout>(R.id.ribbonAdFrame)
        val adInside = frame != null && frameHasAd(frame)
        if (!hasItems) {
            frame?.isVisible = false
            bannerInFlight = false
        } else if (adInside) {
            frame?.isVisible = true
        }
        b.spotBannerDivider.isVisible = hasItems && adInside && frame?.isVisible == true
        if (hasItems && visibleToUser) requestBanner()
    }

    private companion object {
        const val SHORTEST_NUMBER = 6

        val GEO_PERMS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        /** Same ScreenAds entry as the standalone home, so one switch covers both. */
        const val HOME_BANNER_KEY = "CoreHubActivity"
    }
}
