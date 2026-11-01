package cz.radek.dekontaminace.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DuplicatesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(rel: String, bytes: ByteArray, modified: Long = 1_000L): FileEntry {
        val f = File(tmp.root, rel).apply { parentFile?.mkdirs(); writeBytes(bytes) }
        f.setLastModified(modified)
        // Lomítka jako na Androidu, i když test běží na Windows (originál se pozná podle /DCIM/Camera/).
        return FileEntry(f.path.replace('\\', '/'), f.length(), modified)
    }

    private fun content(size: Int, seed: Int) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    @Test
    fun identicalFilesFormOneGroupAndCameraOriginalIsKept() {
        val data = content(300_000, 1)
        val a = entry("Download/IMG_1.jpg", data, modified = 100)
        val b = entry("DCIM/Camera/IMG_1.jpg", data, modified = 500)
        val c = entry("WhatsApp/IMG_1.jpg", data, modified = 50)
        val groups = Duplicates.find(listOf(a, b, c)) { _, _ -> }
        val g = groups.single()
        assertEquals(3, g.files.size)
        assertEquals(b.path, g.keep)
        assertEquals(600_000L, g.wasted)
    }

    @Test
    fun withoutCameraCopyTheOldestIsKept() {
        val data = content(200_000, 2)
        val a = entry("x/a.bin", data, modified = 900)
        val b = entry("y/b.bin", data, modified = 100)
        assertEquals(b.path, Duplicates.find(listOf(a, b)) { _, _ -> }.single().keep)
    }

    @Test
    fun sameSizeDifferentContentIsNotDuplicate() {
        val a = entry("a.bin", content(300_000, 3))
        // Stejný začátek i konec, liší se jen uprostřed: zachytí to až plný otisk.
        val bytes = content(300_000, 3).also { it[150_000] = (it[150_000] + 1).toByte() }
        val b = entry("b.bin", bytes)
        assertTrue(Duplicates.find(listOf(a, b)) { _, _ -> }.isEmpty())
    }

    @Test
    fun smallFilesAreIgnored() {
        val data = content(50_000, 4)
        assertTrue(Duplicates.find(listOf(entry("a", data), entry("b", data))) { _, _ -> }.isEmpty())
    }

    @Test
    fun groupsAreSortedByWastedSpace() {
        val small = content(150_000, 5)
        val big = content(400_000, 6)
        val groups = Duplicates.find(listOf(entry("s1", small), entry("s2", small), entry("b1", big), entry("b2", big))) { _, _ -> }
        assertEquals(listOf(400_000L, 150_000L), groups.map { it.wasted })
    }
}
