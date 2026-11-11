package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The DEX list's TYPE filter and SORT over Emerald's own type chart. */
class DexQueryTest {
    private val fighting = 1
    private val ground = 4
    private val fire = 10
    private val water = 11
    private val grass = 12
    private val electric = 13
    private val dragon = 16

    @Before fun setUp() {
        activeGame = GameKind.EMERALD
    }

    @Test fun isMatchesEitherOfItsTypes() {
        assertTrue(matchesType(water, ground, TypeFilter(ground, TypeRelation.IS)))
        assertFalse(matchesType(fire, TYPE_NONE, TypeFilter(water, TypeRelation.IS)))
    }

    @Test fun hitsHardMeansOneOfItsOwnTypesIsSuperEffective() {
        val f = TypeFilter(water, TypeRelation.HITS)
        assertTrue(matchesType(grass, fighting, f))
        assertTrue(matchesType(electric, TYPE_NONE, f))
        assertFalse(matchesType(fire, TYPE_NONE, f))
        assertFalse(matchesType(water, ground, f))
    }

    @Test fun resistsMeansThePickedTypeDoesLessThanOnceOverBothTypes() {
        val f = TypeFilter(water, TypeRelation.RESISTS)
        assertTrue(matchesType(water, TYPE_NONE, f))
        assertTrue(matchesType(dragon, TYPE_NONE, f))
        assertFalse(matchesType(fire, TYPE_NONE, f))
        // Water ½x on Water, 2x on Ground: 1x, not a resist.
        assertFalse(matchesType(water, ground, f))
    }

    @Test fun aStatSortGoesHighestFirstAndKeepsDexOrderOnTies() {
        val stats = mapOf(
            1 to DexStats(grass, TYPE_NONE, listOf(45, 49, 49, 65, 65, 45)),
            2 to DexStats(grass, TYPE_NONE, listOf(60, 62, 63, 80, 80, 60)),
            3 to DexStats(fire, TYPE_NONE, listOf(39, 62, 43, 60, 50, 65)),
        )
        assertEquals(listOf(2, 3, 1), applyDexQuery(listOf(1, 2, 3), stats::get, null, DexSort.ATTACK))
        assertEquals(listOf(2, 1, 3), applyDexQuery(listOf(1, 2, 3), stats::get, null, DexSort.TOTAL))
        assertEquals(listOf(1, 2, 3), applyDexQuery(listOf(1, 2, 3), stats::get, null, DexSort.NUMBER))
    }

    @Test fun anEntryWithoutStatsIsDroppedByATypeFilterAndSortedLast() {
        val stats = mapOf(1 to DexStats(grass, TYPE_NONE, listOf(45, 49, 49, 65, 65, 45)))
        assertEquals(listOf(1), applyDexQuery(listOf(9, 1), stats::get, TypeFilter(grass, TypeRelation.IS), DexSort.NUMBER))
        assertEquals(listOf(1, 9), applyDexQuery(listOf(9, 1), stats::get, null, DexSort.SPEED))
    }

    @Test fun theTypeListLeavesOutTheUnusedMysteryType() {
        val types = pickableTypes()
        assertTrue(water in types)
        assertFalse(9 in types)
    }
}
