package cz.radek.dekontaminace.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrashTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Android používá jen lomítka; na Windows je musíme vnutit, jinak by se jméno souboru četlo špatně.
    private fun file(rel: String, text: String): File =
        File(tmp.root, rel).apply { parentFile?.mkdirs(); writeText(text) }

    private fun slashes(f: File) = f.path.replace('\\', '/')

    private fun trash() = Trash(File(tmp.root, ".Dekontaminace/Kos"))

    @Test
    fun moveInRemovesFileAndRemembersOrigin() {
        val f = file("Download/video.mp4", "12345")
        val t = trash()
        val item = t.moveIn(slashes(f)).getOrThrow()
        assertFalse(f.exists())
        assertTrue(item.stored.exists())
        assertEquals(5L, item.size)
        assertEquals(slashes(f), item.originalPath)
        assertTrue(File(t.root, ".nomedia").exists())
        val listed = t.list().single()
        assertEquals(item.id, listed.id)
        assertEquals(slashes(f), listed.originalPath)
        assertEquals(item.deletedAt + Trash.RETENTION_MS, listed.expiresAt)
    }

    @Test
    fun missingFileFailsWithoutLeftovers() {
        val t = trash()
        assertTrue(t.moveIn(slashes(File(tmp.root, "nic.txt"))).isFailure)
        assertTrue(t.list().isEmpty())
    }

    @Test
    fun restoreReturnsFileToOriginalPlace() {
        val f = file("Pictures/a.jpg", "foto")
        val t = trash()
        val item = t.moveIn(slashes(f)).getOrThrow()
        assertEquals(f, File(t.restore(item).getOrThrow()))
        assertEquals("foto", f.readText())
        assertTrue(t.list().isEmpty())
    }

    @Test
    fun restoreDoesNotOverwriteNewerFile() {
        val f = file("Pictures/a.jpg", "stará")
        val t = trash()
        val item = t.moveIn(slashes(f)).getOrThrow()
        file("Pictures/a.jpg", "nová")
        val restored = t.restore(item).getOrThrow()
        assertEquals(File(tmp.root, "Pictures/a (obnoveno).jpg"), File(restored))
        assertEquals("nová", f.readText())
        assertEquals("stará", File(restored).readText())
    }

    @Test
    fun restoreRecreatesMissingFolder() {
        val f = file("Documents/sub/x.pdf", "pdf")
        val t = trash()
        val item = t.moveIn(slashes(f)).getOrThrow()
        File(tmp.root, "Documents/sub").deleteRecursively()
        t.restore(item).getOrThrow()
        assertTrue(f.exists())
    }

    @Test
    fun purgeDeletesOnlyExpired() {
        val t = trash()
        val a = t.moveIn(slashes(file("a.bin", "aaaa"))).getOrThrow()
        val (count, bytes) = t.purgeExpired(now = a.deletedAt + Trash.RETENTION_MS - 1)
        assertEquals(0, count)
        assertEquals(0L, bytes)
        val (count2, bytes2) = t.purgeExpired(now = a.deletedAt + Trash.RETENTION_MS + 1)
        assertEquals(1, count2)
        assertEquals(4L, bytes2)
        assertTrue(t.list().isEmpty())
    }

    @Test
    fun deleteForeverRemovesItem() {
        val t = trash()
        val item = t.moveIn(slashes(file("b.bin", "b"))).getOrThrow()
        assertTrue(t.deleteForever(item))
        assertTrue(t.list().isEmpty())
    }
}
