package cz.radek.dekontaminace.data

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    private val prague = ZoneId.of("Europe/Prague")
    private val now = ZonedDateTime.of(2026, 11, 1, 17, 40, 0, 0, prague).toInstant().toEpochMilli()

    private fun ago(days: Long, h: Int, m: Int) =
        ZonedDateTime.of(2026, 11, 1, h, m, 0, 0, prague).minusDays(days).toInstant().toEpochMilli()

    @Test
    fun bytesUseDecimalUnitsAndCzechComma() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("999 B", formatBytes(999))
        assertEquals("1,00 kB", formatBytes(1000))
        assertEquals("12,3 MB", formatBytes(12_345_678))
        assertEquals("290 MB", formatBytes(290_000_000))
        assertEquals("202 GB", formatBytes(202_000_000_000))
        assertEquals("3,26 MB", formatBytes(3_260_000))
    }

    @Test
    fun agoReadsLikeSpeech() {
        assertEquals("dnes 9:05", formatAgo(ago(0, 9, 5), now, prague))
        assertEquals("včera 23:59", formatAgo(ago(1, 23, 59), now, prague))
        assertEquals("včera 0:00", formatAgo(ago(1, 0, 0), now, prague))
        assertEquals("před 3 dny", formatAgo(ago(3, 12, 0), now, prague))
        assertEquals("25. 10.", formatAgo(ago(7, 12, 0), now, prague))
    }

    @Test
    fun futureTimeCountsAsToday() {
        assertEquals("dnes 18:00", formatAgo(ago(0, 18, 0), now, prague))
    }

    @Test
    fun czechPluralForms() {
        val f = { n: Int -> czPlural(n, "položka", "položky", "položek") }
        assertEquals("položek", f(0))
        assertEquals("položka", f(1))
        assertEquals("položky", f(2))
        assertEquals("položky", f(4))
        assertEquals("položek", f(5))
        assertEquals("položek", f(11))
    }
}
