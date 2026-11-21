package io.github.cinpecan.engram.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MemoryEntity::class,
        MemoryRelationEntity::class,
        DeletedMemoryEntity::class,
        MemoryFtsEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class EngramDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        private const val DB_NAME = "engram_room.db"

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE memories SET updated_at = created_at WHERE updated_at = 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS deleted_memories (id TEXT NOT NULL, deleted_at INTEGER NOT NULL, PRIMARY KEY(id))",
                )
            }
        }

        /**
         * Adds the external-content FTS4 (`porter`) index and the exact sync triggers Room generates
         * for [MemoryFtsEntity], then backfills it from existing rows. Statements mirror the
         * KSP-generated `EngramDatabase_Impl` so Room's schema validation passes after migration.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                FTS_CREATE_STATEMENTS.forEach(db::execSQL)
                db.execSQL("INSERT INTO `memories_fts`(`memories_fts`) VALUES ('rebuild')")
            }
        }

        internal val FTS_CREATE_STATEMENTS: List<String> = listOf(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `memories_fts` USING FTS4(`content` TEXT NOT NULL, `summary` TEXT NOT NULL, tokenize=porter, content=`memories`)",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_memories_fts_BEFORE_UPDATE BEFORE UPDATE ON `memories` BEGIN DELETE FROM `memories_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_memories_fts_BEFORE_DELETE BEFORE DELETE ON `memories` BEGIN DELETE FROM `memories_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_memories_fts_AFTER_UPDATE AFTER UPDATE ON `memories` BEGIN INSERT INTO `memories_fts`(`docid`, `content`, `summary`) VALUES (NEW.`rowid`, NEW.`content`, NEW.`summary`); END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_memories_fts_AFTER_INSERT AFTER INSERT ON `memories` BEGIN INSERT INTO `memories_fts`(`docid`, `content`, `summary`) VALUES (NEW.`rowid`, NEW.`content`, NEW.`summary`); END",
        )

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        fun create(context: Context): EngramDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                EngramDatabase::class.java,
                DB_NAME,
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*ALL_MIGRATIONS)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
