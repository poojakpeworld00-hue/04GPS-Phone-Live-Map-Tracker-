package com.livemap.homedeck.parts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import androidx.core.view.GestureDetectorCompat
import androidx.core.widget.doAfterTextChanged
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.normalizeString
import com.livemap.homedeck.R
import com.livemap.homedeck.screens.HomeDeck
import com.livemap.homedeck.binders.DeckProgramsFeed
import com.livemap.homedeck.databinding.HdLeftPanelFragmentBinding
import com.livemap.homedeck.ext.fireApp
import com.livemap.homedeck.entities.CoreHome
import com.livemap.homedeck.teaser.HomeSpotsProfile
import com.livemap.homedeck.contract.HomeBook
import com.livemap.homedeck.entities.appLauncherComparator
import kotlin.math.abs

class ProgramsDeckPlane(
    context: Context,
    attributeSet: AttributeSet,
) : HomePlane<HdLeftPanelFragmentBinding>(context, attributeSet) {
    private var launchers = emptyList<CoreHome>()
    private var resultsCap = COLLAPSED_RESULTS

    private lateinit var suggestedAdapter: DeckProgramsFeed
    private lateinit var recentAdapter: DeckProgramsFeed
    private lateinit var resultsAdapter: DeckProgramsFeed
    private lateinit var searchInAdapter: DeckProgramsFeed

    private val gestureDetector = GestureDetectorCompat(context, object : SimpleOnGestureListener() {
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            if (velocityX > 0 && abs(velocityX) > abs(velocityY)) {
                activity?.concealLeftPanel()
                return true
            }

            return false
        }
    })

    override fun setupFragment(activity: HomeDeck) {
        this.activity = activity
        this.binding = HdLeftPanelFragmentBinding.bind(this)

        suggestedAdapter = DeckProgramsFeed(R.layout.hd_item_panel_grid_app, ::fireLauncher)
        recentAdapter = DeckProgramsFeed(R.layout.hd_item_panel_grid_app, ::fireLauncher)
        resultsAdapter = DeckProgramsFeed(R.layout.hd_item_panel_result, ::fireLauncher)
        searchInAdapter = DeckProgramsFeed(R.layout.hd_item_panel_search_in, ::searchInApp)

        binding.deckSuggestedGridUi.adapter = suggestedAdapter
        binding.deckRecentGridUi.adapter = recentAdapter
        binding.deckResultsListUi.adapter = resultsAdapter
        binding.deckSearchInListUi.adapter = searchInAdapter

        binding.deckSearchUi.doAfterTextChanged {
            resultsCap = COLLAPSED_RESULTS
            reviseSections()
        }

        binding.deckSearchClearUi.setOnClickListener {
            binding.deckSearchUi.setText("")
        }

        binding.deckSeeMoreUi.setOnClickListener {
            resultsCap = if (resultsCap == COLLAPSED_RESULTS) {
                EXPANDED_RESULTS
            } else {
                COLLAPSED_RESULTS
            }
            reviseSections()
        }
    }

    fun onOpened() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        binding.deckGreetingUi.setText(
            when {
                hour < 12 -> R.string.launcher_greeting_morning
                hour < 17 -> R.string.launcher_greeting_afternoon
                else -> R.string.launcher_greeting_evening
            }
        )
        val locale = java.util.Locale.getDefault()
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
        binding.deckDateUi.text = java.text.SimpleDateFormat(pattern, locale).format(java.util.Date())
        com.livemap.homedeck.anim.InductionMovement.enter(
            null,
            binding.deckHeaderUi,
            binding.deckSearchHolderUi,
            binding.deckSuggestedHeaderUi,
            binding.deckSuggestedGridUi,
            binding.deckRecentHeaderUi,
            binding.deckRecentGridUi,
        )
    }

    fun renewAds() {
        val activity = activity ?: return
        HomeBook.ads.wireNativeOnOpen(
            activity, HomeSpotsProfile.KEY_PANEL_MIDDLE, binding.deckMidNativeFrameUi
        )
        HomeBook.ads.wireNativeOnOpen(
            activity, HomeSpotsProfile.KEY_PANEL_BOTTOM, binding.deckNativeFrameUi
        )
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    fun gotLaunchers(appLaunchers: List<CoreHome>) {
        launchers = appLaunchers.sortedWith(appLauncherComparator)
        activity?.runOnUiThread {
            reviseSections()
        }
    }

    fun hasQuery() = getQuery().isNotEmpty()

    fun rewindSearch() {
        binding.deckSearchUi.setText("")
        binding.deckScrollUi.scrollTo(0, 0)
    }

    private fun getQuery() = binding.deckSearchUi.text.toString().trim()

    private fun reviseSections() {
        val query = getQuery()
        val hasQuery = query.isNotEmpty()

        binding.deckSearchClearUi.beVisibleIf(hasQuery)
        binding.deckSuggestedHeaderUi.beVisibleIf(!hasQuery)
        binding.deckSuggestedGridUi.beVisibleIf(!hasQuery)

        if (hasQuery) {
            val results = launchers.filter {
                it.title.normalizeString().contains(query.normalizeString(), ignoreCase = true)
            }

            binding.deckRecentHeaderUi.beGone()
            binding.deckRecentGridUi.beGone()
            binding.deckResultsHeaderUi.beVisible()
            binding.deckResultsListUi.beVisible()
            binding.deckSeeMoreUi.beVisibleIf(results.size > COLLAPSED_RESULTS)
            binding.deckSeeMoreUi.setText(
                if (resultsCap == COLLAPSED_RESULTS) R.string.launcher_see_more else R.string.launcher_see_less
            )
            resultsAdapter.submitList(results.take(resultsCap))

            val searchTargets = launchers.filter { it.packageName in SEARCH_IN_PACKAGES }
            binding.deckSearchInHeaderUi.beVisibleIf(searchTargets.isNotEmpty())
            binding.deckSearchInListUi.beVisibleIf(searchTargets.isNotEmpty())
            searchInAdapter.submitList(searchTargets)
        } else {
            val recent = launchers.drop(SUGGESTED_COUNT).take(RECENT_COUNT)
            binding.deckResultsHeaderUi.beGone()
            binding.deckResultsListUi.beGone()
            binding.deckSearchInHeaderUi.beGone()
            binding.deckSearchInListUi.beGone()
            binding.deckRecentHeaderUi.beVisibleIf(recent.isNotEmpty())
            binding.deckRecentGridUi.beVisibleIf(recent.isNotEmpty())

            suggestedAdapter.submitList(launchers.take(SUGGESTED_COUNT))
            recentAdapter.submitList(recent)
        }
    }

    private fun fireLauncher(launcher: CoreHome) {
        if (launcher.packageName == context.applicationContext.packageName) {
            activity?.enterHostApp(launcher.activityName)
            return
        }
        activity?.fireApp(launcher.packageName, launcher.activityName)
        activity?.concealLeftPanel()
    }

    private fun searchInApp(launcher: CoreHome) {
        val query = getQuery()
        val uri = when (launcher.packageName) {
            PACKAGE_MAPS -> "geo:0,0?q=${Uri.encode(query)}"
            PACKAGE_PLAY_STORE -> "market://search?q=${Uri.encode(query)}"
            else -> "https://www.google.com/search?q=${Uri.encode(query)}"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(launcher.packageName)
        }

        try {
            activity?.startActivity(intent)
            activity?.concealLeftPanel()
        } catch (_: ActivityNotFoundException) {
            fireLauncher(launcher)
        }
    }

    companion object {
        private const val SUGGESTED_COUNT = 8
        private const val RECENT_COUNT = 4
        private const val COLLAPSED_RESULTS = 4
        private const val EXPANDED_RESULTS = 8

        private const val PACKAGE_CHROME = "com.android.chrome"
        private const val PACKAGE_MAPS = "com.google.android.apps.maps"
        private const val PACKAGE_PLAY_STORE = "com.android.vending"
        private val SEARCH_IN_PACKAGES =
            setOf(PACKAGE_CHROME, PACKAGE_MAPS, PACKAGE_PLAY_STORE)
    }
}
