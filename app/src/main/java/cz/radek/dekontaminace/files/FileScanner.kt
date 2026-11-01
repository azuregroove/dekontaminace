package cz.radek.dekontaminace.files

import java.io.File

data class FileEntry(val path: String, val size: Long, val lastModified: Long) {
    val name: String get() = path.substringAfterLast('/')
}

data class ScanResult(
    val files: List<FileEntry>,
    /** Prázdné složky v pořadí od nejhlubší, takže jdou mazat popořadě. */
    val emptyDirs: List<String>,
    val unreadableDirs: Int,
    /** Soubory v systémovém koši Androidu (.trashed-…); ten je smaže sám a přesouvat je nedovolí. */
    val systemTrashCount: Int = 0,
    val systemTrashBytes: Long = 0,
)

/**
 * Projde sdílené úložiště (s oprávněním „Přístup ke všem souborům“).
 * Android/data a Android/obb jsou pro tohle oprávnění zavřené, ty se přeskakují.
 */
class FileScanner(private val root: File, private val trashRoot: File) {

    private val skippedDirs = setOf(
        File(root, "Android/data").path,
        File(root, "Android/obb").path,
        trashRoot.path,
    )

    // Standardní složky, které Android nebo aplikace očekávají, i když jsou prázdné.
    private val keepWhenEmpty = setOf(
        "Alarms", "Audiobooks", "DCIM", "Documents", "Download", "Movies", "Music",
        "Notifications", "Pictures", "Podcasts", "Recordings", "Ringtones", "Android",
        "DCIM/Camera", "Pictures/Screenshots",
    ).map { File(root, it).path }.toSet()

    fun scan(onProgress: (Int) -> Unit): ScanResult {
        val files = ArrayList<FileEntry>(20_000)
        val emptyDirs = ArrayList<String>()
        var unreadable = 0
        var sysTrashCount = 0
        var sysTrashBytes = 0L
        val androidDir = File(root, "Android").path

        fun walk(dir: File, depth: Int): Boolean {
            val children = dir.listFiles()
            if (children == null) {
                unreadable++
                return true // nečitelnou složku nikdy nehlásíme jako prázdnou
            }
            var hasContent = false
            for (child in children) {
                if (child.isDirectory) {
                    if (child.path in skippedDirs) {
                        hasContent = true
                        continue
                    }
                    if (walk(child, depth + 1)) hasContent = true
                } else if (child.name.startsWith(".trashed-")) {
                    sysTrashCount++
                    sysTrashBytes += child.length()
                    hasContent = true
                } else {
                    files += FileEntry(child.path, child.length(), child.lastModified())
                    hasContent = true
                    if (files.size % 500 == 0) onProgress(files.size)
                }
            }
            if (!hasContent && depth > 0 && dir.path !in keepWhenEmpty && !dir.path.startsWith(androidDir)) {
                emptyDirs += dir.path
            }
            return hasContent
        }

        walk(root, 0)
        onProgress(files.size)
        return ScanResult(files, emptyDirs, unreadable, sysTrashCount, sysTrashBytes)
    }
}
