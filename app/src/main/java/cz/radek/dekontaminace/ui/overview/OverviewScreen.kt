package cz.radek.dekontaminace.ui.overview

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import cz.radek.dekontaminace.CargoState
import cz.radek.dekontaminace.FilesState
import cz.radek.dekontaminace.HistoryState
import cz.radek.dekontaminace.RulesState
import cz.radek.dekontaminace.data.czPlural
import cz.radek.dekontaminace.data.formatAgo
import cz.radek.dekontaminace.ui.console.Section
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.radek.dekontaminace.MainViewModel
import cz.radek.dekontaminace.UiState
import cz.radek.dekontaminace.data.AppEntry
import cz.radek.dekontaminace.data.formatBytes
import cz.radek.dekontaminace.shizuku.ShizukuBridge
import cz.radek.dekontaminace.shizuku.ShizukuState
import cz.radek.dekontaminace.ui.console.Meter
import cz.radek.dekontaminace.ui.console.MeterPart
import cz.radek.dekontaminace.ui.console.Pill
import cz.radek.dekontaminace.ui.console.SectionHeader
import cz.radek.dekontaminace.ui.theme.Console

@Composable
fun OverviewScreen(
    state: UiState,
    vm: MainViewModel,
    files: FilesState,
    cargo: CargoState,
    history: HistoryState,
    rules: RulesState,
    onStartCargo: () -> Unit,
    onOpen: (Section) -> Unit,
) {
    val shizukuReady = state.shizuku == ShizukuState.READY
    // Koš a zbytky v Android/data vidí jen shell, proto se náklad změří, jakmile Shizuku naběhne.
    LaunchedEffect(shizukuReady) { if (shizukuReady) onStartCargo() }
    val now = System.currentTimeMillis()
    // Android/data a obb už jsou ve statistikách aplikací započítané; tady je jen ukážeme zvlášť.
    val cargoByPackage = remember(cargo.apps) { cargo.apps.associateBy { it.packageName } }

    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ShizukuPanel(state, vm) }
        if (!state.usageAccess) {
            item { UsageAccessPanel(state, vm) }
        }
        state.message?.let { msg ->
            item { MessagePanel(msg, onDismiss = vm::dismissMessage) }
        }
        item { StoragePanel(state, vm) }

        item { SectionHeader("Hlášení palub", Console.Sky) }
        item { LogTile(history, now, onClick = { onOpen(Section.HISTORY) }) }
        item { CacheTile(state, onClick = { onOpen(Section.CACHE) }) }
        item { FilesTrashTile(files, now, onClick = { onOpen(Section.FILES) }) }
        item { CargoTile(cargo, shizukuReady, now, onClick = { onOpen(Section.CARGO) }) }
        item { RulesTile(rules, now, onClick = { onOpen(Section.RULES) }) }

        val byCache = state.apps.filter { it.cacheBytes > 0 }.sortedByDescending { it.cacheBytes }.take(5)
        if (byCache.isNotEmpty()) {
            item { SectionHeader("Nejvíc cache", Console.Lilac) }
            items(byCache, key = { "c-" + it.packageName }) { app ->
                RankRow(app, value = app.cacheBytes, max = byCache.first().cacheBytes, color = Console.Lilac)
            }
            item { Pill("Do filtrů", Console.Teal, onClick = { onOpen(Section.CACHE) }) }
        }

        val bySize = state.apps.sortedByDescending { it.totalBytes }.take(6)
        if (bySize.isNotEmpty()) {
            item { SectionHeader("Největší žrouti", Console.Periwinkle) }
            items(bySize, key = { "s-" + it.packageName }) { app ->
                val external = cargoByPackage[app.packageName]?.totalBytes ?: 0L
                RankRow(
                    app,
                    value = app.totalBytes,
                    max = bySize.first().totalBytes,
                    color = Console.Periwinkle,
                    detail = buildString {
                        append("apl. ${formatBytes(app.appBytes)} · data ${formatBytes(app.dataBytes)} · cache ${formatBytes(app.cacheBytes)}")
                        if (external > 0) append("\nz toho Android/data a obb ${formatBytes(external)}")
                    },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Dlaždice hlášení: barevný okraj podle modulu, popis, výrazná hodnota a podrobnost. Ťuknutí otevře modul. */
@Composable
private fun DeckTile(title: String, value: String, detail: String?, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp, topEnd = 4.dp, bottomEnd = 4.dp))
            .background(color.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.width(8.dp).fillMaxHeight().background(color))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = color.copy(alpha = 0.8f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = color)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Console.Peach)
            }
        }
    }
}

@Composable
private fun LogTile(history: HistoryState, now: Long, onClick: () -> Unit) {
    val last = history.entries.firstOrNull { it.kind.freesSpace || it.kind.isTrashMove }
    val monthAgo = now - TimeUnit.DAYS.toMillis(30)
    val recent = history.entries.filter { it.at >= monthAgo }
    val freed = recent.filter { it.kind.freesSpace }.sumOf { it.bytes.coerceAtLeast(0L) }
    val trashed = recent.filter { it.kind.isTrashMove }.sumOf { it.bytes.coerceAtLeast(0L) }
    DeckTile(
        title = "Poslední úklid",
        value = when {
            !history.loaded -> "Čtu deník…"
            last == null -> "Zatím žádný"
            else -> "${formatAgo(last.at, now)} · ${formatBytes(last.bytes)}"
        },
        detail = last?.let { "${it.kind.label}${if (it.auto) ", rozkazem" else ""}. Za 30 dní uvolněno ${formatBytes(freed)}${if (trashed > 0) ", do košů ${formatBytes(trashed)}" else ""}." },
        color = Console.Amber,
        onClick = onClick,
    )
}

@Composable
private fun CacheTile(state: UiState, onClick: () -> Unit) {
    val total = state.apps.sumOf { it.cacheBytes }
    val top = state.apps.maxByOrNull { it.cacheBytes }?.takeIf { it.cacheBytes > 0 }
    DeckTile(
        title = "Cache všech aplikací",
        value = when {
            !state.usageAccess -> "Senzory slepé"
            state.apps.isEmpty() -> if (state.scanning) "Měřím…" else "Bez údajů"
            else -> formatBytes(total)
        },
        detail = top?.let { "Nejvíc ${it.label}, ${formatBytes(it.cacheBytes)}." },
        color = Console.Teal,
        onClick = onClick,
    )
}

@Composable
private fun FilesTrashTile(files: FilesState, now: Long, onClick: () -> Unit) {
    val n = files.trash.size
    val soonest = files.trash.minOfOrNull { it.expiresAt }
    DeckTile(
        title = "Koš skladu",
        value = when {
            !files.allFilesAccess -> "Bez přístupu k souborům"
            n == 0 -> "Prázdný"
            else -> "$n ${czPlural(n, "položka", "položky", "položek")} · ${formatBytes(files.trashBytes)}"
        },
        detail = soonest?.let { "Nejbližší se smaže sama ${expiresIn(it, now)}." },
        color = Console.Azure,
        onClick = onClick,
    )
}

@Composable
private fun CargoTile(cargo: CargoState, shizukuReady: Boolean, now: Long, onClick: () -> Unit) {
    val n = cargo.trash.size
    val leftovers = cargo.apps.filter { !it.installed && it.totalBytes > 0 }
    DeckTile(
        title = "Koš nákladu",
        value = when {
            !shizukuReady && cargo.scannedAt == null -> "Zapečetěno, Shizuku neběží"
            n == 0 -> "Prázdný"
            else -> "$n ${czPlural(n, "položka", "položky", "položek")} · ${formatBytes(cargo.trashBytes)}"
        },
        detail = when {
            cargo.scanning -> "Měřím Android/data…"
            cargo.scannedAt == null -> null
            leftovers.isEmpty() -> "Žádné zbytky po odinstalovaných aplikacích."
            else -> "Zbytky po odinstalovaných: ${leftovers.size} ${czPlural(leftovers.size, "složka", "složky", "složek")}, ${formatBytes(leftovers.sumOf { it.totalBytes })}."
        }.let { d ->
            val soonest = cargo.trash.minOfOrNull { it.expiresAt }
            if (soonest == null) d else listOfNotNull(d, "Nejbližší z koše se smaže ${expiresIn(soonest, now)}.").joinToString(" ")
        },
        color = Console.Lime,
        onClick = onClick,
    )
}

@Composable
private fun RulesTile(rules: RulesState, now: Long, onClick: () -> Unit) {
    val run = rules.lastCacheRun
    DeckTile(
        title = "Rozkaz: hromadná cache",
        value = rules.cacheInterval.label,
        detail = when {
            rules.running -> "Právě běží."
            run == null -> "Ještě neproběhl."
            run.ok -> "Naposledy ${formatAgo(run.at, now)}, uvolněno ${formatBytes(run.freedBytes)}."
            else -> "Naposledy ${formatAgo(run.at, now)} selhal: ${run.text}"
        },
        color = Console.Violet,
        onClick = onClick,
    )
}

private fun expiresIn(at: Long, now: Long): String {
    val days = ((at - now) / TimeUnit.DAYS.toMillis(1)).toInt()
    return when {
        at <= now -> "při příštím otevření modulu"
        days < 1 -> "do 24 hodin"
        else -> "za $days ${czPlural(days, "den", "dny", "dní")}"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShizukuPanel(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    val (color, headline, text) = when (state.shizuku) {
        ShizukuState.READY -> Triple(
            Console.Sky, "Shizuku online",
            "Privilegovaný přístup aktivní. Paluba je připravena k dekontaminaci.",
        )
        ShizukuState.NOT_RUNNING -> Triple(
            Console.Red, "Shizuku offline",
            "Po restartu telefonu Shizuku neběží. Otevři Shizuku a spusť ho přes bezdrátové ladění, pak se sem vrať.",
        )
        ShizukuState.NOT_INSTALLED -> Triple(
            Console.Red, "Shizuku chybí",
            "Bez Shizuku nejde čistit cache cizích aplikací. Nainstaluj ho a spusť přes bezdrátové ladění.",
        )
        ShizukuState.NO_PERMISSION -> Triple(
            Console.Gold, "Čekám na povolení",
            "Shizuku běží, ale Dekontaminace zatím nemá povolení ho používat.",
        )
        ShizukuState.DENIED -> Triple(
            Console.Red, "Povolení zamítnuto",
            "Povolení bylo zamítnuto natrvalo. Povol Dekontaminaci ručně v aplikaci Shizuku.",
        )
        ShizukuState.UNSUPPORTED -> Triple(
            Console.Red, "Zastaralé Shizuku",
            "Tahle verze Shizuku je moc stará. Aktualizuj ji.",
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(headline, color)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Console.Peach)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.shizuku) {
                ShizukuState.NO_PERMISSION ->
                    Pill("Udělit povolení", Console.Gold, onClick = vm::requestShizukuPermission)
                ShizukuState.NOT_INSTALLED ->
                    Pill("Stáhnout Shizuku", Console.Amber, onClick = {
                        context.openSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                    })
                ShizukuState.READY -> Unit
                else ->
                    Pill("Otevřít Shizuku", Console.Amber, onClick = {
                        context.packageManager.getLaunchIntentForPackage(ShizukuBridge.SHIZUKU_PACKAGE)
                            ?.let { context.openSafely(it) }
                    })
            }
            if (state.shizuku != ShizukuState.READY) {
                Pill("Znovu ověřit", Console.Periwinkle, onClick = { ShizukuBridge.refresh() })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsageAccessPanel(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Senzory slepé", Console.Gold)
        Text(
            "Bez přístupu k údajům o využití nevidím, kolik místa zabírají aplikace a jejich cache.",
            style = MaterialTheme.typography.bodyLarge,
            color = Console.Peach,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.shizuku == ShizukuState.READY) {
                Pill("Udělit přes Shizuku", Console.Gold, onClick = vm::grantUsageAccessViaShizuku)
            }
            Pill("Otevřít nastavení", Console.Amber, onClick = {
                context.openSafely(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            })
        }
    }
}

@Composable
private fun MessagePanel(message: String, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Hlášení", Console.Rose)
        Text(message, style = MaterialTheme.typography.bodyLarge, color = Console.Peach)
        Pill("Beru na vědomí", Console.Rose, onClick = onDismiss)
    }
}

@Composable
private fun StoragePanel(state: UiState, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Stav úložiště", Console.Amber)
        val s = state.summary
        if (s == null) {
            Text(
                if (state.scanning) "Skenuji palubu…" else "Údaje zatím nejsou k dispozici.",
                style = MaterialTheme.typography.bodyLarge,
                color = Console.Dim,
            )
        } else {
            // Číslo a popisek pod sebou: vedle sebe se při větším písmu popisek zalomil a „GB“ se zdvojilo.
            Text(formatBytes(s.freeBytes), style = MaterialTheme.typography.displaySmall, color = Console.Gold, maxLines = 1)
            Text(
                "volno z ${formatBytes(s.totalBytes)}, obsazeno ${formatBytes(s.usedBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Console.Peach,
            )
            val total = s.totalBytes.toFloat().coerceAtLeast(1f)
            Meter(
                listOf(
                    MeterPart(s.appsBytes / total, Console.Periwinkle),
                    MeterPart(s.mediaBytes / total, Console.Sky),
                    MeterPart(s.otherBytes / total, Console.Lilac),
                ),
                background = Console.Gray,
            )
            Legend(Console.Periwinkle, "Aplikace a jejich data", s.appsBytes)
            if (s.externalAppBytes > 0) {
                Text(
                    "   z toho Android/data a obb: ${formatBytes(s.externalAppBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Console.Dim,
                )
            }
            Legend(Console.Sky, "Fotky, videa a hudba", s.mediaBytes)
            Legend(Console.Lilac, "Systém a ostatní", s.otherBytes)
            Legend(Console.Gray, "Volné místo", s.freeBytes)
            Text(
                "Stažené soubory a dokumenty změří až Sklad, tady spadají pod ostatní.",
                style = MaterialTheme.typography.bodySmall,
                color = Console.Dim,
            )
        }
        Pill(
            if (state.scanning) "Skenuji…" else "Znovu skenovat",
            Console.Amber,
            onClick = vm::rescan,
            enabled = !state.scanning,
        )
    }
}

@Composable
private fun Legend(color: Color, label: String, bytes: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
        Text(
            "  $label",
            style = MaterialTheme.typography.bodyMedium,
            color = Console.Peach,
            modifier = Modifier.weight(1f),
        )
        Text(formatBytes(bytes), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun RankRow(app: AppEntry, value: Long, max: Long, color: Color, detail: String? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                color = Console.Peach,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(formatBytes(value), style = MaterialTheme.typography.labelLarge, color = color)
        }
        Meter(
            listOf(MeterPart(value.toFloat() / max.toFloat().coerceAtLeast(1f), color)),
            height = 6.dp,
            background = Color(0xFF1E1E28),
        )
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Console.Dim)
        }
    }
}

private fun Context.openSafely(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "Tohle se nepodařilo otevřít.", Toast.LENGTH_LONG).show()
    }
}
