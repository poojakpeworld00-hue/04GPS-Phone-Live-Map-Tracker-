package com.find.phone.gps.live.location.tracker.screens.people

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.RawContacts
import java.util.Locale

data class BuddyNum(
    val contactId: Long,
    val name: String,
    val number: String,

    val label: String,

    val starred: Boolean = false,

    val account: String = ACCOUNT_PHONE,
) {
    val letter: String
        get() = name.firstOrNull()?.takeIf(Char::isLetter)?.uppercaseChar()?.toString() ?: "#"

    val initials: String
        get() = name.split(' ').filter { it.isNotBlank() }.take(2)
            .mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
            .joinToString("")
            .ifEmpty { "#" }

    val firstName: String get() = name.substringBefore(' ')

    val avatar: Int get() = ((contactId % AVATAR_COUNT) + AVATAR_COUNT).toInt() % AVATAR_COUNT

    companion object {
        const val AVATAR_COUNT = 6
        const val ACCOUNT_PHONE = "Phone"
        const val ACCOUNT_SIM = "SIM"
    }
}

object GadgetBuddies {
    fun load(context: Context): List<BuddyNum> {
        val accounts = fetchAccounts(context)
        val projection = arrayOf(
            Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.LABEL,
            Phone.STARRED, Phone.RAW_CONTACT_ID,
        )
        val seen = HashSet<String>()
        val out = ArrayList<BuddyNum>()
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
                val account = accounts[c.getLong(6)] ?: BuddyNum.ACCOUNT_PHONE
                out += BuddyNum(id, name, number, label, starred, account)
            }
        }

        return out.sortedWith(compareBy<BuddyNum> { it.letter == "#" }.thenBy { it.name.lowercase(Locale.getDefault()) })
    }

    private fun fetchAccounts(context: Context): Map<Long, String> {
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
            t.contains("sim") -> BuddyNum.ACCOUNT_SIM
            name.isNullOrBlank() || t.isEmpty() || t.contains("local") || t.contains("phone") -> BuddyNum.ACCOUNT_PHONE
            else -> name
        }
    }
}
