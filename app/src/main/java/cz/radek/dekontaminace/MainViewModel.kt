package cz.radek.dekontaminace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.radek.dekontaminace.data.AppEntry
import cz.radek.dekontaminace.data.StorageRepository
import cz.radek.dekontaminace.data.StorageSummary
import cz.radek.dekontaminace.history.History
import cz.radek.dekontaminace.history.HistoryKind
import cz.radek.dekontaminace.shizuku.ShizukuBridge
import cz.radek.dekontaminace.shizuku.ShizukuState
import cz.radek.dekontaminace.shizuku.ShizukuUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CleanProgress(val done: Int, val total: Int, val current: String)

data class CleanResult(
    val succeeded: Int,
    val freedCacheBytes: Long,
    val freeSpaceDelta: Long,
    val aborted: String?,
)

data class UiState(
    val shizuku: ShizukuState = ShizukuState.NOT_RUNNING,
    val usageAccess: Boolean = false,
    val scanning: Boolean = false,
    val summary: StorageSummary? = null,
    val apps: List<AppEntry> = emptyList(),
    val showSystem: Boolean = true,
    val cleaning: CleanProgress? = null,
    val result: CleanResult? = null,
    val message: String? = null,
) {
    val cacheCandidates: List<AppEntry>
        get() = apps
            .filter { it.cacheBytes > 0 && (showSystem || !it.isSystem) }
            .sortedByDescending { it.cacheBytes }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = StorageRepository(app)
    private val ownPackage = app.packageName

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        ShizukuBridge.init(app)
        viewModelScope.launch {
            ShizukuBridge.state.collect { s -> _state.update { it.copy(shizuku = s) } }
        }
        rescan()
    }

    fun onResume() {
        ShizukuBridge.refresh()
        val hadAccess = _state.value.usageAccess
        val hasAccess = repository.hasUsageAccess()
        if (hasAccess != hadAccess) rescan()
    }

    fun rescan() {
        if (_state.value.scanning) return
        viewModelScope.launch {
            val usage = repository.hasUsageAccess()
            _state.update { it.copy(scanning = true, usageAccess = usage) }
            try {
                val (apps, summary) = withContext(Dispatchers.IO) {
                    val list = if (usage) repository.installedApps().filter { it.packageName != ownPackage } else emptyList()
                    list to repository.summary(list, usage)
                }
                _state.update { it.copy(apps = apps, summary = summary) }
            } catch (e: Exception) {
                _state.update { it.copy(message = "Sken paluby selhal: ${e.message}") }
            } finally {
                _state.update { it.copy(scanning = false) }
            }
        }
    }

    fun requestShizukuPermission() = ShizukuBridge.requestPermission()

    fun grantUsageAccessViaShizuku() {
        viewModelScope.launch {
            val msg = try {
                val r = ShizukuBridge.exec("appops set $ownPackage GET_USAGE_STATS allow")
                if (r.ok) null else "Udělení přístupu selhalo (kód ${r.exitCode}): ${r.output.trim()}"
            } catch (e: Exception) {
                "Udělení přístupu selhalo: ${e.message}"
            }
            _state.update { it.copy(message = msg) }
            rescan()
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun dismissResult() = _state.update { it.copy(result = null) }

    fun toggleShowSystem() = _state.update { it.copy(showSystem = !it.showSystem) }

    /**
     * Celá cache všech aplikací přes `pm trim-caches`. Shell nesmí mazat cache jedné aplikace
     * (Android to od něj tiše ignoruje), hromadné uvolnění místa ale smí.
     */
    fun cleanAll() {
        val st = _state.value
        if (st.cleaning != null) return
        val before = st.apps.associate { it.packageName to it.cacheBytes }

        viewModelScope.launch {
            _state.update { it.copy(cleaning = CleanProgress(0, 0, "Android uvolňuje cache všech aplikací…"), result = null) }
            val freeBefore = freeBytesSafe()
            var aborted: String? = null
            try {
                // Číslo je schválně větší než celé úložiště, aby Android prošel všechny kroky úklidu.
                val r = ShizukuBridge.exec("timeout 300 pm trim-caches 9999G")
                if (!r.ok) {
                    aborted = if (r.exitCode == 124) "Android nestihl úklid do 5 minut, výsledek může být jen částečný."
                    else "pm trim-caches skončil s kódem ${r.exitCode}: ${r.output.trim()}"
                }
            } catch (e: ShizukuUnavailableException) {
                aborted = "Shizuku není připravené (${e.state}), nic se nesmazalo."
            } catch (e: Exception) {
                aborted = "Dekontaminace přerušena: ${e.message}"
            }
            finishClean(HistoryKind.CACHE_ALL, before, freeBefore, aborted)
        }
    }

    private suspend fun freeBytesSafe(): Long =
        withContext(Dispatchers.IO) { runCatching { repository.freeBytes() }.getOrDefault(0L) }

    private suspend fun finishClean(
        kind: HistoryKind,
        before: Map<String, Long>,
        freeBefore: Long,
        aborted: String?,
    ) {
        val after = withContext(Dispatchers.IO) { repository.cacheBytesFor(before.keys) }
        val drops = before.mapValues { (pkg, b) -> (b - (after[pkg] ?: b)).coerceAtLeast(0L) }
        val freeAfter = freeBytesSafe()
        // Úbytek cache podle statistik Androidu je přesnější než změna volného místa, do které
        // se míchá všechno, co telefon zrovna dělá; když statistiky chybí, bereme volné místo.
        val freed = drops.values.sum().takeIf { it > 0 } ?: (freeAfter - freeBefore).coerceAtLeast(0L)
        History.record(
            getApplication(),
            kind,
            count = drops.count { (_, d) -> d > 0 }.coerceAtLeast(1),
            bytes = freed,
            ok = aborted == null,
            note = aborted.orEmpty(),
        )
        _state.update {
            it.copy(
                cleaning = null,
                result = CleanResult(
                    succeeded = drops.count { (_, d) -> d > 0 },
                    freedCacheBytes = drops.values.sum(),
                    freeSpaceDelta = freeAfter - freeBefore,
                    aborted = aborted,
                ),
            )
        }
        rescan()
    }
}
