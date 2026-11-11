package com.pokedaisy.app.companion.data

import com.pokedaisy.app.companion.i18n.tk

/**
 * The DEX list's TYPE filter and SORT, worked out from each entry's types and base stats (read from the ROM) and the
 * running game's own type chart. Pure: the screen loads the entries, this decides who shows and in what order.
 */

/** How an entry relates to the picked type. */
enum class TypeRelation(val label: String) {
    /** One of its own types is the picked one. */
    IS(tk("IS {0}")),
    /** One of its own types hits the picked type super effectively. */
    HITS(tk("HITS {0} HARD")),
    /** The picked type does less than 1x to it (½x, ¼x or nothing). */
    RESISTS(tk("RESISTS {0}")),
}

/** What the list is sorted by: the dex's own order, or a base stat (highest first). [stat] = its index in HP, ATK, DEF, SP.ATK, SP.DEF, SPEED. */
enum class DexSort(val label: String, val stat: Int?) {
    NUMBER(tk("DEX NO."), null),
    HP(tk("HP"), 0),
    ATTACK(tk("ATTACK"), 1),
    DEFENSE(tk("DEFENSE"), 2),
    SP_ATK(tk("SP. ATK"), 3),
    SP_DEF(tk("SP. DEF"), 4),
    SPEED(tk("SPEED"), 5),
    TOTAL(tk("TOTAL"), null);

    /** The number this sort reads off [baseStats]; null for [NUMBER]. */
    fun value(baseStats: List<Int>): Int? = when {
        this == NUMBER -> null
        this == TOTAL -> baseStats.sum()
        else -> baseStats.getOrNull(requireNotNull(stat))
    }
}

/** A type filter: [type] is the running game's type id. */
data class TypeFilter(val type: Int, val relation: TypeRelation)

/** An entry's own types, without TYPE_NONE and without the second when it repeats the first. */
private fun ownTypes(type1: Int, type2: Int) = listOf(type1, type2).filter { it != TYPE_NONE }.distinct()

/** Whether a Pokémon of [type1] / [type2] passes [f], by the running game's type chart. */
fun matchesType(type1: Int, type2: Int, f: TypeFilter): Boolean = when (f.relation) {
    TypeRelation.IS -> f.type in ownTypes(type1, type2)
    TypeRelation.HITS -> ownTypes(type1, type2).any { singleTypeMultiplierPct(it, f.type) > 100 }
    TypeRelation.RESISTS -> typeMultiplierPct(f.type, type1, type2) < 100
}

/** What the filter and sort need from one entry. */
data class DexStats(val type1: Int, val type2: Int, val baseStats: List<Int>)

/**
 * [nationals] (already through the seen / caught filter, in dex order) through [type] and [sort]. An entry with no
 * [stats] can't be judged: a type filter drops it, a stat sort puts it last. The dex order breaks ties.
 */
fun applyDexQuery(nationals: List<Int>, stats: (Int) -> DexStats?, type: TypeFilter?, sort: DexSort): List<Int> {
    val kept = if (type == null) nationals else nationals.filter { n -> stats(n)?.let { matchesType(it.type1, it.type2, type) } == true }
    if (sort == DexSort.NUMBER) return kept
    return kept.withIndex()
        .sortedWith(compareByDescending<IndexedValue<Int>> { stats(it.value)?.let { s -> sort.value(s.baseStats) } ?: Int.MIN_VALUE }.thenBy { it.index })
        .map { it.value }
}

/** The types the TYPE filter offers: the running game's, by id, without the unused "???". */
fun pickableTypes(): List<Int> = activeTypeNames.filter { (_, name) -> name.isNotBlank() && name != "???" }.keys.sorted()
