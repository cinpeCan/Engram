package io.github.cinpecan.engram.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the v4 -> v5 migration (FTS4 `porter` index) preserves user data, passes Room's schema
 * validation without the destructive fallback, and keeps the index in sync with DAO writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EngramDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration4To5PreservesRowsAndEnablesStemmedSearch() = runBlocking {
        createVersion4Database()

        val db = Room.databaseBuilder(context, EngramDatabase::class.java, dbName)
            .addMigrations(*EngramDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.memoryDao()
            val all = dao.getAllMemories()
            assertEquals(listOf("mem_b", "mem_a"), all.map { it.memory.id })
            assertEquals(1, all.first { it.memory.id == "mem_a" }.relations.size)

            // Backfilled index + porter stemming: "quoting" matches stored "quoted".
            assertEquals(listOf("mem_a"), dao.searchMemoryIds("quoting", 10))
            assertEquals(listOf("mem_b"), dao.searchMemoryIds("deposits", 10))
            assertEquals(listOf("mem_b", "mem_a"), dao.searchMemoryIds("quoting OR deposit", 10))

            // Triggers keep the index in sync for inserts, updates and deletes.
            dao.upsertMemory(memory("mem_c", "Sarah reviewed the indemnity clause", createdAt = 3_000L))
            assertEquals(listOf("mem_c"), dao.searchMemoryIds("reviewing", 10))

            dao.upsertMemory(memory("mem_c", "Sarah signed the addendum", createdAt = 3_000L))
            assertTrue(dao.searchMemoryIds("indemnity", 10).isEmpty())
            assertEquals(listOf("mem_c"), dao.searchMemoryIds("addendums", 10))
            // "signing" stems to "sign": matches both the new row and mem_b's "sign-off".
            assertEquals(listOf("mem_c", "mem_b"), dao.searchMemoryIds("signing", 10))

            dao.deleteMemoryById("mem_c")
            assertTrue(dao.searchMemoryIds("addendum", 10).isEmpty())
        } finally {
            db.close()
        }
    }

    private fun createVersion4Database() {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS `memories` (`id` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                    "`summary` TEXT NOT NULL, `tags_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                    "`analysis_status` TEXT NOT NULL, `calendar_json` TEXT, `calendar_saved` INTEGER NOT NULL, " +
                    "`analysis_source` TEXT NOT NULL, `telemetry_badge` TEXT NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
            )
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_created_at` ON `memories` (`created_at`)")
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS `memory_relations` (`id` TEXT NOT NULL, `memory_id` TEXT NOT NULL, " +
                    "`source` TEXT NOT NULL, `target` TEXT NOT NULL, `relation` TEXT NOT NULL, " +
                    "`bidirectional` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`memory_id`) " +
                    "REFERENCES `memories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_relations_memory_id` ON `memory_relations` (`memory_id`)")
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_relations_source` ON `memory_relations` (`source`)")
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_relations_target` ON `memory_relations` (`target`)")
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS `deleted_memories` (`id` TEXT NOT NULL, `deleted_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            raw.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            raw.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'engram-v4')")

            raw.execSQL(
                "INSERT INTO memories VALUES ('mem_a', 'Marcus quoted the Micro-OLED module at Net-60 terms', " +
                    "'• Marcus quoted Net-60', '[]', 1000, 'READY', NULL, 0, 'SEED', '', 1000)",
            )
            raw.execSQL(
                "INSERT INTO memories VALUES ('mem_b', 'Priya releases the escrow deposit after sign-off', " +
                    "'• Deposit waits for sign-off', '[]', 2000, 'READY', NULL, 0, 'SEED', '', 2000)",
            )
            raw.execSQL(
                "INSERT INTO memory_relations VALUES ('mem_a_r0', 'mem_a', 'Marcus Cole', 'Micro-OLED Module', 'quotes', 0)",
            )
            raw.version = 4
        }
    }

    private fun memory(id: String, content: String, createdAt: Long) = MemoryEntity(
        id = id,
        content = content,
        summary = content,
        tagsJson = "[]",
        createdAtMillis = createdAt,
        analysisStatus = "READY",
        calendarJson = null,
        calendarSaved = false,
    )
}
