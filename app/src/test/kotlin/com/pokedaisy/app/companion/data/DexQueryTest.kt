package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The DEX list's TYPE relations, SORT and NAME search, over Emerald's own type chart. */
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
        assertTrue(matchesType(water, ground, ground, TypeRelation.IS))
        assertFalse(matchesType(fire, TYPE_NONE, water, TypeRelation.IS))
    }

    @Test fun hitsHardMeansOneOfItsOwnTypesIsSuperEffective() {
        assertTrue(matchesType(grass, fighting, water, TypeRelation.HITS))
        assertTrue(matchesType(electric, TYPE_NONE, water, TypeRelation.HITS))
        assertFalse(matchesType(fire, TYPE_NONE, water, TypeRelation.HITS))
        assertFalse(matchesType(water, ground, water, TypeRelation.HITS))
    }

    @Test fun resistsMeansThePickedTypeDoesLessThanOnceOverBothTypes() {
        assertTrue(matchesType(water, TYPE_NONE, water, TypeRelation.RESISTS))
        assertTrue(matchesType(dragon, TYPE_NONE, water, TypeRelation.RESISTS))
        assertFalse(matchesType(fire, TYPE_NONE, water, TypeRelation.RESISTS))
        // Water ½x on Water, 2x on Ground: 1x, not a resist.
        assertFalse(matchesType(water, ground, water, TypeRelation.RESISTS))
    }

    @Test fun aStatSortGoesHighestFirstAndKeepsDexOrderOnTies() {
        val stats = mapOf(
            1 to listOf(45, 49, 49, 65, 65, 45),
            2 to listOf(60, 62, 63, 80, 80, 60),
            3 to listOf(39, 62, 43, 60, 50, 65),
        )
        // ATTACK 49 / 62 / 62: the tie keeps the dex order.
        assertEquals(listOf(2, 3, 1), sortDex(listOf(1, 2, 3), DexSort.ATTACK, stats::get))
        // Totals 318 / 405 / 319.
        assertEquals(listOf(2, 3, 1), sortDex(listOf(1, 2, 3), DexSort.TOTAL, stats::get))
        assertEquals(listOf(3, 2, 1), sortDex(listOf(1, 2, 3), DexSort.SPEED, stats::get))
        assertEquals(listOf(1, 2, 3), sortDex(listOf(1, 2, 3), DexSort.NUMBER, stats::get))
    }

    @Test fun anEntryWithoutStatsIsSortedLast() {
        val stats = mapOf(1 to listOf(45, 49, 49, 65, 65, 45), 7 to emptyList())
        assertEquals(listOf(1, 9, 7), sortDex(listOf(9, 7, 1), DexSort.SPEED, stats::get))
        assertNull(DexSort.TOTAL.value(emptyList()))
    }

    // NAME search: invented names.
    private val names = mapOf(1 to "EMBERPUP", 2 to "Emberhound", 3 to "Pokéfin", 5 to "MR. TIDE")

    @Test fun nameSearchIsContainsIgnoringCaseAccentsAndSpaces() {
        val all = names.keys.toList()
        assertEquals(listOf(1, 2), filterDexByName(all, "ember", names::get))
        assertEquals(listOf(1), filterDexByName(all, "berp", names::get))
        assertEquals(listOf(3), filterDexByName(all, "POKEF", names::get))
        assertEquals(listOf(5), filterDexByName(all, "MR.T", names::get))
        assertEquals(all, filterDexByName(all, "", names::get))
    }

    @Test fun anEntryWithNoNameNeverMatches() {
        assertEquals(emptyList<Int>(), filterDexByName(listOf(1), "EMBER") { null })
    }

    @Test fun theGridOffersAToZPlusTheNamesOtherCharacters() {
        val keys = dexNameKeys(listOf("MR. TIDE", "Pokéfin"))
        assertEquals(('A'..'Z').toList(), keys.take(26))
        assertEquals(listOf('.'), keys.drop(26))
    }

    @Test fun aGameWithoutLatinNamesGetsNoAToZ() {
        assertEquals(listOf('カ', 'ヒ'), dexNameKeys(listOf("ヒカ", "ビガ")))
        // A Hangul syllable stays one key.
        assertEquals(listOf('각'), dexNameKeys(listOf("각")))
    }

    @Test fun onlyKeysThatStillFindANameAreLive() {
        val all = names.values
        assertEquals(setOf('P', 'H'), dexNameNextKeys(all, "EMBER"))
        // Every occurrence counts: E is followed by M, R and N here.
        assertEquals(setOf('M', 'R', 'N'), dexNameNextKeys(listOf("EMBER", "PEN"), "E"))
        // A full name has nothing after it.
        assertEquals(emptySet<Char>(), dexNameNextKeys(all, "EMBERPUP"))
        assertTrue('Z' !in dexNameNextKeys(all, ""))
    }
}
