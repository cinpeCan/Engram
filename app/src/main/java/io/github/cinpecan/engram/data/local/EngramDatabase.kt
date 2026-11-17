package io.github.cinpecan.engram.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MemoryEntity::class,
        MemoryRelationEntity::class,
        MemoryEmbeddingEntity::class,
        MemoryFtsEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class EngramDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        private const val DB_NAME = "engram_room.db"

        fun create(context: Context): EngramDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                EngramDatabase::class.java,
                DB_NAME,
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
