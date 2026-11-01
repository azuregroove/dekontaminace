package cz.radek.dekontaminace.files

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

data class DuplicateGroup(val files: List<FileEntry>, val keep: String) {
    val size: Long get() = files.first().size
    val wasted: Long get() = size * (files.size - 1)
}

/**
 * Duplicity podle obsahu, ne podle jména: nejdřív stejná velikost, pak otisk začátku a konce,
 * a teprve u zbylých kandidátů plný SHA-256. Tak se celé soubory čtou jen tam, kde je to nutné.
 */
object Duplicates {

    private const val MIN_SIZE = 100_000L
    private const val PROBE = 64 * 1024

    fun find(files: List<FileEntry>, onProgress: (done: Int, total: Int) -> Unit): List<DuplicateGroup> {
        val bySize = files.filter { it.size >= MIN_SIZE }
            .groupBy { it.size }
            .values
            .filter { it.size > 1 }
        val result = ArrayList<DuplicateGroup>()
        bySize.forEachIndexed { index, sameSize ->
            onProgress(index, bySize.size)
            val byProbe = sameSize.groupBy { probeHash(it) }.filterKeys { it != null }.values.filter { it.size > 1 }
            for (candidates in byProbe) {
                candidates.groupBy { fullHash(it) }
                    .filterKeys { it != null }
                    .values
                    .filter { it.size > 1 }
                    .forEach { group -> result += DuplicateGroup(group, chooseKeeper(group).path) }
            }
        }
        onProgress(bySize.size, bySize.size)
        return result.sortedByDescending { it.wasted }
    }

    /** Originál z fotoaparátu má přednost, jinak nejstarší kopie. */
    private fun chooseKeeper(group: List<FileEntry>): FileEntry =
        group.firstOrNull { it.path.contains("/DCIM/Camera/") }
            ?: group.minWith(compareBy<FileEntry> { it.lastModified }.thenBy { it.path.length })

    private fun probeHash(entry: FileEntry): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(File(entry.path), "r").use { raf ->
            val buf = ByteArray(PROBE)
            val first = raf.read(buf)
            if (first > 0) digest.update(buf, 0, first)
            if (raf.length() > PROBE * 2) {
                raf.seek(raf.length() - PROBE)
                val last = raf.read(buf)
                if (last > 0) digest.update(buf, 0, last)
            }
        }
        digest.digest().toHex()
    }.getOrNull()

    private fun fullHash(entry: FileEntry): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        File(entry.path).inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().toHex()
    }.getOrNull()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
