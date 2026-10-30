package com.airgaprx.app.ui.review

import com.airgaprx.core.model.Ingredient
import com.airgaprx.core.model.IngredientKind
import com.airgaprx.core.model.Mention
import com.airgaprx.core.model.MentionSource
import com.airgaprx.core.model.Candidate
import com.airgaprx.core.model.RecognisedItem
import com.airgaprx.core.model.Resolution
import com.airgaprx.app.data.ShelfDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSaveTest {
    private fun ready() = ReviewUi(listOf(ReviewRow(
        0, Mention("paracetamol", MentionSource.MANUAL),
        RowState.Accepted(Ingredient("paracetamol", "Paracetamol", IngredientKind.OTC), 1.0),
    )))

    @Test fun productCannotBeSavedAgainWhileSaving() {
        assertFalse("a second save must be disabled while persistence is running", ready().copy(saving = true).canSave)
    }

    @Test fun completedProductCannotBeSavedAgain() {
        assertFalse("a completed product must not be inserted twice", ready().copy(saved = true).canSave)
    }

    private fun recognised(id: String) = RecognisedItem(
        Mention(id, MentionSource.MANUAL),
        Resolution.Resolved(Candidate(Ingredient(id, id, IngredientKind.OTC), 1.0, id)),
    )

    @Test fun wholeProductIsOneSaveAndRepeatedClicksAreIgnored() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<List<ShelfDraft>>()
        var saved = 0
        val vm = ReviewViewModel(
            listOf(recognised("paracetamol"), recognised("warfarin")),
            { entries, product -> assertEquals("My product", product); calls += entries; gate.await() },
            { emptyList() }, { saved++ }, this,
        )
        vm.setProduct("My product")
        vm.save()
        vm.save()
        yield()
        assertEquals(1, calls.size)
        assertEquals(listOf("paracetamol", "warfarin"), calls.single().map { it.ingredientId })
        assertTrue(vm.ui.value.saving)
        vm.remove(0)
        assertEquals(2, vm.ui.value.rows.size)
        assertTrue(vm.ui.value.rows.all { it.state is RowState.Accepted })
        gate.complete(Unit)
        yield()
        assertTrue(vm.ui.value.saved)
        assertEquals(1, saved)
        vm.save()
        yield()
        assertEquals(1, calls.size)
    }

    @Test fun failedSaveAllowsAnExplicitRetry() = runBlocking {
        var attempts = 0
        val vm = ReviewViewModel(
            listOf(recognised("paracetamol")),
            { _, _ -> if (++attempts == 1) throw java.io.IOException("test write failed") },
            { emptyList() }, {}, this,
        )
        vm.save()
        yield()
        assertTrue(vm.ui.value.canSave)
        assertTrue(vm.ui.value.error != null)
        assertFalse(vm.ui.value.saved)
        vm.save()
        yield()
        assertTrue(vm.ui.value.saved)
        assertEquals(2, attempts)
    }
}
