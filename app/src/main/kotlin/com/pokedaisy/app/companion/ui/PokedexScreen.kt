package com.pokedaisy.app.companion.ui

import androidx.compose.ui.graphics.RectangleShape
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.DexEntry
import com.pokedaisy.app.companion.data.DexSort
import com.pokedaisy.app.companion.data.DexStats
import com.pokedaisy.app.companion.data.TypeFilter
import com.pokedaisy.app.companion.data.TypeRelation
import com.pokedaisy.app.companion.data.applyDexQuery
import com.pokedaisy.app.companion.data.dexNameKeys
import com.pokedaisy.app.companion.data.dexNameNextKeys
import com.pokedaisy.app.companion.data.filterDexByName
import com.pokedaisy.app.companion.data.pickableTypes
import com.pokedaisy.app.companion.data.typeLabel
import com.pokedaisy.app.companion.data.typeName
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.GuideTables
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.data.PokedexSource
import com.pokedaisy.app.companion.data.PokedexState
import com.pokedaisy.app.companion.data.PokedexTables
import com.pokedaisy.app.companion.data.dexCategoryLine
import com.pokedaisy.app.companion.data.formatDexHeight
import com.pokedaisy.app.companion.data.formatDexWeight
import com.pokedaisy.app.companion.data.speciesName
import com.pokedaisy.app.companion.data.spriteAsset
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import androidx.compose.foundation.interaction.collectIsFocusedAsState

/** Which entries the list shows. SEEN = seen but not caught yet. [label] is English; OptionRows translates it. */
enum class DexFilter(val label: String) {
    ALL(tk("ALL")), CAUGHT(tk("CAUGHT")),
    /** In the party or the PC right now ([PokedexState.held]); offered only where the PC is read. */
    HELD(tk("PARTY + PC")),
    SEEN(tk("SEEN ONLY")), UNSEEN(tk("NOT SEEN")),
}

/**
 * The DEX tab's view state, held by CompanionScreen so the filter, the dex
 * (regional / national) and the list's scroll position survive opening an
 * entry and switching tabs.
 */
@Stable
class DexUiState(val list: LazyListState) {
    var filter by mutableStateOf(DexFilter.ALL)
    /** The TYPE filter (null = any type) and the SORT, over the seen / caught filter. */
    var type by mutableStateOf<TypeFilter?>(null)
    var sort by mutableStateOf(DexSort.NUMBER)
    /** NAME search ("" = any name), and whether its letter grid is open in the left column. */
    var name by mutableStateOf("")
    var typing by mutableStateOf(false)
    /** null = follow the save (national once it's unlocked). */
    var national by mutableStateOf<Boolean?>(null)
    /** National dex number of the open entry; null = the list. */
    var open by mutableStateOf<Int?>(null)
    /** The entry last opened: where the D-pad's cursor goes back to in the list (the companion's window). */
    var lastOpened by mutableStateOf<Int?>(null)
    /** The entry page's section (INFO / EVOLVE / AREA / MOVES), kept while stepping through entries. */
    var section by mutableStateOf(DexSection.INFO)

    fun showsNational(dex: PokedexState) = !dex.tables.hasRegional || (national ?: dex.national)

    /** The dex numbers the list shows: [order] (the whole dex, see
     * [rememberDexOrder]) through the filter, then NAME search while [nameOf] can name them (null: names not read yet). */
    fun entries(dex: PokedexState, order: DexOrder, nameOf: ((Int) -> String?)? = null): List<Int> {
        val filtered = order.nationals.filter { n ->
            when (filter) {
                DexFilter.ALL -> true
                DexFilter.CAUGHT -> n in dex.caught
                // A game whose PC isn't read keeps no HELD: everything shows, as ALL.
                DexFilter.HELD -> dex.held?.contains(n) ?: true
                DexFilter.SEEN -> n in dex.seen && n !in dex.caught
                DexFilter.UNSEEN -> n !in dex.seen
            }
        }
        return if (nameOf == null) filtered else filterDexByName(filtered, name, { it in dex.seen }, nameOf)
    }
}

/**
 * The dex being shown, as national numbers in its own order - national, or the
 * regional dex (Hoenn's order differs: TREECKO is HOENN No.001), with the number
 * each entry is listed under there, from [first] (Unova's starts at No.000: VICTINI).
 */
class DexOrder(val nationals: List<Int>, private val regional: Boolean, private val first: Int = 1) {
    private val position = if (regional) nationals.withIndex().associate { it.value to it.index + first } else emptyMap()
    private val members = if (regional) position.keys else nationals.toHashSet()
    fun number(national: Int): Int = if (regional) position[national] ?: national else national
    operator fun contains(national: Int) = national in members
}

@Composable
fun rememberDexOrder(dex: PokedexState, national: Boolean): DexOrder {
    val t = dex.tables
    val regional by produceKeyedState(PokedexSource.cachedRegionalOrder(t), t) {
        if (value == null) value = withContext(Dispatchers.IO) { PokedexSource.regionalOrder(t) }
    }
    val species = rememberSpeciesMap(t)
    return remember(t, national, regional, species) {
        when {
            // Numbers with no species (a hack's unused slots) aren't listed.
            national -> DexOrder((1..t.nationalCount).filter { species == null || species.getOrElse(it) { 0 } != 0 }, regional = false)
            // Until a non-Kanto regional order has loaded: an empty list, not a wrong one.
            else -> DexOrder(regional?.filter { it in 1..t.nationalCount } ?: emptyList(), regional = true,
                first = if (t.gen4?.gen5 == true) 0 else 1)
        }
    }
}

/** Dex number -> the name the list shows, through [species] (null until it's read; 0 = no species there). */
private fun dexNameOf(species: IntArray?): ((Int) -> String?)? =
    species?.let { map -> { n: Int -> map.getOrElse(n) { 0 }.takeIf { it > 0 }?.let(::speciesName) } }

/** Dex number -> species id, read from the ROM once (off the main thread). */
@Composable
private fun rememberSpeciesMap(t: PokedexTables): IntArray? =
    produceKeyedState(PokedexSource.cachedSpeciesMap(t), t) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { PokedexSource.speciesFor(t, 1); PokedexSource.cachedSpeciesMap(t) }
        }
    }.value

/**
 * Types and base stats for the TYPE filter and SORT, read once per entry off the main thread, only while one of them is
 * on. Entries the player hasn't seen are left out: the list mustn't tell their types before the game does.
 */
@Composable
private fun rememberDexStats(dex: PokedexState, nationals: List<Int>, needed: Boolean): Map<Int, DexStats>? {
    val t = dex.tables
    val seen = nationals.filter { it in dex.seen }
    return produceKeyedState<Map<Int, DexStats>?>(null, t, seen, needed) {
        value = if (!needed) null else withContext(Dispatchers.IO) {
            seen.mapNotNull { n -> PokedexSource.entry(t, n)?.let { e -> n to DexStats(e.type1, e.type2, e.baseStats) } }.toMap()
        }
    }.value
}

/** What a pick-list over the DEX list is choosing. */
private sealed class DexPick {
    object Type : DexPick()
    class Relation(val type: Int) : DexPick()
    object Sort : DexPick()
}

/**
 * The POKéDEX: every entry of the regional or National Dex with the save's
 * seen / caught marks, filterable, over the game backdrop. The left column is
 * the counts + filters (in the OPTION look), the right the numbered list; a
 * row opens its [PokedexEntryScreen].
 */
@Composable
fun PokedexScreen(dex: PokedexState, ui: DexUiState, modifier: Modifier = Modifier) {
    // Dense pages sized to Pixel Operator: a game font keeps its metrics here.
    val m = rememberGbaTextMetrics()
    val small = rememberGbaTextMetrics(1f)
    val national = ui.showsNational(dex)
    val order = rememberDexOrder(dex, national)
    val last = order.nationals.size
    val seen = dex.seen.count { it in order }
    val caught = dex.caught.count { it in order }
    val species = rememberSpeciesMap(dex.tables)
    // PARTY + PC only where the PC is read; a pick kept from another game reads as ALL.
    val filters = DexFilter.entries.filter { it != DexFilter.HELD || dex.held != null }
    val nameOf = dexNameOf(species)
    val listed = ui.entries(dex, order, nameOf)
    val querying = ui.type != null || ui.sort != DexSort.NUMBER
    val stats = rememberDexStats(dex, order.nationals, querying)
    // NAME search waits on the names as TYPE / SORT wait on the stats.
    val loading = (querying && stats == null) || (ui.name.isNotEmpty() && nameOf == null)
    // The letter grid's keys come from the seen names only: it can't spell out one the player hasn't met.
    val seenNames = remember(species, dex.seen, order.nationals) {
        nameOf?.let { f -> order.nationals.filter { it in dex.seen }.mapNotNull(f) } ?: emptyList()
    }
    val entries = if (!querying || stats == null) listed else applyDexQuery(listed, { stats[it] }, ui.type, ui.sort)
    var pick by remember { mutableStateOf<DexPick?>(null) }
    val u = m.u

    Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.typing) {
            val keys = remember(seenNames) { dexNameKeys(seenNames) }
            val live = remember(seenNames, ui.name) { dexNameNextKeys(seenNames, ui.name) }
            DexNameSearch(
                ui.name, keys, live, m, small,
                onQuery = { ui.name = it },
                onClose = { ui.typing = false },
                modifier = Modifier.fillMaxHeight().weight(0.36f),
            )
        } else Column(Modifier.fillMaxHeight().weight(0.36f), verticalArrangement = Arrangement.spacedBy(u * 4)) {
            OptionTitleWindow(tk("POKéDEX"), m)
            // A short companion (the RG DS) needs the room for the filters: CAUGHT's count is OWNED's there.
            if (!LocalShortCompanion.current) OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = u * 8, vertical = u * 4), verticalArrangement = Arrangement.spacedBy(u * 3)) {
                    CountLine(tr("SEEN"), seen, small)
                    CountLine(tr("OWNED"), caught, small)
                }
            }
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
                val counts = mapOf(
                    DexFilter.ALL to last,
                    DexFilter.CAUGHT to caught,
                    DexFilter.HELD to (dex.held?.count { it in order } ?: 0),
                    DexFilter.SEEN to seen - caught,
                    DexFilter.UNSEEN to last - seen,
                )
                OptionRows(
                    filters.map { f -> Triple(f.label, "${counts[f]}", { ui.filter = f }) },
                    small,
                    Modifier.fillMaxSize(),
                    selected = filters.indexOf(ui.filter).coerceAtLeast(0),
                    labelWeight = 0.72f,
                )
            }
            // NAME opens the letter grid in this column; TYPE and SORT open pick-lists (a type, then how it relates).
            // Each button says what's on.
            OptionButton(
                if (ui.name.isEmpty()) tr("ANY NAME") else tr("NAME: {0}", ui.name), small,
                onClick = { ui.typing = true },
                modifier = Modifier.fillMaxWidth(),
            )
            OptionButton(
                ui.type?.let { tr(it.relation.label, typeLabel(typeName(it.type))) } ?: tr("ANY TYPE"), small,
                onClick = { pick = DexPick.Type },
                modifier = Modifier.fillMaxWidth(),
            )
            OptionButton(
                tr("SORT: {0}", tr(ui.sort.label)), small,
                onClick = { pick = DexPick.Sort },
                modifier = Modifier.fillMaxWidth(),
            )
            // Two dexes, so a plain toggle (never more than two values per button).
            // Games with one dex (Gaia, Celia's hack) get no toggle.
            dex.tables.regionName?.let { region ->
                OptionButton(
                    if (national) tr("NATIONAL DEX") else tr("{0} DEX", region), small,
                    onClick = { ui.national = !national },
                    modifier = Modifier.fillMaxWidth(),
                    emphasis = true,
                )
            }
        }

        OptionListWindow(m, Modifier.fillMaxHeight().weight(0.64f)) {
            if (loading || entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GbaText(if (loading) tr("LOADING") else tr("NONE"), OptionColors.muted, OptionColors.mutedShadow, m)
                }
            } else {
                // The D-pad starts on the entry last opened while it's in the list, else on the top row showing.
                val back = ui.lastOpened?.takeIf { it in entries }
                val top = entries.getOrNull(ui.list.firstVisibleItemIndex)
                LazyColumn(Modifier.fillMaxSize(), state = ui.list) {
                    items(entries, key = { it }) { n ->
                        DexRow(
                            order.number(n), species?.getOrNull(n) ?: 0, n in dex.caught, n in dex.seen, m, small,
                            start = n == (back ?: top),
                            stat = stats?.get(n)?.let { ui.sort.value(it.baseStats) },
                        ) { ui.lastOpened = n; ui.open = n }
                    }
                }
            }
        }
    }

    when (val p = pick) {
        DexPick.Type -> {
            val any = -1
            OptionSelector(
                tk("TYPE"), listOf(any) + pickableTypes(), ui.type?.type ?: any,
                { if (it == any) tr("ANY TYPE") else typeLabel(typeName(it)) }, small,
                onPick = { t -> if (t == any) { ui.type = null; pick = null } else pick = DexPick.Relation(t) },
                onDismiss = { pick = null },
            )
        }
        is DexPick.Relation -> OptionSelector(
            typeLabel(typeName(p.type)), TypeRelation.entries, ui.type?.takeIf { it.type == p.type }?.relation ?: TypeRelation.IS,
            { tr(it.label, typeLabel(typeName(p.type))) }, small,
            onPick = { ui.type = TypeFilter(p.type, it); pick = null },
            onDismiss = { pick = null },
        )
        DexPick.Sort -> OptionSelector(
            tk("SORT"), DexSort.entries, ui.sort, { tr(it.label) }, small,
            onPick = { ui.sort = it; pick = null },
            onDismiss = { pick = null },
        )
        null -> Unit
    }
}

@Composable
private fun CountLine(label: String, value: Int, small: GbaTextMetrics) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GbaText(label, OptionColors.label, OptionColors.labelShadow, small, Modifier.weight(1f))
        GbaText("$value", OptionColors.value, OptionColors.valueShadow, small)
    }
}

/** "● [icon] No.025 PIKACHU" - the ball marks a caught entry, a grey one a seen one.
 * [n] is the number in the dex being shown. */
@Composable
private fun DexRow(
    n: Int, species: Int, caught: Boolean, seen: Boolean,
    m: GbaTextMetrics, small: GbaTextMetrics,
    /** Where the D-pad starts in the list (the companion's window). */
    start: Boolean = false,
    /** The stat the list is sorted by, drawn at the row's end; null = none. */
    stat: Int? = null,
    onClick: () -> Unit,
) {
    val u = m.u
    val noRipple = remember { MutableInteractionSource() }
    // The D-pad's row (the companion's window) turns white, the OPTION lists' cursor.
    val focused by noRipple.collectIsFocusedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .focusStart(start)
            .fillMaxWidth()
            .height(u * 34)
            .background(if (focused) OptionColors.rowSelected else Color.Transparent)
            .drawBehind { drawRowDivider(OptionColors.divider, u.toPx(), 4 * u.toPx()) }
            .soundClickable(interactionSource = noRipple, indication = null, onClick = onClick)
            .padding(horizontal = u * 4),
    ) {
        DexMark(caught, seen, Modifier.size(u * 12))
        Spacer(Modifier.width(u * 4))
        Box(Modifier.size(u * 32).alpha(if (seen) 1f else 0.45f)) {
            if (species > 0) SpeciesIcon(spriteAsset("pokemon", species), size = u * 32)
        }
        Spacer(Modifier.width(u * 6))
        GbaText(dexNo(n), OptionColors.muted, OptionColors.mutedShadow, small)
        Spacer(Modifier.width(u * 8))
        GbaText(
            if (species > 0) speciesName(species) else "-----",
            if (seen) OptionColors.label else OptionColors.muted,
            if (seen) OptionColors.labelShadow else OptionColors.mutedShadow,
            m, Modifier.weight(1f),
        )
        stat?.let {
            Spacer(Modifier.width(u * 6))
            GbaText("$it", OptionColors.value, OptionColors.valueShadow, m)
        }
    }
}

private fun dexNo(n: Int) = "No.%03d".format(n)

internal val caughtBall = mapOf(
    'K' to Color(0xFF202020), 'R' to Color(0xFFE83028), 'W' to Color.White, 'H' to Color(0xFFF8A8A0),
)
private val seenBall = mapOf(
    'K' to Color(0xFF8C8C94), 'R' to Color(0xFFC6C5C5), 'W' to Color(0xFFF0F0F0), 'H' to Color(0xFFE0E0E0),
)
// Dark mode: the seen-only ball as dim as the light one is faint, on the dark list.
private val seenBallNight = mapOf(
    'K' to Color(0xFF70778A), 'R' to Color(0xFF3E4552), 'W' to Color(0xFF4C5464), 'H' to Color(0xFF454C5A),
)

@Composable
private fun DexMark(caught: Boolean, seen: Boolean, modifier: Modifier) {
    when {
        caught -> PixelArt(PixelIcons.pokeBall, caughtBall, modifier.semantics { contentDescription = tr("Caught") })
        seen -> PixelArt(PixelIcons.pokeBall, if (OptionColors.dark) seenBallNight else seenBall, modifier.semantics { contentDescription = tr("Seen") })
        else -> Spacer(modifier)
    }
}

/**
 * One dex page, filling the tab: the front sprite, number, name, category,
 * types, height / weight and footprint on the left; on the right, chips switch
 * between INFO (the dex text, base stats and abilities), EVOLVE, AREA and
 * MOVES ([DexSection], those the game's tables have) - all read from the ROM;
 * [guide] (the GUIDE's tables) feeds AREA and, for most games, MOVES. Up /
 * down step through the list as it's currently filtered; back returns to it.
 * [current] is passed in (not read from [DexUiState.open]) so the page stays
 * drawn while it animates out after Back.
 */
@Composable
fun PokedexEntryScreen(dex: PokedexState, ui: DexUiState, current: Int, modifier: Modifier = Modifier, guide: GuideTables? = null) {
    // Dense pages sized to Pixel Operator: a game font keeps its metrics here.
    val m = rememberGbaTextMetrics()
    val small = rememberGbaTextMetrics(1f)
    val order = rememberDexOrder(dex, ui.showsNational(dex))
    val entries = ui.entries(dex, order, dexNameOf(rememberSpeciesMap(dex.tables)))
    val index = entries.indexOf(current)
    val step = { d: Int ->
        if (entries.isNotEmpty()) {
            val i = if (index < 0) 0 else (index + d + entries.size) % entries.size
            ui.open = entries[i]
        }
    }
    // Keep the list's scroll position on the entry being looked at.
    LaunchedEffect(index) { if (index >= 0) ui.list.scrollToItem((index - 3).coerceAtLeast(0)) }

    SummaryFrame(
        m, onBack = { ui.open = null }, modifier = modifier,
        buttons = {
            NavButton(PixelIcons.arrowUp, tr("Previous entry"), MonGreen, MonGreenDark, entries.size > 1) { step(-1) }
            NavButton(PixelIcons.arrowDown, tr("Next entry"), MonGreen, MonGreenDark, entries.size > 1) { step(1) }
            if (index >= 0) {
                Spacer(Modifier.width(8.dp))
                BackdropText("${index + 1} / ${entries.size}", small)
            }
        },
    ) {
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                val down = targetState > initialState
                (fadeIn(tween(160)) + slideInVertically(tween(180)) { h -> if (down) h / 10 else -h / 10 }) togetherWith
                    fadeOut(tween(110))
            },
            modifier = Modifier.fillMaxSize(),
            label = "dex-entry",
        ) { n ->
            val entry by produceKeyedState(PokedexSource.cachedEntry(dex.tables, n), dex.tables, n) {
                if (value == null) value = withContext(Dispatchers.IO) { PokedexSource.entry(dex.tables, n) }
            }
            EntryPage(n, order.number(n), entry, dex.tables, guide, n in dex.caught, n in dex.seen, m, small, ui)
        }
    }
}

@Composable
private fun EntryPage(
    n: Int, shownNo: Int, e: DexEntry?, t: PokedexTables, guide: GuideTables?, caught: Boolean, seen: Boolean,
    m: GbaTextMetrics, small: GbaTextMetrics, ui: DexUiState,
) {
    val u = m.u
    val gap = u * 3
    val species = e?.species ?: 0
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(0.42f).fillMaxHeight().padding(end = gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GbaText(dexNo(shownNo), OptionColors.value, OptionColors.valueShadow, small)
                Spacer(Modifier.width(u * 6))
                GbaText(if (species > 0) speciesName(species) else "-----", OptionColors.label, OptionColors.labelShadow, m, Modifier.weight(1f))
                DexMark(caught, seen, Modifier.size(u * 12))
            }
            // The sprite gets whatever height the text below leaves it.
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val sprite = rememberRomBitmap(t, species, PokedexSource::cachedFrontSprite, PokedexSource::frontSprite)
                val paper = OptionColors.spritePaper
                if (paper != null) {
                    // Dark mode on Gen 1: the picture on its own white card, padded inside the dark well.
                    Box(Modifier.fillMaxSize().clip(PixelRoundedShape(u * 4)).background(OptionColors.listFill).padding(u * 4)) {
                        PixelImage(sprite, 64, Modifier.fillMaxSize().clip(PixelRoundedShape(u * 3)).background(paper).padding(u * 4))
                    }
                } else {
                    PixelImage(sprite, 64, Modifier.fillMaxSize().clip(PixelRoundedShape(u * 4)).background(OptionColors.listFill))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.footprints != 0L) {
                    val print = rememberRomBitmap(t, species, PokedexSource::cachedFootprint, PokedexSource::footprint)
                    PixelImage(print, 16, Modifier.size(u * 16))
                    Spacer(Modifier.width(u * 5))
                }
                // A narrow column (a short screen's, the RG DS): the category takes a second line rather than being cut.
                GbaText(e?.let { dexCategoryLine(t, it.category) } ?: "", OptionColors.label, OptionColors.labelShadow, small, maxLines = 2)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { e?.types?.forEach { TypeBadge(it) } }
            val height = @Composable { Stat(tr("HT"), e?.let { formatDexHeight(t, it) } ?: "", small) }
            val weight = @Composable { Stat(tr("WT"), e?.let { formatDexWeight(t, it) } ?: "", small) }
            // A short screen: HT over WT (side by side, the weight's unit was cut off), the sprite a line smaller.
            if (LocalShortCompanion.current) {
                height()
                weight()
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                height()
                Spacer(Modifier.width(u * 10))
                weight()
            }
        }
        Separator(m, Modifier.fillMaxHeight(), vertical = true)
        val sections = remember(t, guide) { dexSections(t, guide) }
        val section = ui.section.takeIf { it in sections } ?: DexSection.INFO
        Column(Modifier.weight(0.58f).fillMaxHeight().padding(start = gap + u * 2)) {
            if (sections.size > 1) {
                DexSectionChips(sections, section, small) { ui.section = it }
                Spacer(Modifier.height(u * 3))
            }
            val body = Modifier.fillMaxWidth().weight(1f)
            when {
                section == DexSection.EVOLVE -> Box(body) { DexEvolveSection(t, species, m, small) { no -> ui.open = no } }
                section == DexSection.AREA && guide != null -> Box(body) { DexAreaSection(guide, species, m, small) }
                section == DexSection.MOVES -> Box(body) { DexMovesSection(t, guide, species, m, small) }
                else -> DexInfoSection(e, small, m, body)
            }
        }
    }
}

/** INFO: the dex text, base stats, abilities, egg groups, gender and catch rate. */
@Composable
private fun DexInfoSection(e: DexEntry?, small: GbaTextMetrics, m: GbaTextMetrics, modifier: Modifier) {
    val u = m.u
    // Nothing to tap: one D-pad stop that scrolls (the companion's window).
    val scroll = rememberScrollState()
    Column(modifier.dpadScroll(scroll, RectangleShape, m.lineHeight * 3).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(u * 2)) {
        GbaText(e?.description ?: "", OptionColors.label, OptionColors.labelShadow, small, maxLines = 6)
        Separator(m, Modifier.fillMaxWidth())
        if (e != null) {
            BaseStats(e.baseStats, small)
            Separator(m, Modifier.fillMaxWidth())
            // Gen 1 has none of these: no abilities, eggs or genders (genderRatio -1).
            // Two names outrun a line in a wide game font (Polished Crystal's): one a line there.
            val wide = com.pokedaisy.app.companion.ui.theme.LocalGameFont.current != null
            if (e.abilities.isNotEmpty()) {
                InfoLine(tr("ABILITY"), e.abilities.joinToString(if (wide) "\n" else " / "), small, maxLines = if (wide) e.abilities.size else 1)
            }
            e.hiddenAbility?.let { InfoLine(tr("HIDDEN|ability"), it, small) }
            if (e.eggGroups.isNotEmpty()) InfoLine(tr("EGG GROUP"), e.eggGroups.joinToString(" / ") { dexCase(it) }, small)
            if (e.genderRatio >= 0) InfoLine(tr("GENDER"), genderLabel(e.genderRatio), small)
            InfoLine(tr("CATCH RATE"), "${e.catchRate}", small)
        }
    }
}

/** The shared caps tables (egg groups) in the game's casing: FireRed / Emerald
 * print them in capitals, Unbound in Title Case ("Water 1"). */
private fun dexCase(s: String): String = when (activeGame) {
    GameKind.FIRERED, GameKind.EMERALD -> s
    else -> s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        .split('-').joinToString("-") { w -> w.replaceFirstChar { it.uppercase() } }
}

private val STAT_LABELS = listOf(tk("HP"), tk("ATTACK"), tk("DEFENSE"), tk("SP. ATK"), tk("SP. DEF"), tk("SPEED"))
/** Gen 1's five, in its own order: one SPECIAL for both. */
private val STAT_LABELS_GEN1 = listOf(tk("HP"), tk("ATTACK"), tk("DEFENSE"), tk("SPEED"), tk("SPECIAL"))

/** Base stats as labelled bars (255 = full), then their total. */
@Composable
private fun BaseStats(stats: List<Int>, small: GbaTextMetrics) {
    val u = small.u
    // Room for "DEFENSE" in a fixed-width game font (Gen 1's 8px letters).
    val gameFont = com.pokedaisy.app.companion.ui.theme.LocalGameFont.current != null
    val labelU = if (gameFont) 64 else 58
    // And for three digits in it (a base stat of 100 or more read "1..." there).
    val valueU = if (gameFont) 28 else 22
    Column(verticalArrangement = Arrangement.spacedBy(u)) {
        stats.forEachIndexed { i, v ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                GbaText(tr((if (stats.size == 5) STAT_LABELS_GEN1 else STAT_LABELS)[i]), OptionColors.label, OptionColors.labelShadow, small, Modifier.width(u * labelU))
                GbaText("$v", OptionColors.value, OptionColors.valueShadow, small, Modifier.width(u * valueU))
                StatBar(v, Modifier.weight(1f).height(u * 6), u)
            }
        }
        Row {
            GbaText(tr("TOTAL"), OptionColors.label, OptionColors.labelShadow, small, Modifier.width(u * labelU))
            GbaText("${stats.sum()}", OptionColors.value, OptionColors.valueShadow, small)
        }
    }
}

@Composable
private fun StatBar(value: Int, modifier: Modifier, u: Dp) {
    val color = when {
        value < 50 -> Color(0xFFF08030)
        value < 80 -> Color(0xFFF8D030)
        value < 110 -> Color(0xFF78C850)
        else -> Color(0xFF40B8F8)
    }
    Box(
        modifier.drawBehind {
            val px = u.toPx()
            drawPixelRoundRect(OptionColors.frameDark, radius = 2 * px, step = px)
            val inner = Size(size.width - 2 * px, size.height - 2 * px)
            drawPixelRoundRect(Color(0xFF506858), Offset(px, px), inner, radius = px, step = px)
            val fill = floor(inner.width * (value / 255f).coerceIn(0f, 1f) / px) * px
            if (fill > 0) drawPixelRoundRect(color, Offset(px, px), Size(fill, inner.height), radius = px, step = px)
        },
    )
}

@Composable
private fun InfoLine(label: String, value: String, small: GbaTextMetrics, maxLines: Int = 1) {
    Row(verticalAlignment = Alignment.Top) {
        // A fixed-width game font (Gen 1's 8px letters) needs a wider label column.
        val wide = com.pokedaisy.app.companion.ui.theme.LocalGameFont.current != null
        GbaText(label, OptionColors.label, OptionColors.labelShadow, small, Modifier.width(small.u * if (wide) 92 else 80))
        GbaText(value, OptionColors.value, OptionColors.valueShadow, small, Modifier.weight(1f), maxLines = maxLines)
    }
}

private fun genderLabel(ratio: Int): String = when (ratio) {
    255 -> tr("UNKNOWN")
    254 -> tr("♀ ONLY")
    0 -> tr("♂ ONLY")
    else -> {
        // The data is PERCENT_FEMALE(x) = x * 255 / 100 and every Gen 3 ratio
        // is a multiple of 12.5%: round back to it (31 -> 12.5%, not 12.1%).
        val female = Math.round(ratio * 100 / 255.0 / 12.5).toInt() * 125 // tenths of a percent
        "♂ %s%%  ♀ %s%%".format(pct(1000 - female), pct(female))
    }
}

private fun pct(tenths: Int) = if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"

@Composable
internal fun rememberRomBitmap(
    t: PokedexTables, species: Int,
    cached: (PokedexTables, Int) -> Bitmap?, load: (PokedexTables, Int) -> Bitmap?,
): Bitmap? =
    produceKeyedState(cached(t, species), t, species) {
        if (value == null && species > 0) value = withContext(Dispatchers.IO) { load(t, species) }
    }.value

/**
 * [bitmap] ([srcPx] square) scaled by the largest whole number of screen
 * pixels per sprite pixel that fits, centered - pixel art stays crisp.
 */
@Composable
internal fun PixelImage(bitmap: Bitmap?, srcPx: Int, modifier: Modifier) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val room = with(density) { minOf(maxWidth, maxHeight).toPx() }
        val scale = floor(room / srcPx).coerceAtLeast(1f)
        val side = with(density) { (srcPx * scale).toDp() }
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(), contentDescription = null,
                modifier = Modifier.size(side), filterQuality = FilterQuality.None,
            )
        }
    }
}
