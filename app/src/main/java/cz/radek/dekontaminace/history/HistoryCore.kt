package cz.radek.dekontaminace.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class HistoryKind(val label: String, val freesSpace: Boolean, val isTrashMove: Boolean = false) {
    CACHE_ALL("Hromadná cache", true),
    CACHE_EXTERNAL("Vnější cache vybraných", true),
    FILES_TRASH("Soubory do koše", false, isTrashMove = true),
    FILES_RESTORE("Soubory obnoveny z koše", false),
    FILES_DELETE("Koš souborů smazán natrvalo", true),
    FILES_PURGE("Koš souborů: vypršelo po 7 dnech", true),
    EMPTY_DIRS("Prázdné složky smazány", false),
    CARGO_TRASH("Náklad do koše", false, isTrashMove = true),
    CARGO_RESTORE("Náklad obnoven z koše", false),
    CARGO_DELETE("Koš nákladu smazán natrvalo", true),
    CARGO_PURGE("Koš nákladu: vypršelo po 7 dnech", true),
}

data class HistoryEntry(
    val at: Long,
    val kind: HistoryKind,
    val count: Int,
    val bytes: Long,
    val auto: Boolean,
    val ok: Boolean,
    val note: String = "",
)

data class DayTotal(val day: LocalDate, val freed: Long, val trashed: Long) {
    val total: Long get() = freed + trashed
}

/** Jeden záznam = jeden řádek oddělený tabulátory; neznámé nebo poškozené řádky se přeskočí. */
object HistoryCodec {

    fun encode(e: HistoryEntry): String = listOf(
        e.at.toString(),
        e.kind.name,
        e.count.toString(),
        e.bytes.toString(),
        if (e.auto) "1" else "0",
        if (e.ok) "1" else "0",
        e.note.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '),
    ).joinToString("\t")

    fun decode(line: String): HistoryEntry? {
        val p = line.split('\t', limit = 7)
        if (p.size < 6) return null
        val kind = runCatching { HistoryKind.valueOf(p[1]) }.getOrNull() ?: return null
        return HistoryEntry(
            at = p[0].toLongOrNull() ?: return null,
            kind = kind,
            count = p[2].toIntOrNull() ?: 0,
            bytes = p[3].toLongOrNull() ?: 0L,
            auto = p[4] == "1",
            ok = p[5] == "1",
            note = p.getOrElse(6) { "" },
        )
    }

    /** Součty po dnech za posledních [days] dní včetně dneška, nejstarší první. */
    fun dailyTotals(entries: List<HistoryEntry>, days: Int, today: LocalDate, zone: ZoneId): List<DayTotal> {
        val from = today.minusDays((days - 1).toLong())
        val byDay = entries.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
        return (0 until days).map { i ->
            val day = from.plusDays(i.toLong())
            val list = byDay[day].orEmpty()
            DayTotal(
                day = day,
                freed = list.filter { it.kind.freesSpace }.sumOf { it.bytes.coerceAtLeast(0L) },
                trashed = list.filter { it.kind.isTrashMove }.sumOf { it.bytes.coerceAtLeast(0L) },
            )
        }
    }
}
