package cz.radek.dekontaminace

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.radek.dekontaminace.cargo.Cargo
import cz.radek.dekontaminace.cargo.CargoEntry
import cz.radek.dekontaminace.cargo.CargoSizes
import cz.radek.dekontaminace.cargo.CargoTrash
import cz.radek.dekontaminace.cargo.CargoTrashItem
import cz.radek.dekontaminace.cargo.OpResult
import cz.radek.dekontaminace.data.formatBytes
import cz.radek.dekontaminace.history.History
import cz.radek.dekontaminace.history.HistoryKind
import cz.radek.dekontaminace.shizuku.ShizukuBridge
import cz.radek.dekontaminace.shizuku.ShizukuUnavailableException
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CargoView { APPS, BROWSE, TRASH }

data class CargoApp(
    val packageName: String,
    /** null = aplikace už není nainstalovaná, složka je zbytek po ní. */
    val label: String?,
    val isSystem: Boolean,
    val dataBytes: Long?,
    val obbBytes: Long?,
) {
    val totalBytes: Long get() = (dataBytes ?: 0L) + (obbBytes ?: 0L)
    val installed: Boolean get() = label != null
}

data class CargoState(
    val scanning: Boolean = false,
    val scannedAt: Long? = null,
    val apps: List<CargoApp> = emptyList(),
    val view: CargoView = CargoView.APPS,
    val app: CargoApp? = null,
    /** Otevřené podsložky; prázdné = kořen aplikace (obsah data i obb dohromady). */
    val path: List<String> = emptyList(),
    val entries: List<CargoEntry>? = null,
    val selected: Set<String> = emptySet(),
    val trash: List<CargoTrashItem> = emptyList(),
    val busy: String? = null,
    val message: String? = null,
) {
    val totalBytes: Long get() = apps.sumOf { it.totalBytes }
    val trashBytes: Long get() = trash.sumOf { it.bytes }
}

class CargoViewModel(app: Application) : AndroidViewModel(app) {

    private val ownPackage = app.packageName
    @Suppress("DEPRECATION") // Stejný kořen jako modul Soubory; shell vidí úložiště pod touto cestou.
    private val root: String = Environment.getExternalStorageDirectory().path
    private val trash = CargoTrash("${Cargo.dataDir(ownPackage, root)}/Kos")

    private val _state = MutableStateFlow(CargoState())
    val state: StateFlow<CargoState> = _state.asStateFlow()

    init {
        // Naši složku v Android/data musí založit systém (se správným vlastníkem), ne shell.
        viewModelScope.launch(Dispatchers.IO) { runCatching { app.getExternalFilesDir(null) } }
    }

    /** Volá obrazovka při prvním otevření modulu, až je Shizuku připravené. */
    fun start() {
        if (_state.value.scannedAt == null && !_state.value.scanning) scan()
        refreshTrash(purge = true)
    }

    fun scan() {
        if (_state.value.scanning) return
        viewModelScope.launch {
            _state.update { it.copy(scanning = true) }
            try {
                val r = ShizukuBridge.exec(Cargo.scanScript(root))
                if (!r.ok) error("kód ${r.exitCode}: ${r.output.trim().take(300)}")
                val apps = withContext(Dispatchers.IO) { Cargo.parseScan(r.output).filter { it.packageName != ownPackage }.map(::label) }
                _state.update { it.copy(apps = apps.sortedByDescending { a -> a.totalBytes }, scannedAt = System.currentTimeMillis()) }
            } catch (e: Exception) {
                _state.update { it.copy(message = "Sken nákladového prostoru selhal: ${describe(e)}") }
            } finally {
                _state.update { it.copy(scanning = false) }
            }
        }
    }

    private fun label(s: CargoSizes): CargoApp {
        val pm = getApplication<Application>().packageManager
        val info = try {
            pm.getApplicationInfo(s.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        return CargoApp(
            packageName = s.packageName,
            label = info?.loadLabel(pm)?.toString(),
            isSystem = info != null && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            dataBytes = s.dataBytes,
            obbBytes = s.obbBytes,
        )
    }

    fun openApp(app: CargoApp) {
        _state.update { it.copy(view = CargoView.BROWSE, app = app, path = emptyList(), entries = null, selected = emptySet()) }
        loadListing()
    }

    fun openDir(entry: CargoEntry) {
        _state.update { it.copy(path = it.path + entry.path, entries = null, selected = emptySet()) }
        loadListing()
    }

    fun up() {
        val st = _state.value
        if (st.path.isEmpty()) showApps()
        else {
            _state.update { it.copy(path = it.path.dropLast(1), entries = null, selected = emptySet()) }
            loadListing()
        }
    }

    fun showApps() = _state.update { it.copy(view = CargoView.APPS, app = null, path = emptyList(), entries = null, selected = emptySet()) }

    fun showTrash() {
        _state.update { it.copy(view = CargoView.TRASH, selected = emptySet()) }
        refreshTrash(purge = false)
    }

    private fun currentDirs(st: CargoState): List<String> {
        val app = st.app ?: return emptyList()
        return st.path.lastOrNull()?.let { listOf(it) }
            ?: listOf(Cargo.dataDir(app.packageName, root), Cargo.obbDir(app.packageName, root))
    }

    private fun loadListing() {
        val dirs = currentDirs(_state.value)
        if (dirs.isEmpty()) return
        viewModelScope.launch {
            val msg = try {
                val r = ShizukuBridge.exec(Cargo.listScript(dirs))
                val listing = Cargo.parseList(r.output)
                // Mezitím mohl uživatel odejít jinam, starý výsledek pak nepatří na obrazovku.
                if (currentDirs(_state.value) == dirs) _state.update { it.copy(entries = listing.entries) }
                when {
                    listing.errors.isNotEmpty() -> "Shell nesmí otevřít: ${listing.errors.joinToString { it.substringAfterLast('/') }}"
                    !r.ok -> "Výpis skončil s kódem ${r.exitCode}."
                    else -> null
                }
            } catch (e: Exception) {
                if (currentDirs(_state.value) == dirs) _state.update { it.copy(entries = emptyList()) }
                "Výpis selhal: ${describe(e)}"
            }
            if (msg != null) _state.update { it.copy(message = msg) }
        }
    }

    fun toggle(key: String) = _state.update {
        it.copy(selected = if (key in it.selected) it.selected - key else it.selected + key)
    }

    fun selectAll() = _state.update { st ->
        val keys = when (st.view) {
            CargoView.TRASH -> st.trash.map { it.id }
            else -> st.entries.orEmpty().map { it.path }.filter { Cargo.isMovable(it, ownPackage, root) }
        }
        st.copy(selected = keys.toSet())
    }

    fun selectNone() = _state.update { it.copy(selected = emptySet()) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun moveSelectedToTrash() {
        val st = _state.value
        if (st.busy != null) return
        val paths = st.selected.filter { Cargo.isMovable(it, ownPackage, root) }
        if (paths.isEmpty()) return
        val sizes = st.entries.orEmpty().associate { it.path to it.bytes }
        viewModelScope.launch {
            _state.update { it.copy(busy = "Přesouvám do koše…") }
            val base = System.currentTimeMillis()
            val ids = paths.mapIndexed { i, p -> "${base}_${i}${Random.nextInt(1000, 9999)}" to p }
            val results = runOps(trash.moveScript(ids, base), ids.map { it.first })
            val byId = ids.toMap()
            val ok = results.filter { it.ok }.mapNotNull { byId[it.id] }
            val failed = results.filter { !it.ok }
            History.record(getApplication(), HistoryKind.CARGO_TRASH, ok.size, ok.sumOf { sizes[it] ?: 0L }, ok = failed.isEmpty())
            _state.update { s ->
                s.copy(
                    busy = null,
                    selected = emptySet(),
                    entries = s.entries?.filter { it.path !in ok },
                    message = buildString {
                        append("Do koše přesunuto ${ok.size} položek (${formatBytes(ok.sumOf { sizes[it] ?: 0L })}). ")
                        append("Místo se uvolní až vysypáním koše, obnovit jde 7 dní.")
                        if (failed.isNotEmpty()) {
                            append("\nNepovedlo se ${failed.size}: ")
                            append(failed.take(5).joinToString("; ") { f -> "${byId[f.id]?.substringAfterLast('/') ?: f.id}: ${f.detail}" })
                        }
                    },
                )
            }
            refreshTrash(purge = false)
            ok.mapNotNull { Cargo.ownerOf(it, root)?.first }.toSet().let { rescanPackages(it) }
        }
    }

    fun restoreSelected() {
        val st = _state.value
        val items = st.trash.filter { it.id in st.selected }
        if (items.isEmpty() || st.busy != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = "Obnovuji…") }
            val results = runOps(trash.restoreScript(items), items.map { it.id })
            val byId = items.associateBy { it.id }
            val ok = results.filter { it.ok }
            val renamed = ok.count { r -> r.detail != byId[r.id]?.originalPath }
            val failed = results.filter { !it.ok }
            History.record(getApplication(), HistoryKind.CARGO_RESTORE, ok.size, ok.sumOf { r -> byId[r.id]?.bytes ?: 0L }, ok = failed.isEmpty())
            _state.update {
                it.copy(
                    busy = null,
                    selected = emptySet(),
                    message = buildString {
                        append("Obnoveno ${ok.size} položek na původní místo.")
                        if (renamed > 0) append(" $renamed dostalo příponu „(obnoveno)“, protože na místě už bylo něco nového.")
                        if (failed.isNotEmpty()) {
                            append("\nNepovedlo se ${failed.size}: ")
                            append(failed.take(5).joinToString("; ") { f -> "${byId[f.id]?.name ?: f.id}: ${f.detail}" })
                        }
                    },
                )
            }
            refreshTrash(purge = false)
            rescanPackages(ok.mapNotNull { r -> byId[r.id]?.let { Cargo.ownerOf(it.originalPath, root)?.first } }.toSet())
        }
    }

    fun deleteSelectedForever() {
        val st = _state.value
        val items = st.trash.filter { it.id in st.selected }
        if (items.isEmpty() || st.busy != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = "Mažu natrvalo…") }
            val results = runOps(trash.deleteScript(items.map { it.id }), items.map { it.id })
            val okIds = results.filter { it.ok }.map { it.id }.toSet()
            val freed = items.filter { it.id in okIds }.sumOf { it.bytes }
            History.record(getApplication(), HistoryKind.CARGO_DELETE, okIds.size, freed, ok = okIds.size == items.size)
            _state.update {
                it.copy(
                    busy = null,
                    selected = emptySet(),
                    message = "Natrvalo smazáno ${okIds.size} položek (${formatBytes(freed)})." +
                        if (okIds.size < items.size) " ${items.size - okIds.size} se smazat nepodařilo." else "",
                )
            }
            refreshTrash(purge = false)
        }
    }

    /** Spustí skript a doplní chybějící odpovědi, ať žádná položka nezmizí bez hlášení. */
    private suspend fun runOps(script: String, ids: List<String>): List<OpResult> {
        val (results, fatal) = try {
            val r = ShizukuBridge.exec(script)
            val parsed = CargoTrash.parseResults(r.output)
            val err = r.output.lineSequence().firstOrNull { it.startsWith("ERR|") }?.substring(4)
            parsed to (err ?: if (!r.ok && parsed.isEmpty()) "kód ${r.exitCode}: ${r.output.trim().take(200)}" else null)
        } catch (e: Exception) {
            emptyList<OpResult>() to describe(e)
        }
        val answered = results.map { it.id }.toSet()
        return results + ids.filter { it !in answered }.map { OpResult(it, false, fatal ?: "Bez odpovědi ze shellu") }
    }

    private fun rescanPackages(packages: Set<String>) {
        if (packages.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val r = ShizukuBridge.exec(Cargo.scanScript(root, packages))
                val fresh = Cargo.parseScan(r.output).associateBy { it.packageName }
                _state.update { st ->
                    val apps = st.apps.map { a ->
                        if (a.packageName !in packages) a
                        else fresh[a.packageName]?.let { f -> a.copy(dataBytes = f.dataBytes, obbBytes = f.obbBytes) } ?: a
                    }.sortedByDescending { it.totalBytes }
                    st.copy(apps = apps, app = st.app?.let { cur -> apps.firstOrNull { it.packageName == cur.packageName } ?: cur })
                }
                if (_state.value.view == CargoView.BROWSE) loadListing()
            }
        }
    }

    private fun refreshTrash(purge: Boolean) {
        viewModelScope.launch {
            try {
                var items = trash.parseList(ShizukuBridge.exec(trash.listScript()).output)
                var purgedMsg: String? = null
                if (purge) {
                    val expired = items.filter { it.expiresAt < System.currentTimeMillis() }
                    if (expired.isNotEmpty()) {
                        val ok = CargoTrash.parseResults(ShizukuBridge.exec(trash.deleteScript(expired.map { it.id })).output)
                            .filter { it.ok }.map { it.id }.toSet()
                        items = items.filter { it.id !in ok }
                        History.record(getApplication(), HistoryKind.CARGO_PURGE, ok.size, expired.filter { it.id in ok }.sumOf { it.bytes }, ok = true, auto = true)
                        purgedMsg = "Z koše nákladu po 7 dnech definitivně zmizelo ${ok.size} položek " +
                            "(${formatBytes(expired.filter { it.id in ok }.sumOf { it.bytes })})."
                    }
                }
                _state.update { it.copy(trash = items, message = it.message ?: purgedMsg) }
            } catch (e: Exception) {
                _state.update { it.copy(message = it.message ?: "Koš nákladu nejde načíst: ${describe(e)}") }
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is ShizukuUnavailableException -> "Shizuku není připravené (${e.state})"
        else -> e.message ?: e.javaClass.simpleName
    }
}
