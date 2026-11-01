package cz.radek.dekontaminace.data

import java.util.Locale

private val czech: Locale = Locale.forLanguageTag("cs-CZ")
private val units = listOf("B", "kB", "MB", "GB", "TB")

/** Desítková soustava jako v Nastavení Androidu, aby čísla seděla s tím, co ukazuje systém. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    val pattern = when {
        value < 10 -> "%.2f %s"
        value < 100 -> "%.1f %s"
        else -> "%.0f %s"
    }
    return String.format(czech, pattern, value, units[unit])
}

/** „dnes 14:32“, „včera 9:05“, „před 3 dny“, starší jako datum „12. 10.“. */
fun formatAgo(at: Long, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    val then = java.time.Instant.ofEpochMilli(at).atZone(zone)
    val days = java.time.temporal.ChronoUnit.DAYS.between(then.toLocalDate(), java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
    val time = "${then.hour}:${then.minute.toString().padStart(2, '0')}"
    return when {
        days <= 0L -> "dnes $time"
        days == 1L -> "včera $time"
        days < 7L -> "před $days dny"
        else -> "${then.dayOfMonth}. ${then.monthValue}."
    }
}

/** Skloňování po číslovce: 1 položka, 2 položky, 5 položek. */
fun czPlural(n: Int, one: String, few: String, many: String): String = when {
    n == 1 -> one
    n in 2..4 -> few
    else -> many
}
