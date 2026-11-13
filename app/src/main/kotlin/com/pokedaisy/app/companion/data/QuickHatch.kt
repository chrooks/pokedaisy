package com.pokedaisy.app.companion.data

/**
 * QUICK HATCH (a GAMEPLAY setting, off by default): an Egg in the party hatches on the player's next step, the game's own
 * way. daycare.c's TryProduceOrHatchEgg runs on every step the player takes (field_control_avatar.c's
 * TryStartStepCountScript): it adds one to SaveBlock1's daycare.stepCounter and, when that reaches 255, takes a cycle off
 * every party Egg (the count lives in MON_DATA_FRIENDSHIP) - or, for an Egg already at 0, starts EventScript_EggHatch
 * ("Huh?", the hatch scene, the nickname question). So HATCH sets the Egg's count to 0 through the game's SetMonData (it
 * re-encrypts the box and fixes its checksum) and the counter to 254. The hatch, its Pokédex entry and the HATCHED EGGS
 * game stat are the game's own; any other Egg in the party loses one cycle early, as on a 255th step.
 *
 * Checked headless on retail English Emerald (2026-11-12, mgba_dump): the lead made an Egg with 20 cycles (SetMonData
 * IS_EGG / FRIENDSHIP), then these two writes; one step on Route 101 gave "Huh?" and the hatch scene, IS_EGG 0 after it.
 * Without them, three steps took the counter 0x3D -> 0x40 and nothing hatched.
 *
 * The counter's place in the other retail layouts, from pret's global.h (each checked against the next field's offset):
 * FireRed / LeafGreen 0x309A (daycare 0x2F80, giftRibbons 0x309C), Ruby / Sapphire 0x30B6 (daycare 0x2F9C,
 * DayCareStepCountersEtc.eggCycleStepsRemaining; linkBattleRecords 0x30B8). Not run headless yet - no ROM on hand - so
 * not offered there until they are.
 */
const val EMERALD_DAYCARE_STEP_COUNTER = 0x314C

/** Where the running ROM keeps the daycare's step counter in SaveBlock1, or null where HATCH isn't offered: retail
 * Emerald in every language (they share English's save layout; Kaizo and the randomized builds too), not the rebuilds
 * whose SaveBlock1 moved (Legacy, Recharged: [hack]), not Ruby / Sapphire, which run as EMERALD under their own codes. */
fun quickHatchCounterOff(kind: GameKind?, gameCode: String, hack: Boolean): Int? =
    if (!hack && kind == GameKind.EMERALD && gameCode.startsWith("BPE")) EMERALD_DAYCARE_STEP_COUNTER else null

enum class HatchResult {
    /** The Egg hatches on the player's next step. */
    HATCHING,
    /** Not on the free field right now (a menu, a script, a battle). */
    NOT_NOW,
    /** The Pokémon in that slot isn't an Egg (or is a Bad Egg, which never hatches). */
    NOT_EGG,
    /** The party changed under the summary (the Egg isn't in that slot any more). */
    MOVED,
    FAILED,
}

private const val MON_SIZE = 100
private const val MON_DATA_PERSONALITY = 0
private const val MON_DATA_SANITY_IS_BAD_EGG = 4
private const val MON_DATA_FRIENDSHIP = 32
/** One below the 255 TryProduceOrHatchEgg checks for: the next step reaches it. */
private const val STEP_COUNTER_NEXT_STEP = 254
private const val EWRAM_FIRST = 0x02000000L
private const val EWRAM_LAST = 0x0203FFFFL

/**
 * Makes party slot [slot] ([personality]), an Egg, hatch on the next step: its egg cycles to 0, the daycare's step counter
 * ([counterOff] into SaveBlock1, whose pointer is [saveBlock1Ptr] - or SaveBlock1 itself when [staticSaveBlocks]) to 254.
 * Only on the free field, like the bag's USE. Emu thread.
 */
fun hatchSoon(
    fns: FieldItemFns, saveBlock1Ptr: Long, staticSaveBlocks: Boolean, counterOff: Int,
    slot: Int, personality: Long, busy: Boolean, g: GameCalls,
): HatchResult = runCatching {
    val p = fns.party ?: return HatchResult.FAILED
    if (!fieldItemCodeMatches(fns, g::read)) return HatchResult.FAILED
    if (busy || g.call(fns.fieldControlsLocked) != 0L) return HatchResult.NOT_NOW
    val count = g.read(p.partyCount, 1)[0].toInt() and 0xFF
    if (slot !in 0 until count.coerceAtMost(6)) return HatchResult.MOVED
    val mon = (p.party + slot * MON_SIZE).toInt()
    if (g.call(p.getMonData, mon, MON_DATA_PERSONALITY) and 0xFFFFFFFFL != personality) return HatchResult.MOVED
    if (g.call(p.getMonData, mon, p.isEggField) == 0L) return HatchResult.NOT_EGG
    if (g.call(p.getMonData, mon, MON_DATA_SANITY_IS_BAD_EGG) != 0L) return HatchResult.NOT_EGG
    val base = if (staticSaveBlocks) saveBlock1Ptr else u32le(g.read(saveBlock1Ptr, 4), 0)
    val counter = base + counterOff
    if (base < EWRAM_FIRST || counter > EWRAM_LAST) return HatchResult.FAILED
    // The Egg's cycles to 0: SetMonData reads the value through a pointer, a byte for FRIENDSHIP.
    val friendship = MON_DATA_FRIENDSHIP + p.monFieldShift
    val before = g.read(p.scratch, 2)
    if (!g.write(p.scratch, byteArrayOf(0, 0))) return HatchResult.FAILED
    g.call(p.setMonData, mon, friendship, p.scratch.toInt())
    g.write(p.scratch, before)
    if (g.call(p.getMonData, mon, friendship) != 0L) return HatchResult.FAILED
    if (!g.write(counter, byteArrayOf(STEP_COUNTER_NEXT_STEP.toByte()))) return HatchResult.FAILED
    if (g.read(counter, 1)[0].toInt() and 0xFF != STEP_COUNTER_NEXT_STEP) return HatchResult.FAILED
    HatchResult.HATCHING
}.getOrDefault(HatchResult.FAILED)
