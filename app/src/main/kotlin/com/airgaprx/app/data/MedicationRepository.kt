package com.airgaprx.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.airgaprx.app.platform.KeystorePassphrase
import com.airgaprx.core.kb.KnowledgeBase
import com.airgaprx.core.reasoning.ShelfEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * One item on the medication shelf. [ingredientId] is null for an ingredient the user kept even
 * though it is not in the knowledge base: it is shown as "not checked" so coverage gaps are
 * visible rather than silently ignored (R3, R6).
 */
data class ShelfItem(
    val id: Long,
    val ingredientId: String?,
    val displayName: String,
    val productName: String?,
    val addedAt: Long,
)

/**
 * Owns the encrypted medication profile (M1 MedicationRepository, R8). The database file is
 * encrypted with SQLCipher; its passphrase is wrapped by a hardware-backed Keystore key.
 */
class MedicationRepository(context: Context) {
    private val helper: SupportSQLiteOpenHelper

    init {
        System.loadLibrary("sqlcipher")
        val passphrase = KeystorePassphrase(context).get()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """CREATE TABLE shelf (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            ingredient_id TEXT,
                            display_name TEXT NOT NULL,
                            product_name TEXT,
                            added_at INTEGER NOT NULL)"""
                    )
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        helper = SupportOpenHelperFactory(passphrase).create(config)
    }

    private val db: SupportSQLiteDatabase get() = helper.writableDatabase

    private val _items = MutableStateFlow<List<ShelfItem>>(emptyList())
    val items: StateFlow<List<ShelfItem>> = _items
    private val mutex = Mutex()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock { _items.value = readItems(db) }
    }

    private fun readItems(database: SupportSQLiteDatabase): List<ShelfItem> =
        database.query("SELECT id, ingredient_id, display_name, product_name, added_at FROM shelf ORDER BY added_at DESC, id DESC")
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        ShelfItem(c.getLong(0), if (c.isNull(1)) null else c.getString(1), c.getString(2),
                            if (c.isNull(3)) null else c.getString(3), c.getLong(4))
                    )
                }
            }

    /** Commit and publish one complete snapshot even if the initiating screen is destroyed. */
    private suspend fun mutateShelf(write: (SupportSQLiteDatabase) -> Unit) = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock {
            val database = db
            database.beginTransaction()
            val snapshot = try {
                write(database)
                // A query failure must roll back too, rather than report failure after committing
                // and allow the caller to insert the same product again on retry.
                val items = readItems(database)
                database.setTransactionSuccessful()
                items
            } finally {
                database.endTransaction()
            }
            _items.value = snapshot
        }
    }

    suspend fun add(ingredientId: String?, displayName: String, productName: String?) =
        addProduct(listOf(ShelfDraft(ingredientId, displayName)), productName)

    /** All ingredients for one reviewed product are saved together, or none are. */
    suspend fun addProduct(entries: List<ShelfDraft>, productName: String?) {
        val batch = entries.toList()
        mutateShelf { database ->
            val now = System.currentTimeMillis()
            batch.forEach { entry ->
                val id = database.insert("shelf", SQLiteDatabase.CONFLICT_NONE, ContentValues().apply {
                    put("ingredient_id", entry.ingredientId)
                    put("display_name", entry.displayName)
                    put("product_name", productName?.takeIf { it.isNotBlank() })
                    put("added_at", now)
                })
                check(id != -1L) { "Could not insert a reviewed ingredient" }
            }
        }
    }

    suspend fun remove(id: Long) = mutateShelf { database ->
        database.delete("shelf", "id = ?", arrayOf(id))
    }

    suspend fun clear() = mutateShelf { database ->
        database.delete("shelf", null, null)
    }

    /** Shelf items that map to knowledge-base ingredients, as core [ShelfEntry]s for the engine. */
    fun checkable(kb: KnowledgeBase): List<ShelfEntry> = _items.value.mapNotNull { item ->
        item.ingredientId?.let { kb.ingredient(it) }?.let { ShelfEntry(item.id, it, item.productName) }
    }

    fun unchecked(kb: KnowledgeBase): List<ShelfItem> =
        _items.value.filter { it.ingredientId == null || kb.ingredient(it.ingredientId) == null }

    companion object {
        const val DB_NAME = "shelf.db"
    }
}
