package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** LEVEL UP ALL on the user's Monomyth save (the monomyth fixture: MISHIMIZU Lv12 12/37 first in the party). */
class LevelUpAllTest {
    private val base = intArrayOf(50, 60, 45, 70, 55, 40)
    private val rules = LevelUpRules(
        layout = MONOMYTH_PARTY_MON,
        expRow = { expTablesMonomyth[2] },
        baseStats = { base },
    )

    @Before fun setUp() {
        activeGame = GameKind.MONOMYTH
    }

    private fun party(slot: Int): ByteArray =
        FixtureMemoryReader.load("monomyth").readCoreMemory(NATIVE_MONOMYTH.playerParty + slot * 100L, 100)

    @Test fun aPartyPokemonReachesTheTargetWithItsStatsWorkedOut() {
        val before = decodePartyMon(party(0), 0, MONOMYTH_PARTY_MON)!!
        val plan = planRaise(party(0), isParty = true, target = 20, rules)!!
        val after = decodePartyMon(applyRaise(party(0), isParty = true, plan, rules), 0, MONOMYTH_PARTY_MON)

        assertNotNull("the checksum must still hold", after)
        assertEquals(12, plan.oldLevel)
        assertEquals(20, after!!.level)
        assertEquals(expTablesMonomyth[2][20].toLong(), after.exp)
        assertEquals(before.species, after.species)
        val stats = gen3Stats(before.species, base, before.stats!!.ivs, before.stats!!.evs, MONOMYTH_NATURE_BASE, 20)
        assertEquals(stats, after.stats!!.stats)
        // Damage carries over: the HP goes up only by what the maximum gained.
        assertEquals(stats[0] - (before.maxHp - before.hp), after.hp)
        assertEquals(stats[0], after.maxHp)
    }

    private fun box(slot: Int): ByteArray {
        val fx = FixtureMemoryReader.load("monomyth")
        val storage = fx.readCoreMemory(MONOMYTH_STORAGE_PTR, 4).let { b -> (0..3).fold(0L) { a, i -> a or ((b[i].toLong() and 0xFF) shl (8 * i)) } }
        return fx.readCoreMemory(storage + 4 + slot * 80L, 80)
    }

    @Test fun aBoxPokemonGainsOnlyExperienceInItsEightyBytes() {
        val plan = planRaise(box(1), isParty = false, target = 30, rules)!!
        val out = applyRaise(box(1), isParty = false, plan, rules)

        assertEquals(80, out.size)
        assertEquals("the raised box record reads back at its new level", 30, planRaise(out, isParty = false, target = 99, rules)!!.oldLevel)
    }

    @Test fun nothingAlreadyAtTheTargetChangesAndEggsAndEmptySlotsAreLeftAlone() {
        assertNull(planRaise(party(0), isParty = true, target = 12, rules))
        assertNull(planRaise(party(0), isParty = true, target = 5, rules))
        assertNull(planRaise(ByteArray(80), isParty = false, target = 30, rules))
    }

    private val species0 get() = decodePartyMon(party(0), 0, MONOMYTH_PARTY_MON)!!.species
    private val midBase = intArrayOf(70, 80, 60, 90, 75, 60)
    private val nameOf: (Int) -> ByteArray? = { id ->
        if (id == species0) party(0).copyOfRange(8, 18) else ByteArray(10) { i -> if (i == 0) 0xC8.toByte() else 0xFF.toByte() }
    }
    private fun chain(first: Int, second: Int) = rules.copy(
        baseStats = { if (it == species0) base else midBase },
        evolutions = { when (it) {
            species0 -> listOf(EvoLink(species0, first, listOf(EvoReq.Level(16))))
            first -> listOf(EvoLink(first, second, listOf(EvoReq.Level(36))))
            else -> emptyList()
        } },
        speciesName = nameOf,
    )

    @Test fun aLevelEvolutionChainsToTheLastStageItReaches() {
        val r = chain(900, 901)
        val plan = planRaise(party(0), isParty = true, target = 40, r)!!
        val after = decodePartyMon(applyRaise(party(0), isParty = true, plan, r), 0, MONOMYTH_PARTY_MON)!!

        assertEquals(901, after.species)
        assertEquals(species0, plan.oldSpecies)
        val stats = gen3Stats(901, midBase, after.stats!!.ivs, after.stats!!.evs, MONOMYTH_NATURE_BASE, 40)
        assertEquals(stats, after.stats!!.stats)
    }

    @Test fun aChainStopsAtTheTargetLevel() {
        assertEquals(900, planRaise(party(0), isParty = true, target = 20, chain(900, 901))!!.species)
    }

    @Test fun aStillDefaultNameFollowsTheEvolutionAndANicknameStays() {
        val r = chain(900, 901)
        val plan = planRaise(party(0), isParty = true, target = 20, r)!!
        val renamed = applyRaise(party(0), isParty = true, plan, r)
        assertEquals(0xC8.toByte(), renamed[8])

        val nicknamed = r.copy(speciesName = { ByteArray(10) { 0xBB.toByte() } })
        val kept = applyRaise(party(0), isParty = true, planRaise(party(0), isParty = true, target = 20, nicknamed)!!, nicknamed)
        assertArrayEquals(party(0).copyOfRange(8, 18), kept.copyOfRange(8, 18))
    }

    @Test fun anEverstoneStopsTheEvolutionButNotTheLevels() {
        // MISHIMIZU holds nothing, so "item 0 blocks" stands in for an Everstone in its slot.
        val r = chain(900, 901).copy(blocksEvolution = { it == 0 })
        val plan = planRaise(party(0), isParty = true, target = 40, r)!!
        assertEquals(species0, plan.species)
        assertEquals(40, plan.newLevel)
    }

    @Test fun aMethodThatIsNotLevelBasedStaysManual() {
        val r = rules.copy(evolutions = { listOf(EvoLink(it, 900, listOf(EvoReq.UseItem(103)))) })
        assertEquals(species0, planRaise(party(0), isParty = true, target = 40, r)!!.species)
    }

    @Test fun anAttackAgainstDefenseEvolutionFollowsTheStats() {
        // base's ATTACK 60 beats its DEFENSE 45 for this Pokémon's IVs and EVs at Lv30.
        val r = rules.copy(evolutions = { if (it == species0) listOf(
            EvoLink(it, 800, listOf(EvoReq.Level(20), EvoReq.Stats(-1))),
            EvoLink(it, 801, listOf(EvoReq.Level(20), EvoReq.Stats(1))),
        ) else emptyList() }, baseStats = { base })
        assertEquals(801, planRaise(party(0), isParty = true, target = 30, r)!!.species)
    }

    private val learnset = listOf(LevelMove(10, 1), LevelMove(14, 33), LevelMove(18, 45), LevelMove(25, 52))

    @Test fun onlyMovesPassedOnTheWayUpAreOfferedAndNotOnesItKnows() {
        val known = decodePartyMon(party(0), 0, MONOMYTH_PARTY_MON)!!.moves.first()
        val r = rules.copy(levelUpMoves = { learnset + LevelMove(15, known) })
        assertEquals(listOf(33, 45), planRaise(party(0), isParty = true, target = 20, r)!!.learnable)
    }

    @Test fun anEvolvedStagesMovesCountFromItsEvolution() {
        val r = chain(900, 901).copy(levelUpMoves = { sp -> when (sp) {
            species0 -> listOf(LevelMove(14, 33), LevelMove(17, 45))
            900 -> listOf(LevelMove(0, 77), LevelMove(16, 78), LevelMove(17, 79))
            else -> emptyList()
        } })
        // It evolves at 16: its old stage's 17 is never reached, the new stage's "on evolving" (0), 16 and 17 are.
        assertEquals(listOf(33, 77, 78, 79), planRaise(party(0), isParty = true, target = 20, r)!!.learnable)
    }

    @Test fun skippingNewMovesLeavesTheMovesAsTheyWere() {
        val before = decodePartyMon(party(0), 0, MONOMYTH_PARTY_MON)!!
        val r = rules.copy(levelUpMoves = { learnset })
        val after = decodePartyMon(applyRaise(party(0), isParty = true, planRaise(party(0), isParty = true, target = 20, r)!!, r), 0, MONOMYTH_PARTY_MON)!!
        assertArrayEquals(before.moves, after.moves)
        assertArrayEquals(before.pp, after.pp)
    }

    @Test fun aChosenMoveReplacesItsSlotAtFullPpAndTheOthersKeepTheirs() {
        val before = decodePartyMon(party(0), 0, MONOMYTH_PARTY_MON)!!
        val r = rules.copy(levelUpMoves = { learnset }, movePp = { if (it == 33) 35 else null })
        val plan = planRaise(party(0), isParty = true, target = 20, r)!!
        val moves = before.moves.copyOf().also { it[3] = 33 }
        val after = decodePartyMon(applyRaise(party(0), isParty = true, plan, r, moves), 0, MONOMYTH_PARTY_MON)!!

        assertArrayEquals(moves, after.moves)
        assertEquals(35, after.pp[3])
        assertEquals(before.pp[0], after.pp[0])
    }

    /** The fixture's RAM as the game's memory; [failWritesAfter] writes then refuse. */
    private class Ram(val failWritesAfter: Int = Int.MAX_VALUE) : GameCalls {
        private val fx = FixtureMemoryReader.load("monomyth")
        val written = HashMap<Long, Byte>()
        var writes = 0
        override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) = 0L
        override fun read(addr: Long, len: Int): ByteArray =
            fx.readCoreMemory(addr, len).also { b -> for (i in b.indices) written[addr + i]?.let { v -> b[i] = v } }
        override fun write(addr: Long, bytes: ByteArray): Boolean {
            if (writes++ >= failWritesAfter) return false
            bytes.forEachIndexed { i, v -> written[addr + i] = v }
            return true
        }
    }

    private val tables = LevelUpTables(storagePtr = MONOMYTH_STORAGE_PTR)

    @Test fun theRosterIsThePartyAndEveryFilledBoxSlot() {
        val roster = readRoster(NATIVE_MONOMYTH, tables, Ram())!!
        assertEquals(6, roster.count { it.isParty })
        assertEquals(5, roster.count { !it.isParty })
        assertEquals(12, highestLevel(roster, rules))
    }

    private val dexFlags = DexFlags(DexFlagBlock.SAVE_BLOCK_1, seen = 0x37F4, caught = 0x3827, bytes = 0x33)

    private fun planAllAt(target: Int, ram: GameCalls, r: LevelUpRules = rules) = planAll(readRoster(NATIVE_MONOMYTH, tables, ram)!!, target, r)

    private fun offerFor(ram: GameCalls, bossLevel: Int?): LevelUpOffer {
        val roster = readRoster(NATIVE_MONOMYTH, tables, ram)!!
        val top = highestLevel(roster, rules)
        fun lowest(party: Boolean) = roster.filter { it.isParty == party }.mapNotNull { planRaise(it.record, it.isParty, 100, rules)?.oldLevel }.minOrNull()
        return LevelUpOffer(
            rules, LevelUpChoice(top, planAll(roster, top, rules)), bossLevel?.let { LevelUpChoice(it, planAll(roster, it, rules)) }, null,
            partyLowest = lowest(party = true), pcLowest = lowest(party = false), planAt = { planAll(roster, it, rules) },
        )
    }

    @Test fun partyOnlyAndPcOnlyRaiseJustTheirOwnPokemon() {
        val offer = offerFor(Ram(), bossLevel = 20)
        val party = offer.choiceAt(20, LevelUpScope.PARTY).planned
        val pc = offer.choiceAt(20, LevelUpScope.PC).planned

        assertEquals(6, party.size)
        assertTrue(party.all { it.slot.isParty })
        assertEquals(5, pc.size)
        assertTrue(pc.none { it.slot.isParty })
        assertEquals(11, offer.choiceAt(20, LevelUpScope.ALL).planned.size)
        assertEquals(pc.map { it.slot.addr }, offer.nextBoss!!.within(LevelUpScope.PC).planned.map { it.slot.addr })
    }

    @Test fun aPickedLevelRunsFromJustAboveTheLowestToTheHigherOfHighestAndNextBoss() {
        val ram = Ram()
        val withBoss = offerFor(ram, bossLevel = 30)
        val lowest = withBoss.lowest(LevelUpScope.ALL)!!

        assertEquals(30, withBoss.cap)
        assertEquals((lowest + 1)..30, withBoss.pickable(LevelUpScope.ALL))
        // A boss below the highest Pokémon leaves the highest as the top.
        assertEquals(12, offerFor(ram, bossLevel = 8).cap)
        assertEquals(12, offerFor(ram, bossLevel = null).cap)
    }

    @Test fun aPickedLevelRaisesOnlyThoseBelowIt() {
        val offer = offerFor(Ram(), bossLevel = 30)
        val picked = offer.choiceAt(25, LevelUpScope.ALL)

        assertEquals(25, picked.level)
        assertTrue(picked.planned.all { it.plan.newLevel == 25 })
        assertEquals(11, picked.planned.size)
    }

    @Test fun nothingToPickWhenEveryoneInTheScopeIsAtTheTopAlready() {
        val ram = Ram()
        assertEquals(LevelUpResult.DONE, applyLevelUps(planAllAt(12, ram), rules, NATIVE_MONOMYTH, dexFlags, ram))
        val offer = offerFor(ram, bossLevel = null)

        assertEquals(12, offer.lowest(LevelUpScope.ALL))
        assertNull(offer.pickable(LevelUpScope.ALL))
    }

    @Test fun applyingRaisesEveryoneAndTheRecordsReadBack() {
        val ram = Ram()
        val planned = planAllAt(20, ram)
        assertEquals(LevelUpResult.DONE, applyLevelUps(planned, rules, NATIVE_MONOMYTH, dexFlags, ram))
        assertEquals(20, highestLevel(readRoster(NATIVE_MONOMYTH, tables, ram)!!, rules))
        assertEquals(11, planned.size)
    }

    @Test fun aRecordThatMovedSincePlanningStopsEverythingUnwritten() {
        val ram = Ram()
        val planned = planAllAt(20, ram)
        ram.write(planned[3].slot.addr, byteArrayOf(0x55))
        ram.writes = 0
        assertEquals(LevelUpResult.CHANGED, applyLevelUps(planned, rules, NATIVE_MONOMYTH, dexFlags, ram))
        assertEquals(0, ram.writes)
    }

    @Test fun aWriteThatFailsPutsEveryRecordBack() {
        val ram = Ram(failWritesAfter = 4)
        val planned = planAllAt(20, ram)
        val before = planned.map { it.slot.addr to ram.read(it.slot.addr, it.slot.record.size).toList() }
        // The put-back has to get through: only the 5th write refuses.
        val flaky = object : GameCalls by ram {
            var n = 0
            override fun write(addr: Long, bytes: ByteArray) = n++ != 4 && (ram.written.let { w -> bytes.forEachIndexed { i, v -> w[addr + i] = v }; true })
        }
        assertEquals(LevelUpResult.FAILED, applyLevelUps(planned, rules, NATIVE_MONOMYTH, dexFlags, flaky))
        before.forEach { (addr, bytes) -> assertEquals(bytes, ram.read(addr, bytes.size).toList()) }
    }

    @Test fun anEvolutionMarksTheNewSpeciesSeenAndCaught() {
        val ram = Ram()
        val r = chain(900, 901).copy(dexNumber = { if (it == 900) 100 else 0 })
        val planned = planAllAt(20, ram, r)
        assertEquals(LevelUpResult.DONE, applyLevelUps(planned, r, NATIVE_MONOMYTH, dexFlags, ram))
        val sb1 = ram.read(NATIVE_MONOMYTH.saveBlock1Ptr, 4).let { b -> (0..3).fold(0L) { a, i -> a or ((b[i].toLong() and 0xFF) shl (8 * i)) } }
        val bit = 1 shl (99 % 8)
        assertEquals(bit, ram.read(sb1 + 0x37F4 + 99 / 8, 1)[0].toInt() and bit)
        assertEquals(bit, ram.read(sb1 + 0x3827 + 99 / 8, 1)[0].toInt() and bit)
    }

    @Test fun aChainMarksEveryStageItPassedThrough() {
        val ram = Ram()
        val r = chain(900, 901).copy(dexNumber = { when (it) { 900 -> 100; 901 -> 101; else -> 0 } })
        assertEquals(LevelUpResult.DONE, applyLevelUps(planAllAt(40, ram, r), r, NATIVE_MONOMYTH, dexFlags, ram))
        val sb1 = ram.read(NATIVE_MONOMYTH.saveBlock1Ptr, 4).let { b -> (0..3).fold(0L) { a, i -> a or ((b[i].toLong() and 0xFF) shl (8 * i)) } }
        listOf(99, 100).forEach { bit ->
            assertEquals("No.${bit + 1} seen", 1 shl (bit % 8), ram.read(sb1 + 0x37F4 + bit / 8, 1)[0].toInt() and (1 shl (bit % 8)))
        }
    }

    @Test fun anEggMarkedInItsFlagsByteIsLeftAlone() {
        val egg = party(0).also { it[MONOMYTH_PARTY_MON.flags] = (it[MONOMYTH_PARTY_MON.flags].toInt() or MONOMYTH_PARTY_MON.eggMask).toByte() }
        assertNull(planRaise(egg, isParty = true, target = 20, rules))
    }

    /** Species 303 is SHEDINJA in vanilla, whose HP is always 1; Monomyth's 303 is an ordinary Pokémon. */
    @Test fun monomythsSpeciesThreeOhThreeGetsNormalHp() {
        val after = decodePartyMon(applyRaise(party(0), isParty = true, RaisePlan(12, 20, 303, 303), rules), 0, MONOMYTH_PARTY_MON)!!
        assertTrue("max HP ${after.maxHp}", after.maxHp > 1)
    }

    private val askRules get() = rules.copy(levelUpMoves = { listOf(LevelMove(14, 33), LevelMove(18, 45)) }, movePp = { 20 })

    @Test fun askForEachAsksAboutEachMoveTheFullPokemonCouldLearn() {
        val ram = Ram()
        val session = LevelUpSession(planAllAt(20, ram, askRules).take(1), askRules)
        val q = session.next()!!
        assertEquals(33, q.move)
        session.answer(forgetSlot = 2)
        assertEquals(45, session.next()!!.move)
        session.answer(forgetSlot = null)
        assertNull(session.next())
        assertEquals(33, session.planned()[0].moves!![2])
        assertEquals(q.current[0], session.planned()[0].moves!![0])
    }

    @Test fun skipTheRestAnswersEveryQuestionLeftWithNo() {
        val ram = Ram()
        val session = LevelUpSession(planAllAt(20, ram, askRules), askRules)
        session.next()!!
        session.skipRest()
        assertNull(session.next())
        assertTrue(session.planned().all { it.moves == null })
    }

    @Test fun anEmptyMoveSlotIsFilledWithoutAsking() {
        val ram = Ram()
        val planned = planAllAt(20, ram, askRules).first().let { p ->
            // A box Pokémon can know fewer than four moves; this one's last two slots stand empty.
            p.copy(known = intArrayOf(10, 55, 0, 0))
        }
        val session = LevelUpSession(listOf(planned), askRules)
        assertNull(session.next())
        assertArrayEquals(intArrayOf(10, 55, 33, 45), session.planned()[0].moves)
    }

    private fun boss(flag: Int, vararg trainers: Int) = Boss(
        "B$flag", "", flag, variants = trainers.map { "t$it" to it }, trainer = { trainers[0] },
    )

    @Test fun theNextBossLevelIsItsStrongestPokemonAcrossItsTeams() {
        val teams = mapOf(1 to listOf(14, 17), 2 to listOf(30), 3 to listOf(41))
        val party: (Int) -> List<TrainerMon> = { t -> teams[t].orEmpty().map { TrainerMon(species = 1, level = it, heldItem = 0, moves = emptyList()) } }
        val beaten = SaveProgress(ByteArray(64).also { it[100 / 8] = (1 shl (100 % 8)).toByte() }, ByteArray(0))

        val (next, level) = nextBossLevel(listOf(boss(100, 1), boss(101, 2, 3)), beaten, party)!!
        assertEquals(101, next.done)
        assertEquals(41, level)
    }

    @Test fun noBossLeftMeansNoNextBoss() {
        assertNull(nextBossLevel(listOf(boss(100, 1)), SaveProgress(ByteArray(64) { -1 }, ByteArray(0))) { emptyList() })
    }

    /** The real tables, from the v1.1 ROM (skipped without it): the fixture's stats and evolutions as the game has them. */
    @Test fun theRealRulesMatchTheGame() {
        val rom = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon Monomyth (v1.1).gba") ?: return
        PokedexSource.reader = rom
        val r = monomythLevelUpRules(POKEDEX_MONOMYTH_V1_1, GUIDE_TABLES_MONOMYTH_V1_1, LEVEL_UP_MONOMYTH_V1_1)
        (0 until 6).forEach { slot ->
            val m = decodePartyMon(party(slot), 0, MONOMYTH_PARTY_MON)!!
            val worked = gen3Stats(m.species, r.baseStats(m.species)!!, m.stats!!.ivs, m.stats!!.evs, MONOMYTH_NATURE_BASE, m.level)
            assertEquals("slot $slot's stats", m.stats!!.stats, worked)
            assertEquals("slot $slot's EXP curve", m.level, levelFromExp(r.expRow(m.species)!!, m.exp!!))
        }
        assertEquals(35, r.movePp(10))
        assertEquals(25, r.movePp(55))
        assertEquals("Mishimizu".length, r.speciesName(194)!!.takeWhile { it != (-1).toByte() }.size)
        val plan = planRaise(party(0), isParty = true, target = 20, r)!!
        assertEquals(195, plan.species)
    }
}
