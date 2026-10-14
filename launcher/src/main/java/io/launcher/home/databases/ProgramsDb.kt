package io.launcher.home.databases

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.launcher.home.helpers.Converters
import io.launcher.home.interfaces.CoreHomesDao
import io.launcher.home.interfaces.CoreStatsDao
import io.launcher.home.interfaces.StashedBadgesDao
import io.launcher.home.interfaces.MainDisplayLatticeElementsDao
import io.launcher.home.models.CoreHome
import io.launcher.home.models.CoreStats
import io.launcher.home.models.StashedBadge
import io.launcher.home.models.MainDisplayLatticeElement

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

        /** The launcher's own launch log for the search's suggested apps. */
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
