package cz.radek.dekontaminace

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.radek.dekontaminace.rules.CacheRule
import cz.radek.dekontaminace.rules.RuleInterval
import cz.radek.dekontaminace.rules.RuleNotifications
import cz.radek.dekontaminace.rules.RuleRun
import cz.radek.dekontaminace.rules.RuleScheduler
import cz.radek.dekontaminace.rules.RuleStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RulesState(
    val cacheInterval: RuleInterval = RuleInterval.OFF,
    val lastCacheRun: RuleRun? = null,
    val running: Boolean = false,
    val canNotify: Boolean = false,
)

class RulesViewModel(app: Application) : AndroidViewModel(app) {

    private val store = RuleStore(app)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<RulesState> = _state.asStateFlow()

    // Plánovaný běh zapisuje výsledek, zatímco je aplikace otevřená; drží se v poli, jinak by ho GC zahodil.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _state.update { read().copy(running = it.running) }
    }

    init {
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onCleared() {
        store.prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun read() = RulesState(
        cacheInterval = store.cacheInterval,
        lastCacheRun = store.lastCacheRun(),
        canNotify = RuleNotifications.canNotify(getApplication()),
    )

    fun onResume() = _state.update { read().copy(running = it.running) }

    fun setCacheInterval(interval: RuleInterval) {
        store.cacheInterval = interval
        RuleScheduler.applyCache(getApplication(), interval)
    }

    fun runCacheNow() {
        if (_state.value.running) return
        viewModelScope.launch {
            _state.update { it.copy(running = true) }
            try {
                CacheRule.run(getApplication(), manual = true)
            } finally {
                _state.update { read().copy(running = false) }
            }
        }
    }
}
