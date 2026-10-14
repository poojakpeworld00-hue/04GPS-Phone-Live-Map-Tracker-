package com.livemap.homedeck.entities

import android.graphics.drawable.Drawable
import androidx.room.*
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.helpers.SORT_BY_TITLE
import org.fossify.commons.helpers.SORT_DESCENDING

val appLauncherComparator = compareBy<CoreHome>(
    { it.title.normalizeString().lowercase() },
    { it.packageName }
)

@Entity(tableName = "apps", indices = [(Index(value = ["package_name"], unique = true))])
data class CoreHome(
    @PrimaryKey(autoGenerate = true) var id: Long?,
    @ColumnInfo(name = "title") var title: String,
    @ColumnInfo(name = "package_name") var packageName: String,
    @ColumnInfo(name = "activity_name") var activityName: String,
    @ColumnInfo(name = "order") var order: Int,
    @ColumnInfo(name = "thumbnail_color") var thumbnailColor: Int,

    @Ignore var drawable: Drawable?
) : Comparable<CoreHome> {
    constructor() : this(null, "", "", "", 0, 0, null)

    companion object {
        var sorting = 0
    }

    override fun equals(other: Any?) = other is CoreHome && packageName.equals(other.packageName, true)

    override fun hashCode() = packageName.lowercase().hashCode()

    fun getBubbleText() = title

    fun getLauncherIdentifier() = "$packageName/$activityName"

    override fun compareTo(other: CoreHome): Int {
        var result = when {
            sorting and SORT_BY_TITLE != 0 -> title.normalizeString().lowercase().compareTo(other.title.normalizeString().lowercase())
            else -> {
                if (order > 0 && other.order == 0) {
                    -1
                } else if (order == 0 && other.order > 0) {
                    1
                } else if (order > 0 && other.order > 0) {
                    order.compareTo(other.order)
                } else {
                    title.lowercase().compareTo(other.title.lowercase())
                }
            }
        }

        if (sorting and SORT_DESCENDING != 0) {
            result *= -1
        }

        return result
    }
}
