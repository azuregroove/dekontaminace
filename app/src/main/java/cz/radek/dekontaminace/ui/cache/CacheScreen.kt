package cz.radek.dekontaminace.ui.cache

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
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

/**
 * Filtry: cache všech aplikací. Android dovolí shellu smazat cache jen hromadně (pm trim-caches);
 * mazání po jedné aplikaci bylo odebráno, vnější cache na telefonu prakticky nebyla (ověřeno 1. 11.).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CacheScreen(state: UiState, vm: MainViewModel) {
    var confirm by remember { mutableStateOf(false) }
    val candidates = state.cacheCandidates
    val ready = state.shizuku == ShizukuState.READY
    val busy = state.cleaning != null
    val allCache = state.apps.sumOf { it.cacheBytes }

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
                        "Shizuku není připravené, čištění je zablokované. Stav najdeš na Můstku.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Console.Red,
                    )
                }
                if (!state.usageAccess) {
                    Text(
                        "Bez přístupu k údajům o využití nevidím velikosti cache. Povol ho na Můstku.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Console.Gold,
                    )
                }
                Text(
                    "Cache celkem ${formatBytes(allCache)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Console.Gold,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(
                        "Vyčistit vše",
                        Console.Amber,
                        onClick = { confirm = true },
                        enabled = ready && !busy && state.apps.isNotEmpty(),
                    )
                    Pill(
                        if (state.showSystem) "Skrýt systémové" else "Ukázat systémové",
                        Console.Sky,
                        onClick = vm::toggleShowSystem,
                        enabled = !busy,
                    )
                }
            }
        }

        state.cleaning?.let { p ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader("Probíhá dekontaminace", Console.Gold)
                    Text(p.current, style = MaterialTheme.typography.bodyMedium, color = Console.Peach, maxLines = 2)
                }
            }
        }

        state.result?.let { r -> item { ResultPanel(r, onDismiss = vm::dismissResult) } }

        if (state.scanning && candidates.isEmpty()) {
            item { Text("Skenuji palubu…", style = MaterialTheme.typography.bodyLarge, color = Console.Dim) }
        }

        if (candidates.isNotEmpty()) {
            item { SectionHeader("Cache podle aplikací", Console.Lilac) }
            items(candidates, key = { it.packageName }) { app ->
                CacheRow(app, max = candidates.first().cacheBytes)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Console.Black,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.border(2.dp, Console.Amber, RoundedCornerShape(24.dp)),
            title = { Text("VYČISTIT CACHE VŠECH", style = MaterialTheme.typography.titleLarge, color = Console.Amber) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Android smaže cache všech aplikací, teď je jí celkem ${formatBytes(allCache)}. " +
                            "Data, přihlášení ani postup ve hrách se nemažou.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Console.Peach,
                    )
                    Text(
                        "Při té příležitosti může Android uklidit i nepoužívané sdílené knihovny a staré okamžité aplikace.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Console.Gold,
                    )
                }
            },
            confirmButton = {
                Pill("Zahájit", Console.Amber, onClick = {
                    confirm = false
                    vm.cleanAll()
                })
            },
            dismissButton = {
                Pill("Zrušit", Console.Periwinkle, onClick = { confirm = false })
            },
        )
    }
}

@Composable
private fun CacheRow(app: AppEntry, max: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Console.Peach,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Console.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(formatBytes(app.cacheBytes), style = MaterialTheme.typography.labelLarge, color = Console.Lilac)
        }
        Meter(
            listOf(MeterPart(app.cacheBytes.toFloat() / max.toFloat().coerceAtLeast(1f), Console.Lilac)),
            height = 5.dp,
            background = Color(0xFF1E1E28),
        )
    }
}

@Composable
private fun ResultPanel(r: CleanResult, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val color = if (r.aborted == null) Console.Sky else Console.Rose
        SectionHeader("Hlášení", color)
        Text(
            "Uvolněno ${formatBytes(r.freedCacheBytes)} cache u ${r.succeeded} aplikací.",
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
        Pill("Beru na vědomí", color, onClick = onDismiss)
    }
}
