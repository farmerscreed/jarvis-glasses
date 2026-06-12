package com.echo.memory.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class SyncStateConverter {
    @TypeConverter fun toState(value: String): SyncState = SyncState.valueOf(value)
    @TypeConverter fun fromState(state: SyncState): String = state.name
}

@Database(entities = [LocalMemory::class], version = 1, exportSchema = false)
@TypeConverters(SyncStateConverter::class)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        /** Build the on-device memory DB. Kept here so callers (DI) never touch Room directly. */
        fun build(context: Context): MemoryDatabase =
            Room.databaseBuilder(context, MemoryDatabase::class.java, "echo-memory.db").build()
    }
}
