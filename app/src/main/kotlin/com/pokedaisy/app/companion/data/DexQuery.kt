package com.pokedaisy.app.companion.data

import com.pokedaisy.app.companion.i18n.stripAccents
import com.pokedaisy.app.companion.i18n.tk

/**
 * The DEX list's TYPE relations, SORT and NAME search, worked out from each entry's types, base stats and name and the
 * running game's own type chart. Pure: the screen loads the entries, this decides who shows and in what order.
 */

/** How an entry relates to the picked type. [label] is English, with the type as {0}. */
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

    /** The number this sort reads off [baseStats]; null for [NUMBER], or with no stats read. */
    fun value(baseStats: List<Int>): Int? = when {
        this == NUMBER || baseStats.isEmpty() -> null
        this == TOTAL -> baseStats.sum()
        else -> baseStats.getOrNull(requireNotNull(stat))
    }
}

/** An entry's own types, without TYPE_NONE and without the second when it repeats the first. */
private fun ownTypes(type1: Int, type2: Int) = listOf(type1, type2).filter { it != TYPE_NONE }.distinct()

/** Whether a Pokémon of [type1] / [type2] is in [relation] to [type] (the running game's type ids and chart). */
fun matchesType(type1: Int, type2: Int, type: Int, relation: TypeRelation): Boolean = when (relation) {
    TypeRelation.IS -> type in ownTypes(type1, type2)
    TypeRelation.HITS -> ownTypes(type1, type2).any { singleTypeMultiplierPct(it, type) > 100 }
    TypeRelation.RESISTS -> typeMultiplierPct(type, type1, type2) < 100
}

/**
 * [nationals] (already filtered, in dex order) in [sort]'s order: the highest stat first, the dex order on ties. An
 * entry with no stats ([statsOf] null: not read, or not seen yet) goes last.
 */
fun sortDex(nationals: List<Int>, sort: DexSort, statsOf: (Int) -> List<Int>?): List<Int> {
    if (sort == DexSort.NUMBER) return nationals
    return nationals.withIndex()
        .sortedWith(compareByDescending<IndexedValue<Int>> { statsOf(it.value)?.let(sort::value) ?: Int.MIN_VALUE }.thenBy { it.index })
        .map { it.value }
}

private const val HANGUL_FIRST = 0xAC00
private const val HANGUL_LAST = 0xD7A3

/**
 * [name] as NAME search compares it: capitals, accents dropped (é -> E) and spaces gone, so the letter grid's A-Z find
 * every Latin name ("Poké" and "POKE" match). Other characters (♀, ♂, '.', '-', digits, kana, Hangul syllables) stay
 * whole.
 */
fun foldDexName(name: String): String = buildString(name.length) {
    for (c in name) {
        if (c.isWhitespace()) continue
        // stripAccents splits a Hangul syllable into its letters: a key of the grid is the whole syllable.
        if (c.code in HANGUL_FIRST..HANGUL_LAST) append(c) else append(stripAccents(c.toString()).uppercase())
    }
}

/** [nationals] through NAME search: the entries whose name contains [query] (folded, [foldDexName]). An empty [query] keeps everything. */
fun filterDexByName(nationals: List<Int>, query: String, nameOf: (Int) -> String?): List<Int> {
    val q = foldDexName(query)
    if (q.isEmpty()) return nationals
    return nationals.filter { n -> nameOf(n)?.let { foldDexName(it).contains(q) } == true }
}

/** The letter grid's keys: A-Z where a name uses Latin letters, then any other character the [names] have (♀, ♂, '.', kana, ...), in code order. */
fun dexNameKeys(names: Collection<String>): List<Char> {
    val used = names.flatMap { foldDexName(it).toList() }.toSet()
    val letters = if (used.any { it in 'A'..'Z' }) ('A'..'Z').toList() else emptyList()
    return letters + used.filter { it !in 'A'..'Z' }.sorted()
}

/**
 * The keys that would still find a name after [query]: every character that follows the query somewhere in one of
 * [names] (any character of them for an empty query). The letter grid dims the rest, so a typed name never runs into
 * an empty list.
 */
fun dexNameNextKeys(names: Collection<String>, query: String): Set<Char> {
    val q = foldDexName(query)
    val next = HashSet<Char>()
    for (name in names) {
        val f = foldDexName(name)
        var at = f.indexOf(q)
        while (at >= 0 && at + q.length < f.length) {
            next += f[at + q.length]
            at = f.indexOf(q, at + 1)
        }
    }
    return next
}
