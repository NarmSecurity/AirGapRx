package com.airgaprx.app.ui.shelf

import com.airgaprx.app.data.KbState
import com.airgaprx.app.data.ShelfItem
import com.airgaprx.core.model.Ingredient
import com.airgaprx.core.model.IngredientKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfRowStatusTest {
    private val warfarin = Ingredient("warfarin", "Warfarin", IngredientKind.PRESCRIPTION)
    private val saved = ShelfItem(1, "warfarin", "Warfarin", null, 0)
    private val unknown = ShelfItem(2, null, "Ashwagandha", null, 0)

    /** Emulator finding: after a cold start every row said "Not in the knowledge base" until the KB loaded. */
    @Test fun knownItemIsCheckingWhileKbLoads() {
        assertEquals(RowStatus.CHECKING, rowStatus(KbState.Loading, saved, ingredient = null))
    }

    @Test fun knownItemCannotBeCheckedWhenKbFailed() {
        assertEquals(RowStatus.KB_UNAVAILABLE, rowStatus(KbState.Failed("bad signature"), saved, ingredient = null))
    }

    @Test fun itemSavedWithoutAnIngredientIsNotInKb() {
        assertEquals(RowStatus.NOT_IN_KB, rowStatus(KbState.Loading, unknown, ingredient = null))
    }

    @Test fun resolvedItemIsKnown() {
        assertEquals(RowStatus.KNOWN, rowStatus(KbState.Loading, saved, warfarin))
    }
}
