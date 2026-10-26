package com.find.phone.gps.live.location.tracker.screens.dashboard

import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.store.LookBook
import com.find.phone.gps.live.location.tracker.store.LockerBook
import com.find.phone.gps.live.location.tracker.store.peoplesync.BuddyRelay
import com.find.phone.gps.live.location.tracker.memo.MemoProfile
import com.find.phone.gps.live.location.tracker.memo.MemoShelf
import com.find.phone.gps.live.location.tracker.screens.memo.MemoListActivity
import kotlinx.coroutines.launch
import com.find.phone.gps.live.location.tracker.databinding.PartHomeDrawerBinding
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.screens.tongue.TongueSelectorActivity
import com.find.phone.gps.live.location.tracker.screens.tongue.LiveLocales
import com.find.phone.gps.live.location.tracker.helpers.CoreLocker
import com.find.phone.gps.live.location.tracker.helpers.enterPolicyLink
import com.find.phone.gps.live.location.tracker.helpers.enterTermLink
import com.find.phone.gps.live.location.tracker.helpers.rateApp
import com.find.phone.gps.live.location.tracker.helpers.spreadApp
import java.util.Locale

class MainSliderOptions(
    private val activity: AppCompatActivity,
    private val binding: PartHomeDrawerBinding,
    private val requestPhonePermission: (Array<String>) -> Unit = {},
    private val onNavigate: () -> Unit,
) {
    private val segments get() = listOf(binding.partLight, binding.partDark, binding.partSystem)

    private val myNumberCard by lazy {
        MineNumPlate(activity, binding.ownNumber, LockerBook(activity)) { requestPhonePermission(it) }
    }

    fun onPhonePermissionResult(result: Map<String, Boolean>) = myNumberCard.onPermissionResult(result)

    fun bind() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.sliderHeader) { v, insets ->
            v.updatePadding(top = dp(22) + insets.getInsets(WindowInsetsCompat.Type.systemBars()).top)
            insets
        }
        binding.txtVersion.text = activity.getString(R.string.ds_version_fmt, BuildConfig.VERSION_NAME)

        binding.lineLanguage.setOnClickListener {
            onNavigate()
            activity.startActivity(TongueSelectorActivity.newIntent(activity, standalone = true))
        }
        binding.lineReminders.setOnClickListener {
            onNavigate()
            activity.startActivity(MemoListActivity.intent(activity))
        }
        binding.lineRate.setOnClickListener { onNavigate(); activity.rateApp() }
        binding.lineShare.setOnClickListener { onNavigate(); activity.spreadApp() }
        binding.linePrivacy.setOnClickListener { onNavigate(); activity.enterPolicyLink() }
        binding.lineTerms.setOnClickListener { onNavigate(); activity.enterTermLink() }
        binding.lineDeleteContacts.setOnClickListener { onNavigate(); affirmDeleteContacts() }
        listOf(binding.lineLanguage, binding.lineReminders, binding.lineRate, binding.lineShare, binding.linePrivacy, binding.lineTerms, binding.lineDeleteContacts)
            .forEach { CraftMovement.pressScale(it) }

        binding.partLight.setOnClickListener { chooseTheme(CoreLocker.THEME_LIGHT, AppCompatDelegate.MODE_NIGHT_NO) }
        binding.partDark.setOnClickListener { chooseTheme(CoreLocker.THEME_DARK, AppCompatDelegate.MODE_NIGHT_YES) }
        binding.partSystem.setOnClickListener { chooseTheme(CoreLocker.THEME_SYSTEM, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }

        refresh()
    }

    private fun affirmDeleteContacts() {
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.ds_delete_contacts_title)
            .setMessage(R.string.ds_delete_contacts_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ds_delete_contacts_confirm) { _, _ ->
                activity.lifecycleScope.launch {
                    val deleted = BuddyRelay.eraseUploaded(activity)
                    val msg = if (deleted != null) R.string.ds_delete_contacts_done else R.string.ds_delete_contacts_failed
                    android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
                    binding.lineDeleteContacts.isVisible = BuddyRelay.hasUploaded(activity)
                }
            }
            .show()
    }

    fun watch(drawer: DrawerLayout) {
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerStateChanged(newState: Int) {
                if (newState != DrawerLayout.STATE_IDLE && !drawer.isDrawerOpen(GravityCompat.START)) {
                    dismissKeyboard(drawer)
                    refresh()
                }
            }
        })
    }

    private fun dismissKeyboard(drawer: DrawerLayout) {
        val focused = activity.currentFocus
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow((focused ?: drawer).windowToken, 0)
        focused?.clearFocus()
    }

    fun refresh() {
        binding.txtLanguageValue.text = currentLanguageName()

        val vault = SpotsLocker.getInstance(activity)
        binding.lineRate.isVisible = vault.getBoolean("is_rateus", true)
        binding.lineShare.isVisible = vault.getBoolean("is_share", true)

        binding.lineReminders.isVisible = MemoProfile.isSettingsRowAllowed(activity)
        val active = MemoShelf.activeCount(activity)
        binding.txtRemindersCount.isVisible = active > 0
        binding.txtRemindersCount.text = active.toString()

        binding.lineDeleteContacts.isVisible = BuddyRelay.hasUploaded(activity)
        val selected = when (CoreLocker.effectiveTheme(activity)) {
            CoreLocker.THEME_DARK -> binding.partDark
            CoreLocker.THEME_LIGHT -> binding.partLight
            else -> binding.partSystem
        }
        segments.forEach { styleSegment(it, it === selected) }
        myNumberCard.render()
    }

    private fun chooseTheme(theme: String, mode: Int) {
        if (CoreLocker.effectiveTheme(activity) == theme) return
        CoreLocker.setTheme(activity, theme)
        refresh()

        LookBook.apply(mode)
    }

    private fun styleSegment(view: TextView, selected: Boolean) {
        val color = ContextCompat.getColor(activity, if (selected) R.color.ds_primary else R.color.ds_muted)
        view.setTextColor(color)
        TextViewCompat.setCompoundDrawableTintList(view, android.content.res.ColorStateList.valueOf(color))
        view.typeface = ResourcesCompat.getFont(activity, if (selected) R.font.figtree_bold else R.font.figtree_semibold)

        val icon = view.compoundDrawablesRelative[0]?.mutate()?.apply { setBounds(0, 0, dp(14), dp(14)) }
        view.setCompoundDrawablesRelative(icon, null, null, null)
        view.background = if (selected) ContextCompat.getDrawable(activity, R.drawable.lv_seg_thumb_r10) else null
        view.elevation = if (selected) dp(2).toFloat() else 0f
    }

    private fun currentLanguageName(): String {
        val tag = CoreLocker.selectedLanguage(activity).let { if (it == "in") "id" else it }
        LiveLocales.all.firstOrNull { it.tag == tag }?.let { return it.name }
        val current = activity.resources.configuration.locales[0]
        LiveLocales.all.firstOrNull { it.tag == current.language }?.let { return it.name }
        return current.getDisplayLanguage(Locale.ENGLISH).ifEmpty { "English" }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
