package com.pokedaisy.app.companion.data

import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import org.json.JSONObject

/**
 * CHROOKED RULES (SETTINGS > GAMEPLAY, the fork's): a chrooked-pokedex Blocks file beside the ROM (`chrooked.blocks.json`,
 * the dex's ADR 0003) whose blocks go into the ROM in memory like PLAYER LOOK's ([com.pokedaisy.app.EmulatorEngine]'s
 * applyBlocks, one `pkRomBlocks` call with the look and the pin). The file is never trusted: it's refused whole when its
 * `rom_sha1` isn't this ROM's, and refused whole when one block's `original_crc32` isn't the CRC32 of the ROM's bytes there
 * (no partial write, ever). Pure: no Android, so the JVM tests read the real file.
 *
 * Format 1: `{format, rom_sha1, blocks: [{address, length, original_crc32, bytes}]}` - addresses on the GBA bus
 * (0x08000000 + the file offset) as hex strings, sorted and apart, `bytes` hex, `original_crc32` a hex string.
 */
object ChrookedRules {
    const val FILE_NAME = "chrooked.blocks.json"
    const val FORMAT = 1
    private const val ROM_BASE = 0x08000000L
    private const val ROM_END = 0x0A000000L

    /** Room left in `pk_cheats.c`'s PK_MAX_BLOCKS (256) for PLAYER LOOK's and WILD PIN's blocks. */
    const val MAX_BLOCKS = 192

    /** What the row says: [Ready] = on offer; every other one greys the row out with its [reason]. */
    sealed class Status(val reason: String) {
        class Ready(val blocks: List<WildPin.RomBlock>) : Status("Changes from your own POKéDEX")
        object Missing : Status("No rules file next to this ROM")
        object WrongRom : Status("The rules file is for another ROM")
        object Mismatch : Status("The rules file doesn't match this ROM")
        object Unreadable : Status("The rules file can't be read")
        /** Checked fine, but the engine couldn't put them in (another patch already on those bytes). */
        object Refused : Status("The rules couldn't go into the game")
    }

    /** One parsed block: [address] on the bus, the CRC32 the ROM must have there, the new bytes. */
    class Block(val address: Long, val originalCrc: Long, bytes: ByteArray) {
        private val raw = bytes.copyOf()
        val bytes: ByteArray get() = raw.copyOf()
        val length: Int get() = raw.size
        val offset: Int get() = (address - ROM_BASE).toInt()
    }

    class Parsed(val romSha1: String, val blocks: List<Block>)

    /** The file's blocks, checked for shape only (no ROM): throws [IllegalArgumentException] on anything off. */
    fun parse(json: String): Parsed {
        val o = runCatching { JSONObject(json) }.getOrElse { throw IllegalArgumentException("not JSON: ${it.message}") }
        require(o.optInt("format", -1) == FORMAT) { "format ${o.opt("format")}, want $FORMAT" }
        val sha1 = o.optString("rom_sha1").lowercase()
        require(sha1.matches(Regex("[0-9a-f]{40}"))) { "rom_sha1 isn't a SHA-1" }
        val arr = o.optJSONArray("blocks") ?: throw IllegalArgumentException("no blocks")
        require(arr.length() in 1..MAX_BLOCKS) { "${arr.length()} blocks (1..$MAX_BLOCKS)" }
        val blocks = (0 until arr.length()).map { i ->
            val b = arr.getJSONObject(i)
            val address = hexLong(b.optString("address"), "block $i address")
            val crc = hexLong(b.optString("original_crc32"), "block $i original_crc32")
            val bytes = hexBytes(b.optString("bytes"), "block $i bytes")
            val length = b.optInt("length", -1)
            require(length == bytes.size && length > 0) { "block $i: length $length, ${bytes.size} bytes" }
            require(address >= ROM_BASE && address + length <= ROM_END) { "block $i: %08X isn't in the ROM".format(address) }
            require(crc in 0..0xFFFFFFFFL) { "block $i: CRC out of range" }
            Block(address, crc, bytes)
        }
        blocks.zipWithNext().forEach { (a, b) ->
            require(a.address + a.length <= b.address) { "blocks %08X and %08X overlap or aren't sorted".format(a.address, b.address) }
        }
        return Parsed(sha1, blocks)
    }

    /** [parsed] against the ROM's bytes: the SHA-1, then every block's CRC32. All or none. */
    fun check(parsed: Parsed, rom: ByteArray): Status {
        if (sha1(rom) != parsed.romSha1) return Status.WrongRom
        val crc = CRC32()
        for (b in parsed.blocks) {
            if (b.offset + b.length > rom.size) return Status.Mismatch
            crc.reset()
            crc.update(rom, b.offset, b.length)
            if (crc.value != b.originalCrc) return Status.Mismatch
        }
        return Status.Ready(parsed.blocks.map { WildPin.RomBlock(it.address.toInt(), it.originalCrc.toInt(), it.bytes) })
    }

    /** The file in [romDir] (the folder the ROM file sits in) checked against [rom], with why not when it isn't on offer. */
    fun load(romDir: File?, rom: ByteArray): Status {
        val f = romDir?.let { File(it, FILE_NAME) }
        if (f == null || !f.isFile) return Status.Missing
        val parsed = runCatching { parse(f.readText()) }.getOrElse { return Status.Unreadable }
        return check(parsed, rom)
    }

    /** Which block sources one `pkRomBlocks` call carries. */
    data class Mix(val rules: Boolean, val look: Boolean, val pin: Boolean)

    /** The calls to try in turn, each replacing every block, until one goes in whole: everything, then without the pin,
     * then the rules alone, then the look alone. The rules are the player's chosen game data, so they're never the one
     * dropped quietly to keep a costume or a one-encounter pin. Only sources that have blocks; none = nothing to put in. */
    fun attempts(hasRules: Boolean, hasLook: Boolean, hasPin: Boolean): List<Mix> = listOf(
        Mix(hasRules, hasLook, hasPin),
        Mix(hasRules, hasLook, false),
        Mix(hasRules, false, false),
        Mix(false, hasLook, false),
    ).filter { it.rules || it.look || it.pin }.distinct()

    /** mgba_dump's `romblocks` lines (`block ADDR CRC HEX`), as PlayerLook's / WildPin's `toText`. */
    fun toText(blocks: List<WildPin.RomBlock>): String = blocks.joinToString("") { b ->
        "block %08X %08X %s\n".format(b.addr, b.crc, b.data.joinToString("") { "%02X".format(it.toInt() and 0xFF) })
    }

    fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun hexLong(s: String, what: String): Long {
        val t = s.removePrefix("0x").removePrefix("0X")
        require(t.isNotEmpty() && t.length <= 8 && t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "$what: '$s'" }
        return t.toLong(16)
    }

    private fun hexBytes(s: String, what: String): ByteArray {
        require(s.isNotEmpty() && s.length % 2 == 0 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "$what isn't hex" }
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
