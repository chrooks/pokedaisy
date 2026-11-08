package com.pokedaisy.app.companion.data

/**
 * LEVEL UP ALL: every Pokémon the player owns brought up to one level, written straight into the running game's party and
 * PC records (nothing reaches the save file until the player saves in the game). The target is the highest level the
 * player owns or the strongest Pokémon on the next unbeaten boss's team. Level evolutions happen silently, with the rename
 * and the Pokédex marks the game's own would make; new moves are skipped unless the player asks ([LevelUpSession]).
 *
 * Eggs, Bad Eggs and Pokémon at or past the target are left alone. A party record gets its level byte and stats worked
 * out as CalculateMonStats does; a box record keeps only experience, so its level follows from that.
 */
data class LevelUpRules(
    val layout: PartyMonLayout,
    /** The species' row of gExperienceTables (index = level); null = its growth rate isn't known. */
    val expRow: (species: Int) -> IntArray?,
    /** HP, ATTACK, DEFENSE, SPEED, SP. ATK, SP. DEF; null = unknown. */
    val baseStats: (species: Int) -> IntArray?,
    /** The species' level evolutions and the rest ([planRaise] takes only the level-based ones). */
    val evolutions: (species: Int) -> List<EvoLink> = { emptyList() },
    val levelUpMoves: (species: Int) -> List<LevelMove> = { emptyList() },
    /** A move's base PP; null = unknown, and the move can't be taught. */
    val movePp: (move: Int) -> Int? = { null },
    /** The species' name as the game's text, 10 bytes (0xFF after it); null = unknown, and no name follows an evolution. */
    val speciesName: (species: Int) -> ByteArray? = { null },
    /** A held item that stops evolutions (Everstone). */
    val blocksEvolution: (heldItem: Int) -> Boolean = { false },
    /** The species' Pokédex number; 0 = none. */
    val dexNumber: (species: Int) -> Int = { 0 },
)

internal const val MONOMYTH_STORAGE_PTR = 0x03001248L

/** What one record will become: [species] is its last stage at [newLevel], [learnable] the moves it passed on the way, [stages] every species it evolves into. */
data class RaisePlan(val oldLevel: Int, val newLevel: Int, val species: Int, val oldSpecies: Int = species, val learnable: List<Int> = emptyList(), val stages: List<Int> = emptyList())

private const val BOX_SIZE = 80
private const val PARTY_RECORD = 100
private const val PARTY_SLOTS = 6
private const val BOX_SLOTS = 14 * 30
private const val STORAGE_RECORDS = 4
private const val EWRAM_START = 0x02000000L
private const val EWRAM_END = 0x02040000L
private const val SUBSTRUCTS = 0x20
private const val CHECKSUM = 0x1C
private const val PARTY_LEVEL = 0x54
private const val PARTY_HP = 0x56
private const val PARTY_MAX_HP = 0x58
private const val GROWTH_SPECIES = 0
private const val GROWTH_ITEM = 2
private const val GROWTH_EXP = 4
private const val GROWTH_PP_BONUSES = 8
private const val ATTACKS_PP = 8
private const val NICKNAME = 8
private const val NICKNAME_LEN = 10
private const val TEXT_END: Byte = -1
private const val MISC_IV_WORD = 4
private const val IV_EGG_BIT = 30

private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
private fun u32(b: ByteArray, o: Int) = (0..3).fold(0L) { acc, i -> acc or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }
private fun putU16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
private fun putU32(b: ByteArray, o: Int, v: Long) { for (i in 0..3) b[o + i] = (v shr (8 * i)).toByte() }

/** A record's 48 substruct bytes in the clear, and where each substruct sits in them. */
private class Opened(val block: ByteArray, order: IntArray) {
    val growth = order.indexOf(0) * 12
    val attacks = order.indexOf(1) * 12
    val evs = order.indexOf(2) * 12
    val misc = order.indexOf(3) * 12
    val ivs get() = List(6) { ((u32(block, misc + MISC_IV_WORD) shr (5 * it)) and 0x1F).toInt() }
    val evList get() = List(6) { block[evs + it].toInt() and 0xFF }
}

/** CalculateMonStats for Monomyth: natures change no stat, and it has no Shedinja, so species 303 (0 here) is an ordinary one. */
private fun climbedStats(base: IntArray, o: Opened, level: Int) = gen3Stats(0, base, o.ivs, o.evList, MONOMYTH_NATURE_BASE, level)

private fun key(r: ByteArray, layout: PartyMonLayout): Long {
    val personality = u32(r, 0)
    val otId = u32(r, 4)
    return if (layout.monomythKey) monomythKey(personality, otId) else personality xor otId
}

/** Null when the checksum fails: the game's Bad Egg, left as it is. */
private fun open(r: ByteArray, layout: PartyMonLayout): Opened? {
    val k = key(r, layout)
    val block = ByteArray(48)
    var sum = 0
    for (i in 0 until 12) {
        val w = u32(r, SUBSTRUCTS + i * 4) xor k
        putU32(block, i * 4, w)
        sum = (sum + (w and 0xFFFF).toInt() + (w ushr 16).toInt()) and 0xFFFF
    }
    if (sum != u16(r, CHECKSUM)) return null
    return Opened(block, gen3SubstructOrder[(u32(r, 0) % 24).toInt()])
}

/** [o]'s bytes encrypted back into a copy of [r], with their new checksum. */
private fun sealed(r: ByteArray, o: Opened, layout: PartyMonLayout): ByteArray {
    val out = r.copyOf()
    val k = key(r, layout)
    var sum = 0
    for (i in 0 until 12) {
        val w = u32(o.block, i * 4)
        sum = (sum + (w and 0xFFFF).toInt() + (w ushr 16).toInt()) and 0xFFFF
        putU32(out, SUBSTRUCTS + i * 4, w xor k)
    }
    putU16(out, CHECKSUM, sum)
    return out
}

private class Climber(val o: Opened, val species: Int, val level: Int)

/** [record] read as a Pokémon that can climb; null = an empty slot, an egg, a Bad Egg or an unknown growth rate. */
private fun climber(record: ByteArray, isParty: Boolean, rules: LevelUpRules): Climber? {
    val o = open(record, rules.layout) ?: return null
    val species = u16(o.block, o.growth + GROWTH_SPECIES)
    val eggFlag = record[rules.layout.flags].toInt() and rules.layout.eggMask != 0
    if (species == 0 || eggFlag || (u32(o.block, o.misc + MISC_IV_WORD) shr IV_EGG_BIT) and 1L == 1L) return null
    val row = rules.expRow(species) ?: return null
    return Climber(o, species, if (isParty) record[PARTY_LEVEL].toInt() and 0xFF else levelFromExp(row, u32(o.block, o.growth + GROWTH_EXP)))
}

/** What [record] (a party or box Pokémon) becomes at [target]; null = it stays as it is. */
fun planRaise(record: ByteArray, isParty: Boolean, target: Int, rules: LevelUpRules): RaisePlan? {
    val c = climber(record, isParty, rules) ?: return null
    val o = c.o
    val species = c.species
    val level = c.level
    if (level >= target) return null
    val known = IntArray(NUM_MOVES) { u16(o.block, o.attacks + it * 2) }
    val canEvolve = !rules.blocksEvolution(u16(o.block, o.growth + GROWTH_ITEM))
    val learnable = LinkedHashSet<Int>()
    val stages = ArrayList<Int>()
    var stage = species
    // The way a Pokémon climbs in play: each level's moves, then its evolution, then what the new stage learns there.
    for (l in level + 1..target) {
        rules.levelUpMoves(stage).filter { it.level == l }.mapTo(learnable) { it.move }
        while (canEvolve) {
            stage = rules.evolutions(stage).firstOrNull { evolvesAt(it, l, o, rules) }?.to ?: break
            stages += stage
            rules.levelUpMoves(stage).filter { it.level == 0 || it.level == l }.mapTo(learnable) { it.move }
        }
    }
    return RaisePlan(level, target, stage, species, (learnable - known.toSet()).toList(), stages)
}

/** Whether [link] is one the climb takes at level [l]: a level, and at most ATTACK against DEFENSE beside it. */
private fun evolvesAt(link: EvoLink, l: Int, o: Opened, rules: LevelUpRules): Boolean {
    if (link.reqs.none { it is EvoReq.Level }) return false
    return link.reqs.all { req ->
        when (req) {
            is EvoReq.Level -> l >= req.level
            is EvoReq.Stats -> rules.baseStats(link.from)?.let { base ->
                val stats = climbedStats(base, o, l)
                stats[STAT_ATK].compareTo(stats[STAT_DEF]) == req.cmp
            } ?: false
            else -> false
        }
    }
}

internal fun levelFromExp(row: IntArray, exp: Long): Int {
    var level = 1
    while (level < 100 && exp >= row[level + 1]) level++
    return level
}

/** [record] raised as [plan] says: a new array, [record] untouched. */
fun applyRaise(record: ByteArray, isParty: Boolean, plan: RaisePlan, rules: LevelUpRules, moves: IntArray? = null): ByteArray {
    val o = requireNotNull(open(record, rules.layout)) { "the record changed since it was planned" }
    // The last stage's curve: an evolution can change the growth rate.
    val row = requireNotNull(rules.expRow(plan.species))
    putU16(o.block, o.growth + GROWTH_SPECIES, plan.species)
    putU32(o.block, o.growth + GROWTH_EXP, row[plan.newLevel].toLong())
    moves?.let { teach(o, it, rules) }
    val out = sealed(record, o, rules.layout)
    if (plan.species != plan.oldSpecies) renameIfDefault(out, plan, rules)
    if (!isParty) return out

    val base = requireNotNull(rules.baseStats(plan.species))
    val stats = climbedStats(base, o, plan.newLevel)
    out[PARTY_LEVEL] = plan.newLevel.toByte()
    val hp = u16(record, PARTY_HP)
    val oldMax = u16(record, PARTY_MAX_HP)
    // CalculateMonStats: a fainted Pokémon stays fainted; damage carries over.
    putU16(out, PARTY_HP, if (hp == 0) 0 else (hp + stats[STAT_HP] - oldMax).coerceAtLeast(1))
    stats.forEachIndexed { i, v -> putU16(out, PARTY_MAX_HP + i * 2, v) }
    return out
}

/** Puts [moves] in the Attacks substruct: a changed slot gets the new move's full PP and loses its PP Ups, as the game does. */
private fun teach(o: Opened, moves: IntArray, rules: LevelUpRules) {
    var bonuses = o.block[o.growth + GROWTH_PP_BONUSES].toInt() and 0xFF
    moves.forEachIndexed { i, move ->
        if (u16(o.block, o.attacks + i * 2) == move) return@forEachIndexed
        val pp = requireNotNull(rules.movePp(move)) { "move $move has no known PP" }
        putU16(o.block, o.attacks + i * 2, move)
        o.block[o.attacks + ATTACKS_PP + i] = pp.toByte()
        bonuses = bonuses and (3 shl (i * 2)).inv()
    }
    o.block[o.growth + GROWTH_PP_BONUSES] = bonuses.toByte()
}

/** EvolutionRenameMon: a Pokémon still called by its old species' name takes the new one; a nickname stays. */
private fun renameIfDefault(out: ByteArray, plan: RaisePlan, rules: LevelUpRules) {
    val old = rules.speciesName(plan.oldSpecies) ?: return
    val new = rules.speciesName(plan.species) ?: return
    if (!textEquals(out.copyOfRange(NICKNAME, NICKNAME + NICKNAME_LEN), old)) return
    new.copyInto(out, NICKNAME, 0, NICKNAME_LEN)
}

private fun textEquals(a: ByteArray, b: ByteArray): Boolean {
    fun text(x: ByteArray) = x.takeWhile { it != TEXT_END }
    return text(a) == text(b)
}

/** Where a game keeps what LEVEL UP ALL reads past [NativeConfig]'s own tables. */
data class LevelUpTables(
    /** gPokemonStoragePtr: u8 currentBox + padding, then boxes[14][30] of 80 bytes. */
    val storagePtr: Long,
    /** gSpeciesNames: [speciesNameStride] bytes a name, 10 letters at most. */
    val speciesNames: Long = 0,
    val speciesNameStride: Int = 11,
    /** gBattleMoves: a record per move, its base PP at [movePpOff]. */
    val battleMoves: Long = 0,
    val moveStride: Int = 16,
    val movePpOff: Int = 5,
    val everstone: Int = 0,
)

/** Monomyth v1.0: the storage pointer beside the save blocks' (the fixture's boxes pass the game's checksum), its name and
 * move tables (scripts/gen_monomyth_tables.py), PP checked on Scratch 35 and Water Gun 25, EVERSTONE its item 206. */
val LEVEL_UP_MONOMYTH = LevelUpTables(
    storagePtr = MONOMYTH_STORAGE_PTR, speciesNames = 0x084E8BBCL, battleMoves = 0x084F17E0L, everstone = 206,
)

/** Monomyth v1.1: the species block moved +0x488. */
val LEVEL_UP_MONOMYTH_V1_1 = LEVEL_UP_MONOMYTH.copy(speciesNames = 0x084E9044L, battleMoves = 0x084F1C68L)

/** Monomyth's rules over its ROM tables, read through [PokedexSource.reader]: off the emu thread. */
fun monomythLevelUpRules(dex: PokedexTables, guide: GuideTables?, t: LevelUpTables): LevelUpRules = LevelUpRules(
    layout = MONOMYTH_PARTY_MON,
    expRow = { s -> growthRatesMonomyth.getOrNull(s)?.let { expTablesMonomyth.getOrNull(it) } },
    baseStats = { PokedexSource.baseStats(dex, it) },
    evolutions = { s -> DexDetails.family(dex, s).orEmpty().filter { it.from == s } },
    levelUpMoves = { DexDetails.levelUp(dex, guide, it).orEmpty() },
    movePp = { m ->
        runCatching { PokedexSource.reader.readCoreMemory(t.battleMoves + m * t.moveStride.toLong() + t.movePpOff, 1)[0].toInt() and 0xFF }
            .getOrNull()?.takeIf { it > 0 }
    },
    speciesName = { s ->
        runCatching { PokedexSource.reader.readCoreMemory(t.speciesNames + s * t.speciesNameStride.toLong(), NICKNAME_LEN) }.getOrNull()?.let { raw ->
            val end = raw.indexOf(TEXT_END).let { if (it < 0) NICKNAME_LEN else it }
            ByteArray(NICKNAME_LEN) { if (it < end) raw[it] else TEXT_END }
        }
    },
    blocksEvolution = { it == t.everstone },
    dexNumber = { PokedexSource.nationalOf(dex, it) },
)

/** One filled party or box slot: where it is and its bytes when read. */
data class RosterSlot(val addr: Long, val isParty: Boolean, val record: ByteArray)

/** The party and every filled box slot, as they are now; null = the storage isn't set up (not on the field yet). */
fun readRoster(cfg: NativeConfig, t: LevelUpTables, g: GameCalls): List<RosterSlot>? {
    val storage = u32(g.read(t.storagePtr, 4), 0)
    if (storage !in EWRAM_START until EWRAM_END) return null
    val count = (g.read(cfg.playerPartyCount, 1)[0].toInt() and 0xFF).coerceAtMost(PARTY_SLOTS)
    val party = (0 until count).map { RosterSlot(cfg.playerParty + it * PARTY_RECORD.toLong(), true, g.read(cfg.playerParty + it * PARTY_RECORD.toLong(), PARTY_RECORD)) }
    val first = storage + STORAGE_RECORDS
    val all = g.read(first, BOX_SLOTS * BOX_SIZE)
    val boxed = (0 until BOX_SLOTS).mapNotNull { n ->
        val rec = all.copyOfRange(n * BOX_SIZE, (n + 1) * BOX_SIZE)
        if (rec.all { it == 0.toByte() }) null else RosterSlot(first + n * BOX_SIZE.toLong(), false, rec)
    }
    return party + boxed
}

/** The highest level among the player's Pokémon (eggs aside); 1 with none. */
fun highestLevel(roster: List<RosterSlot>, rules: LevelUpRules): Int =
    roster.mapNotNull { climber(it.record, it.isParty, rules)?.level }.maxOrNull() ?: 1

/** A slot's raise, the moves it [known]s now and those it will end with ([moves] null = unchanged). */
data class PlannedRaise(val slot: RosterSlot, val plan: RaisePlan, val known: IntArray, val moves: IntArray? = null)

/** Every Pokémon below [target], with what it becomes. Reads the ROM's tables: off the emu thread. */
fun planAll(roster: List<RosterSlot>, target: Int, rules: LevelUpRules): List<PlannedRaise> = roster.mapNotNull { slot ->
    planRaise(slot.record, slot.isParty, target, rules)?.let { plan ->
        PlannedRaise(slot, plan, requireNotNull(climber(slot.record, slot.isParty, rules)).let { c -> IntArray(NUM_MOVES) { u16(c.o.block, c.o.attacks + it * 2) } })
    }
}

enum class LevelUpResult {
    DONE,
    /** Not on the field with the controls free (a battle, a menu, a text box). */
    NOT_NOW,
    /** The party or a box changed since the plan was made: nothing written. */
    CHANGED,
    FAILED,
}

/**
 * [planned] written into the game: every record checked against what was planned, then all written, and the new species
 * marked seen and caught in [flags], every stage on the way as the game would. All or nothing. Emu thread, on a free field (the caller's check).
 */
fun applyLevelUps(planned: List<PlannedRaise>, rules: LevelUpRules, cfg: NativeConfig, flags: DexFlags, g: GameCalls): LevelUpResult {
    if (planned.any { !g.read(it.slot.addr, it.slot.record.size).contentEquals(it.slot.record) }) return LevelUpResult.CHANGED
    val raised = runCatching { planned.map { applyRaise(it.slot.record, it.slot.isParty, it.plan, rules, it.moves) } }
        .getOrElse { return LevelUpResult.FAILED }
    val base = if (flags.block == DexFlagBlock.SAVE_BLOCK_1) u32(g.read(cfg.saveBlock1Ptr, 4), 0) else 0L
    val numbers = planned.flatMap { it.plan.stages }.distinct().map(rules.dexNumber).filter { it > 0 }
    val seenBefore = g.read(base + flags.seen, flags.bytes)
    val caughtBefore = g.read(base + flags.caught, flags.bytes)

    fun putBack(): LevelUpResult {
        planned.forEach { g.write(it.slot.addr, it.slot.record) }
        g.write(base + flags.seen, seenBefore)
        g.write(base + flags.caught, caughtBefore)
        return LevelUpResult.FAILED
    }
    planned.zip(raised).forEach { (p, bytes) -> if (!g.write(p.slot.addr, bytes)) return putBack() }
    if (numbers.isNotEmpty() && (!g.write(base + flags.seen, withBits(seenBefore, numbers, flags)) ||
            !g.write(base + flags.caught, withBits(caughtBefore, numbers, flags)))) return putBack()
    return LevelUpResult.DONE
}

private fun withBits(bits: ByteArray, numbers: List<Int>, flags: DexFlags): ByteArray = bits.copyOf().also { out ->
    numbers.map { it - flags.firstBit }.filter { it / 8 in out.indices }.forEach { out[it / 8] = (out[it / 8].toInt() or (1 shl (it % 8))).toByte() }
}

/** A move a full Pokémon could learn: which slot to forget, or none. */
data class MoveQuestion(val addr: Long, val name: String, val move: Int, val current: IntArray)

/**
 * ASK FOR EACH: the plans' new moves one at a time, as the game's own level-up asks them. A free move slot takes the move
 * without a question; a move whose PP isn't known can't be taught and is left out.
 */
class LevelUpSession(planned: List<PlannedRaise>, private val rules: LevelUpRules) {
    private val plans = planned.map { it.copy(moves = it.known.copyOf()) }
    private var mon = 0
    private var next = 0

    /** The question to answer now; null = none left. */
    fun next(): MoveQuestion? {
        while (mon < plans.size) {
            val p = plans[mon]
            val moves = requireNotNull(p.moves)
            while (next < p.plan.learnable.size) {
                val move = p.plan.learnable[next]
                if (rules.movePp(move) == null) { next++; continue }
                val free = moves.indexOf(0)
                if (free >= 0) { moves[free] = move; next++; continue }
                return MoveQuestion(p.slot.addr, gen3Nickname(p.slot.record, 0, rules.layout)?.takeIf { it.isNotBlank() } ?: speciesName(p.plan.species), move, moves.copyOf())
            }
            mon++
            next = 0
        }
        return null
    }

    /** The current question's answer: the slot the new move takes, or null to leave it. */
    fun answer(forgetSlot: Int?) {
        val p = plans[mon]
        if (forgetSlot != null) requireNotNull(p.moves)[forgetSlot] = p.plan.learnable[next]
        next++
    }

    /** No to every question left. */
    fun skipRest() {
        mon = plans.size
    }

    /** The plans with the answers in them. */
    fun planned(): List<PlannedRaise> = plans.map { p -> if (p.moves!!.contentEquals(p.known)) p.copy(moves = null) else p }
}

/** One of the targets the player can pick: the level and who it would raise. */
data class LevelUpChoice(val level: Int, val planned: List<PlannedRaise>)

/** What LEVEL UP ALL can do right now. [bossName] is [nextBoss]'s; a null [nextBoss] = every boss is beaten. */
class LevelUpOffer(val rules: LevelUpRules, val highest: LevelUpChoice, val nextBoss: LevelUpChoice?, val bossName: String?)

/** The next unbeaten boss and the level of the strongest Pokémon on its team, over every team it may bring; null = none left. */
fun nextBossLevel(bosses: List<Boss>, progress: SaveProgress, party: (trainer: Int) -> List<TrainerMon>): Pair<Boss, Int>? {
    val boss = bosses.firstOrNull { !it.isDone(progress) } ?: return null
    val level = boss.trainers(progress).flatMap(party).maxOfOrNull { it.level } ?: return null
    return boss to level
}

/** Whether the game can be changed now: its code is the expected build's and the field is the player's. */
fun levelUpFieldFree(fields: FieldItemFns, busy: Boolean, g: GameCalls): Boolean =
    fieldItemCodeMatches(fields, g::read) && !busy && g.call(fields.fieldControlsLocked) == 0L

/** Both targets for [roster]. Reads the ROM's tables: off the emu thread. */
fun buildLevelUpOffer(roster: List<RosterSlot>, cfg: NativeConfig, t: LevelUpTables, progress: SaveProgress?): LevelUpOffer? {
    val dex = cfg.pokedex ?: return null
    val guide = cfg.guideTables
    val rules = monomythLevelUpRules(dex, guide, t)
    val top = highestLevel(roster, rules)
    val boss = if (guide == null || progress == null) null
        else nextBossLevel(gameGuide(guide.guide)?.bosses.orEmpty(), progress) { GuideRomSource.party(guide, it).orEmpty() }
    return LevelUpOffer(
        rules, LevelUpChoice(top, planAll(roster, top, rules)),
        boss?.let { (_, level) -> LevelUpChoice(level, planAll(roster, level, rules)) }, boss?.first?.title,
    )
}
