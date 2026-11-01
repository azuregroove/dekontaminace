package cz.radek.dekontaminace.ui.files

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.radek.dekontaminace.FileCategory
import cz.radek.dekontaminace.FilesState
import cz.radek.dekontaminace.FilesViewModel
import cz.radek.dekontaminace.data.formatBytes
import cz.radek.dekontaminace.files.ApkStatus
import cz.radek.dekontaminace.files.FileEntry
import cz.radek.dekontaminace.files.OldKind
import cz.radek.dekontaminace.files.Trash
import cz.radek.dekontaminace.ui.console.Meter
import cz.radek.dekontaminace.ui.console.MeterPart
import cz.radek.dekontaminace.ui.console.Pill
import cz.radek.dekontaminace.ui.console.SectionHeader
import cz.radek.dekontaminace.ui.theme.Console
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val MAX_ROWS = 1500

private enum class Confirm { TRASH, EMPTY_DIRS, DELETE_FOREVER }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilesScreen(state: FilesState, vm: FilesViewModel, shizukuReady: Boolean) {
    val context = LocalContext.current
    val root = vm.root.path
    val dateFormat = remember { SimpleDateFormat("d. M. yyyy", Locale.forLanguageTag("cs-CZ")) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val busy = state.busy != null || state.scanning
    val sizeByPath = remember(state.files) { state.files.associate { it.path to it.size } }

    val oldFiles = remember(state.files, state.oldDays, state.oldKinds) { state.oldFiles(root) }
    val largeFiles = remember(state.files, state.largeThresholdMb) { state.largeFiles }

    val keys: List<String> = when (state.category) {
        FileCategory.LARGE -> largeFiles.map { it.path }
        FileCategory.OLD -> oldFiles.map { it.path }
        FileCategory.APK -> state.apks.orEmpty().map { it.file.path }
        FileCategory.DUPLICATES -> state.duplicates.orEmpty().flatMap { g -> g.files.map { it.path } }
        FileCategory.EMPTY -> state.emptyDirs
        FileCategory.TRASH -> state.trash.map { it.id }
    }
    val selectedBytes = when (state.category) {
        FileCategory.TRASH -> state.trash.filter { it.id in state.selected }.sumOf { it.size }
        FileCategory.EMPTY -> 0L
        else -> state.selected.sumOf { sizeByPath[it] ?: 0L }
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Sklad", Console.Azure)
                if (!state.allFilesAccess) {
                    Text(
                        "Pro skenování potřebuju „Přístup ke všem souborům“. Složky Android/data a obb tím " +
                            "stejně neuvidím, ty přijdou na řadu přes Shizuku.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Console.Peach,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (shizukuReady) Pill("Udělit přes Shizuku", Console.Gold, onClick = vm::grantViaShizuku)
                        Pill("Otevřít nastavení", Console.Amber, onClick = {
                            val pkg = context.packageName
                            val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$pkg"))
                            try {
                                context.startActivity(specific)
                            } catch (e: ActivityNotFoundException) {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                } catch (e2: ActivityNotFoundException) {
                                    Toast.makeText(context, "Nastavení se nepodařilo otevřít.", Toast.LENGTH_LONG).show()
                                }
                            }
                        })
                    }
                } else {
                    Text(
                        when {
                            state.scanning -> "Skenuji palubu… ${state.scanCount} souborů"
                            state.scannedAt == null -> "Zatím neskenováno."
                            else -> "Naskenováno ${state.files.size} souborů, ${formatBytes(state.files.sumOf { it.size })}." +
                                (if (state.unreadableDirs > 0) " Nečitelných složek: ${state.unreadableDirs}." else "") +
                                (if (state.systemTrashCount > 0) {
                                    " V systémovém koši Androidu je ${state.systemTrashCount} souborů " +
                                        "(${formatBytes(state.systemTrashBytes)}), ty Android smaže sám do 30 dní."
                                } else "")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Console.Peach,
                    )
                    Pill(if (state.scanning) "Skenuji…" else "Skenovat", Console.Sky, onClick = vm::scan, enabled = !busy)
                }
            }
        }

        state.message?.takeIf { !state.allFilesAccess || (state.scannedAt == null && state.category != FileCategory.TRASH) }?.let { msg ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader("Hlášení", Console.Rose)
                    Text(msg, style = MaterialTheme.typography.bodyLarge, color = Console.Peach)
                    Pill("Beru na vědomí", Console.Rose, onClick = vm::dismissMessage)
                }
            }
        }

        if (state.allFilesAccess) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FileCategory.entries.forEach { c ->
                        val label = if (c == FileCategory.TRASH && state.trash.isNotEmpty()) "${c.label} (${state.trash.size})" else c.label
                        Pill(label, if (c == state.category) Console.Gold else Console.Periwinkle, onClick = { vm.selectCategory(c) }, enabled = !busy)
                    }
                }
            }
            item { CategoryControls(state, vm, busy) }

            if (state.scannedAt != null || state.category == FileCategory.TRASH) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (state.category == FileCategory.EMPTY) "Vybráno ${state.selected.size} složek"
                            else "Vybráno ${state.selected.size} · ${formatBytes(selectedBytes)}",
                            style = MaterialTheme.typography.titleMedium,
                            color = Console.Gold,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Vybrat vše", Console.Periwinkle, onClick = { vm.selectAll(keys) }, enabled = !busy && keys.isNotEmpty())
                            Pill("Nic", Console.Periwinkle, onClick = vm::selectNone, enabled = !busy)
                            val any = state.selected.isNotEmpty() && !busy
                            when (state.category) {
                                FileCategory.TRASH -> {
                                    Pill("Obnovit", Console.Sky, onClick = vm::restoreSelected, enabled = any)
                                    Pill("Smazat natrvalo", Console.Rose, onClick = { confirm = Confirm.DELETE_FOREVER }, enabled = any)
                                }
                                FileCategory.EMPTY -> Pill("Smazat složky", Console.Amber, onClick = { confirm = Confirm.EMPTY_DIRS }, enabled = any)
                                else -> Pill("Do koše", Console.Amber, onClick = { confirm = Confirm.TRASH }, enabled = any)
                            }
                        }
                        state.busy?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Console.Gold) }
                        state.message?.let { msg ->
                            SectionHeader("Hlášení", Console.Rose)
                            Text(msg, style = MaterialTheme.typography.bodyLarge, color = Console.Peach)
                            Pill("Beru na vědomí", Console.Rose, onClick = vm::dismissMessage)
                        }
                    }
                }
                categoryList(state, root, dateFormat, keys.size, vm, busy, largeFiles, oldFiles)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    confirm?.let { kind ->
        val count = state.selected.size
        val (title, text) = when (kind) {
            Confirm.TRASH -> "PŘESUNOUT DO KOŠE" to
                "$count souborů, celkem ${formatBytes(selectedBytes)}, přesunu do koše. " +
                "7 dní je jde jedním ťuknutím obnovit, pak zmizí natrvalo."
            Confirm.EMPTY_DIRS -> "SMAZAT PRÁZDNÉ SLOŽKY" to
                "Smažu $count prázdných složek. Neobsahují žádné soubory, proto nejdou přes koš. " +
                "Složka, do které mezitím něco přibylo, zůstane."
            Confirm.DELETE_FOREVER -> "SMAZAT NATRVALO" to
                "$count souborů, ${formatBytes(selectedBytes)}, smažu z koše natrvalo. Tohle už nejde vrátit."
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Console.Black,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.border(2.dp, if (kind == Confirm.DELETE_FOREVER) Console.Red else Console.Amber, RoundedCornerShape(24.dp)),
            title = { Text(title, style = MaterialTheme.typography.titleLarge, color = if (kind == Confirm.DELETE_FOREVER) Console.Red else Console.Amber) },
            text = { Text(text, style = MaterialTheme.typography.bodyLarge, color = Console.Peach) },
            confirmButton = {
                Pill("Zahájit", if (kind == Confirm.DELETE_FOREVER) Console.Red else Console.Amber, onClick = {
                    confirm = null
                    when (kind) {
                        Confirm.TRASH -> vm.moveSelectedToTrash()
                        Confirm.EMPTY_DIRS -> vm.deleteSelectedEmptyDirs()
                        Confirm.DELETE_FOREVER -> vm.deleteSelectedForever()
                    }
                })
            },
            dismissButton = { Pill("Zrušit", Console.Periwinkle, onClick = { confirm = null }) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryControls(state: FilesState, vm: FilesViewModel, busy: Boolean) = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (state.category) {
            FileCategory.LARGE -> listOf(50, 100, 250, 500).forEach { mb ->
                Pill("$mb+ MB", if (state.largeThresholdMb == mb) Console.Gold else Console.Sky, onClick = { vm.setLargeThreshold(mb) }, enabled = !busy)
            }
            FileCategory.OLD -> {
                listOf(30, 90, 180).forEach { d ->
                    Pill("> $d dní", if (state.oldDays == d) Console.Gold else Console.Sky, onClick = { vm.setOldDays(d) }, enabled = !busy)
                }
                OldKind.entries.forEach { k ->
                    Pill(k.label, if (k in state.oldKinds) Console.Gold else Console.Gray, onClick = { vm.toggleOldKind(k) }, enabled = !busy)
                }
            }
            FileCategory.DUPLICATES -> {
                val p = state.duplicateProgress
                Pill(
                    if (p != null) "Porovnávám ${p.first} / ${p.second}" else "Hledat duplicity",
                    Console.Sky,
                    onClick = vm::findDuplicates,
                    enabled = !busy && p == null && state.scannedAt != null,
                )
            }
            FileCategory.TRASH -> Text(
                "V koši ${state.trash.size} souborů, ${formatBytes(state.trashBytes)}. Po 7 dnech se mažou samy.",
                style = MaterialTheme.typography.bodyMedium,
                color = Console.Peach,
            )
            else -> Unit
        }
    }
    if (state.scannedAt == null && state.category != FileCategory.TRASH && state.allFilesAccess) {
        Text("Nejdřív spusť sken.", style = MaterialTheme.typography.bodyMedium, color = Console.Dim)
    }
}

private fun LazyListScope.categoryList(
    state: FilesState,
    root: String,
    dateFormat: SimpleDateFormat,
    total: Int,
    vm: FilesViewModel,
    busy: Boolean,
    largeFiles: List<FileEntry>,
    oldFiles: List<FileEntry>,
) {
    fun rel(path: String) = path.removePrefix("$root/")
    fun date(ms: Long) = dateFormat.format(Date(ms))

    when (state.category) {
        FileCategory.LARGE -> fileRows(largeFiles, state, vm, busy) { f -> "${rel(f.path).substringBeforeLast('/', "")} · ${date(f.lastModified)}" }
        FileCategory.OLD -> fileRows(oldFiles, state, vm, busy) { f -> "${rel(f.path).substringBeforeLast('/', "")} · ${date(f.lastModified)}" }
        FileCategory.APK -> {
            val apks = state.apks
            if (apks == null) {
                item { Text("Čtu instalačky…", style = MaterialTheme.typography.bodyMedium, color = Console.Dim) }
            } else {
                items(apks.take(MAX_ROWS), key = { it.file.path }) { a ->
                    SelectRow(
                        title = a.label?.let { "$it ${a.versionName.orEmpty()}".trim() } ?: a.file.name,
                        subtitle = "${a.status.label} · ${rel(a.file.path)}",
                        size = a.file.size,
                        selected = a.file.path in state.selected,
                        enabled = !busy,
                        accent = if (a.status == ApkStatus.INSTALLED_SAME_OR_NEWER) Console.Gold else Console.Lilac,
                        onClick = { vm.toggle(a.file.path) },
                    )
                }
            }
        }
        FileCategory.DUPLICATES -> {
            val groups = state.duplicates
            when {
                groups == null -> item {
                    Text("Duplicity se hledají na vyžádání, u velkého úložiště to chvíli trvá.", style = MaterialTheme.typography.bodyMedium, color = Console.Dim)
                }
                groups.isEmpty() -> item { Text("Žádné duplicity. Paluba je čistá.", style = MaterialTheme.typography.bodyLarge, color = Console.Sky) }
                else -> {
                    item {
                        Text(
                            "${groups.size} skupin, kopie zabírají ${formatBytes(groups.sumOf { it.wasted })}. Jeden soubor v každé skupině vždy zůstane.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Console.Peach,
                        )
                    }
                    groups.take(300).forEachIndexed { i, g ->
                        item(key = "g$i") {
                            Text(
                                "SKUPINA ${i + 1} · ${g.files.size}× ${formatBytes(g.size)}",
                                style = MaterialTheme.typography.labelLarge,
                                color = Console.Sky,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        items(g.files, key = { "d$i" + it.path }) { f ->
                            val sel = f.path in state.selected
                            SelectRow(
                                title = f.name,
                                subtitle = (if (!sel) "PONECHÁ SE · " else "") + rel(f.path).substringBeforeLast('/', "") + " · " + date(f.lastModified),
                                size = f.size,
                                selected = sel,
                                enabled = !busy,
                                accent = if (sel) Console.Gold else Console.Sky,
                                onClick = { vm.toggle(f.path) },
                            )
                        }
                    }
                }
            }
        }
        FileCategory.EMPTY -> {
            if (state.emptyDirs.isEmpty()) {
                item { Text("Žádné prázdné složky.", style = MaterialTheme.typography.bodyLarge, color = Console.Sky) }
            }
            items(state.emptyDirs.take(MAX_ROWS), key = { it }) { d ->
                SelectRow(
                    title = rel(d).substringAfterLast('/'),
                    subtitle = rel(d),
                    size = null,
                    selected = d in state.selected,
                    enabled = !busy,
                    accent = Console.Lilac,
                    onClick = { vm.toggle(d) },
                )
            }
        }
        FileCategory.TRASH -> {
            if (state.trash.isEmpty()) {
                item { Text("Koš je prázdný.", style = MaterialTheme.typography.bodyLarge, color = Console.Sky) }
            }
            val now = System.currentTimeMillis()
            items(state.trash.take(MAX_ROWS), key = { it.id }) { t ->
                val daysLeft = TimeUnit.MILLISECONDS.toDays(t.expiresAt - now).coerceAtLeast(0) + 1
                SelectRow(
                    title = t.name,
                    subtitle = "${rel(t.originalPath).substringBeforeLast('/', "")} · zbývá ${daysLeft.coerceAtMost(Trash.RETENTION_MS / 86_400_000L)} dní",
                    size = t.size,
                    selected = t.id in state.selected,
                    enabled = !busy,
                    accent = Console.Rose,
                    onClick = { vm.toggle(t.id) },
                )
            }
        }
    }
    if (total > MAX_ROWS) {
        item { Text("Zobrazeno prvních $MAX_ROWS z $total.", style = MaterialTheme.typography.bodySmall, color = Console.Dim) }
    }
}

private fun LazyListScope.fileRows(
    files: List<FileEntry>,
    state: FilesState,
    vm: FilesViewModel,
    busy: Boolean,
    subtitle: (FileEntry) -> String,
) {
    if (files.isEmpty()) {
        item { Text("Nic takového tu není.", style = MaterialTheme.typography.bodyLarge, color = Console.Sky) }
        return
    }
    val maxSize = files.maxOf { it.size }.coerceAtLeast(1)
    items(files.take(MAX_ROWS), key = { it.path }) { f ->
        Column {
            SelectRow(
                title = f.name,
                subtitle = subtitle(f),
                size = f.size,
                selected = f.path in state.selected,
                enabled = !busy,
                accent = Console.Sky,
                onClick = { vm.toggle(f.path) },
            )
            Meter(
                listOf(MeterPart(f.size.toFloat() / maxSize, Console.Sky.copy(alpha = 0.6f))),
                height = 3.dp,
                background = Color(0xFF1E1E28),
            )
        }
    }
}

@Composable
private fun SelectRow(
    title: String,
    subtitle: String,
    size: Long?,
    selected: Boolean,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val color = if (selected) Console.Gold else accent
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .border(2.dp, color, RoundedCornerShape(6.dp))
                .padding(4.dp)
                .background(if (selected) color else Console.Black, RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Console.Peach, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Console.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (size != null) {
            Spacer(Modifier.width(8.dp))
            Text(formatBytes(size), style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}
