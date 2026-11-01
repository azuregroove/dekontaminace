package cz.radek.dekontaminace.rules

import android.content.Context
import android.content.SharedPreferences

enum class RuleInterval(val label: String, val days: Long?) {
    OFF("Vypnuto", null),
    DAILY("Denně", 1),
    WEEKLY("Týdně", 7),
}

data class RuleRun(
    val at: Long,
    val ok: Boolean,
    val freedBytes: Long,
    val text: String,
    val manual: Boolean,
)

/** Nastavení pravidel a poslední běh. Worker i obrazovka běží ve stejném procesu, sdílí jedny prefs. */
class RuleStore(context: Context) {

    val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("rules", Context.MODE_PRIVATE)

    var cacheInterval: RuleInterval
        get() = runCatching { RuleInterval.valueOf(prefs.getString(KEY_INTERVAL, null) ?: "") }.getOrDefault(RuleInterval.OFF)
        set(value) = prefs.edit().putString(KEY_INTERVAL, value.name).apply()

    fun lastCacheRun(): RuleRun? {
        val at = prefs.getLong("last_at", 0L)
        if (at == 0L) return null
        return RuleRun(
            at = at,
            ok = prefs.getBoolean("last_ok", false),
            freedBytes = prefs.getLong("last_freed", 0L),
            text = prefs.getString("last_text", "").orEmpty(),
            manual = prefs.getBoolean("last_manual", false),
        )
    }

    fun saveCacheRun(run: RuleRun) {
        prefs.edit()
            .putLong("last_at", run.at)
            .putBoolean("last_ok", run.ok)
            .putLong("last_freed", run.freedBytes)
            .putString("last_text", run.text)
            .putBoolean("last_manual", run.manual)
            .apply()
    }

    private companion object {
        const val KEY_INTERVAL = "cache_interval"
    }
}
