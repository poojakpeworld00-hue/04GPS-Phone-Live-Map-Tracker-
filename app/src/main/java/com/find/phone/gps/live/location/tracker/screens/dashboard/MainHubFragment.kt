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

class MainHubFragment : Fragment() {
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

    private var pickedIso: String = "US"

    private var digitGrouper: PhoneNumberFormattingTextWatcher? = null

    private var visibleToUser = false

    private var nativeLoadedOnce = false

    private var bannerInFlight = false

    private var navInset = 0

    private var heldSearch: FreshHunt? = null

    private var prepaidByAd = false

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

    private val locationAsk = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
        if (true in answers.values) context?.let(PlaceDiary::log)
        askContactsThenProceed()
    }

    private val searchContactsAsk = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        kickPhonebookUpload()
        releaseHeldSearch()
    }

    private val ceilingPaywall = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshQuota()
        if (PlusCheckout.isPremium(requireContext())) submitSearch()
    }

    private val locatingLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val answered = res.data?.getBooleanExtra(PinpointingActivity.EXTRA_FOUND, false) == true
        if (answered) _binding?.lvPhoneInput?.setText("")
        reloadRecents()
        refreshQuota()
        refreshProChip()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
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

        binding.spotNativeCard.isVisible = false
        binding.root.findViewById<View>(R.id.ribbonAdFrame)?.isVisible = false
        if (visibleToUser) loadAdSlots()
    }

    override fun onResume() {
        super.onResume()

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

    fun setOnScreen(visible: Boolean) {
        visibleToUser = visible
        if (!visible || _binding == null) return
        loadAdSlots()
    }

    private fun installInsets(view: View) {
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

    private fun loadAdSlots() {
        val host = activity ?: return
        val slot = HomeSpotsProfile.rightPanelNativeSlot(host)
        val showNative = SpotsLocker.getInstance(host).getBoolean("IsAdsON") && slot.visible
        binding.spotNativeCard.isVisible = showNative
        if (showNative) {
            if (nativeLoadedOnce) {
                HomeSpotsProfile.renewSlot(host, slot, binding.spotNativeFrame, binding.spotShimmer)
            } else {
                nativeLoadedOnce = true
                HomeSpotsProfile.presentSlot(host, slot, binding.spotNativeFrame, binding.spotShimmer)
            }
        }
        requestBanner()
    }

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

    private fun frameHasAd(frame: FrameLayout): Boolean {
        val placeholder = frame.findViewById<View>(R.id.ribbonShimmer)
        for (i in 0 until frame.childCount) {
            if (frame.getChildAt(i) !== placeholder) return true
        }
        return false
    }

    private fun px(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun padForNavBar(view: View) {
        if (view.height == 0) return
        val pos = IntArray(2).also { view.getLocationInWindow(it) }
        val barTop = view.rootView.height - navInset
        val overlap = (pos[1] + view.height - barTop).coerceIn(0, navInset)
        if (view.paddingBottom == overlap) return
        view.updatePadding(bottom = overlap)
    }

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

    private fun hasContacts(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

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

    private fun launchBuddyPicker() {
        val ctx = context ?: return
        buddyPickerLauncher.launch(BuddyChooserActivity.intent(ctx))
        kickPhonebookUpload()
    }

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

        releaseHeldSearch()
    }

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

    private fun openLocating(query: FreshHunt, saved: NumDigest?) {
        val host = activity ?: return
        val isNew = saved == null
        if (isNew) {
            HuntRation.consume(host)
            refreshQuota()
        }

        val unlockedEarlier = recentShelf.all().any { it.digits == query.digits && it.dial == query.dial && it.unlocked }
        val skipUnlock = unlockedEarlier || (isNew && prepaidByAd)
        if (isNew) prepaidByAd = false
        locatingLauncher.launch(PinpointingActivity.intent(host, query, saved, skipUnlock))
    }

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

        const val HOME_BANNER_KEY = "CoreHubActivity"
    }
}
