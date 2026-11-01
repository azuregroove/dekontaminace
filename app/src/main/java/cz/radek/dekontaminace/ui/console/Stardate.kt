package cz.radek.dekontaminace.ui.console

import java.time.Duration
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Hvězdné datum svázané s premiérou TNG: 1. řada (1987) nesla data 41xxx, takže
 * 1. ledna 1987 = 41000,0 a každý rok přidá přesně 1000 jednotek. Poloha v roce
 * se počítá z jeho skutečné délky (přestupné roky).
 */
fun stardate(now: ZonedDateTime = ZonedDateTime.now()): Double {
    val start = now.toLocalDate().withDayOfYear(1).atStartOfDay(now.zone)
    val yearLength = Duration.between(start, start.plusYears(1)).toMillis().toDouble()
    val fraction = Duration.between(start, now).toMillis() / yearLength
    return 41_000.0 + (now.year - 1987) * 1_000.0 + fraction * 1_000.0
}

fun formatStardate(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
