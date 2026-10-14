package com.find.my.phone.gps.location.tracker.reminder

import android.content.Context
import com.find.my.phone.gps.location.tracker.reminder.access.ReminderAccess
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar

/** The reminders list, kept on the device only (SharedPreferences, JSON). */
object ReminderStore {

    private const val PREFS = "reminder_prefs"
    private const val KEY = "reminders"
    private const val KEY_SEEDED = "reminder_defaults_seeded"

    fun load(context: Context): MutableList<ReminderItem> {
        val json = prefs(context).getString(KEY, null)
        if (json.isNullOrEmpty()) return mutableListOf()
        val type = object : TypeToken<MutableList<ReminderItem>>() {}.type
        return runCatching { Gson().fromJson<MutableList<ReminderItem>>(json, type) }.getOrNull()
            ?.filter { it.title != null }?.toMutableList() ?: mutableListOf()
    }

    fun get(context: Context, id: Long): ReminderItem? = load(context).firstOrNull { it.id == id }

    /** Inserts or replaces by id. */
    fun upsert(context: Context, item: ReminderItem) {
        val list = load(context)
        val i = list.indexOfFirst { it.id == item.id }
        if (i >= 0) list[i] = item else list.add(item)
        save(context, list)
    }

    fun remove(context: Context, id: Long) {
        val list = load(context)
        if (list.removeAll { it.id == id }) save(context, list)
    }

    /** Reminders still waiting to fire: the drawer badge. */
    fun activeCount(context: Context): Int = load(context).count { it.isActive }

    private fun save(context: Context, list: List<ReminderItem>) {
        prefs(context).edit().putString(KEY, Gson().toJson(list)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Seeds `default_config_list` once, the first time reminders are usable, when
     * `is_default_config` is on and the list is empty. Samples are switched off, so nothing
     * fires until the user opens one and saves it.
     */
    fun seedDefaultsIfNeeded(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        val cfg = ReminderConfig.get(context)
        if (!cfg.isDefaultConfig || !ReminderAccess.isAllowed(context)) return
        if (load(context).isEmpty()) {
            val now = System.currentTimeMillis()
            val seeded = cfg.defaultConfigList.filter { it.title.isNotBlank() }.mapIndexed { i, spec ->
                val at = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, spec.daysAhead.coerceAtLeast(0))
                    set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                ReminderItem(id = now + i, title = spec.title.trim(), note = spec.note.trim(), dateTime = at, enabled = false)
            }
            if (seeded.isNotEmpty()) save(context, seeded)
        }
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }
}
