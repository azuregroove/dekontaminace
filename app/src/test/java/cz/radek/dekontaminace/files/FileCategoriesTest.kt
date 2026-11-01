package cz.radek.dekontaminace.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileCategoriesTest {

    private val root = "/storage/emulated/0"
    private fun kind(path: String) = FileEntry(path, 1, 0).oldKind(root)

    @Test
    fun screenshotsRecordingsAndDownloads() {
        assertEquals(OldKind.SCREENSHOTS, kind("$root/Pictures/Screenshots/Screenshot_1.png"))
        assertEquals(OldKind.SCREENSHOTS, kind("$root/DCIM/Screenshots/a.png"))
        assertEquals(OldKind.RECORDINGS, kind("$root/Movies/Screen recordings/rec.mp4"))
        assertEquals(OldKind.RECORDINGS, kind("$root/Movies/screen-20261101.mp4"))
        assertEquals(OldKind.DOWNLOADS, kind("$root/Download/navod.pdf"))
        assertEquals(OldKind.DOWNLOADS, kind("$root/Download/sub/x.zip"))
    }

    @Test
    fun ordinaryFilesHaveNoKind() {
        assertNull(kind("$root/DCIM/Camera/IMG_1.jpg"))
        assertNull(kind("$root/Movies/film.mp4"))
        assertNull(kind("$root/Documents/Download/x.pdf"))
    }
}
