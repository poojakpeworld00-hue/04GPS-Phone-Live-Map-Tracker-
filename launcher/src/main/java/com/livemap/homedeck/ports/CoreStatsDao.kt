package com.livemap.homedeck.ports

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.livemap.homedeck.entities.CoreStats

@Dao
interface CoreStatsDao {
    @Query("SELECT * FROM app_usage")
    fun getAll(): List<CoreStats>

    @Query("SELECT * FROM app_usage WHERE package_name = :packageName")
    fun get(packageName: String): CoreStats?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(usage: CoreStats)

    @Query("DELETE FROM app_usage WHERE package_name = :packageName")
    fun remove(packageName: String)

    /** Counts one more launch of the app, now. */
    @Transaction
    fun noteLaunch(packageName: String, activityName: String, now: Long = System.currentTimeMillis()) {
        val previous = get(packageName)
        insert(CoreStats(packageName, activityName, (previous?.launchCount ?: 0) + 1, now))
    }
}
