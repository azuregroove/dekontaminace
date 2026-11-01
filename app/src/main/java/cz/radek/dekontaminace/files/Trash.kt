package cz.radek.dekontaminace.files

import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class TrashItem(
    val id: String,
    val originalPath: String,
    val size: Long,
    val deletedAt: Long,
    val stored: File,
) {
    val name: String get() = originalPath.substringAfterLast('/')
    val expiresAt: Long get() = deletedAt + Trash.RETENTION_MS
}

/**
 * Vlastní koš: každý smazaný soubor má složku <id>/ se souborem v data/ a popisem v meta.properties.
 * Leží na stejném úložišti jako mazané soubory, takže přesun je jen přejmenování, žádné kopírování.
 */
class Trash(val root: File) {

    private fun ensureRoot() {
        if (!root.exists()) root.mkdirs()
        // Galerie a hudební přehrávače pak smazané fotky a písničky neukazují.
        val noMedia = File(root, ".nomedia")
        if (!noMedia.exists()) runCatching { noMedia.createNewFile() }
    }

    fun moveIn(path: String): Result<TrashItem> = runCatching {
        ensureRoot()
        val src = File(path)
        require(src.isFile) { "Soubor už neexistuje" }
        val size = src.length()
        val id = "${System.currentTimeMillis()}_${Random.nextInt(100_000, 999_999)}"
        val itemDir = File(root, id)
        val dataDir = File(itemDir, DATA)
        check(dataDir.mkdirs()) { "Nepodařilo se vytvořit místo v koši" }
        val dest = File(dataDir, src.name)
        if (!src.renameTo(dest)) {
            itemDir.deleteRecursively()
            error("Přesun do koše selhal")
        }
        val now = System.currentTimeMillis()
        val props = Properties().apply {
            setProperty("path", path)
            setProperty("size", size.toString())
            setProperty("time", now.toString())
        }
        File(itemDir, META).outputStream().use { props.store(it, null) }
        TrashItem(id, path, size, now, dest)
    }

    fun list(): List<TrashItem> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            runCatching {
                val props = Properties().apply { File(dir, META).inputStream().use { load(it) } }
                val path = props.getProperty("path") ?: return@runCatching null
                val stored = File(File(dir, DATA), path.substringAfterLast('/'))
                if (!stored.exists()) return@runCatching null
                TrashItem(
                    id = dir.name,
                    originalPath = path,
                    size = props.getProperty("size")?.toLongOrNull() ?: stored.length(),
                    deletedAt = props.getProperty("time")?.toLongOrNull() ?: dir.lastModified(),
                    stored = stored,
                )
            }.getOrNull()
        }.sortedByDescending { it.deletedAt }
    }

    /** Vrátí soubor na původní místo; když tam mezitím vznikl jiný se stejným jménem, přidá příponu. */
    fun restore(item: TrashItem): Result<String> = runCatching {
        var target = File(item.originalPath)
        if (target.exists()) target = uniqueSibling(target)
        target.parentFile?.mkdirs()
        check(item.stored.renameTo(target)) { "Obnova selhala" }
        File(root, item.id).deleteRecursively()
        target.path
    }

    fun deleteForever(item: TrashItem): Boolean = File(root, item.id).deleteRecursively()

    /** Smaže položky starší než lhůta. Vrací počet a velikost. */
    fun purgeExpired(now: Long = System.currentTimeMillis()): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        list().filter { it.expiresAt < now }.forEach {
            if (deleteForever(it)) {
                count++
                bytes += it.size
            }
        }
        return count to bytes
    }

    private fun uniqueSibling(file: File): File {
        val base = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (true) {
            val candidate = File(file.parentFile, "$base (obnoveno${if (n > 1) " $n" else ""})$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    companion object {
        val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(7)
        private const val DATA = "data"
        private const val META = "meta.properties"
    }
}
