package cz.radek.dekontaminace.rules

import android.content.Context
import cz.radek.dekontaminace.data.StorageRepository
import cz.radek.dekontaminace.data.formatBytes
import cz.radek.dekontaminace.history.History
import cz.radek.dekontaminace.history.HistoryKind
import cz.radek.dekontaminace.shizuku.ShizukuBridge
import cz.radek.dekontaminace.shizuku.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hromadné uvolnění cache všech aplikací; sdílí ho plánovaný běh i tlačítko „Spustit teď“. */
object CacheRule {

    suspend fun run(context: Context, manual: Boolean): RuleRun {
        val app = context.applicationContext
        val repository = StorageRepository(app)
        val started = System.currentTimeMillis()

        // Posluchače Shizuku registrujeme z hlavního vlákna stejně jako při startu z aktivity.
        withContext(Dispatchers.Main) { ShizukuBridge.init(app) }
        // Když proces spustil plánovač, binder od Shizuku dorazí až chvíli po startu.
        val state = ShizukuBridge.awaitReady(15_000L)
        if (state != ShizukuState.READY) {
            val why = when (state) {
                ShizukuState.NOT_RUNNING -> "Shizuku neběží. Po restartu telefonu ho musíš znovu spustit."
                ShizukuState.NOT_INSTALLED -> "Shizuku není nainstalované."
                ShizukuState.NO_PERMISSION, ShizukuState.DENIED -> "Dekontaminace nemá oprávnění Shizuku."
                else -> "Shizuku není připravené ($state)."
            }
            return save(app, RuleRun(started, ok = false, freedBytes = 0, text = "Neproběhlo: $why", manual = manual))
        }

        val freeBefore = freeBytes(repository)
        val run = try {
            // Číslo je schválně větší než celé úložiště, aby Android prošel všechny kroky úklidu.
            val r = ShizukuBridge.exec("timeout 300 pm trim-caches 9999G")
            val freed = (freeBytes(repository) - freeBefore).coerceAtLeast(0L)
            when {
                r.ok -> RuleRun(started, true, freed, "Uvolněno ${formatBytes(freed)}.", manual)
                r.exitCode == 124 -> RuleRun(started, false, freed, "Android nestihl úklid do 5 minut, uvolněno aspoň ${formatBytes(freed)}.", manual)
                else -> RuleRun(started, false, freed, "pm trim-caches skončil s kódem ${r.exitCode}: ${r.output.trim().take(200)}", manual)
            }
        } catch (e: Exception) {
            RuleRun(started, false, 0, "Selhalo: ${e.message ?: e.javaClass.simpleName}", manual)
        }
        return save(app, run)
    }

    private suspend fun freeBytes(repository: StorageRepository): Long =
        withContext(Dispatchers.IO) { runCatching { repository.freeBytes() }.getOrDefault(0L) }

    private fun save(context: Context, run: RuleRun): RuleRun {
        RuleStore(context).saveCacheRun(run)
        History.record(context, HistoryKind.CACHE_ALL, 1, run.freedBytes, ok = run.ok, auto = !run.manual, note = if (run.ok) "" else run.text)
        return run
    }
}
