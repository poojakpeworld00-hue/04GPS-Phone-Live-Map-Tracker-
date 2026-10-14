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

class TongueSelectorActivity : StageActivity<ScreenLanguageBinding>() {
    override val layoutId: Int = R.layout.screen_language

    private val lingoVm: LingoViewModel by viewModels()
    private val lockerStore by lazy { LockerBook(this) }

    private val fromSettings by lazy { intent.getBooleanExtra(EXTRA_STANDALONE, false) }

    private lateinit var regionAdapter: LingoAdapter
    private lateinit var fullAdapter: LingoAdapter

    private var hasLeft = false
    private var backAdvancing = false

    override fun initView() {
        if (!fromSettings) OpeningUnveilCharter.flagShown(this, OpeningUnveilProfile.LANGUAGE)

        ViewCompat.setOnApplyWindowInsetsListener(binding.tongueRoot) { root, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        val startTag = CoreLocker.language(this) ?: CoreLocker.LANGUAGE_DEFAULT
        lingoVm.init(startTag)

        HomeSpotsProfile.presentSlot(
            activity = this,
            slot = HomeSpotsProfile.onboardingSlot(this, HomeSpotsProfile.OnboardScreen.LANGUAGE),
            container = binding.spotNativeFrame,
            shimmer = binding.spotShimmer,
        )
        binding.spotNativeDivider.followAdContainer(binding.spotNativeFrame)

        when {
            fromSettings -> binding.root.findViewById<View>(R.id.induct_step_header).isVisible = false
            else -> HomeStream.wireStepHeader(this, HomeSpotsProfile.OnboardScreen.LANGUAGE, binding.root)
        }

        seedRegion()

        regionAdapter = makeAdapter(startTag)
        fullAdapter = makeAdapter(startTag)
        with(binding) {
            listSuggested.layoutManager = LinearLayoutManager(this@TongueSelectorActivity)
            listSuggested.adapter = regionAdapter
            listLanguages.layoutManager = LinearLayoutManager(this@TongueSelectorActivity)
            listLanguages.adapter = fullAdapter
            actInfo.setOnClickListener { showLanguageInfo() }
            CraftMovement.pressScale(actContinue)
            actContinue.setOnClickListener { confirmChoice() }
        }

        if (fromSettings) return

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val host = this@TongueSelectorActivity
                when {
                    !HomeStream.backMovesForward(host) -> HomeStream.passBackThrough(host, this)
                    backAdvancing -> Unit
                    else -> {
                        backAdvancing = true
                        confirmChoice()
                    }
                }
            }
        })
    }

    override fun initObservers() {
        lingoVm.selectedTag.observe(this) { picked ->
            regionAdapter.setSelected(picked)
            fullAdapter.setSelected(picked)
            bounceDone()
        }
        lingoVm.suggested.observe(this) { items ->
            regionAdapter.submitList(items)
            val any = items.isNotEmpty()
            binding.txtSuggested.isVisible = any
            binding.listSuggested.isVisible = any
        }
        lingoVm.others.observe(this) { items ->
            fullAdapter.submitList(items)
            val any = items.isNotEmpty()
            binding.txtAll.isVisible = any
            binding.listLanguages.isVisible = any
        }
    }

    private fun makeAdapter(startTag: String): LingoAdapter =
        LingoAdapter { element: LingoElement -> lingoVm.select(element.tag) }.also { it.setCurrent(startTag) }

    private fun seedRegion() {
        val local = localCountryCode()
        WardenRail.log(TAG, "resolveRegion: device=$local (sync seed)")
        lingoVm.enactCountry(local)
        refineRegionByIp()
    }

    private fun localCountryCode(): String? {
        val telephony = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val candidates = sequenceOf<() -> String?>(
            { telephony?.simCountryIso },
            { telephony?.networkCountryIso },
            { resources.configuration.locales[0].country },
        )
        return candidates.map { it() }.firstOrNull { !it.isNullOrBlank() }?.uppercase()
    }

    private fun refineRegionByIp() {
        lifecycleScope.launch {
            val found = ZoneSpotter.senseCountry(this@TongueSelectorActivity)
            if (found == null) {
                WardenRail.log(TAG, "IP geo unavailable → keeping device seed")
                return@launch
            }
            WardenRail.log(TAG, "IP refine → country=${found.iso}")
            lingoVm.enactCountry(found.iso)
        }
    }

    private fun bounceDone() {
        binding.actContinue.run {
            animate().cancel()
            scaleX = 0.8f
            scaleY = 0.8f
            animate()
                .scaleX(1f).scaleY(1f)
                .setInterpolator(OvershootInterpolator(3f))
                .setDuration(260L)
                .start()
        }
    }

    private fun showLanguageInfo() {
        val content = layoutInflater.inflate(R.layout.popup_language_info, binding.tongueRoot, false)
        val dlg = MaterialAlertDialogBuilder(this).setView(content).create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        content.findViewById<View>(R.id.actGotIt).setOnClickListener { dlg.dismiss() }
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.85f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun confirmChoice() {
        val chosen = lingoVm.selectedTag.value ?: CoreLocker.LANGUAGE_DEFAULT
        CoreLocker.setLanguage(this, chosen)
        lockerStore.isLanguageSelected = true

        if (fromSettings) {
            LingoBook.apply(chosen)
            finish()
            return
        }

        ConsentDriver.check(this) {
            val next = when {
                !fromSettings && HomeStream.isOnboardingActive(this) -> HomeStream.nextActivity(this)
                OpeningUnveilCharter.mustShowTerms(this) -> PolicyPortalActivity::class.java
                OpeningUnveilCharter.mustShowOnboarding(this) -> BriefingActivity::class.java
                else -> HomeStream.homeActivity()
            }
            val goingHome = next == HomeStream.homeActivity()
            if (goingHome) HomeStream.flagOnboardingCompleted(this)
            val target = when {
                !fromSettings && HomeStream.isOnboardingActive(this) && !goingHome ->
                    HomeStream.onboardingIntent(this, next)
                goingHome -> MemoOpeningActivity.wrap(this, Intent(this, next))
                else -> Intent(this, next)
            }
            HomeSpotsProfile.execOnboardingInter(this, HomeSpotsProfile.OnboardScreen.LANGUAGE) {
                if (hasLeft) return@execOnboardingInter
                hasLeft = true

                val activeTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                if (chosen != activeTags) LingoBook.apply(chosen)
                startActivity(target)
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "TongueSelectorActivity"
        private const val EXTRA_STANDALONE = "extra_standalone"

        fun newIntent(context: Context, standalone: Boolean = false): Intent =
            Intent(context, TongueSelectorActivity::class.java).putExtra(EXTRA_STANDALONE, standalone)
    }
}
