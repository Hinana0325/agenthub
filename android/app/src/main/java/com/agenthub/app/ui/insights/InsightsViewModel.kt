package com.agenthub.app.ui.insights

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agenthub.app.R
import com.agenthub.app.data.insights.DataInsightsManager
import com.agenthub.app.data.local.AppDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class InsightsViewModel @Inject constructor(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val insightsManager = DataInsightsManager(db.messageDao(), db.sessionDao())

    private val _insights = MutableStateFlow(DataInsightsManager.Insights())
    val insights: StateFlow<DataInsightsManager.Insights> = _insights.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadInsights()
    }

    fun loadInsights() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _insights.value = insightsManager.generateInsights()
            } catch (_: Exception) {
                // Keep default empty insights
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun exportReport(context: Context) {
        viewModelScope.launch {
            try {
                val report = insightsManager.exportReport()
                val dir = File(context.cacheDir, "insights")
                dir.mkdirs()
                val file = File(dir, "agenthub_insights_${System.currentTimeMillis()}.txt")
                file.writeText(report)
                Toast.makeText(
                    context,
                    context.getString(R.string.insights_export_success, file.absolutePath),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.insights_export_failed, e.message ?: ""),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
