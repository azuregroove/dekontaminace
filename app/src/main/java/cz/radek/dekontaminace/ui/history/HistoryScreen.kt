package cz.radek.dekontaminace.ui.history

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.radek.dekontaminace.HistoryState
import cz.radek.dekontaminace.HistoryViewModel
import cz.radek.dekontaminace.data.formatBytes
import cz.radek.dekontaminace.history.DayTotal
import cz.radek.dekontaminace.history.HistoryCodec
import cz.radek.dekontaminace.history.HistoryEntry
import cz.radek.dekontaminace.ui.console.Pill
import cz.radek.dekontaminace.ui.console.SectionHeader
import cz.radek.dekontaminace.ui.theme.Console
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormat = DateTimeFormatter.ofPattern("d. M.", Locale.forLanguageTag("cs-CZ"))
private val timeFormat = DateTimeFormatter.ofPattern("d. M. H:mm", Locale.forLanguageTag("cs-CZ"))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(state: HistoryState, vm: HistoryViewModel) {
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(state.entries, state.rangeDays) {
        HistoryCodec.dailyTotals(state.entries, state.rangeDays, LocalDate.now(zone), zone)
    }
    val inRange = remember(state.entries, state.rangeDays) {
        val from = LocalDate.now(zone).minusDays((state.rangeDays - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        state.entries.filter { it.at >= from }
    }
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Lodní deník", Console.Amber)
                Text(
                    "Za ${state.rangeDays} dní uvolněno ${formatBytes(days.sumOf { it.freed })}, " +
                        "do košů přesunuto ${formatBytes(days.sumOf { it.trashed })}, akcí ${inRange.size}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Console.Peach,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(7, 30, 90).forEach { d ->
                        Pill("$d dní", if (d == state.rangeDays) Console.Gold else Console.Periwinkle, onClick = { vm.setRange(d) })
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DayChart(days, state.selectedDay, vm::selectDay)
                Row(Modifier.fillMaxWidth()) {
                    Text(days.first().day.format(dayFormat), style = MaterialTheme.typography.labelSmall, color = Console.Dim)
                    Spacer(Modifier.weight(1f))
                    Text("dnes", style = MaterialTheme.typography.labelSmall, color = Console.Dim)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Legend(Console.Amber, "uvolněno")
                    Spacer(Modifier.width(14.dp))
                    Legend(Console.Amber.copy(alpha = 0.35f), "do koše")
                }
                val sel = state.selectedDay?.let { days.getOrNull(it) }
                Text(
                    if (sel == null) "Ťukni na sloupec pro detail dne."
                    else "${sel.day.format(dayFormat)}: uvolněno ${formatBytes(sel.freed)}, do koše ${formatBytes(sel.trashed)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (sel == null) Console.Dim else Console.Gold,
                )
            }
        }
        item { SectionHeader("Záznamy", Console.Amber) }
        if (state.loaded && inRange.isEmpty()) {
            item { Text("Za tohle období se nic nedělo. Klid na palubě.", style = MaterialTheme.typography.bodyLarge, color = Console.Sky) }
        }
        items(inRange.take(500)) { e -> EntryRow(e, zone) }
        if (state.entries.isNotEmpty()) {
            item { Pill("Vymazat historii", Console.Rose, onClick = { confirmClear = true }) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Console.Black,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.border(2.dp, Console.Rose, RoundedCornerShape(24.dp)),
            title = { Text("VYMAZAT HISTORII", style = MaterialTheme.typography.titleLarge, color = Console.Rose) },
            text = { Text("Smažu celý deník i graf. Soubory a koše to neovlivní.", style = MaterialTheme.typography.bodyLarge, color = Console.Peach) },
            confirmButton = { Pill("Vymazat", Console.Rose, onClick = { confirmClear = false; vm.clear() }) },
            dismissButton = { Pill("Zrušit", Console.Periwinkle, onClick = { confirmClear = false }) },
        )
    }
}

@Composable
private fun DayChart(days: List<DayTotal>, selected: Int?, onSelect: (Int) -> Unit) {
    val max = (days.maxOfOrNull { it.total } ?: 0L).coerceAtLeast(1L)
    val reveal = remember(days.size) { Animatable(0f) }
    LaunchedEffect(days.size) { reveal.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(Console.Amber.copy(alpha = 0.05f), RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
            .padding(horizontal = 6.dp, vertical = 8.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(days.size) {
                    detectTapGestures { o ->
                        onSelect((o.x / size.width * days.size).toInt().coerceIn(0, days.size - 1))
                    }
                },
        ) {
            val n = days.size
            val slot = size.width / n
            val bar = (slot * 0.68f).coerceAtLeast(1.5f)
            listOf(0.25f, 0.5f, 0.75f, 1f).forEach { f ->
                drawRect(Console.Amber.copy(alpha = 0.12f), topLeft = Offset(0f, size.height * (1f - f)), size = Size(size.width, 1f))
            }
            val radius = CornerRadius(bar / 3f, bar / 3f)
            days.forEachIndexed { i, d ->
                val x = i * slot + (slot - bar) / 2f
                if (i == selected) {
                    drawRect(Console.Gold.copy(alpha = 0.18f), topLeft = Offset(i * slot, 0f), size = Size(slot, size.height))
                }
                val hFreed = size.height * (d.freed.toFloat() / max) * reveal.value
                val hTrash = size.height * (d.trashed.toFloat() / max) * reveal.value
                if (hTrash > 0f) {
                    drawRoundRect(
                        Console.Amber.copy(alpha = 0.35f),
                        topLeft = Offset(x, size.height - hFreed - hTrash),
                        size = Size(bar, hTrash),
                        cornerRadius = radius,
                    )
                }
                if (hFreed > 0f) {
                    drawRoundRect(
                        if (i == selected) Console.Gold else Console.Amber,
                        topLeft = Offset(x, size.height - hFreed),
                        size = Size(bar, hFreed),
                        cornerRadius = radius,
                    )
                }
                if (d.total == 0L) {
                    drawRect(Console.Amber.copy(alpha = 0.25f), topLeft = Offset(x, size.height - 2f), size = Size(bar, 2f))
                }
            }
        }
        Text(
            formatBytes(max),
            style = MaterialTheme.typography.labelSmall,
            color = Console.Dim,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Console.Dim)
    }
}

@Composable
private fun EntryRow(e: HistoryEntry, zone: ZoneId) {
    val color = when {
        !e.ok -> Console.Rose
        e.kind.freesSpace -> Console.Amber
        else -> Console.Lilac
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(34.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.kind.label, style = MaterialTheme.typography.bodyLarge, color = Console.Peach, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    java.time.Instant.ofEpochMilli(e.at).atZone(zone).format(timeFormat),
                    if (e.auto) "pravidlem" else "ručně",
                    "${e.count}×".takeIf { e.count > 1 },
                    e.note.ifBlank { null },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (e.ok) Console.Dim else Console.Rose,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (e.bytes > 0) {
            Spacer(Modifier.width(8.dp))
            Text(formatBytes(e.bytes), style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}
