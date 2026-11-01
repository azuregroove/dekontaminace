package cz.radek.dekontaminace.ui.rules

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.radek.dekontaminace.RulesState
import cz.radek.dekontaminace.RulesViewModel
import cz.radek.dekontaminace.rules.RuleInterval
import cz.radek.dekontaminace.ui.console.Pill
import cz.radek.dekontaminace.ui.console.SectionHeader
import cz.radek.dekontaminace.ui.theme.Console
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesScreen(state: RulesState, vm: RulesViewModel, shizukuReady: Boolean) {
    val timeFormat = remember { SimpleDateFormat("d. M. yyyy H:mm", Locale.forLanguageTag("cs-CZ")) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onResume() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionHeader("Rozkazy", Console.Violet)
        Text(
            "Pravidla běží sama na pozadí. Smí jen věci, které nic důležitého nezničí, a po každém běhu pošlou souhrn.",
            style = MaterialTheme.typography.bodyMedium,
            color = Console.Peach,
        )

        if (!state.canNotify && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Text(
                "Oznámení jsou vypnutá, takže o výsledku pravidel se dozvíš jen tady v aplikaci.",
                style = MaterialTheme.typography.bodyLarge,
                color = Console.Rose,
            )
            Pill("Povolit oznámení", Console.Rose, onClick = { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) })
        }

        Spacer(Modifier.height(4.dp))
        SectionHeader("Hromadná cache", Console.Teal)
        Text(
            "Uvolní cache všech aplikací (pm trim-caches). Bez výjimek, cache si aplikace znovu vytvoří. " +
                "Potřebuje běžící Shizuku: po restartu telefonu ho spusť, jinak pravidlo jen ohlásí, že neproběhlo.",
            style = MaterialTheme.typography.bodyMedium,
            color = Console.Peach,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RuleInterval.entries.forEach { i ->
                Pill(i.label, if (i == state.cacheInterval) Console.Gold else Console.Periwinkle, onClick = { vm.setCacheInterval(i) })
            }
        }
        Text(
            when (state.cacheInterval) {
                RuleInterval.OFF -> "Pravidlo je vypnuté."
                RuleInterval.DAILY -> "Android spustí úklid zhruba jednou za den, když baterie není skoro vybitá. Přesnou hodinu si volí sám."
                RuleInterval.WEEKLY -> "Android spustí úklid zhruba jednou za týden, když baterie není skoro vybitá. Přesný čas si volí sám."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Console.Dim,
        )

        Pill(
            if (state.running) "Probíhá dekontaminace…" else "Spustit teď",
            Console.Teal,
            onClick = vm::runCacheNow,
            enabled = !state.running && shizukuReady,
        )
        if (!shizukuReady) {
            Text("Shizuku teď neběží, ruční spuštění je zamčené.", style = MaterialTheme.typography.bodySmall, color = Console.Dim)
        }

        state.lastCacheRun?.let { run ->
            Text(
                "POSLEDNÍ BĚH · ${timeFormat.format(Date(run.at))} · ${if (run.manual) "ručně" else "plánovaně"}",
                style = MaterialTheme.typography.labelMedium,
                color = if (run.ok) Console.Teal else Console.Rose,
            )
            Text(run.text, style = MaterialTheme.typography.bodyLarge, color = Console.Peach)
        }
        Spacer(Modifier.height(24.dp))
    }
}
