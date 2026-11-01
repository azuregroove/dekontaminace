package cz.radek.dekontaminace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cz.radek.dekontaminace.shizuku.ShizukuState
import cz.radek.dekontaminace.ui.cache.CacheScreen
import cz.radek.dekontaminace.ui.cargo.CargoScreen
import cz.radek.dekontaminace.ui.console.ConsoleFrame
import cz.radek.dekontaminace.ui.console.Section
import cz.radek.dekontaminace.ui.files.FilesScreen
import cz.radek.dekontaminace.ui.history.HistoryScreen
import cz.radek.dekontaminace.ui.overview.OverviewScreen
import cz.radek.dekontaminace.ui.rules.RulesScreen
import cz.radek.dekontaminace.ui.theme.DekontaminaceTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private val filesVm: FilesViewModel by viewModels()
    private val cargoVm: CargoViewModel by viewModels()
    private val rulesVm: RulesViewModel by viewModels()
    private val historyVm: HistoryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DekontaminaceTheme {
                DekontaminaceApp(vm, filesVm, cargoVm, rulesVm, historyVm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
        filesVm.onResume()
        rulesVm.onResume()
    }
}

@Composable
private fun DekontaminaceApp(
    vm: MainViewModel,
    filesVm: FilesViewModel,
    cargoVm: CargoViewModel,
    rulesVm: RulesViewModel,
    historyVm: HistoryViewModel,
) {
    val state by vm.state.collectAsState()
    val filesState by filesVm.state.collectAsState()
    val cargoState by cargoVm.state.collectAsState()
    val rulesState by rulesVm.state.collectAsState()
    val historyState by historyVm.state.collectAsState()
    // Ukládáme jméno, ne enum: po aktualizaci, která modul odebere, se obnova nezadrhne.
    var sectionName by rememberSaveable { mutableStateOf(Section.OVERVIEW.name) }
    val section = Section.entries.firstOrNull { it.name == sectionName } ?: Section.OVERVIEW
    val shizukuReady = state.shizuku == ShizukuState.READY

    ConsoleFrame(
        title = "DEKONTAMINACE",
        current = section,
        alert = !shizukuReady,
        onSelect = { sectionName = it.name },
    ) {
        AnimatedContent(
            targetState = section,
            transitionSpec = {
                (fadeIn(tween(250)) + slideInHorizontally(tween(250)) { it / 10 }) togetherWith fadeOut(tween(120))
            },
            modifier = Modifier.fillMaxSize(),
            label = "section",
        ) { s ->
            when (s) {
                Section.OVERVIEW -> OverviewScreen(
                    state, vm, filesState, cargoState, historyState, rulesState,
                    onStartCargo = cargoVm::start,
                    onOpen = { sectionName = it.name },
                )
                Section.CACHE -> CacheScreen(state, vm)
                Section.FILES -> FilesScreen(filesState, filesVm, shizukuReady = shizukuReady)
                Section.CARGO -> CargoScreen(cargoState, cargoVm, shizukuReady = shizukuReady)
                Section.RULES -> RulesScreen(rulesState, rulesVm, shizukuReady = shizukuReady)
                Section.HISTORY -> HistoryScreen(historyState, historyVm)
            }
        }
    }
}
