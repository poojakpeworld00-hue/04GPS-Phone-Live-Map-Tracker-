package com.livemap.homedeck.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.livemap.homedeck.support.Converters
import com.livemap.homedeck.ports.CoreHomesDao
import com.livemap.homedeck.ports.CoreStatsDao
import com.livemap.homedeck.ports.StashedBadgesDao
import com.livemap.homedeck.ports.MainDisplayLatticeElementsDao
import com.livemap.homedeck.entities.CoreHome
import com.livemap.homedeck.entities.CoreStats
import com.livemap.homedeck.entities.StashedBadge
import com.livemap.homedeck.entities.MainDisplayLatticeElement

@Database(
    entities = [CoreHome::class, MainDisplayLatticeElement::class, StashedBadge::class, CoreStats::class],
    version = 6
)
@TypeConverters(Converters::class)
abstract class ProgramsDb : RoomDatabase() {
    abstract fun CoreHomesDao(): CoreHomesDao

    abstract fun MainDisplayLatticeElementsDao(): MainDisplayLatticeElementsDao

    abstract fun StashedBadgesDao(): StashedBadgesDao

    abstract fun CoreStatsDao(): CoreStatsDao

    companion object {
        private var db: ProgramsDb? = null

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `app_usage` (`package_name` TEXT NOT NULL, `activity_name` TEXT NOT NULL, " +
                        "`launch_count` INTEGER NOT NULL, `last_launched` INTEGER NOT NULL, PRIMARY KEY(`package_name`))"
                )
            }
        }

        fun getInstance(context: Context): ProgramsDb {
            if (db == null) {
                synchronized(ProgramsDb::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(
                            context.applicationContext,
                            ProgramsDb::class.java,
                            "apps.db"
                        ).addMigrations(MIGRATION_5_6).build()
                    }
                }
            }
            return db!!
        }
    }
}
