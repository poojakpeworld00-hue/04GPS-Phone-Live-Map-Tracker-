package com.find.my.phone.gps.location.tracker.ui.home

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
import com.find.my.phone.gps.location.tracker.BuildConfig
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault
import com.find.my.phone.gps.location.tracker.data.ThemeRegistry
import com.find.my.phone.gps.location.tracker.data.VaultRegistry
import com.find.my.phone.gps.location.tracker.data.contactsync.ContactSync
import com.find.my.phone.gps.location.tracker.reminder.ReminderConfig
import com.find.my.phone.gps.location.tracker.reminder.ReminderStore
import com.find.my.phone.gps.location.tracker.ui.reminder.ReminderListActivity
import kotlinx.coroutines.launch
import com.find.my.phone.gps.location.tracker.databinding.PieceHomeDrawerBinding
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.find.my.phone.gps.location.tracker.ui.language.LangChooserActivity
import com.find.my.phone.gps.location.tracker.ui.language.Locales
import com.find.my.phone.gps.location.tracker.util.AppVault
import com.find.my.phone.gps.location.tracker.util.openPolicyLink
import com.find.my.phone.gps.location.tracker.util.openTermLink
import com.find.my.phone.gps.location.tracker.util.rateApp
import com.find.my.phone.gps.location.tracker.util.shareApp
import java.util.Locale

/**
 * The home drawer (design 3e): wires the menu rows and the theme segment of
 * `piece_home_drawer.xml` to the app's existing helpers (language picker, theme registry, rate,
 * share, privacy and terms links from Remote Config).
 */
class HomeDrawerMenu(
    private val activity: AppCompatActivity,
    private val binding: PieceHomeDrawerBinding,
    private val requestPhonePermission: (Array<String>) -> Unit = {},
    private val onNavigate: () -> Unit,
) {

    private val segments get() = listOf(binding.segLight, binding.segDark, binding.segSystem)

    /** The drawer-header "My number" card (design 1a); shows the phone's own SIMs. */
    private val myNumberCard by lazy {
        MyNumberCard(activity, binding.myNumber, VaultRegistry(activity)) { requestPhonePermission(it) }
    }

    /** Forwarded from the host's phone-permission launcher result. */
    fun onPhonePermissionResult(result: Map<String, Boolean>) = myNumberCard.onPermissionResult(result)

    fun bind() {
        // The header's 58dp top = the status bar + 22dp, so it reads the same on every phone.
        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerHeader) { v, insets ->
            v.updatePadding(top = dp(22) + insets.getInsets(WindowInsetsCompat.Type.systemBars()).top)
            insets
        }
        binding.tvVersion.text = activity.getString(R.string.ds_version_fmt, BuildConfig.VERSION_NAME)

        binding.rowLanguage.setOnClickListener {
            onNavigate()
            activity.startActivity(LangChooserActivity.newIntent(activity, standalone = true))
        }
        binding.rowReminders.setOnClickListener {
            onNavigate()
            activity.startActivity(ReminderListActivity.intent(activity))
        }
        binding.rowRate.setOnClickListener { onNavigate(); activity.rateApp() }
        binding.rowShare.setOnClickListener { onNavigate(); activity.shareApp() }
        binding.rowPrivacy.setOnClickListener { onNavigate(); activity.openPolicyLink() }
        binding.rowTerms.setOnClickListener { onNavigate(); activity.openTermLink() }
        binding.rowDeleteContacts.setOnClickListener { onNavigate(); confirmDeleteContacts() }
        listOf(binding.rowLanguage, binding.rowReminders, binding.rowRate, binding.rowShare, binding.rowPrivacy, binding.rowTerms, binding.rowDeleteContacts)
            .forEach { DesignMotion.pressScale(it) }

        binding.segLight.setOnClickListener { pickTheme(AppVault.THEME_LIGHT, AppCompatDelegate.MODE_NIGHT_NO) }
        binding.segDark.setOnClickListener { pickTheme(AppVault.THEME_DARK, AppCompatDelegate.MODE_NIGHT_YES) }
        binding.segSystem.setOnClickListener { pickTheme(AppVault.THEME_SYSTEM, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }

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
                    val deleted = ContactSync.deleteUploaded(activity)
                    val msg = if (deleted != null) R.string.ds_delete_contacts_done else R.string.ds_delete_contacts_failed
                    android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
                    binding.rowDeleteContacts.isVisible = ContactSync.hasUploaded(activity)
                }
            }
            .show()
    }

    /** Re-reads the language value, the theme selection and the RC row switches (call on resume). */
    fun refresh() {
        binding.tvLanguageValue.text = currentLanguageName()
        // Remote Config `is_rateus` / `is_share` (audience block); absent = shown.
        val vault = AdsVault.getInstance(activity)
        binding.rowRate.isVisible = vault.getBoolean("is_rateus", true)
        binding.rowShare.isVisible = vault.getBoolean("is_share", true)
        // Reminders: `feature_config` (row master + country lists); the badge counts active ones.
        binding.rowReminders.isVisible = ReminderConfig.isSettingsRowAllowed(activity)
        val active = ReminderStore.activeCount(activity)
        binding.tvRemindersCount.isVisible = active > 0
        binding.tvRemindersCount.text = active.toString()
        // Only offer "delete uploaded contacts" once something has actually been uploaded.
        binding.rowDeleteContacts.isVisible = ContactSync.hasUploaded(activity)
        val selected = when (AppVault.effectiveTheme(activity)) {
            AppVault.THEME_DARK -> binding.segDark
            AppVault.THEME_LIGHT -> binding.segLight
            else -> binding.segSystem
        }
        segments.forEach { styleSegment(it, it === selected) }
        myNumberCard.render()
    }

    private fun pickTheme(theme: String, mode: Int) {
        if (AppVault.effectiveTheme(activity) == theme) return
        AppVault.setTheme(activity, theme)
        refresh()
        // Applies globally and recreates the running activities.
        ThemeRegistry.apply(mode)
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
        view.background = if (selected) ContextCompat.getDrawable(activity, R.drawable.ds_seg_thumb_r10) else null
        view.elevation = if (selected) dp(2).toFloat() else 0f
    }

    /** The English name of the chosen app language, else the device language's own name. */
    private fun currentLanguageName(): String {
        val tag = AppVault.selectedLanguage(activity).let { if (it == "in") "id" else it }
        Locales.all.firstOrNull { it.tag == tag }?.let { return it.name }
        val current = activity.resources.configuration.locales[0]
        Locales.all.firstOrNull { it.tag == current.language }?.let { return it.name }
        return current.getDisplayLanguage(Locale.ENGLISH).ifEmpty { "English" }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
