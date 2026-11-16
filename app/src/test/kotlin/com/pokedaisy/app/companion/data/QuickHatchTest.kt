package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.zip.CRC32

/**
 * QuickHatch's rules against a fake game: an Egg's cycles go to 0 through SetMonData and the daycare step counter to
 * 254, only for an Egg still in its slot on the free field. The real thing was checked headless (QuickHatch.kt).
 */
class QuickHatchTest {
    private class Mon(val personality: Long, val egg: Boolean, val badEgg: Boolean = false, var cycles: Int = 20)

    /** The party behind [fns]' calls; SaveBlock1 is a pointer at [SB1_PTR] to [SB1]. ROM reads are zeros. */
    private class FakeGame(val fns: FieldItemFns, vararg mons: Mon) : GameCalls {
        val p = fns.party!!
        val party = mons.toMutableList()
        var locked = false
        val ram = mutableMapOf<Long, Byte>()
        private fun mon(addr: Int) = party[(((addr.toLong() and 0xFFFFFFFFL) - p.party) / 100).toInt()]

        init { write(SB1_PTR, byteArrayOf(0x00, 0x10, 0x02, 0x02)); write(SB1 + EMERALD_DAYCARE_STEP_COUNTER, byteArrayOf(0x3D)) }

        override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long = when (fn) {
            fns.fieldControlsLocked -> if (locked) 1 else 0
            p.getMonData -> mon(a0).let { m ->
                when (a1) {
                    0 -> m.personality
                    4 -> if (m.badEgg) 1 else 0
                    32 -> m.cycles.toLong()
                    45 -> if (m.egg) 1 else 0
                    else -> error("field $a1")
                }
            }
            p.setMonData -> { assertEquals(32, a1); mon(a0).cycles = read(a2.toLong() and 0xFFFFFFFFL, 1)[0].toInt() and 0xFF; 0x080000C1 }
            else -> -1
        }

        override fun read(addr: Long, len: Int): ByteArray = when {
            addr == p.partyCount -> byteArrayOf(party.size.toByte())
            addr >= 0x08000000L -> ByteArray(len)
            else -> ByteArray(len) { ram[addr + it] ?: 0 }
        }

        override fun write(addr: Long, bytes: ByteArray): Boolean {
            bytes.forEachIndexed { i, b -> ram[addr + i] = b }
            return true
        }

        val counter get() = ram[SB1 + EMERALD_DAYCARE_STEP_COUNTER]!!.toInt() and 0xFF
    }

    private companion object {
        const val SB1_PTR = 0x03005D8CL
        const val SB1 = 0x02021000L
        val ZERO_CRC = CRC32().apply { update(ByteArray(32)) }.value
        val FNS = FIELD_ITEMS_EMERALD.copy(
            codeCrcs = List(FIELD_ITEMS_EMERALD.codeCrcs.size) { ZERO_CRC },
            party = FIELD_ITEMS_EMERALD.party!!.copy(codeCrcs = List(FIELD_ITEMS_EMERALD.party!!.codeCrcs.size) { ZERO_CRC }),
        )
    }

    private fun hatch(g: FakeGame, slot: Int, personality: Long, busy: Boolean = false) =
        hatchSoon(g.fns, SB1_PTR, false, EMERALD_DAYCARE_STEP_COUNTER, slot, personality, busy, g)

    @Test
    fun anEggHatchesOnTheNextStep() {
        val g = FakeGame(FNS, Mon(1, egg = false), Mon(2, egg = true))
        assertEquals(HatchResult.HATCHING, hatch(g, 1, 2))
        assertEquals(0, g.party[1].cycles)
        assertEquals(254, g.counter)
        // SetMonData's scratch bytes are put back.
        assertEquals(0, g.ram[FNS.party!!.scratch]?.toInt() ?: 0)
    }

    @Test
    fun onlyAnEggStillInItsSlotOnTheFreeField() {
        val g = FakeGame(FNS, Mon(1, egg = false), Mon(2, egg = true), Mon(3, egg = true, badEgg = true))
        assertEquals(HatchResult.NOT_EGG, hatch(g, 0, 1))
        assertEquals(HatchResult.NOT_EGG, hatch(g, 2, 3))
        assertEquals(HatchResult.MOVED, hatch(g, 1, 9))
        assertEquals(HatchResult.MOVED, hatch(g, 5, 2))
        assertEquals(HatchResult.NOT_NOW, hatch(g, 1, 2, busy = true))
        g.locked = true
        assertEquals(HatchResult.NOT_NOW, hatch(g, 1, 2))
        // Nothing was written by any of them.
        assertEquals(20, g.party[1].cycles)
        assertEquals(0x3D, g.counter)
    }

    @Test
    fun offeredOnRetailEmeraldOnly() {
        assertEquals(EMERALD_DAYCARE_STEP_COUNTER, quickHatchCounterOff(GameKind.EMERALD, "BPEE", hack = false))
        assertEquals(EMERALD_DAYCARE_STEP_COUNTER, quickHatchCounterOff(GameKind.EMERALD, "BPEJ", hack = false))
        assertNull(quickHatchCounterOff(GameKind.EMERALD, "AXVE", hack = false)) // Ruby runs as EMERALD
        assertNull(quickHatchCounterOff(GameKind.EMERALD, "BPEE", hack = true)) // Legacy, Recharged
        assertNull(quickHatchCounterOff(GameKind.FIRERED, "BPRE", hack = false))
    }
}
