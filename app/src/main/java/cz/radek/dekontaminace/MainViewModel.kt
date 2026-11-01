package cz.radek.dekontaminace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.radek.dekontaminace.data.AppEntry
import cz.radek.dekontaminace.data.ExceptionStore
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

data class CleanFailure(val packageName: String, val label: String, val reason: String)

data class CleanResult(
    val succeeded: Int,
    val failures: List<CleanFailure>,
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
    val exceptions: Set<String> = emptySet(),
    val selected: Set<String> = emptySet(),
    val cleaning: CleanProgress? = null,
    val result: CleanResult? = null,
    val message: String? = null,
) {
    val cacheCandidates: List<AppEntry>
        get() = apps
            .filter { it.cacheBytes > 0 && (showSystem || !it.isSystem) }
            .sortedByDescending { it.cacheBytes }

    val selectedBytes: Long
        get() = apps.filter { it.packageName in selected }.sumOf { it.cacheBytes }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = StorageRepository(app)
    private val exceptionStore = ExceptionStore(app)
    private val ownPackage = app.packageName

    private val _state = MutableStateFlow(UiState(exceptions = exceptionStore.load()))
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
                _state.update { st ->
                    st.copy(
                        apps = apps,
                        summary = summary,
                        selected = st.selected.filter { p -> apps.any { it.packageName == p } }.toSet(),
                    )
                }
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

    fun toggleShowSystem() = _state.update { st ->
        val show = !st.showSystem
        val visible = st.apps.filter { show || !it.isSystem }.map { it.packageName }.toSet()
        st.copy(showSystem = show, selected = st.selected intersect visible)
    }

    fun toggleSelected(pkg: String) = _state.update { st ->
        if (pkg in st.exceptions) st
        else st.copy(selected = if (pkg in st.selected) st.selected - pkg else st.selected + pkg)
    }

    fun selectAll() = _state.update { st ->
        st.copy(selected = st.cacheCandidates.map { it.packageName }.filter { it !in st.exceptions }.toSet())
    }

    fun selectNone() = _state.update { it.copy(selected = emptySet()) }

    fun toggleException(pkg: String) {
        _state.update { st ->
            val ex = if (pkg in st.exceptions) st.exceptions - pkg else st.exceptions + pkg
            exceptionStore.save(ex)
            st.copy(exceptions = ex, selected = st.selected - ex)
        }
    }

    /**
     * Celá cache všech aplikací přes `pm trim-caches`. Shell nesmí mazat cache jedné aplikace
     * (Android to od něj tiše ignoruje), hromadné uvolnění místa ale smí. Výjimky to neumí.
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
            finishClean(HistoryKind.CACHE_ALL, before, freeBefore, failures = emptyList(), aborted = aborted)
        }
    }

    /** Jen vnější část cache vybraných aplikací (Android/data/<balíček>/cache), ta je shellu přístupná. */
    fun cleanSelectedExternal() {
        val st = _state.value
        if (st.cleaning != null) return
        val targets = st.apps.filter { it.packageName in st.selected && it.packageName !in st.exceptions }
        if (targets.isEmpty()) return
        val labels = targets.associate { it.packageName to it.label }
        val before = targets.associate { it.packageName to it.cacheBytes }

        viewModelScope.launch {
            _state.update { it.copy(cleaning = CleanProgress(0, targets.size, ""), result = null) }
            val freeBefore = freeBytesSafe()
            val failures = mutableListOf<CleanFailure>()
            var aborted: String? = null
            var done = 0

            val (valid, invalid) = targets.map { it.packageName }.partition { PACKAGE_NAME.matches(it) }
            invalid.forEach { failures += CleanFailure(it, labels[it] ?: it, "Neobvyklý název balíčku, přeskočeno") }
            done += invalid.size

            for (chunk in valid.chunked(CHUNK)) {
                _state.update { it.copy(cleaning = CleanProgress(done, targets.size, labels[chunk.first()] ?: chunk.first())) }
                try {
                    // Mažeme jen obsah složky, ne složku samotnou: znovu vytvořená shellem by měla
                    // špatného vlastníka a aplikace by do ní nemusela smět zapisovat.
                    val script = "for p in ${chunk.joinToString(" ")}; do " +
                        "d=/storage/emulated/0/Android/data/\$p/cache; " +
                        "if [ -d \"\$d\" ]; then " +
                        "r=\$(timeout 60 find \"\$d\" -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>&1 | tr '\\n' ' '); " +
                        "echo \"\$p|\$r\"; " +
                        "else echo \"\$p|\"; fi; done"
                    val result = ShizukuBridge.exec(script)
                    val answers = result.output.lineSequence()
                        .mapNotNull { line ->
                            val i = line.indexOf('|')
                            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1).trim()
                        }
                        .toMap()
                    for (pkg in chunk) {
                        val answer = answers[pkg]
                        when {
                            answer == null -> failures += CleanFailure(pkg, labels[pkg] ?: pkg, "Bez odpovědi z shellu")
                            answer.isNotEmpty() -> failures += CleanFailure(pkg, labels[pkg] ?: pkg, answer)
                        }
                    }
                } catch (e: ShizukuUnavailableException) {
                    aborted = "Shizuku přestalo odpovídat (${e.state}). Zbytek seznamu nebyl vyčištěn."
                    break
                } catch (e: Exception) {
                    aborted = "Dekontaminace přerušena: ${e.message}"
                    break
                }
                done += chunk.size
            }
            finishClean(HistoryKind.CACHE_EXTERNAL, before, freeBefore, failures, aborted)
        }
    }

    private suspend fun freeBytesSafe(): Long =
        withContext(Dispatchers.IO) { runCatching { repository.freeBytes() }.getOrDefault(0L) }

    private suspend fun finishClean(
        kind: HistoryKind,
        before: Map<String, Long>,
        freeBefore: Long,
        failures: List<CleanFailure>,
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
            count = drops.count { (_, d) -> d > 0 }.coerceAtLeast(if (kind == HistoryKind.CACHE_ALL) 1 else 0),
            bytes = freed,
            ok = aborted == null && failures.isEmpty(),
            note = aborted ?: failures.takeIf { it.isNotEmpty() }?.let { "Nepovedlo se ${it.size} aplikací" }.orEmpty(),
        )
        _state.update {
            it.copy(
                cleaning = null,
                selected = emptySet(),
                result = CleanResult(
                    succeeded = drops.count { (_, d) -> d > 0 },
                    failures = failures,
                    freedCacheBytes = drops.values.sum(),
                    freeSpaceDelta = freeAfter - freeBefore,
                    aborted = aborted,
                ),
            )
        }
        rescan()
    }

    private companion object {
        const val CHUNK = 15
        // Názvy balíčků jdou přímo do shellu, takže pouštíme jen bezpečné znaky.
        val PACKAGE_NAME = Regex("^[A-Za-z0-9_.]+$")
    }
}
