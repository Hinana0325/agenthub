package com.agentcontrolcenter.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentcontrolcenter.app.core.featureflag.FeatureFlagManager
import com.agentcontrolcenter.app.core.featureflag.FeatureFlagManager.FeatureFlag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Feature Flag 设置 ViewModel — 设置页「实验性功能」分类专用。
 *
 * 与 [FeatureFlagManager] 1:1 透传，仅做 UI 友好的封装：
 * - 把 [FeatureFlagManager.overrides]（key=枚举名）+ 各 flag 的 [FeatureFlag.defaultEnabled]
 *   组合为 [FlagUiState] 列表，UI 直接 ForEach 渲染即可。
 * - 切换时调用 [FeatureFlagManager.setOverride] / [clearOverride]，自动同步到 DataStore。
 *
 * 与 iOS SettingsView.experimentalFeaturesSection 行为对齐。
 */
@HiltViewModel
class FeatureFlagSettingsViewModel @Inject constructor(
    private val featureFlagManager: FeatureFlagManager
) : ViewModel() {

    /** 单个 Flag 的 UI 投影。 */
    data class FlagUiState(
        val flag: FeatureFlag,
        /** 当前生效值（用户覆盖优先于默认值） */
        val currentValue: Boolean,
        /** 是否被用户显式覆盖（用于 UI 显示「恢复默认」按钮） */
        val isOverridden: Boolean
    )

    /**
     * 所有 Flag 的 UI 状态列表。每次 [FeatureFlagManager.overrides] 变化都会重发。
     */
    val flags: StateFlow<List<FlagUiState>> = featureFlagManager.overrides
        .map { overrides ->
            FeatureFlag.entries.map { flag ->
                val overridden = overrides.containsKey(flag.name)
                FlagUiState(
                    flag = flag,
                    currentValue = overrides[flag.name] ?: flag.defaultEnabled,
                    isOverridden = overridden
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /** 切换 flag 状态。 */
    fun toggle(flag: FeatureFlag, enabled: Boolean) {
        viewModelScope.launch {
            featureFlagManager.setOverride(flag, enabled)
        }
    }

    /** 恢复 flag 为默认值（清除覆盖）。 */
    fun resetToDefault(flag: FeatureFlag) {
        viewModelScope.launch {
            featureFlagManager.clearOverride(flag)
        }
    }

    /** 清除所有 flag 覆盖，全部恢复默认。 */
    fun resetAll() {
        viewModelScope.launch {
            FeatureFlag.entries.forEach { featureFlagManager.clearOverride(it) }
        }
    }
}
