package cz.radek.dekontaminace.ui.cache

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.radek.dekontaminace.CleanResult
import cz.radek.dekontaminace.MainViewModel
import cz.radek.dekontaminace.UiState
import cz.radek.dekontaminace.data.AppEntry
import cz.radek.dekontaminace.data.formatBytes
import cz.radek.dekontaminace.shizuku.ShizukuState
import cz.radek.dekontaminace.ui.console.Meter
import cz.radek.dekontaminace.ui.console.MeterPart
import cz.radek.dekontaminace.ui.console.Pill
import cz.radek.dekontaminace.ui.console.SectionHeader
import cz.radek.dekontaminace.ui.theme.Console

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CacheScreen(state: UiState, vm: MainViewModel) {
    var confirm by remember { mutableStateOf<CleanMode?>(null) }
    val candidates = state.cacheCandidates
    val ready = state.shizuku == ShizukuState.READY
    val busy = state.cleaning != null

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Filtry", Console.Teal)
                Text(
                    "Cache je dočasná, aplikace si ji vytvoří znovu. Přihlášení ani postup ve hrách se tím nesmaže.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Console.Peach,
                )
                if (!ready) {
                    Text(
                        "Shizuku není připravené, čištění je zablokované. Stav najdeš v Přehledu.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Console.Red,
                    )
                }
                if (!state.usageAccess) {
                    Text(
                        "Bez přístupu k údajům o využití nevidím velikosti cache. Povol ho v Přehledu.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Console.Gold,
                    )
                }
                Text(
                    "Vybráno ${state.selected.size} aplikací · ${formatBytes(state.selectedBytes)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Console.Gold,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Vybrat vše", Console.Periwinkle, onClick = vm::selectAll, enabled = !busy)
                    Pill("Nic", Console.Periwinkle, onClick = vm::selectNone, enabled = !busy)
                    Pill(
                        if (state.showSystem) "Skrýt systémové" else "Ukázat systémové",
                        Console.Sky,
                        onClick = vm::toggleShowSystem,
                        enabled = !busy,
                    )
                    Pill(
                        "Vyčistit vše",
                        Console.Amber,
                        onClick = { confirm = CleanMode.ALL },
                        enabled = ready && !busy && state.apps.isNotEmpty(),
                    )
                    Pill(
                        "Vnější cache vybraných",
                        Console.Gold,
                        onClick = { confirm = CleanMode.EXTERNAL },
                        enabled = ready && !busy && state.selected.isNotEmpty(),
                    )
                }
            }
        }

        state.cleaning?.let { p ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader("Probíhá dekontaminace", Console.Gold)
                    if (p.total > 0) {
                        Meter(listOf(MeterPart(p.done.toFloat() / p.total, Console.Gold)))
                    }
                    Text(
                        if (p.total > 0) "${p.done} / ${p.total} · ${p.current}" else p.current,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Console.Peach,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        state.result?.let { r -> item { ResultPanel(r, onDismiss = vm::dismissResult) } }

        if (state.scanning && candidates.isEmpty()) {
            item { Text("Skenuji palubu…", style = MaterialTheme.typography.bodyLarge, color = Console.Dim) }
        }

        items(candidates, key = { it.packageName }) { app ->
            CacheRow(
                app = app,
                selected = app.packageName in state.selected,
                isException = app.packageName in state.exceptions,
                enabled = !busy,
                onToggle = { vm.toggleSelected(app.packageName) },
                onToggleException = { vm.toggleException(app.packageName) },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    confirm?.let { mode ->
        val chosen = candidates.filter { it.packageName in state.selected }
        val allCache = state.apps.sumOf { it.cacheBytes }
        val exceptionApps = state.apps.filter { it.packageName in state.exceptions && it.cacheBytes > 0 }
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Console.Black,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.border(2.dp, Console.Amber, RoundedCornerShape(24.dp)),
            title = {
                Text(
                    if (mode == CleanMode.ALL) "VYČISTIT CACHE VŠECH" else "VNĚJŠÍ CACHE VYBRANÝCH",
                    style = MaterialTheme.typography.titleLarge,
                    color = Console.Amber,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (mode == CleanMode.ALL) {
                        Text(
                            "Android smaže cache všech aplikací, teď je jí celkem ${formatBytes(allCache)}. " +
                                "Data, přihlášení ani postup ve hrách se nemažou.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Console.Peach,
                        )
                        Text(
                            "Výjimky tady neplatí: hromadné čištění Android neumí omezit na vybrané aplikace. " +
                                "Při té příležitosti může uklidit i nepoužívané sdílené knihovny a staré okamžité aplikace.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Console.Gold,
                        )
                        if (exceptionApps.isNotEmpty()) {
                            val names = exceptionApps.take(4).joinToString { it.label } +
                                if (exceptionApps.size > 4) " a další" else ""
                            Text(
                                "Smaže se i cache výjimek: $names",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Console.Rose,
                            )
                        }
                    } else {
                        Text(
                            "U ${chosen.size} aplikací smažu jen vnější část cache (Android/data/…/cache). " +
                                "Vnitřní cache shell smazat nesmí, takže uvolněné místo bude menší než " +
                                "${formatBytes(chosen.sumOf { it.cacheBytes })}. Přesné číslo ukážu po dokončení.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Console.Peach,
                        )
                        chosen.take(6).forEach {
                            Text(
                                "• ${it.label}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Console.Lilac,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (chosen.size > 6) {
                            Text("…a dalších ${chosen.size - 6}", style = MaterialTheme.typography.bodyMedium, color = Console.Dim)
                        }
                    }
                }
            },
            confirmButton = {
                Pill("Zahájit", Console.Amber, onClick = {
                    confirm = null
                    if (mode == CleanMode.ALL) vm.cleanAll() else vm.cleanSelectedExternal()
                })
            },
            dismissButton = {
                Pill("Zrušit", Console.Periwinkle, onClick = { confirm = null })
            },
        )
    }
}

private enum class CleanMode { ALL, EXTERNAL }

@Composable
private fun CacheRow(
    app: AppEntry,
    selected: Boolean,
    isException: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onToggleException: () -> Unit,
) {
    val accent = when {
        isException -> Console.Gray
        selected -> Console.Gold
        else -> Console.Lilac
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && !isException, onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .border(2.dp, accent, RoundedCornerShape(6.dp))
                .padding(4.dp)
                .background(if (selected) accent else Console.Black, RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isException) Console.Dim else Console.Peach,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (isException) "výjimka, nečistí se" else app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = Console.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(formatBytes(app.cacheBytes), style = MaterialTheme.typography.labelLarge, color = accent)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .background(if (isException) Console.Rose else Console.Gray, RoundedCornerShape(50))
                .clickable(enabled = enabled, onClick = onToggleException)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                if (isException) "VÝJIMKA" else "CHRÁNIT",
                style = MaterialTheme.typography.labelSmall,
                color = Console.Black,
            )
        }
    }
}

@Composable
private fun ResultPanel(r: CleanResult, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val color = if (r.failures.isEmpty() && r.aborted == null) Console.Sky else Console.Rose
        SectionHeader("Hlášení o dekontaminaci", color)
        Text(
            "Uvolněno ${formatBytes(r.freedCacheBytes)} cache u ${r.succeeded} aplikací. Chyb: ${r.failures.size}.",
            style = MaterialTheme.typography.titleMedium,
            color = Console.Gold,
        )
        if (r.freeSpaceDelta > 0) {
            Text(
                "Volné místo vzrostlo o ${formatBytes(r.freeSpaceDelta)}.",
                style = MaterialTheme.typography.bodyMedium,
                color = Console.Peach,
            )
        }
        r.aborted?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Console.Red) }
        r.failures.take(10).forEach { f ->
            Text(
                "✕ ${f.label}: ${f.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = Console.Rose,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (r.failures.size > 10) {
            Text("…a dalších ${r.failures.size - 10} chyb", style = MaterialTheme.typography.bodySmall, color = Console.Dim)
        }
        Pill("Beru na vědomí", color, onClick = onDismiss)
    }
}
