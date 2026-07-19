package com.agenthub.app.feature.insights

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agenthub.app.data.insights.DataInsightsManager
import com.agenthub.app.core.database.dao.MessageDao
import com.agenthub.app.core.database.dao.SessionDao
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 导出结果事件，由 [InsightsScreen] 收集后展示 Toast。
 * Phase 3.5: 将 Toast 从 ViewModel 移至 Composable 层，
 * ViewModel 只负责业务逻辑和事件发射。
 */
sealed interface ExportEvent {
    data class Success(val filePath: String) : ExportEvent
    data class Failure(val message: String) : ExportEvent
}

@HiltViewModel
class InsightsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    messageDao: MessageDao,
    sessionDao: SessionDao
) : ViewModel() {

    private val insightsManager = DataInsightsManager(messageDao, sessionDao)

    private val _insights = MutableStateFlow(DataInsightsManager.Insights())
    val insights: StateFlow<DataInsightsManager.Insights> = _insights.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _exportEvent = Channel<ExportEvent>(Channel.BUFFERED)
    val exportEvent = _exportEvent.receiveAsFlow()

    init {
        loadInsights()
    }

    fun loadInsights() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _insights.value = insightsManager.generateInsights()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Keep default empty insights
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Phase 3.5: 不再接收 [Context] 参数，改用构造器注入的 [appContext]。
     * 不再直接调用 Toast，改为发射 [ExportEvent] 供 UI 层消费。
     */
    fun exportReport() {
        viewModelScope.launch {
            try {
                val report = insightsManager.exportReport()
                val dir = File(appContext.cacheDir, "insights")
                dir.mkdirs()
                val file = File(dir, "agenthub_insights_${System.currentTimeMillis()}.txt")
                file.writeText(report)
                _exportEvent.send(ExportEvent.Success(file.absolutePath))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _exportEvent.send(ExportEvent.Failure(e.message ?: ""))
            }
        }
    }
}
