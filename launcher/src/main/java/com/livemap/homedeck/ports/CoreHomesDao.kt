package com.livemap.homedeck.ports

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.livemap.homedeck.entities.CoreHome

@Dao
interface CoreHomesDao {
    @Query("SELECT * FROM apps")
    fun getAppLaunchers(): List<CoreHome>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(appLaunchers: List<CoreHome>)

    @Query("DELETE FROM apps WHERE package_name = :packageName")
    fun deleteApp(packageName: String)

    @Query("DELETE FROM apps WHERE id = :id")
    fun deleteById(id: Long)
}
