package com.find.my.phone.gps.location.tracker.ui.contacts

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.RawContacts
import java.util.Locale

/** One phone number of one contact, as the contact list shows it. */
data class ContactNumber(
    val contactId: Long,
    val name: String,
    val number: String,
    /** "Mobile", "Work", a custom label… */
    val label: String,
    /** Starred in the phone book: shown in the Favorites row. */
    val starred: Boolean = false,
    /** The account the contact lives in ("Phone", "SIM", or the account name), for the filter. */
    val account: String = ACCOUNT_PHONE,
) {
    /** Group heading: the first letter, or "#" for names that don't start with one. */
    val letter: String
        get() = name.firstOrNull()?.takeIf(Char::isLetter)?.uppercaseChar()?.toString() ?: "#"

    /** Up to two initials ("Aditya Kumar" → "AK"). */
    val initials: String
        get() = name.split(' ').filter { it.isNotBlank() }.take(2)
            .mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
            .joinToString("")
            .ifEmpty { "#" }

    /** The given name, for the Favorites row. */
    val firstName: String get() = name.substringBefore(' ')

    /** Which of the six avatar gradients this contact gets (stable per contact). */
    val avatar: Int get() = ((contactId % AVATAR_COUNT) + AVATAR_COUNT).toInt() % AVATAR_COUNT

    companion object {
        const val AVATAR_COUNT = 6
        const val ACCOUNT_PHONE = "Phone"
        const val ACCOUNT_SIM = "SIM"
    }
}

/**
 * Reads every phone number in the phone book (READ_CONTACTS must be granted), sorted by name,
 * with the starred flag and the owning account. The same number saved twice on one contact
 * (different formatting) is listed once.
 */
object DeviceContacts {

    fun load(context: Context): List<ContactNumber> {
        val accounts = loadAccounts(context)
        val projection = arrayOf(
            Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.LABEL,
            Phone.STARRED, Phone.RAW_CONTACT_ID,
        )
        val seen = HashSet<String>()
        val out = ArrayList<ContactNumber>()
        context.contentResolver.query(
            Phone.CONTENT_URI, projection, null, null, "${Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val number = c.getString(2)?.trim().orEmpty()
                val digits = number.filter(Char::isDigit)
                if (digits.length < 4) continue
                if (!seen.add("$id:${digits.takeLast(10)}")) continue
                val name = c.getString(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: number
                val label = Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4)).toString()
                val starred = c.getInt(5) == 1
                val account = accounts[c.getLong(6)] ?: ContactNumber.ACCOUNT_PHONE
                out += ContactNumber(id, name, number, label, starred, account)
            }
        }
        // Names starting with a letter first (A–Z), the rest ("#") after them.
        return out.sortedWith(compareBy<ContactNumber> { it.letter == "#" }.thenBy { it.name.lowercase(Locale.getDefault()) })
    }

    /** raw contact id → the account it belongs to, as the filter names it. */
    private fun loadAccounts(context: Context): Map<Long, String> {
        val map = HashMap<Long, String>()
        runCatching {
            context.contentResolver.query(
                RawContacts.CONTENT_URI,
                arrayOf(RawContacts._ID, RawContacts.ACCOUNT_NAME, RawContacts.ACCOUNT_TYPE),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    map[c.getLong(0)] = accountLabel(c.getString(1), c.getString(2))
                }
            }
        }
        return map
    }

    private fun accountLabel(name: String?, type: String?): String {
        val t = type.orEmpty().lowercase(Locale.ROOT)
        return when {
            t.contains("sim") -> ContactNumber.ACCOUNT_SIM
            name.isNullOrBlank() || t.isEmpty() || t.contains("local") || t.contains("phone") -> ContactNumber.ACCOUNT_PHONE
            else -> name
        }
    }
}
