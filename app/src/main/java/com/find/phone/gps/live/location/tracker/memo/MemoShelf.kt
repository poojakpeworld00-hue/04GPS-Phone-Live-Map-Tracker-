package com.find.phone.gps.live.location.tracker.memo

import android.content.Context
import android.content.SharedPreferences
import com.find.phone.gps.live.location.tracker.memo.grant.MemoConsent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar

/** Device-local reminder storage: one JSON array in SharedPreferences. */
object MemoShelf {

    private const val FILE = "reminder_prefs"
    private const val LIST_KEY = "reminders"
    private const val SEEDED_KEY = "reminder_defaults_seeded"
    private const val SAMPLE_HOUR = 9

    private fun store(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun persist(context: Context, items: List<MemoElement>) {
        val encoded = Gson().toJson(items)
        store(context).edit().putString(LIST_KEY, encoded).apply()
    }

    fun load(context: Context): MutableList<MemoElement> {
        val raw = store(context).getString(LIST_KEY, null)
        if (raw == null || raw.isEmpty()) return ArrayList()
        val listType = object : TypeToken<MutableList<MemoElement>>() {}.type
        val decoded: List<MemoElement>? = runCatching { Gson().fromJson<MutableList<MemoElement>>(raw, listType) }.getOrNull()
        val result = ArrayList<MemoElement>()
        decoded?.forEach { if (it.title != null) result.add(it) }
        return result
    }

    fun get(context: Context, id: Long): MemoElement? = load(context).find { it.id == id }

    fun upsert(context: Context, item: MemoElement) {
        val items = load(context)
        val pos = items.indexOfFirst { it.id == item.id }
        when {
            pos < 0 -> items.add(item)
            else -> items[pos] = item
        }
        persist(context, items)
    }

    fun remove(context: Context, id: Long) {
        val items = load(context)
        val changed = items.removeAll { it.id == id }
        if (changed) persist(context, items)
    }

    fun activeCount(context: Context): Int = load(context).count(MemoElement::isActive)

    private fun sampleTime(daysAhead: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, maxOf(daysAhead, 0))
        cal.set(Calendar.HOUR_OF_DAY, SAMPLE_HOUR)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * One-time seeding of `default_config_list` (when `is_default_config` is on and storage is
     * empty). Seeds are disabled so nothing fires until the user saves one.
     */
    fun plantDefaultsIfNeeded(context: Context) {
        val sp = store(context)
        if (sp.getBoolean(SEEDED_KEY, false)) return
        val options = MemoProfile.get(context)
        if (!options.isDefaultConfig) return
        if (!MemoConsent.isAllowed(context)) return
        if (load(context).isEmpty()) {
            val base = System.currentTimeMillis()
            val samples = ArrayList<MemoElement>()
            options.defaultConfigList.asSequence()
                .filter { it.title.isNotBlank() }
                .forEachIndexed { idx, spec ->
                    samples += MemoElement(
                        id = base + idx,
                        title = spec.title.trim(),
                        note = spec.note.trim(),
                        dateTime = sampleTime(spec.daysAhead),
                        enabled = false,
                    )
                }
            if (samples.isNotEmpty()) persist(context, samples)
        }
        sp.edit().putBoolean(SEEDED_KEY, true).apply()
    }
}
