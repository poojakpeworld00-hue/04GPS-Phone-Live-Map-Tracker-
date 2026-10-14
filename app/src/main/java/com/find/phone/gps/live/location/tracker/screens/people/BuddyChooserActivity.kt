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
import android.view.View
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

class BuddyChooserActivity : StageActivity<ScreenContactPickerBinding>() {
    override val layoutId: Int = R.layout.screen_contact_picker

    private var everyone: List<BuddyNum> = emptyList()
    private var activeAccount: String? = null
    private var reverseOrder = false

    private val book by lazy { LockerBook(this) }
    private val starAdapter = PinnedAdapter { returnNumber(it) }
    private val contactsAdapter = BuddyAdapter(onLocate = { returnNumber(it) }, onCall = { callNumber(it) })

    private val contactsAsk = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        when (ok) {
            true -> revealContacts()
            false -> showGrantGate()
        }
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.lvContactsRoot) { root, insets ->
            insets.getInsets(WindowInsetsCompat.Type.systemBars()).let { sb ->
                root.updatePadding(left = sb.left, top = sb.top, right = sb.right, bottom = sb.bottom)
            }
            insets
        }

        binding.listContacts.apply {
            layoutManager = LinearLayoutManager(this@BuddyChooserActivity)
            adapter = contactsAdapter
        }
        binding.listFavorites.apply {
            layoutManager = LinearLayoutManager(this@BuddyChooserActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = starAdapter
        }

        binding.huntInput.doAfterTextChanged { typed ->
            binding.actClearSearch.isVisible = !typed.isNullOrEmpty()
            applyQuery()
        }
        binding.actClearSearch.setOnClickListener { binding.huntInput.setText("") }
        binding.lvAccountChip.setOnClickListener { openAccountPopup() }
        binding.actTools.setOnClickListener { openToolsPopup() }
        binding.actAllow.setOnClickListener { askForContacts() }
        arrayOf<View>(binding.lvAccountChip, binding.actTools, binding.actAllow).forEach(CraftMovement::pressScale)
        attachRailTouch()

        if (contactsGranted()) revealContacts() else showGrantGate()
    }

    override fun onResume() {
        super.onResume()

        if (!binding.grantState.isVisible) return
        if (contactsGranted()) revealContacts()
    }

    private fun contactsGranted(): Boolean = PackageManager.PERMISSION_GRANTED ==
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)

    private fun askForContacts() {
        val readContacts = Manifest.permission.READ_CONTACTS
        if (!book.hasRequestedPermission(readContacts)) {
            book.flagPermissionRequested(readContacts)
            contactsAsk.launch(readContacts)
            return
        }
        if (shouldShowRequestPermissionRationale(readContacts)) {
            contactsAsk.launch(readContacts)
            return
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun showGrantGate() = with(binding) {
        grantState.isVisible = true
        lvListGroup.isVisible = false
        lvRailBox.isVisible = false
    }

    private fun revealContacts() {
        with(binding) {
            grantState.isVisible = false
            lvListGroup.isVisible = true
        }
        mountNativeAd()
        readPhoneBook()
    }

    private fun inScope(): List<BuddyNum> = everyone.filter { activeAccount == null || it.account == activeAccount }

    private fun readPhoneBook() {
        binding.spinLoading.isVisible = true
        binding.txtAccountCount.isVisible = false
        lifecycleScope.launch {
            everyone = withContext(Dispatchers.IO) {
                runCatching { GadgetBuddies.load(this@BuddyChooserActivity) }.getOrDefault(emptyList())
            }
            binding.spinLoading.isVisible = false
            val acc = activeAccount
            if (acc != null && everyone.none { it.account == acc }) activeAccount = null
            refreshAccountChip()
            applyQuery()
        }
    }

    private fun refreshAccountChip() {
        val uniquePeople = inScope().distinctBy { it.contactId }.size
        binding.txtAccount.text = activeAccount ?: getString(R.string.ds_contacts_all_accounts)
        binding.txtAccountCount.text = uniquePeople.toString()
        binding.txtAccountCount.isVisible = true
    }

    private fun matches(entry: BuddyNum, lowered: String, numeric: String): Boolean {
        if (entry.name.lowercase(Locale.getDefault()).contains(lowered)) return true
        return numeric.isNotEmpty() && entry.number.filter(Char::isDigit).contains(numeric)
    }

    private fun applyQuery() {
        if (binding.spinLoading.isVisible) return
        val term = binding.huntInput.text?.toString()?.trim().orEmpty()
        val lowered = term.lowercase(Locale.getDefault())
        val numeric = term.filter(Char::isDigit)
        val pool = inScope()
        val filtered = when {
            term.isEmpty() -> pool
            else -> pool.filter { matches(it, lowered, numeric) }
        }
        val visible = if (!reverseOrder) filtered else filtered.sortedWith(
            compareBy<BuddyNum> { it.letter == "#" }.thenByDescending { it.name.lowercase(Locale.getDefault()) }
        )
        contactsAdapter.lodgeContacts(visible) {
            binding.listContacts.scrollToPosition(0)
            buildLetterRail()
        }

        val starred = pool.filter { it.starred }.distinctBy { it.contactId }
        starAdapter.submitList(starred)
        binding.lvFavoritesGroup.isVisible = term.isEmpty() && starred.isNotEmpty()
        binding.txtFavoritesCount.text = starred.size.toString()

        if (everyone.isEmpty()) {
            showBlank(R.string.ds_contacts_empty, R.string.ds_contacts_empty_body)
        } else if (visible.isEmpty()) {
            showBlank(R.string.ds_contacts_no_match, R.string.ds_contacts_no_match_body)
        } else {
            binding.blankState.isVisible = false
        }
        binding.lvRailBox.isVisible = visible.isNotEmpty()
    }

    private fun showBlank(headline: Int, detail: Int) {
        binding.txtEmpty.setText(headline)
        binding.txtEmptyBody.setText(detail)
        binding.blankState.isVisible = true
    }

    private fun accountIcon(name: String): Int = when (name) {
        BuddyNum.ACCOUNT_PHONE -> R.drawable.lv_ic_phone
        BuddyNum.ACCOUNT_SIM -> R.drawable.lv_ic_contact_card
        else -> R.drawable.lv_ic_globe
    }

    private fun openAccountPopup() {
        val unique = everyone.distinctBy { it.contactId }
        val perAccount = unique.groupingBy { it.account }.eachCount()
        val menu = KitFlyout(this)
        menu.add(
            label = getString(R.string.ds_contacts_all_accounts),
            icon = R.drawable.lv_ic_contact_card,
            badge = unique.size.toString(),
            selected = activeAccount == null,
        ) { chooseAccount(null) }
        for (accName in perAccount.keys.sorted()) {
            menu.add(
                label = accName,
                icon = accountIcon(accName),
                badge = perAccount.getValue(accName).toString(),
                selected = activeAccount == accName,
            ) { chooseAccount(accName) }
        }
        menu.show(binding.lvAccountChip, Gravity.START)
    }

    private fun chooseAccount(accName: String?) {
        activeAccount = accName
        refreshAccountChip()
        applyQuery()
    }

    private fun openToolsPopup() {
        val sortLabel = if (reverseOrder) R.string.ds_contacts_sort_az else R.string.ds_contacts_sort_za
        KitFlyout(this)
            .add(label = getString(sortLabel), icon = R.drawable.lv_ic_grid) {
                reverseOrder = !reverseOrder
                applyQuery()
            }
            .add(label = getString(R.string.ds_contacts_refresh), icon = R.drawable.lv_ic_history) { readPhoneBook() }
            .show(binding.actTools, Gravity.END)
    }

    private fun returnNumber(entry: BuddyNum) {
        val data = Intent().putExtra(EXTRA_NUMBER, entry.number)
        setResult(RESULT_OK, data)
        finish()
    }

    private fun callNumber(entry: BuddyNum) {
        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${entry.number}"))
        runCatching { startActivity(dialIntent) }
    }

    private fun mountNativeAd() {
        val enabled = SpotsLocker.getInstance(this).getBoolean("IsAdsON") &&
            DisplayTeaserProfile.resolve(this, javaClass.simpleName).show
        binding.spotCard.isVisible = enabled
        if (!enabled) return
        EmbeddedTeaserRibbon().presentNativeBannerNative(this, binding.spotNativeFrame, binding.spotShimmer)
    }

    private fun railLabel(letter: String, face: android.graphics.Typeface?): TextView = TextView(this).apply {
        text = letter
        gravity = Gravity.CENTER
        typeface = face
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
        setTextColor(ContextCompat.getColor(context, R.color.ds_primary))
        setPadding(px(4), px(1), px(4), px(1))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun buildLetterRail() {
        binding.lvRail.removeAllViews()
        val face = ResourcesCompat.getFont(this, R.font.figtree_bold)
        for (letter in contactsAdapter.letterPositions.keys) {
            binding.lvRail.addView(railLabel(letter, face))
        }
    }

    private fun jumpToLetterAt(rail: View, y: Float) {
        val keys = contactsAdapter.letterPositions.keys.toList()
        if (keys.isEmpty() || rail.height <= 0) return
        val slot = ((y / rail.height) * keys.size).toInt().coerceIn(0, keys.lastIndex)
        val target = contactsAdapter.letterPositions[keys[slot]] ?: return
        (binding.listContacts.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(target, 0)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachRailTouch() {
        binding.lvRail.setOnTouchListener { rail, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    jumpToLetterAt(rail, ev.y)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    rail.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun px(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_NUMBER = "number"

        fun intent(context: Context): Intent = Intent(context, BuddyChooserActivity::class.java)
    }
}
