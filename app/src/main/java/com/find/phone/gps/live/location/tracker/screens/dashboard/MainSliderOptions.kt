package com.find.phone.gps.live.location.tracker.screens.dashboard

import android.widget.TextView
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
import com.find.phone.gps.live.location.tracker.helpers.openPolicyLink
import com.find.phone.gps.live.location.tracker.helpers.openTermLink
import com.find.phone.gps.live.location.tracker.helpers.rateApp
import com.find.phone.gps.live.location.tracker.helpers.shareApp
import java.util.Locale

/**
 * The home drawer (design 3e): wires the menu rows and the theme segment of
 * `piece_home_drawer.xml` to the app's existing helpers (language picker, theme registry, rate,
 * share, privacy and terms links from Remote Config).
 */
class MainSliderOptions(
    private val activity: AppCompatActivity,
    private val binding: PartHomeDrawerBinding,
    private val requestPhonePermission: (Array<String>) -> Unit = {},
    private val onNavigate: () -> Unit,
) {

    private val segments get() = listOf(binding.partLight, binding.partDark, binding.partSystem)

    /** The drawer-header "My number" card (design 1a); shows the phone's own SIMs. */
    private val myNumberCard by lazy {
        MineNumPlate(activity, binding.ownNumber, LockerBook(activity)) { requestPhonePermission(it) }
    }

    /** Forwarded from the host's phone-permission launcher result. */
    fun onPhonePermissionResult(result: Map<String, Boolean>) = myNumberCard.onPermissionResult(result)

    fun bind() {
        // The header's 58dp top = the status bar + 22dp, so it reads the same on every phone.
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
        binding.lineShare.setOnClickListener { onNavigate(); activity.shareApp() }
        binding.linePrivacy.setOnClickListener { onNavigate(); activity.openPolicyLink() }
        binding.lineTerms.setOnClickListener { onNavigate(); activity.openTermLink() }
        binding.lineDeleteContacts.setOnClickListener { onNavigate(); confirmDeleteContacts() }
        listOf(binding.lineLanguage, binding.lineReminders, binding.lineRate, binding.lineShare, binding.linePrivacy, binding.lineTerms, binding.lineDeleteContacts)
            .forEach { CraftMovement.pressScale(it) }

        binding.partLight.setOnClickListener { pickTheme(CoreLocker.THEME_LIGHT, AppCompatDelegate.MODE_NIGHT_NO) }
        binding.partDark.setOnClickListener { pickTheme(CoreLocker.THEME_DARK, AppCompatDelegate.MODE_NIGHT_YES) }
        binding.partSystem.setOnClickListener { pickTheme(CoreLocker.THEME_SYSTEM, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }

        refresh()
    }

    /** Confirms, then deletes everything this install uploaded and hides the row. */
    private fun confirmDeleteContacts() {
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.ds_delete_contacts_title)
            .setMessage(R.string.ds_delete_contacts_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ds_delete_contacts_confirm) { _, _ ->
                activity.lifecycleScope.launch {
                    val deleted = BuddyRelay.deleteUploaded(activity)
                    val msg = if (deleted != null) R.string.ds_delete_contacts_done else R.string.ds_delete_contacts_failed
                    android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
                    binding.lineDeleteContacts.isVisible = BuddyRelay.hasUploaded(activity)
                }
            }
            .show()
    }

    /** Re-reads the language value, the theme selection and the RC row switches (call on resume). */
    fun refresh() {
        binding.txtLanguageValue.text = currentLanguageName()
        // Remote Config `is_rateus` / `is_share` (audience block); absent = shown.
        val vault = SpotsLocker.getInstance(activity)
        binding.lineRate.isVisible = vault.getBoolean("is_rateus", true)
        binding.lineShare.isVisible = vault.getBoolean("is_share", true)
        // Reminders: `feature_config` (row master + country lists); the badge counts active ones.
        binding.lineReminders.isVisible = MemoProfile.isSettingsRowAllowed(activity)
        val active = MemoShelf.activeCount(activity)
        binding.txtRemindersCount.isVisible = active > 0
        binding.txtRemindersCount.text = active.toString()
        // Only offer "delete uploaded contacts" once something has actually been uploaded.
        binding.lineDeleteContacts.isVisible = BuddyRelay.hasUploaded(activity)
        val selected = when (CoreLocker.effectiveTheme(activity)) {
            CoreLocker.THEME_DARK -> binding.partDark
            CoreLocker.THEME_LIGHT -> binding.partLight
            else -> binding.partSystem
        }
        segments.forEach { styleSegment(it, it === selected) }
        myNumberCard.render()
    }

    private fun pickTheme(theme: String, mode: Int) {
        if (CoreLocker.effectiveTheme(activity) == theme) return
        CoreLocker.setTheme(activity, theme)
        refresh()
        // Applies globally and recreates the running activities.
        LookBook.apply(mode)
    }

    /** Selected: white thumb, 2dp shadow, bold primary; idle: flat, semibold muted. */
    private fun styleSegment(view: TextView, selected: Boolean) {
        val color = ContextCompat.getColor(activity, if (selected) R.color.ds_primary else R.color.ds_muted)
        view.setTextColor(color)
        TextViewCompat.setCompoundDrawableTintList(view, android.content.res.ColorStateList.valueOf(color))
        view.typeface = ResourcesCompat.getFont(activity, if (selected) R.font.figtree_bold else R.font.figtree_semibold)
        // The 14dp glyph the design uses, whatever the vector's intrinsic size.
        val icon = view.compoundDrawablesRelative[0]?.mutate()?.apply { setBounds(0, 0, dp(14), dp(14)) }
        view.setCompoundDrawablesRelative(icon, null, null, null)
        view.background = if (selected) ContextCompat.getDrawable(activity, R.drawable.lv_seg_thumb_r10) else null
        view.elevation = if (selected) dp(2).toFloat() else 0f
    }

    /** The English name of the chosen app language, else the device language's own name. */
    private fun currentLanguageName(): String {
        val tag = CoreLocker.selectedLanguage(activity).let { if (it == "in") "id" else it }
        LiveLocales.all.firstOrNull { it.tag == tag }?.let { return it.name }
        val current = activity.resources.configuration.locales[0]
        LiveLocales.all.firstOrNull { it.tag == current.language }?.let { return it.name }
        return current.getDisplayLanguage(Locale.ENGLISH).ifEmpty { "English" }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
