package com.echo.memory.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class SyncStateConverter {
    @TypeConverter fun toState(value: String): SyncState = SyncState.valueOf(value)
    @TypeConverter fun fromState(state: SyncState): String = state.name
}

@Database(entities = [LocalMemory::class], version = 2, exportSchema = false)
@TypeConverters(SyncStateConverter::class)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        /** v2 adds the on-device embedding BLOB. Migrate (don't destroy) — the outbox may hold
         *  unsynced memories and the product promise is never to lose one. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_memories ADD COLUMN embedding BLOB")
            }
        }

        /** Build the on-device memory DB. Kept here so callers (DI) never touch Room directly. */
        fun build(context: Context): MemoryDatabase =
            Room.databaseBuilder(context, MemoryDatabase::class.java, "echo-memory.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
