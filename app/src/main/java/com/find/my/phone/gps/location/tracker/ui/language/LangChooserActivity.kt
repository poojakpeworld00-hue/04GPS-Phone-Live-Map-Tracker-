package com.find.my.phone.gps.location.tracker.ui.language

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
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.adcast.domain.LauncherAdsConfig
import com.find.my.phone.gps.location.tracker.base.CanvasActivity
import com.find.my.phone.gps.location.tracker.data.LocaleRegistry
import com.find.my.phone.gps.location.tracker.data.RegionLocator
import com.find.my.phone.gps.location.tracker.data.VaultRegistry
import com.find.my.phone.gps.location.tracker.databinding.ViewLanguageBinding
import com.find.my.phone.gps.location.tracker.onboard.LauncherFlow
import com.find.my.phone.gps.location.tracker.onboard.ReminderIntroActivity
import com.find.my.phone.gps.location.tracker.permission.AccessEngine
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.find.my.phone.gps.location.tracker.ui.intro.IntroRevealConfig
import com.find.my.phone.gps.location.tracker.ui.intro.IntroRevealPolicy
import com.find.my.phone.gps.location.tracker.ui.onboarding.PrimerActivity
import com.find.my.phone.gps.location.tracker.ui.terms.TermsGateActivity
import com.find.my.phone.gps.location.tracker.util.AppVault
import com.find.my.phone.gps.location.tracker.util.GuardRail
import com.find.my.phone.gps.location.tracker.util.followAdContainer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** Language picker (design 4c): Done confirms, the big native ad is pinned below. */
class LangChooserActivity : CanvasActivity<ViewLanguageBinding>() {

    override val layoutId: Int = R.layout.view_language

    private val viewModel: LocaleViewModel by viewModels()
    private val prefs by lazy { VaultRegistry(this) }

    /** True when opened from Settings to change language (vs. the first-run flow). */
    private val standalone by lazy { intent.getBooleanExtra(EXTRA_STANDALONE, false) }

    private lateinit var suggestedAdapter: LocaleAdapter
    private lateinit var allAdapter: LocaleAdapter

    private var forwarding = false
    private var navigated = false

    override fun initView() {
        if (!standalone) IntroRevealPolicy.markShown(this, IntroRevealConfig.LANGUAGE)

        ViewCompat.setOnApplyWindowInsetsListener(binding.languageRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val current = AppVault.language(this) ?: AppVault.LANGUAGE_DEFAULT
        viewModel.init(current)

        // Big native under the list, `launcher_ads.onboarding.language.slot`.
        LauncherAdsConfig.showSlot(
            activity = this,
            slot = LauncherAdsConfig.onboardingSlot(this, LauncherAdsConfig.OnboardScreen.LANGUAGE),
            container = binding.adNativeFrame,
            shimmer = binding.adShimmer,
        )
        binding.adNativeDivider.followAdContainer(binding.adNativeFrame)

        if (!standalone) {
            LauncherFlow.bindStepHeader(this, LauncherAdsConfig.OnboardScreen.LANGUAGE, binding.root)
        } else {
            binding.root.findViewById<View>(R.id.onboarding_step_header).isVisible = false
        }

        // Resolve the region FIRST, so the Suggested list is right on the very first frame.
        resolveRegion()

        val onPick: (LocaleItem) -> Unit = { viewModel.select(it.tag) }
        suggestedAdapter = LocaleAdapter(onPick).apply { setCurrent(current) }
        allAdapter = LocaleAdapter(onPick).apply { setCurrent(current) }
        binding.rvSuggested.layoutManager = LinearLayoutManager(this)
        binding.rvSuggested.adapter = suggestedAdapter
        binding.rvLanguages.layoutManager = LinearLayoutManager(this)
        binding.rvLanguages.adapter = allAdapter

        binding.btnInfo.setOnClickListener { showInfoDialog() }
        DesignMotion.pressScale(binding.btnContinue)
        binding.btnContinue.setOnClickListener { onContinue() }

        // First-run flow: Back must not exit the app for paid users; it advances like Done.
        if (!standalone) {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!LauncherFlow.backMovesForward(this@LangChooserActivity)) {
                        LauncherFlow.passBackThrough(this@LangChooserActivity, this)
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
            binding.tvSuggested.isVisible = it.isNotEmpty()
            binding.rvSuggested.isVisible = it.isNotEmpty()
        }
        viewModel.others.observe(this) {
            allAdapter.submitList(it)
            binding.tvAll.isVisible = it.isNotEmpty()
            binding.rvLanguages.isVisible = it.isNotEmpty()
        }
    }

    private fun resolveRegion() {
        val device = deviceCountry()
        GuardRail.log(TAG, "resolveRegion: device=$device (sync seed)")
        viewModel.applyCountry(device)
        detectCountryByIp()
    }

    private fun deviceCountry(): String? {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val sim = tm?.simCountryIso?.takeIf { it.isNotBlank() }
        val network = tm?.networkCountryIso?.takeIf { it.isNotBlank() }
        val locale = resources.configuration.locales[0].country.takeIf { it.isNotBlank() }
        return (sim ?: network ?: locale)?.uppercase()
    }

    private fun detectCountryByIp() {
        lifecycleScope.launch {
            val geo = RegionLocator.detectCountry(this@LangChooserActivity) ?: run {
                GuardRail.log(TAG, "IP geo unavailable → keeping device seed")
                return@launch
            }
            GuardRail.log(TAG, "IP refine → country=${geo.iso}")
            viewModel.applyCountry(geo.iso)
        }
    }

    /** Small spring on the Done pill each time the selection changes. */
    private fun popConfirm() {
        binding.btnContinue.animate().cancel()
        binding.btnContinue.scaleX = 0.8f
        binding.btnContinue.scaleY = 0.8f
        binding.btnContinue.animate()
            .scaleX(1f).scaleY(1f)
            .setInterpolator(OvershootInterpolator(3f))
            .setDuration(260L)
            .start()
    }

    private fun showInfoDialog() {
        val view = layoutInflater.inflate(R.layout.modal_language_info, binding.languageRoot, false)
        val dialog = MaterialAlertDialogBuilder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<View>(R.id.btnGotIt).setOnClickListener { dialog.dismiss() }
        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 0.85f).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun onContinue() {
        val tag = viewModel.selectedTag.value ?: AppVault.LANGUAGE_DEFAULT
        AppVault.setLanguage(this, tag)
        prefs.isLanguageSelected = true

        if (standalone) {
            LocaleRegistry.apply(tag)
            finish()
            return
        }

        // First-run flow: permission(s) first, then the exit interstitial, then navigate, then
        // apply the locale (applying it recreates this Activity).
        AccessEngine.check(this) {
            val next = when {
                !standalone && LauncherFlow.isOnboardingActive(this) -> LauncherFlow.nextActivity(this)
                IntroRevealPolicy.shouldShowTerms(this) -> TermsGateActivity::class.java
                IntroRevealPolicy.shouldShowOnboarding(this) -> PrimerActivity::class.java
                else -> LauncherFlow.homeActivity()
            }
            if (next == LauncherFlow.homeActivity()) {
                LauncherFlow.markOnboardingCompleted(this)
            }
            val intent = when {
                !standalone && LauncherFlow.isOnboardingActive(this) && next != LauncherFlow.homeActivity() ->
                    LauncherFlow.onboardingIntent(this, next)
                // Later launches: the reminders intro goes right before Home when it is due.
                next == LauncherFlow.homeActivity() -> ReminderIntroActivity.wrap(this, Intent(this, next))
                else -> Intent(this, next)
            }
            LauncherAdsConfig.runOnboardingInter(this, LauncherAdsConfig.OnboardScreen.LANGUAGE) {
                if (navigated) return@runOnboardingInter
                navigated = true
                // Apply the locale BEFORE starting the next screen, so it inflates in the chosen
                // language instead of the old one (the recreate this triggers never lands: this
                // Activity finishes in the same block).
                if (tag != AppCompatDelegate.getApplicationLocales().toLanguageTags()) {
                    LocaleRegistry.apply(tag)
                }
                startActivity(intent)
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "LangChooserActivity"
        private const val EXTRA_STANDALONE = "extra_standalone"

        /** Standalone = opened from Settings to change language (returns on Done). */
        fun newIntent(context: Context, standalone: Boolean = false): Intent =
            Intent(context, LangChooserActivity::class.java).putExtra(EXTRA_STANDALONE, standalone)
    }
}
