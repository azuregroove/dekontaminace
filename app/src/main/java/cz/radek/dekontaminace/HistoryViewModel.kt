package cz.radek.dekontaminace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.radek.dekontaminace.history.History
import cz.radek.dekontaminace.history.HistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HistoryState(
    val entries: List<HistoryEntry> = emptyList(),
    val rangeDays: Int = 30,
    val selectedDay: Int? = null,
    val loaded: Boolean = false,
)

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            History.version.collect {
                val entries = withContext(Dispatchers.IO) { History.load(getApplication()) }
                _state.update { s -> s.copy(entries = entries, loaded = true) }
            }
        }
    }

    fun setRange(days: Int) = _state.update { it.copy(rangeDays = days, selectedDay = null) }

    fun selectDay(index: Int) = _state.update { it.copy(selectedDay = if (it.selectedDay == index) null else index) }

    fun clear() {
        viewModelScope.launch(Dispatchers.IO) { History.clear(getApplication()) }
    }
}
