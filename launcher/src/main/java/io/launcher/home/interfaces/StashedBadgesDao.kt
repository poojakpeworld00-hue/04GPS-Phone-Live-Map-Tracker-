package io.launcher.home.interfaces

import androidx.room.*
import io.launcher.home.models.StashedBadge

@Dao
interface StashedBadgesDao {
    @Query("SELECT * FROM hidden_icons")
    fun getHiddenIcons(): List<StashedBadge>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(hiddenIconUi: StashedBadge): Long

    @Delete
    fun removeHiddenIcons(icons: List<StashedBadge>)
}
