package cz.radek.dekontaminace.history

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryCoreTest {

    private val prague = ZoneId.of("Europe/Prague")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, prague).toInstant().toEpochMilli()

    @Test
    fun encodeDecodeRoundTrip() {
        val e = HistoryEntry(at(2026, 11, 1, 17, 27), HistoryKind.CACHE_ALL, 164, 11_500_000_000L, auto = true, ok = false, note = "Nepovedlo se 3 aplikací")
        assertEquals(e, HistoryCodec.decode(HistoryCodec.encode(e)))
    }

    @Test
    fun noteCannotBreakTheLineFormat() {
        val e = HistoryEntry(1L, HistoryKind.FILES_TRASH, 1, 10, auto = false, ok = true, note = "a\tb\nc\rd")
        val line = HistoryCodec.encode(e)
        assertEquals(1, line.lines().size)
        assertEquals("a b c d", HistoryCodec.decode(line)?.note)
    }

    @Test
    fun brokenOrUnknownLinesAreSkipped() {
        assertNull(HistoryCodec.decode(""))
        assertNull(HistoryCodec.decode("1\tCACHE_ALL\t1\t2\t0"))
        assertNull(HistoryCodec.decode("1\tDATA_CLEAR\t1\t2\t0\t1"))
        assertNull(HistoryCodec.decode("ne\tCACHE_ALL\t1\t2\t0\t1"))
    }

    @Test
    fun oldLinesWithoutNoteStillDecode() {
        val e = HistoryCodec.decode("5\tCARGO_DELETE\t2\t300\t0\t1")
        assertEquals(HistoryKind.CARGO_DELETE, e?.kind)
        assertEquals("", e?.note)
        assertEquals(300L, e?.bytes)
    }

    @Test
    fun dailyTotalsSplitFreedAndTrashedPerDay() {
        val entries = listOf(
            HistoryEntry(at(2026, 11, 1, 10), HistoryKind.CACHE_ALL, 1, 1000, auto = false, ok = true),
            HistoryEntry(at(2026, 11, 1, 11), HistoryKind.FILES_TRASH, 2, 500, auto = false, ok = true),
            HistoryEntry(at(2026, 11, 1, 12), HistoryKind.FILES_RESTORE, 1, 400, auto = false, ok = true),
            HistoryEntry(at(2026, 10, 30, 9), HistoryKind.CARGO_PURGE, 1, 70, auto = true, ok = true),
            HistoryEntry(at(2026, 10, 30, 9), HistoryKind.CACHE_EXTERNAL, 1, -5, auto = false, ok = true),
            HistoryEntry(at(2026, 10, 20, 9), HistoryKind.CACHE_ALL, 1, 9999, auto = false, ok = true),
        )
        val days = HistoryCodec.dailyTotals(entries, 3, LocalDate.of(2026, 11, 1), prague)
        assertEquals(listOf(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1)), days.map { it.day })
        assertEquals(70L, days[0].freed)
        assertEquals(0L, days[1].total)
        assertEquals(1000L, days[2].freed)
        assertEquals(500L, days[2].trashed)
        assertEquals(1500L, days[2].total)
    }

    @Test
    fun midnightBelongsToTheNewDay() {
        val entries = listOf(
            HistoryEntry(at(2026, 10, 31, 23, 59), HistoryKind.CACHE_ALL, 1, 1, auto = false, ok = true),
            HistoryEntry(at(2026, 11, 1, 0, 0), HistoryKind.CACHE_ALL, 1, 2, auto = false, ok = true),
        )
        val days = HistoryCodec.dailyTotals(entries, 2, LocalDate.of(2026, 11, 1), prague)
        assertEquals(1L, days[0].freed)
        assertEquals(2L, days[1].freed)
    }

    @Test
    fun dayWithTimeChangeIsCountedOnce() {
        // 25. 10. 2026 se v Praze přechází na zimní čas, den má 25 hodin.
        val entries = listOf(
            HistoryEntry(at(2026, 10, 25, 1), HistoryKind.CACHE_ALL, 1, 1, auto = false, ok = true),
            HistoryEntry(at(2026, 10, 25, 23), HistoryKind.CACHE_ALL, 1, 2, auto = false, ok = true),
        )
        val days = HistoryCodec.dailyTotals(entries, 1, LocalDate.of(2026, 10, 25), prague)
        assertEquals(3L, days.single().freed)
    }
}
