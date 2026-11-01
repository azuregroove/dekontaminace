package cz.radek.dekontaminace

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.radek.dekontaminace.data.formatBytes
import cz.radek.dekontaminace.files.ApkEntry
import cz.radek.dekontaminace.files.DuplicateGroup
import cz.radek.dekontaminace.files.Duplicates
import cz.radek.dekontaminace.files.FileEntry
import cz.radek.dekontaminace.files.FileScanner
import cz.radek.dekontaminace.files.OldKind
import cz.radek.dekontaminace.files.Trash
import cz.radek.dekontaminace.files.TrashItem
import cz.radek.dekontaminace.files.inspectApk
import cz.radek.dekontaminace.files.oldKind
import cz.radek.dekontaminace.history.History
import cz.radek.dekontaminace.history.HistoryKind
import cz.radek.dekontaminace.shizuku.ShizukuBridge
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FileCategory(val label: String) {
    LARGE("Velké"),
    DUPLICATES("Duplicity"),
    APK("APK"),
    OLD("Staré"),
    EMPTY("Prázdné složky"),
    TRASH("Koš"),
}

data class FilesState(
    val allFilesAccess: Boolean = false,
    val scanning: Boolean = false,
    val scanCount: Int = 0,
    val scannedAt: Long? = null,
    val files: List<FileEntry> = emptyList(),
    val emptyDirs: List<String> = emptyList(),
    val unreadableDirs: Int = 0,
    val systemTrashCount: Int = 0,
    val systemTrashBytes: Long = 0,
    val category: FileCategory = FileCategory.LARGE,
    val largeThresholdMb: Int = 100,
    val oldDays: Int = 30,
    val oldKinds: Set<OldKind> = OldKind.entries.toSet(),
    val duplicates: List<DuplicateGroup>? = null,
    val duplicateProgress: Pair<Int, Int>? = null,
    val apks: List<ApkEntry>? = null,
    val selected: Set<String> = emptySet(),
    val trash: List<TrashItem> = emptyList(),
    val busy: String? = null,
    val message: String? = null,
) {
    val largeFiles: List<FileEntry>
        get() = files.filter { it.size >= largeThresholdMb * 1_000_000L }.sortedByDescending { it.size }

    fun oldFiles(root: String, now: Long = System.currentTimeMillis()): List<FileEntry> {
        val limit = now - TimeUnit.DAYS.toMillis(oldDays.toLong())
        return files.filter { it.lastModified < limit && it.oldKind(root) in oldKinds }
            .sortedBy { it.lastModified }
    }

    val trashBytes: Long get() = trash.sumOf { it.size }
}

class FilesViewModel(app: Application) : AndroidViewModel(app) {

    @Suppress("DEPRECATION") // Pro „Přístup ke všem souborům“ je přímá cesta k úložišti správná cesta.
    val root: File = Environment.getExternalStorageDirectory()
    private val trash = Trash(File(root, ".Dekontaminace/Kos"))
    private val scanner = FileScanner(root, trash.root)
    private val ownPackage = app.packageName

    private val _state = MutableStateFlow(FilesState())
    val state: StateFlow<FilesState> = _state.asStateFlow()

    init {
        onResume()
    }

    fun onResume() {
        val access = Environment.isExternalStorageManager()
        _state.update { it.copy(allFilesAccess = access) }
        if (access) refreshTrash(purge = true)
    }

    fun grantViaShizuku() {
        viewModelScope.launch {
            val msg = try {
                // Přesně tenhle příkaz uvádí dokumentace Androidu pro udělení přes ADB.
                val r = ShizukuBridge.exec("appops set --uid $ownPackage MANAGE_EXTERNAL_STORAGE allow")
                if (r.ok) null else "Udělení selhalo (kód ${r.exitCode}): ${r.output.trim()}"
            } catch (e: Exception) {
                "Udělení selhalo: ${e.message}"
            }
            _state.update { it.copy(message = msg) }
            onResume()
        }
    }

    fun selectCategory(category: FileCategory) {
        _state.update { it.copy(category = category, selected = emptySet()) }
        if (category == FileCategory.APK && _state.value.apks == null && _state.value.scannedAt != null) loadApks()
        if (category == FileCategory.TRASH) refreshTrash(purge = false)
    }

    fun setLargeThreshold(mb: Int) = _state.update { it.copy(largeThresholdMb = mb, selected = emptySet()) }

    fun setOldDays(days: Int) = _state.update { it.copy(oldDays = days, selected = emptySet()) }

    fun toggleOldKind(kind: OldKind) = _state.update {
        val kinds = if (kind in it.oldKinds) it.oldKinds - kind else it.oldKinds + kind
        it.copy(oldKinds = kinds, selected = emptySet())
    }

    fun scan() {
        if (_state.value.scanning || !_state.value.allFilesAccess) return
        viewModelScope.launch {
            _state.update { it.copy(scanning = true, scanCount = 0, selected = emptySet()) }
            try {
                val result = withContext(Dispatchers.IO) {
                    scanner.scan { n -> _state.update { it.copy(scanCount = n) } }
                }
                _state.update {
                    it.copy(
                        files = result.files,
                        emptyDirs = result.emptyDirs,
                        unreadableDirs = result.unreadableDirs,
                        systemTrashCount = result.systemTrashCount,
                        systemTrashBytes = result.systemTrashBytes,
                        scannedAt = System.currentTimeMillis(),
                        duplicates = null,
                        apks = null,
                    )
                }
                if (_state.value.category == FileCategory.APK) loadApks()
            } catch (e: Exception) {
                _state.update { it.copy(message = "Sken selhal: ${e.message}") }
            } finally {
                _state.update { it.copy(scanning = false) }
            }
        }
    }

    fun findDuplicates() {
        val st = _state.value
        if (st.duplicateProgress != null || st.scannedAt == null) return
        viewModelScope.launch {
            _state.update { it.copy(duplicateProgress = 0 to 1, selected = emptySet()) }
            val groups = withContext(Dispatchers.IO) {
                Duplicates.find(st.files) { done, total -> _state.update { it.copy(duplicateProgress = done to total) } }
            }
            // Předvybereme kopie, originál zůstává nevybraný; mazat se stejně bude až po potvrzení.
            val preselected = groups.flatMap { g -> g.files.map { it.path }.filter { it != g.keep } }.toSet()
            _state.update { it.copy(duplicates = groups, duplicateProgress = null, selected = preselected) }
        }
    }

    private fun loadApks() {
        val files = _state.value.files.filter { it.name.endsWith(".apk", ignoreCase = true) }
        viewModelScope.launch {
            val pm = getApplication<Application>().packageManager
            val apks = withContext(Dispatchers.IO) {
                files.map { inspectApk(pm, it) }.sortedByDescending { it.file.size }
            }
            _state.update { it.copy(apks = apks) }
        }
    }

    fun toggle(key: String) = _state.update { st ->
        if (st.category == FileCategory.DUPLICATES) {
            val group = st.duplicates?.firstOrNull { g -> g.files.any { it.path == key } }
            val groupPaths = group?.files?.map { it.path }.orEmpty()
            val next = if (key in st.selected) st.selected - key else st.selected + key
            // V každé skupině musí aspoň jeden soubor zůstat, jinak by to nebyla dekontaminace, ale exekuce.
            if (groupPaths.isNotEmpty() && groupPaths.all { it in next }) return@update st
            st.copy(selected = next)
        } else {
            st.copy(selected = if (key in st.selected) st.selected - key else st.selected + key)
        }
    }

    fun selectAll(keys: Collection<String>) = _state.update { st ->
        if (st.category == FileCategory.DUPLICATES) {
            val all = st.duplicates.orEmpty().flatMap { g -> g.files.map { it.path }.filter { it != g.keep } }
            st.copy(selected = all.toSet())
        } else {
            st.copy(selected = keys.toSet())
        }
    }

    fun selectNone() = _state.update { it.copy(selected = emptySet()) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun moveSelectedToTrash() {
        val paths = _state.value.selected.toList()
        if (paths.isEmpty() || _state.value.busy != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = "Přesouvám do koše…") }
            val (moved, failed) = withContext(Dispatchers.IO) {
                val ok = ArrayList<TrashItem>()
                val bad = ArrayList<String>()
                paths.forEach { p -> trash.moveIn(p).onSuccess { ok += it }.onFailure { bad += "${p.substringAfterLast('/')}: ${it.message}" } }
                ok to bad
            }
            val movedPaths = moved.map { it.originalPath }.toSet()
            val bytes = moved.sumOf { it.size }
            History.record(getApplication(), HistoryKind.FILES_TRASH, moved.size, bytes, ok = failed.isEmpty())
            _state.update { st ->
                st.copy(
                    busy = null,
                    selected = emptySet(),
                    files = st.files.filter { it.path !in movedPaths },
                    duplicates = st.duplicates?.mapNotNull { g ->
                        val rest = g.files.filter { it.path !in movedPaths }
                        if (rest.size > 1) g.copy(files = rest) else null
                    },
                    apks = st.apks?.filter { it.file.path !in movedPaths },
                    message = buildString {
                        append("Do koše přesunuto ${moved.size} souborů (${formatBytes(bytes)}). Obnovit je jde 7 dní v záložce Koš.")
                        if (failed.isNotEmpty()) append("\nNepovedlo se ${failed.size}: " + failed.take(5).joinToString("; "))
                    },
                )
            }
            refreshTrash(purge = false)
        }
    }

    fun deleteSelectedEmptyDirs() {
        val selected = _state.value.selected
        val ordered = _state.value.emptyDirs.filter { it in selected } // pořadí od nejhlubší
        if (ordered.isEmpty() || _state.value.busy != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = "Mažu prázdné složky…") }
            val (deleted, kept) = withContext(Dispatchers.IO) {
                // File.delete() smaže jen opravdu prázdnou složku, takže když do ní mezitím
                // něco přibylo, zůstane i se svým obsahem.
                ordered.partition { File(it).delete() }
            }
            History.record(getApplication(), HistoryKind.EMPTY_DIRS, deleted.size, 0L, ok = kept.isEmpty())
            _state.update { st ->
                st.copy(
                    busy = null,
                    selected = emptySet(),
                    emptyDirs = st.emptyDirs - deleted.toSet(),
                    message = "Smazáno ${deleted.size} prázdných složek." +
                        if (kept.isNotEmpty()) " ${kept.size} nešlo smazat, nejspíš už nejsou prázdné." else "",
                )
            }
        }
    }

    fun restoreSelected() {
        val ids = _state.value.selected
        val items = _state.value.trash.filter { it.id in ids }
        if (items.isEmpty() || _state.value.busy != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = "Obnovuji…") }
            val results = withContext(Dispatchers.IO) { items.map { it to trash.restore(it) } }
            val ok = results.count { it.second.isSuccess }
            val renamed = results.mapNotNull { (item, r) -> r.getOrNull()?.takeIf { it != item.originalPath } }
            val failed = results.filter { it.second.isFailure }
            History.record(getApplication(), HistoryKind.FILES_RESTORE, ok, items.filter { i -> results.any { it.first == i && it.second.isSuccess } }.sumOf { it.size }, ok = failed.isEmpty())
            _state.update {
                it.copy(
                    busy = null,
                    selected = emptySet(),
                    message = buildString {
                        append("Obnoveno $ok souborů na původní místo. V seznamech se objeví po novém skenu.")
                        if (renamed.isNotEmpty()) append(" ${renamed.size} dostaly příponu „(obnoveno)“, protože na místě už byl jiný soubor.")
                        if (failed.isNotEmpty()) append(" Nepovedlo se ${failed.size}.")
                    },
                )
            }
            refreshTrash(purge = false)
        }
    }

    fun deleteSelectedForever() {
        val ids = _state.value.selected
        val items = _state.value.trash.filter { it.id in ids }
        if (items.isEmpty() || _state.value.busy != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = "Mažu natrvalo…") }
            val deleted = withContext(Dispatchers.IO) { items.filter { trash.deleteForever(it) } }
            History.record(getApplication(), HistoryKind.FILES_DELETE, deleted.size, deleted.sumOf { it.size }, ok = deleted.size == items.size)
            _state.update {
                it.copy(
                    busy = null,
                    selected = emptySet(),
                    message = "Natrvalo smazáno ${deleted.size} souborů (${formatBytes(deleted.sumOf { d -> d.size })})." +
                        if (deleted.size < items.size) " ${items.size - deleted.size} se smazat nepodařilo." else "",
                )
            }
            refreshTrash(purge = false)
        }
    }

    private fun refreshTrash(purge: Boolean) {
        viewModelScope.launch {
            val (purged, list) = withContext(Dispatchers.IO) {
                val p = if (purge) trash.purgeExpired() else 0 to 0L
                p to trash.list()
            }
            History.record(getApplication(), HistoryKind.FILES_PURGE, purged.first, purged.second, ok = true, auto = true)
            _state.update {
                it.copy(
                    trash = list,
                    message = if (purged.first > 0 && it.message == null) {
                        "Z koše po 7 dnech definitivně zmizelo ${purged.first} souborů (${formatBytes(purged.second)})."
                    } else {
                        it.message
                    },
                )
            }
        }
    }
}
