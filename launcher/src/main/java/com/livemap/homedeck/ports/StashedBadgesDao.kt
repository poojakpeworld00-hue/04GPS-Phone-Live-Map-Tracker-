package com.livemap.homedeck.ports

import androidx.room.*
import com.livemap.homedeck.entities.StashedBadge

@Dao
interface StashedBadgesDao {
    @Query("SELECT * FROM hidden_icons")
    fun getHiddenIcons(): List<StashedBadge>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(hiddenIconUi: StashedBadge): Long

    @Delete
    fun removeHiddenIcons(icons: List<StashedBadge>)
}
