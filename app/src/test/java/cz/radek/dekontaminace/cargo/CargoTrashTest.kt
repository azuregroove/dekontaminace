package cz.radek.dekontaminace.cargo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CargoTrashTest {

    private val trash = CargoTrash("/storage/emulated/0/Android/data/cz.radek.dekontaminace/Kos")

    @Test
    fun parseListReadsMetaBlocks() {
        val out = listOf(
            "ITEM|1700000000000_123456",
            "path=/storage/emulated/0/Android/data/com.game/files/a=b.pak",
            "kb=2048",
            "time=1700000000000",
            "",
            "END",
            "ITEM|bad id",
            "path=/x",
            "END",
            "ITEM|1800000000000_654321",
            "kb=1",
            "END",
            "ITEM|1600000000000_111111",
            "path=/storage/emulated/0/Android/obb/com.game/main.obb",
            "END",
        ).joinToString("\n")
        val items = trash.parseList(out)
        assertEquals(listOf("1700000000000_123456", "1600000000000_111111"), items.map { it.id })
        val first = items.first()
        // Rovnítko v cestě patří k cestě, dělí se jen na prvním.
        assertEquals("/storage/emulated/0/Android/data/com.game/files/a=b.pak", first.originalPath)
        assertEquals("a=b.pak", first.name)
        assertEquals(2048L * 1024L, first.bytes)
        assertEquals(1700000000000L + CargoTrash.RETENTION_MS, first.expiresAt)
        assertEquals(0L, items[1].bytes)
    }

    @Test
    fun parseResultsKeepsOnlyOkAndFail() {
        val r = CargoTrash.parseResults("OK|1_2|2048\nFAIL|3_4|Jiný oddíl | něco\nšum\nOK\n")
        assertEquals(2, r.size)
        assertTrue(r[0].ok)
        assertEquals("2048", r[0].detail)
        assertFalse(r[1].ok)
        assertEquals("Jiný oddíl | něco", r[1].detail)
    }

    @Test
    fun restoreCandidatesAddSuffixBeforeExtension() {
        val c = CargoTrash.restoreCandidates("/d/com.game/files/save.dat")
        assertEquals(10, c.size)
        assertEquals("/d/com.game/files/save.dat", c[0])
        assertEquals("/d/com.game/files/save (obnoveno).dat", c[1])
        assertEquals("/d/com.game/files/save (obnoveno 2).dat", c[2])
        assertEquals("/d/com.game/files/.nomedia (obnoveno)", CargoTrash.restoreCandidates("/d/com.game/files/.nomedia")[1])
        assertEquals("/d/com.game/cache (obnoveno)", CargoTrash.restoreCandidates("/d/com.game/cache")[1])
    }

    @Test
    fun scriptsRejectInvalidIds() {
        val bad = listOf("1_2; rm -rf /", "../x", "")
        for (id in bad) {
            assertThrowsIllegalArgument { trash.moveScript(listOf(id to "/x"), 1L) }
            assertThrowsIllegalArgument { trash.deleteScript(listOf(id)) }
            assertThrowsIllegalArgument { trash.restoreScript(listOf(CargoTrashItem(id, "/a/b", 0, 0))) }
        }
    }

    @Test
    fun moveScriptQuotesPathsAndPassesTime() {
        val s = trash.moveScript(listOf("1_2" to "/d/com.game/it's here"), 1234L)
        assertTrue(s.contains("one '1_2' '/d/com.game/it'\\''s here' 1234"))
        assertTrue(s.startsWith("set -f\n"))
    }

    @Test
    fun restoreScriptListsAllCandidates() {
        val s = trash.restoreScript(listOf(CargoTrashItem("1_2", "/d/com.game/a.txt", 0, 0)))
        assertTrue(s.contains("r1 '1_2' 'a.txt' '/d/com.game/a.txt' '/d/com.game/a (obnoveno).txt'"))
    }

    @Test
    fun deleteScriptTouchesOnlyGivenIds() {
        val s = trash.deleteScript(listOf("1_2", "3_4"))
        assertTrue(s.contains("rm -rf -- \"\$K/1_2\""))
        assertTrue(s.contains("rm -rf -- \"\$K/3_4\""))
        assertFalse(s.contains("rm -rf -- \"\$K\"\n"))
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Čekala jsem IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
