package cz.radek.dekontaminace.ui.cargo

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.radek.dekontaminace.CargoApp
import cz.radek.dekontaminace.CargoState
import cz.radek.dekontaminace.CargoView
import cz.radek.dekontaminace.CargoViewModel
import cz.radek.dekontaminace.cargo.CargoEntry
import cz.radek.dekontaminace.cargo.CargoTrash
import cz.radek.dekontaminace.cargo.EntryKind
import cz.radek.dekontaminace.data.formatBytes
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

private enum class Confirm { TRASH, DELETE_FOREVER }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CargoScreen(state: CargoState, vm: CargoViewModel, shizukuReady: Boolean) {
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val busy = state.busy != null || state.scanning
    val dateFormat = remember { SimpleDateFormat("d. M. yyyy", Locale.forLanguageTag("cs-CZ")) }

    LaunchedEffect(shizukuReady) { if (shizukuReady) vm.start() }

    val selectedBytes = when (state.view) {
        CargoView.TRASH -> state.trash.filter { it.id in state.selected }.sumOf { it.bytes }
        else -> state.entries.orEmpty().filter { it.path in state.selected }.sumOf { it.bytes }
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Náklad", Console.Lime)
                if (!shizukuReady) {
                    Text(
                        "Do Android/data a Android/obb cizích aplikací se dostanu jen přes Shizuku. " +
                            "Spusť ho a vrať se, nákladový prostor zatím zůstává zapečetěný.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Console.Peach,
                    )
                } else {
                    Text(
                        when {
                            state.scanning -> "Měřím nákladový prostor…"
                            state.scannedAt == null -> "Zatím neměřeno."
                            else -> "${state.apps.size} aplikací, celkem ${formatBytes(state.totalBytes)} v Android/data a obb."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Console.Peach,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("Aplikace", if (state.view != CargoView.TRASH) Console.Gold else Console.Periwinkle, onClick = vm::showApps, enabled = !busy)
                        Pill(
                            if (state.trash.isEmpty()) "Koš" else "Koš (${state.trash.size})",
                            if (state.view == CargoView.TRASH) Console.Gold else Console.Periwinkle,
                            onClick = vm::showTrash,
                            enabled = !busy,
                        )
                        Pill(if (state.scanning) "Měřím…" else "Přeměřit", Console.Lime, onClick = vm::scan, enabled = !busy)
                    }
                }
            }
        }

        if (shizukuReady) {
            if (state.view == CargoView.BROWSE) item { Breadcrumb(state, vm, busy) }

            if (state.view != CargoView.APPS) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Vybráno ${state.selected.size} · ${formatBytes(selectedBytes)}", style = MaterialTheme.typography.titleMedium, color = Console.Gold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Vybrat vše", Console.Periwinkle, onClick = vm::selectAll, enabled = !busy)
                            Pill("Nic", Console.Periwinkle, onClick = vm::selectNone, enabled = !busy)
                            val any = state.selected.isNotEmpty() && !busy
                            if (state.view == CargoView.TRASH) {
                                Pill("Obnovit", Console.Sky, onClick = vm::restoreSelected, enabled = any)
                                Pill("Smazat natrvalo", Console.Rose, onClick = { confirm = Confirm.DELETE_FOREVER }, enabled = any)
                            } else {
                                Pill("Do koše", Console.Amber, onClick = { confirm = Confirm.TRASH }, enabled = any)
                            }
                        }
                    }
                }
            }
            state.busy?.let { b -> item { Text(b, style = MaterialTheme.typography.bodyMedium, color = Console.Gold) } }
            state.message?.let { msg ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionHeader("Hlášení", Console.Rose)
                        Text(msg, style = MaterialTheme.typography.bodyLarge, color = Console.Peach)
                        Pill("Beru na vědomí", Console.Rose, onClick = vm::dismissMessage)
                    }
                }
            }

            when (state.view) {
                CargoView.APPS -> appRows(state, vm, busy)
                CargoView.BROWSE -> entryRows(state, vm, busy, dateFormat)
                CargoView.TRASH -> trashRows(state, vm, busy)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    confirm?.let { kind ->
        val count = state.selected.size
        val forever = kind == Confirm.DELETE_FOREVER
        val (title, text) = if (forever) {
            "SMAZAT NATRVALO" to "$count položek, ${formatBytes(selectedBytes)}, smažu z koše natrvalo. Tohle už nejde vrátit."
        } else {
            "PŘESUNOUT DO KOŠE" to
                "$count položek, ${formatBytes(selectedBytes)}, přesunu do koše nákladu. 7 dní je jde vrátit na původní místo. " +
                "Pozor u her: pokud postup nemají v cloudu, může být uložený právě tady. Místo se uvolní až po vysypání koše."
        }
        val color = if (forever) Console.Red else Console.Amber
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Console.Black,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.border(2.dp, color, RoundedCornerShape(24.dp)),
            title = { Text(title, style = MaterialTheme.typography.titleLarge, color = color) },
            text = { Text(text, style = MaterialTheme.typography.bodyLarge, color = Console.Peach) },
            confirmButton = {
                Pill("Zahájit", color, onClick = {
                    confirm = null
                    if (forever) vm.deleteSelectedForever() else vm.moveSelectedToTrash()
                })
            },
            dismissButton = { Pill("Zrušit", Console.Periwinkle, onClick = { confirm = null }) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Breadcrumb(state: CargoState, vm: CargoViewModel, busy: Boolean) {
    val app = state.app ?: return
    val crumbs = listOf(app.label ?: app.packageName) + state.path.map { it.substringAfterLast('/') }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            crumbs.joinToString(" › ").uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = Console.Lime,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "data ${app.dataBytes?.let(::formatBytes) ?: "–"} · obb ${app.obbBytes?.let(::formatBytes) ?: "–"}",
            style = MaterialTheme.typography.bodySmall,
            color = Console.Dim,
        )
        Pill(if (state.path.isEmpty()) "Zpět na seznam" else "O úroveň výš", Console.Lime, onClick = vm::up, enabled = !busy)
    }
}

private fun LazyListScope.appRows(state: CargoState, vm: CargoViewModel, busy: Boolean) {
    if (state.scannedAt != null && state.apps.isEmpty()) {
        item { Text("Nákladový prostor je prázdný.", style = MaterialTheme.typography.bodyLarge, color = Console.Sky) }
        return
    }
    val max = state.apps.maxOfOrNull { it.totalBytes }?.coerceAtLeast(1) ?: 1
    items(state.apps, key = { it.packageName }) { a ->
        Column {
            AppRow(a, enabled = !busy, onClick = { vm.openApp(a) })
            Meter(
                listOf(
                    MeterPart((a.dataBytes ?: 0L).toFloat() / max, Console.Lime.copy(alpha = 0.7f)),
                    MeterPart((a.obbBytes ?: 0L).toFloat() / max, Console.Sky.copy(alpha = 0.7f)),
                ),
                height = 3.dp,
                background = Color(0xFF1E1E28),
            )
        }
    }
}

@Composable
private fun AppRow(a: CargoApp, enabled: Boolean, onClick: () -> Unit) {
    val accent = if (a.installed) Console.Lime else Console.Rose
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(a.label ?: a.packageName, style = MaterialTheme.typography.bodyLarge, color = Console.Peach, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    if (!a.installed) append("ZBYTEK PO ODINSTALOVANÉ APLIKACI · ") else append("${a.packageName} · ")
                    append("data ${a.dataBytes?.let(::formatBytes) ?: "–"}")
                    a.obbBytes?.let { append(" · obb ${formatBytes(it)}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (a.installed) Console.Dim else Console.Rose,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(formatBytes(a.totalBytes), style = MaterialTheme.typography.labelLarge, color = accent)
        Text(" ›", style = MaterialTheme.typography.labelLarge, color = accent)
    }
}

private fun LazyListScope.entryRows(state: CargoState, vm: CargoViewModel, busy: Boolean, dateFormat: SimpleDateFormat) {
    val entries = state.entries
    when {
        entries == null -> item { Text("Otevírám kontejner…", style = MaterialTheme.typography.bodyMedium, color = Console.Dim) }
        entries.isEmpty() -> item { Text("Tady nic není.", style = MaterialTheme.typography.bodyLarge, color = Console.Sky) }
        else -> {
            val max = entries.maxOf { it.bytes }.coerceAtLeast(1)
            items(entries.take(MAX_ROWS), key = { it.path }) { e ->
                Column {
                    EntryRow(
                        entry = e,
                        tag = if (state.path.isEmpty()) (if ("/Android/obb/" in e.path) "OBB" else "DATA") else null,
                        date = if (e.modified > 0) dateFormat.format(Date(e.modified)) else "",
                        selected = e.path in state.selected,
                        enabled = !busy,
                        onToggle = { vm.toggle(e.path) },
                        onOpen = if (e.kind == EntryKind.DIR) ({ vm.openDir(e) }) else null,
                    )
                    Meter(listOf(MeterPart(e.bytes.toFloat() / max, Console.Lime.copy(alpha = 0.6f))), height = 3.dp, background = Color(0xFF1E1E28))
                }
            }
            if (entries.size > MAX_ROWS) {
                item { Text("Zobrazeno prvních $MAX_ROWS z ${entries.size}.", style = MaterialTheme.typography.bodySmall, color = Console.Dim) }
            }
        }
    }
}

/** Čtvereček vybírá, zbytek řádku u složky otevírá. */
@Composable
private fun EntryRow(
    entry: CargoEntry,
    tag: String?,
    date: String,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    val color = if (selected) Console.Gold else Console.Lime
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .clickable(enabled = enabled, onClick = onToggle)
                .padding(end = 10.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .border(2.dp, color, RoundedCornerShape(6.dp))
                    .padding(4.dp)
                    .background(if (selected) color else Console.Black, RoundedCornerShape(3.dp)),
            )
        }
        Row(
            Modifier.weight(1f).clickable(enabled = enabled, onClick = onOpen ?: onToggle).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge, color = Console.Peach, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        tag,
                        when (entry.kind) {
                            EntryKind.DIR -> "složka"
                            EntryKind.LINK -> "odkaz"
                            EntryKind.FILE -> null
                        },
                        date.ifEmpty { null },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Console.Dim,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(formatBytes(entry.bytes), style = MaterialTheme.typography.labelLarge, color = color)
            if (onOpen != null) Text(" ›", style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

private fun LazyListScope.trashRows(state: CargoState, vm: CargoViewModel, busy: Boolean) {
    item {
        Text(
            "V koši nákladu ${state.trash.size} položek, ${formatBytes(state.trashBytes)}. Po 7 dnech se mažou samy.",
            style = MaterialTheme.typography.bodyMedium,
            color = Console.Peach,
        )
    }
    if (state.trash.isEmpty()) {
        item { Text("Koš je prázdný.", style = MaterialTheme.typography.bodyLarge, color = Console.Sky) }
        return
    }
    val now = System.currentTimeMillis()
    items(state.trash, key = { it.id }) { t ->
        val daysLeft = (TimeUnit.MILLISECONDS.toDays(t.expiresAt - now).coerceAtLeast(0) + 1)
            .coerceAtMost(CargoTrash.RETENTION_MS / 86_400_000L)
        val where = t.originalPath.substringAfter("/Android/").substringBeforeLast('/')
        val sel = t.id in state.selected
        val color = if (sel) Console.Gold else Console.Rose
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = { vm.toggle(t.id) }).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .border(2.dp, color, RoundedCornerShape(6.dp))
                    .padding(4.dp)
                    .background(if (sel) color else Console.Black, RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.bodyLarge, color = Console.Peach, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$where · zbývá $daysLeft dní", style = MaterialTheme.typography.bodySmall, color = Console.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Text(formatBytes(t.bytes), style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}
