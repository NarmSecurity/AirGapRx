package com.airgaprx.app.data

import android.database.sqlite.SQLiteDatabase
import com.airgaprx.core.kb.KnowledgeBase
import com.airgaprx.core.kb.LexiconTerm
import com.airgaprx.core.model.Ingredient
import com.airgaprx.core.model.IngredientKind
import com.airgaprx.core.model.Interaction
import com.airgaprx.core.model.KbVersion
import com.airgaprx.core.model.Severity
import java.io.Closeable
import java.io.File

/**
 * Read-only view over a *verified* kb.sqlite. Ingredients and terms are loaded into memory once
 * (they feed the Lexicon); pairwise lookups go to the indexed interaction table.
 */
class SqliteKnowledgeBase(dbFile: File, override val version: KbVersion) : KnowledgeBase, Closeable {
    private val db = SQLiteDatabase.openDatabase(
        dbFile.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    private val ingredients: Map<String, Ingredient> = buildMap {
        db.rawQuery("SELECT id, name, kind, rxcui FROM ingredient", null).use { c ->
            while (c.moveToNext()) {
                val kind = runCatching { IngredientKind.valueOf(c.getString(2)) }.getOrDefault(IngredientKind.PRESCRIPTION)
                put(c.getString(0), Ingredient(c.getString(0), c.getString(1), kind, c.getString(3)))
            }
        }
    }

    private val terms: List<LexiconTerm> = buildList {
        db.rawQuery("SELECT term, ingredient_id, is_canonical FROM term", null).use { c ->
            while (c.moveToNext()) add(LexiconTerm(c.getString(0), c.getString(1), c.getInt(2) == 1))
        }
    }

    override fun ingredient(id: String) = ingredients[id]
    override fun ingredients(): Collection<Ingredient> = ingredients.values
    override fun terms() = terms

    override fun interactionsBetween(a: String, b: String): List<Interaction> {
        val (x, y) = if (a <= b) a to b else b to a
        return buildList {
            db.rawQuery(
                "SELECT severity, mechanism, management, source, source_ref FROM interaction WHERE a_id = ? AND b_id = ?",
                arrayOf(x, y),
            ).use { c ->
                while (c.moveToNext()) {
                    add(Interaction(x, y, Severity.parse(c.getString(0)), c.getString(1), c.getString(2), c.getString(3), c.getString(4)))
                }
            }
        }
    }

    override fun close() = db.close()
}
