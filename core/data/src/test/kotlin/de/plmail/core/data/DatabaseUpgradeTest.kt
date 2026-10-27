package de.plmail.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import de.plmail.core.database.PlMailDatabase
import java.io.File
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opening a database the app finds on a phone.
 *
 * BearerMail: every version through 12 is rebuilt (see `PlMailDatabase.BEFORE_MIGRATIONS`), so the
 * upstream tests for keeping the cache across v11 -> v12 do not apply here. What does: the current
 * export is the compiled schema, and an old or newer database is rebuilt rather than a crash.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 for the reason FeedProjectionTest gives.
@Config(sdk = [36])
class DatabaseUpgradeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var opened: PlMailDatabase? = null

    @After
    fun close() {
        opened?.close()
        context.deleteDatabase(PlMailDatabase.NAME)
    }

    /**
     * The current schema, as exported, is the one compiled.
     *
     * Room stores the schema's hash and refuses a database whose hash is not its own, so opening
     * the export proves the two agree. An entity changed without its export being written again
     * would otherwise be found the day somebody migrates from it, against a schema that was never
     * true.
     */
    @Test
    fun `the exported schema is the one compiled`() = runTest {
        seed(version = CURRENT) {}

        val database = PlMailDatabase.create(context).also { opened = it }

        assertEquals(emptyList(), database.accounts().all())
    }

    /** A version from before migrations were written is dropped and rebuilt, not fatal. */
    @Test
    fun `a database from before migrations is rebuilt`() = runTest {
        seed(version = 10) {
            execSQL(
                """
                INSERT INTO accounts (uid, serverId, accountId, name, isPersonal, isReadOnly,
                  sortIndex, wantsInboxTabs) VALUES ('a1', 's', '13', 'Me', 1, 0, 0, 0)
                """
            )
        }

        val database = PlMailDatabase.create(context).also { opened = it }

        assertEquals(emptyList(), database.accounts().all())
    }

    /** A development build's newer database, met by an older one: rebuilt, not a crash loop. */
    @Test
    fun `a newer database is rebuilt`() = runTest {
        seed(version = CURRENT) { version = CURRENT + 1 }

        val database = PlMailDatabase.create(context).also { opened = it }

        assertEquals(emptyList(), database.accounts().all())
    }

    /** The database a build compiled against [version] would have left. */
    private fun seed(version: Int, rows: SQLiteDatabase.() -> Unit) {
        val schema =
            Json.parseToJsonElement(File(SCHEMAS, "$version.json").readText())
                .jsonObject
                .getValue("database")
        val path = context.getDatabasePath(PlMailDatabase.NAME).apply { parentFile?.mkdirs() }

        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            schema.jsonObject.getValue("entities").jsonArray.forEach { entity ->
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                fun sql(statement: String) = statement.replace("\${TABLE_NAME}", table)

                db.execSQL(sql(entity.jsonObject.getValue("createSql").jsonPrimitive.content))
                entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(sql(index.jsonObject.getValue("createSql").jsonPrimitive.content))
                }
            }
            schema.jsonObject.getValue("setupQueries").jsonArray.forEach {
                db.execSQL(it.jsonPrimitive.content)
            }
            db.version = version
            db.rows()
        }
    }

    private companion object {
        /** The exported schemas, from this module's directory, where the test runs. */
        val SCHEMAS = File("../database/schemas/de.plmail.core.database.PlMailDatabase")

        /** The version the app opens at, which `PlMailDatabase` declares. */
        const val CURRENT = 13
    }
}
