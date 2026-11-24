package com.vherrmann.linkhole

import com.vherrmann.linkhole.data.Item
import com.vherrmann.linkhole.data.Selection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTest {
    private fun item(id: Long) = Item(id = id, url = "https://x/$id", createdAt = id, updatedAt = id)
    private val list = listOf(item(3), item(2), item(1)) // newest first

    @Test fun latestIsNewest() = assertEquals(3L, Selection.latest(list)?.id)

    @Test fun latestStableAcrossCalls() {
        repeat(5) { assertEquals(3L, Selection.latest(list)?.id) }
    }

    @Test fun emptyHasNothing() {
        assertNull(Selection.latest(emptyList()))
        assertNull(Selection.random(emptyList()))
    }

    @Test fun randomNeverLatestAndCoversRest() {
        val seen = (1..200).map { Selection.random(list)!!.id }.toSet()
        assertEquals(setOf(2L, 1L), seen)
    }

    @Test fun randomFallsBackToOnlyItem() = assertEquals(3L, Selection.random(listOf(item(3)))?.id)

    @Test fun randomWithTwoPicksTheOther() = assertTrue((1..20).all { Selection.random(list.take(2))!!.id == 2L })
}
