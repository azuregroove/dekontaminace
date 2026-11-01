package cz.radek.dekontaminace.ui.console

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class StardateTest {

    private val prague = ZoneId.of("Europe/Prague")

    @Test
    fun premiereYearStartsAt41000() {
        assertEquals(41_000.0, stardate(ZonedDateTime.of(1987, 1, 1, 0, 0, 0, 0, prague)), 1e-9)
    }

    @Test
    fun everyYearAddsThousand() {
        assertEquals(80_000.0, stardate(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, prague)), 1e-9)
    }

    @Test
    fun leapYearMiddleIsHalfway() {
        // 2024 má 366 dní, polovina roku (183 dní) je 2. 7. o půlnoci.
        assertEquals(78_500.0, stardate(ZonedDateTime.of(2024, 7, 2, 0, 0, 0, 0, ZoneId.of("UTC"))), 0.01)
    }

    @Test
    fun formatUsesDotAndOneDecimal() {
        assertEquals("80834.9", formatStardate(80_834.94))
        assertEquals("41000.0", formatStardate(41_000.0))
    }
}
