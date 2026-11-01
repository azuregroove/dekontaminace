package cz.radek.dekontaminace.data

import android.content.Context

/** Aplikace, jejichž cache se nikdy nečistí (offline mapy, hudba a podobně). */
class ExceptionStore(context: Context) {

    private val prefs = context.getSharedPreferences("exceptions", Context.MODE_PRIVATE)

    fun load(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun save(packages: Set<String>) {
        // Vždy nová instance, SharedPreferences jinak změnu ve stejné množině nemusí uložit.
        prefs.edit().putStringSet(KEY, HashSet(packages)).apply()
    }

    private companion object {
        const val KEY = "cache_exceptions"
    }
}
