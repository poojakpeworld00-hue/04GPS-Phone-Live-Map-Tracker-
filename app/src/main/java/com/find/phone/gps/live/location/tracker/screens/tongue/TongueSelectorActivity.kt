package com.find.phone.gps.live.location.tracker.screens.tongue

import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile
import com.find.phone.gps.live.location.tracker.foundation.StageActivity
import com.find.phone.gps.live.location.tracker.store.LingoBook
import com.find.phone.gps.live.location.tracker.store.ZoneSpotter
import com.find.phone.gps.live.location.tracker.store.LockerBook
import com.find.phone.gps.live.location.tracker.databinding.ScreenLanguageBinding
import com.find.phone.gps.live.location.tracker.induction.HomeStream
import com.find.phone.gps.live.location.tracker.induction.MemoOpeningActivity
import com.find.phone.gps.live.location.tracker.consent.ConsentDriver
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.screens.opening.OpeningUnveilProfile
import com.find.phone.gps.live.location.tracker.screens.opening.OpeningUnveilCharter
import com.find.phone.gps.live.location.tracker.screens.welcome.BriefingActivity
import com.find.phone.gps.live.location.tracker.screens.policy.PolicyPortalActivity
import com.find.phone.gps.live.location.tracker.helpers.CoreLocker
import com.find.phone.gps.live.location.tracker.helpers.WardenRail
import com.find.phone.gps.live.location.tracker.helpers.followAdContainer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** Language picker (design 4c): Done confirms, the big native ad is pinned below. */
class TongueSelectorActivity : StageActivity<ScreenLanguageBinding>() {

    override val layoutId: Int = R.layout.screen_language

    private val viewModel: LingoViewModel by viewModels()
    private val prefs by lazy { LockerBook(this) }

    /** True when opened from Settings to change language (vs. the first-run flow). */
    private val standalone by lazy { intent.getBooleanExtra(EXTRA_STANDALONE, false) }

    private lateinit var suggestedAdapter: LingoAdapter
    private lateinit var allAdapter: LingoAdapter

    private var forwarding = false
    private var navigated = false

    override fun initView() {
        if (!standalone) OpeningUnveilCharter.flagShown(this, OpeningUnveilProfile.LANGUAGE)

        ViewCompat.setOnApplyWindowInsetsListener(binding.tongueRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val current = CoreLocker.language(this) ?: CoreLocker.LANGUAGE_DEFAULT
        viewModel.init(current)

        // Big native under the list, `launcher_ads.onboarding.language.slot`.
        HomeSpotsProfile.presentSlot(
            activity = this,
            slot = HomeSpotsProfile.onboardingSlot(this, HomeSpotsProfile.OnboardScreen.LANGUAGE),
            container = binding.spotNativeFrame,
            shimmer = binding.spotShimmer,
        )
        binding.spotNativeDivider.followAdContainer(binding.spotNativeFrame)

        if (!standalone) {
            HomeStream.wireStepHeader(this, HomeSpotsProfile.OnboardScreen.LANGUAGE, binding.root)
        } else {
            binding.root.findViewById<View>(R.id.induct_step_header).isVisible = false
        }

        // Resolve the region FIRST, so the Suggested list is right on the very first frame.
        settleRegion()

        val onPick: (LingoElement) -> Unit = { viewModel.select(it.tag) }
        suggestedAdapter = LingoAdapter(onPick).apply { setCurrent(current) }
        allAdapter = LingoAdapter(onPick).apply { setCurrent(current) }
        binding.listSuggested.layoutManager = LinearLayoutManager(this)
        binding.listSuggested.adapter = suggestedAdapter
        binding.listLanguages.layoutManager = LinearLayoutManager(this)
        binding.listLanguages.adapter = allAdapter

        binding.actInfo.setOnClickListener { presentInfoDialog() }
        CraftMovement.pressScale(binding.actContinue)
        binding.actContinue.setOnClickListener { onContinue() }

        // First-run flow: Back must not exit the app for paid users; it advances like Done.
        if (!standalone) {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!HomeStream.backMovesForward(this@TongueSelectorActivity)) {
                        HomeStream.passBackThrough(this@TongueSelectorActivity, this)
                        return
                    }
                    if (forwarding) return
                    forwarding = true
                    onContinue()
                }
            })
        }
    }

    override fun initObservers() {
        viewModel.selectedTag.observe(this) { tag ->
            suggestedAdapter.setSelected(tag)
            allAdapter.setSelected(tag)
            popConfirm()
        }
        viewModel.suggested.observe(this) {
            suggestedAdapter.submitList(it)
            binding.txtSuggested.isVisible = it.isNotEmpty()
            binding.listSuggested.isVisible = it.isNotEmpty()
        }
        viewModel.others.observe(this) {
            allAdapter.submitList(it)
            binding.txtAll.isVisible = it.isNotEmpty()
            binding.listLanguages.isVisible = it.isNotEmpty()
        }
    }

    private fun settleRegion() {
        val device = deviceCountry()
        WardenRail.log(TAG, "resolveRegion: device=$device (sync seed)")
        viewModel.enactCountry(device)
        senseCountryByIp()
    }

    private fun deviceCountry(): String? {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val sim = tm?.simCountryIso?.takeIf { it.isNotBlank() }
        val network = tm?.networkCountryIso?.takeIf { it.isNotBlank() }
        val locale = resources.configuration.locales[0].country.takeIf { it.isNotBlank() }
        return (sim ?: network ?: locale)?.uppercase()
    }

    private fun senseCountryByIp() {
        lifecycleScope.launch {
            val geo = ZoneSpotter.senseCountry(this@TongueSelectorActivity) ?: run {
                WardenRail.log(TAG, "IP geo unavailable → keeping device seed")
                return@launch
            }
            WardenRail.log(TAG, "IP refine → country=${geo.iso}")
            viewModel.enactCountry(geo.iso)
        }
    }

    /** Small spring on the Done pill each time the selection changes. */
    private fun popConfirm() {
        binding.actContinue.animate().cancel()
        binding.actContinue.scaleX = 0.8f
        binding.actContinue.scaleY = 0.8f
        binding.actContinue.animate()
            .scaleX(1f).scaleY(1f)
            .setInterpolator(OvershootInterpolator(3f))
            .setDuration(260L)
            .start()
    }

    private fun presentInfoDialog() {
        val view = layoutInflater.inflate(R.layout.popup_language_info, binding.tongueRoot, false)
        val dialog = MaterialAlertDialogBuilder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<View>(R.id.actGotIt).setOnClickListener { dialog.dismiss() }
        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 0.85f).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun onContinue() {
        val tag = viewModel.selectedTag.value ?: CoreLocker.LANGUAGE_DEFAULT
        CoreLocker.setLanguage(this, tag)
        prefs.isLanguageSelected = true

        if (standalone) {
            LingoBook.apply(tag)
            finish()
            return
        }

        // First-run flow: permission(s) first, then the exit interstitial, then navigate, then
        // apply the locale (applying it recreates this Activity).
        ConsentDriver.check(this) {
            val next = when {
                !standalone && HomeStream.isOnboardingActive(this) -> HomeStream.nextActivity(this)
                OpeningUnveilCharter.mustShowTerms(this) -> PolicyPortalActivity::class.java
                OpeningUnveilCharter.mustShowOnboarding(this) -> BriefingActivity::class.java
                else -> HomeStream.homeActivity()
            }
            if (next == HomeStream.homeActivity()) {
                HomeStream.flagOnboardingCompleted(this)
            }
            val intent = when {
                !standalone && HomeStream.isOnboardingActive(this) && next != HomeStream.homeActivity() ->
                    HomeStream.onboardingIntent(this, next)
                // Later launches: the reminders intro goes right before Home when it is due.
                next == HomeStream.homeActivity() -> MemoOpeningActivity.wrap(this, Intent(this, next))
                else -> Intent(this, next)
            }
            HomeSpotsProfile.execOnboardingInter(this, HomeSpotsProfile.OnboardScreen.LANGUAGE) {
                if (navigated) return@execOnboardingInter
                navigated = true
                // Apply the locale BEFORE starting the next screen, so it inflates in the chosen
                // language instead of the old one (the recreate this triggers never lands: this
                // Activity finishes in the same block).
                if (tag != AppCompatDelegate.getApplicationLocales().toLanguageTags()) {
                    LingoBook.apply(tag)
                }
                startActivity(intent)
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "TongueSelectorActivity"
        private const val EXTRA_STANDALONE = "extra_standalone"

        /** Standalone = opened from Settings to change language (returns on Done). */
        fun newIntent(context: Context, standalone: Boolean = false): Intent =
            Intent(context, TongueSelectorActivity::class.java).putExtra(EXTRA_STANDALONE, standalone)
    }
}
