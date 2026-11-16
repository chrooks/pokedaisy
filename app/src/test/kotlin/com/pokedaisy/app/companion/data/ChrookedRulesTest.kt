package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import kotlin.random.Random

/**
 * CHROOKED RULES' loader ([ChrookedRules]): a Blocks file is on offer only whole - the ROM's SHA-1 and every block's CRC32
 * must hold, and any shape fault refuses the file. With CHROOKED_TARGET set to a folder holding the ROM and its
 * `chrooked.blocks.json` (the dex's Target folder), it also checks the real file and writes `app/build/chrooked/blocks.txt`,
 * mgba_dump's `romblocks` input for the headless proof.
 */
class ChrookedRulesTest {
    private val rom = Random(7).nextBytes(0x1000)

    private fun crc(off: Int, len: Int) = CRC32().apply { update(rom, off, len) }.value
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun block(off: Int, bytes: ByteArray, crc: Long = crc(off, bytes.size), length: Int = bytes.size) =
        """{"address": "0x%08X", "length": $length, "original_crc32": "0x%08X", "bytes": "${hex(bytes)}"}"""
            .format(0x08000000L + off, crc)

    private fun file(vararg blocks: String, sha1: String = ChrookedRules.sha1(rom), format: Int = 1) =
        """{"format": $format, "rom_sha1": "$sha1", "blocks": [${blocks.joinToString(",")}]}"""

    private fun status(json: String): ChrookedRules.Status {
        val dir = Files.createTempDirectory("chrooked").toFile()
        File(dir, ChrookedRules.FILE_NAME).writeText(json)
        return ChrookedRules.load(dir, rom).also { dir.deleteRecursively() }
    }

    @Test
    fun readyFileGivesItsBlocksAtBusAddresses() {
        val s = status(file(block(0x10, byteArrayOf(1, 2, 3)), block(0x200, ByteArray(24) { it.toByte() })))
        assertTrue(s is ChrookedRules.Status.Ready)
        val blocks = (s as ChrookedRules.Status.Ready).blocks
        assertEquals(listOf(0x08000010, 0x08000200), blocks.map { it.addr })
        assertEquals(crc(0x10, 3).toInt(), blocks[0].crc)
        assertArrayEquals(byteArrayOf(1, 2, 3), blocks[0].data)
        assertEquals("block 08000010 %08X 010203\n".format(crc(0x10, 3)), ChrookedRules.toText(blocks.take(1)))
    }

    @Test
    fun anotherRomsFileIsRefusedWhole() {
        assertSame(ChrookedRules.Status.WrongRom, status(file(block(0x10, byteArrayOf(1)), sha1 = "0".repeat(40))))
    }

    @Test
    fun oneBadCrcRefusesTheWholeFile() {
        val good = block(0x10, byteArrayOf(1, 2))
        val bad = block(0x40, byteArrayOf(3, 4), crc = crc(0x40, 2) xor 1)
        assertSame(ChrookedRules.Status.Mismatch, status(file(good, bad)))
    }

    @Test
    fun aBlockPastTheRomsEndIsAMismatch() {
        assertSame(ChrookedRules.Status.Mismatch, status(file(block(0x1000 - 1, byteArrayOf(1, 2), crc = 0))))
    }

    @Test
    fun noFileIsMissing() {
        assertSame(ChrookedRules.Status.Missing, ChrookedRules.load(Files.createTempDirectory("none").toFile(), rom))
        assertSame(ChrookedRules.Status.Missing, ChrookedRules.load(null, rom))
    }

    @Test
    fun shapeFaultsAreUnreadable() {
        val a = block(0x10, byteArrayOf(1, 2, 3))
        listOf(
            "not json",
            file(a, format = 2),
            file(a, sha1 = "xyz"),
            file(),
            file(block(0x10, byteArrayOf(1, 2, 3), length = 4)),
            file(block(0x20, byteArrayOf(1)), a),   // not sorted
            file(a, block(0x11, byteArrayOf(1))),   // overlaps
            file(a).replace("\"bytes\": \"010203\"", "\"bytes\": \"01020\""),
            file(a).replace("0x08000010", "0x02000010"),   // not in the ROM
        ).forEach { assertSame(it, ChrookedRules.Status.Unreadable, status(it)) }
    }

    @Test
    fun whatDoesntFitDropsThePinThenTheLookNeverTheRulesQuietly() {
        val m = ChrookedRules::Mix
        assertEquals(
            listOf(m(true, true, true), m(true, true, false), m(true, false, false), m(false, true, false)),
            ChrookedRules.attempts(hasRules = true, hasLook = true, hasPin = true),
        )
        // The rules alone: one try, and nothing else is ever put in instead.
        assertEquals(listOf(m(true, false, false)), ChrookedRules.attempts(hasRules = true, hasLook = false, hasPin = false))
        // No rules: the look and pin as before (both, then the look alone).
        assertEquals(listOf(m(false, true, true), m(false, true, false)), ChrookedRules.attempts(false, hasLook = true, hasPin = true))
        assertEquals(emptyList<ChrookedRules.Mix>(), ChrookedRules.attempts(false, false, false))
    }

    @Test
    fun theDexsRealFileHoldsAgainstItsRom() {
        val dir = System.getenv("CHROOKED_TARGET")?.let(::File)
        assumeTrue("CHROOKED_TARGET not set", dir?.isDirectory == true)
        val romFile = dir!!.listFiles { f -> f.extension == "gba" }?.singleOrNull()
        assumeTrue("one .gba in CHROOKED_TARGET", romFile != null)
        val s = ChrookedRules.load(dir, romFile!!.readBytes())
        assertTrue("got ${s.reason}", s is ChrookedRules.Status.Ready)
        val blocks = (s as ChrookedRules.Status.Ready).blocks
        File("build/chrooked").apply { mkdirs() }.resolve("blocks.txt").writeText(ChrookedRules.toText(blocks))
        println("chrooked rules: ${blocks.size} block(s) ready from ${romFile.name}")
    }
}
