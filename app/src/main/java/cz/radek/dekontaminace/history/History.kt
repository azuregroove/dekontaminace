package cz.radek.dekontaminace.history

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Deník akcí v souboru aplikace. Píšou do něj všechny moduly i plánovaná pravidla; běží
 * ve stejném procesu, takže stačí synchronizace a počítadlo změn pro obrazovku Historie.
 */
object History {

    val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(90)
    private const val FILE = "historie.tsv"
    private const val PRUNE_ABOVE_BYTES = 256_000L

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun record(
        context: Context,
        kind: HistoryKind,
        count: Int,
        bytes: Long,
        ok: Boolean,
        auto: Boolean = false,
        note: String = "",
    ) {
        if (count <= 0 && ok) return
        append(context, HistoryEntry(System.currentTimeMillis(), kind, count, bytes, auto, ok, note))
    }

    @Synchronized
    private fun append(context: Context, entry: HistoryEntry) {
        // Deník je doplněk: když se zápis nepovede, akce samotná kvůli tomu selhat nesmí.
        runCatching {
            val f = file(context)
            f.appendText(HistoryCodec.encode(entry) + "\n")
            if (f.length() > PRUNE_ABOVE_BYTES) prune(f)
        }
        _version.update { it + 1 }
    }

    @Synchronized
    fun load(context: Context): List<HistoryEntry> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val limit = System.currentTimeMillis() - RETENTION_MS
        return runCatching {
            f.readLines().mapNotNull(HistoryCodec::decode).filter { it.at >= limit }.sortedByDescending { it.at }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        _version.update { it + 1 }
    }

    private fun prune(f: File) {
        val limit = System.currentTimeMillis() - RETENTION_MS
        val keep = f.readLines().filter { line -> HistoryCodec.decode(line)?.let { it.at >= limit } == true }
        f.writeText(if (keep.isEmpty()) "" else keep.joinToString("\n", postfix = "\n"))
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)
}
