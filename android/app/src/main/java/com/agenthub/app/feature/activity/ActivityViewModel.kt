package com.agenthub.app.feature.activity

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agenthub.app.data.model.ActivityItem
import com.agenthub.app.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ActivityUiState(
    val activities: List<ActivityItem> = emptyList()
)

@HiltViewModel
class ActivityViewModel @Inject constructor(
    application: Application,
    private val repository: ChatRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ActivityUiState())
    val uiState: StateFlow<ActivityUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getAllActivities().collect { activities ->
                _uiState.update { it.copy(activities = activities) }
            }
        }
    }

    fun clearLog() {
        viewModelScope.launch {
            repository.clearActivityLog()
        }
    }

    /**
     * Refresh activities list. The Room Flow auto-updates,
     * but this can be called to simulate a pull-to-refresh delay.
     */
    fun refreshActivities() {
        viewModelScope.launch {
            // Force re-read from Room to trigger the Flow emission
            val activities = repository.getAllActivities().firstOrNull() ?: emptyList()
            _uiState.update { it.copy(activities = activities) }
            delay(300) // Brief delay for pull-to-refresh indicator visibility
        }
    }
}
