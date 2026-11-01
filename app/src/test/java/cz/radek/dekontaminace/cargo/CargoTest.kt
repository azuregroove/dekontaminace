package cz.radek.dekontaminace.cargo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CargoTest {

    private val own = "cz.radek.dekontaminace"
    private val root = Cargo.STORAGE

    @Test
    fun quoteEscapesSingleQuotes() {
        assertEquals("'abc'", Cargo.quote("abc"))
        assertEquals("'it'\\''s'", Cargo.quote("it's"))
        assertEquals("'\$HOME; rm -rf /'", Cargo.quote("\$HOME; rm -rf /"))
    }

    @Test
    fun packageNameAllowsOnlySafeCharacters() {
        assertTrue(Cargo.isPackageName("com.game.space.shooter2"))
        assertTrue(Cargo.isPackageName("a_b.C9"))
        assertFalse(Cargo.isPackageName("com.game;rm"))
        assertFalse(Cargo.isPackageName("com game"))
        assertFalse(Cargo.isPackageName(""))
        assertFalse(Cargo.isPackageName("../etc"))
    }

    @Test
    fun movableOnlyInsideForeignAppFolder() {
        assertTrue(Cargo.isMovable("$root/Android/data/com.game/files/big.pak", own))
        assertTrue(Cargo.isMovable("$root/Android/obb/com.game/main.obb", own))
        // Složka aplikace sama se nepřesouvá nikdy.
        assertFalse(Cargo.isMovable("$root/Android/data/com.game", own))
        assertFalse(Cargo.isMovable("$root/Android/data", own))
        // Naše vlastní složka (koš) je tabu.
        assertFalse(Cargo.isMovable("$root/Android/data/$own/Kos/1_2", own))
        assertFalse(Cargo.isMovable("$root/Android/media/com.game/x", own))
        assertFalse(Cargo.isMovable("$root/DCIM/x.jpg", own))
        assertFalse(Cargo.isMovable("$root/Android/data/com.game/../com.other/x", own))
        assertFalse(Cargo.isMovable("$root/Android/data/com.game/./x", own))
        assertFalse(Cargo.isMovable("$root/Android/data/com.game//x", own))
        assertFalse(Cargo.isMovable("$root/Android/data/com.game/a\nb", own))
        assertFalse(Cargo.isMovable("$root/Android/data/bad;pkg/x", own))
    }

    @Test
    fun ownerOfReadsPackageAndArea() {
        assertEquals("com.game" to "data", Cargo.ownerOf("$root/Android/data/com.game/files"))
        assertEquals("com.game" to "obb", Cargo.ownerOf("$root/Android/obb/com.game"))
        assertNull(Cargo.ownerOf("$root/Android/media/com.game"))
        assertNull(Cargo.ownerOf("$root/Download/x"))
    }

    @Test
    fun parseScanConvertsKibAndMergesDataWithObb() {
        val out = """
            S|data|1024|com.game
            S|obb|2|com.game
            S|data|?|com.broken
            S|data|5|bad;pkg
            junk line
            S|data|10
        """.trimIndent()
        val sizes = Cargo.parseScan(out).associateBy { it.packageName }
        assertEquals(setOf("com.game", "com.broken"), sizes.keys)
        assertEquals(1024L * 1024L, sizes.getValue("com.game").dataBytes)
        assertEquals(2048L, sizes.getValue("com.game").obbBytes)
        assertEquals(1024L * 1024L + 2048L, sizes.getValue("com.game").totalBytes)
        assertEquals(0L, sizes.getValue("com.broken").dataBytes)
        assertNull(sizes.getValue("com.broken").obbBytes)
    }

    @Test
    fun parseScanHandlesSizesOverFourGigabytes() {
        // 6 GB v KiB přeteče Int; v Kotlinu se musí počítat v Long.
        val sizes = Cargo.parseScan("S|data|6000000|com.ai.gallery").single()
        assertEquals(6_000_000L * 1024L, sizes.dataBytes)
    }

    @Test
    fun parseListReadsEntriesMissingAndErrors() {
        val dir = "$root/Android/data/com.game"
        val out = listOf(
            "DIR|$dir",
            "E|d|100|1700000000|files",
            "E|f|5000|1700000001|log.txt",
            "E|l|0|0|odkaz",
            "E|f|1|2|jméno|s|rourou",
            "E|f|1|2|",
            "MISS|$root/Android/obb/com.game",
            "ERR|$root/Android/data/com.locked",
        ).joinToString("\n")
        val listing = Cargo.parseList(out)
        assertEquals(listOf("$root/Android/obb/com.game"), listing.missing)
        assertEquals(listOf("$root/Android/data/com.locked"), listing.errors)
        val byName = listing.entries.associateBy { it.name }
        assertEquals(setOf("files", "log.txt", "odkaz", "jméno|s|rourou"), byName.keys)
        assertEquals(EntryKind.DIR, byName.getValue("files").kind)
        assertEquals(100L * 1024L, byName.getValue("files").bytes)
        assertEquals(5000L, byName.getValue("log.txt").bytes)
        assertEquals(1_700_000_001_000L, byName.getValue("log.txt").modified)
        assertEquals(EntryKind.LINK, byName.getValue("odkaz").kind)
        assertEquals("$dir/files", byName.getValue("files").path)
        // Seřazeno od největšího.
        assertEquals("files", listing.entries.first().name)
    }

    @Test
    fun parseListIgnoresEntriesBeforeFirstDir() {
        assertTrue(Cargo.parseList("E|f|1|1|x").entries.isEmpty())
    }

    @Test
    fun scanScriptQuotesRootAndFiltersPackages() {
        val all = Cargo.scanScript("/sdcard/it's")
        assertTrue(all.startsWith("cd '/sdcard/it'\\''s/Android' || exit 3"))
        val some = Cargo.scanScript(packages = listOf("com.good", "bad;rm -rf"))
        assertTrue(some.contains("for p in com.good; do"))
        assertFalse(some.contains("bad;rm"))
    }

    @Test
    fun listScriptQuotesEveryDirectory() {
        val s = Cargo.listScript(listOf("/a b", "/c'd"))
        assertTrue(s.startsWith("for D in '/a b' '/c'\\''d'; do"))
    }
}
