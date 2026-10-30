package com.airgaprx.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real SQLCipher transactions in an isolated database; never uses the user's shelf or key preferences. */
@RunWith(AndroidJUnit4::class)
class MedicationRepositoryTest {
    private class Fixture : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val testId = "repository-test-${UUID.randomUUID()}"
        private val root = File(base.cacheDir, testId).apply { mkdirs() }
        private val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File =
                File(File(root, "databases").apply { mkdirs() }, name)
            override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$testId-$name", mode)
        }
        val repository = MedicationRepository(context)
        // The helper stays private in production; reflection is confined to this isolated fixture
        // so the test can inject an actual SQLite failure on the second row.
        val helper = MedicationRepository::class.java.getDeclaredField("helper").let { field ->
            field.isAccessible = true
            field.get(repository) as SupportSQLiteOpenHelper
        }

        init {
            check(File(requireNotNull(helper.writableDatabase.path)).canonicalFile == context.getDatabasePath(MedicationRepository.DB_NAME).canonicalFile) {
                "Refusing to test outside the isolated shelf database"
            }
        }

        override fun close() { helper.close() }
    }

    @Test fun failedSecondIngredientRollsBackTheWholeProduct() = runBlocking {
        Fixture().use { f ->
            f.repository.add("existing", "Existing medicine", null)
            f.helper.writableDatabase.execSQL("""
                CREATE TRIGGER reject_second_ingredient BEFORE INSERT ON shelf
                WHEN NEW.display_name = 'Reject this ingredient'
                BEGIN SELECT RAISE(ABORT, 'Injected second-row failure'); END
            """.trimIndent())
            val failure = runCatching {
                f.repository.addProduct(listOf(
                    ShelfDraft("first", "First ingredient"),
                    ShelfDraft("second", "Reject this ingredient"),
                ), "Combination product")
            }.exceptionOrNull()
            f.repository.refresh()
            assertEquals("a failed batch must leave the existing shelf intact",
                listOf("Existing medicine"), f.repository.items.value.map { it.displayName })
            assertNotNull("the caller must learn that the product was not saved", failure)
        }
    }

    @Test fun successfulProductPublishesEveryKnownAndUncheckedIngredient() = runBlocking {
        Fixture().use { f ->
            f.repository.addProduct(listOf(
                ShelfDraft("first", "First ingredient"),
                ShelfDraft(null, "Unknown active"),
                ShelfDraft("second", "Second ingredient"),
            ), "Combination product")
            val items = f.repository.items.value
            assertEquals(setOf("First ingredient", "Unknown active", "Second ingredient"),
                items.map { it.displayName }.toSet())
            assertEquals(3, items.map { it.id }.toSet().size)
            assertEquals(setOf("Combination product"), items.map { it.productName }.toSet())
            assertEquals(null, items.single { it.displayName == "Unknown active" }.ingredientId)
        }
    }
}
