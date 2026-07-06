package com.agenthub.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agenthub.app.R
import com.agenthub.app.ui.theme.GlassTopAppBar
import com.agenthub.app.ui.theme.parseHexColor

/**
 * Screen for customizing the app theme with live preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeCustomizerScreen(
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit = {}
) {
    val uiState by settingsViewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = { Text(stringResource(R.string.custom_theme_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_close))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Enable toggle
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.custom_theme_enable),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.custom_theme_enable_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(
                            checked = uiState.customThemeEnabled,
                            onCheckedChange = { settingsViewModel.setCustomThemeEnabled(it) }
                        )
                    }
                }
            }

            if (uiState.customThemeEnabled) {
                // Live preview
                item {
                    Text(
                        text = stringResource(R.string.custom_theme_preview),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                item {
                    ThemePreviewCard(
                        primaryColor = uiState.customPrimaryColor,
                        accentColor = uiState.customAccentColor,
                        backgroundColor = uiState.customBackgroundColor,
                        cornerRadius = uiState.customCornerRadius
                    )
                }

                // Primary color
                item {
                    Text(
                        text = stringResource(R.string.custom_theme_primary_color),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                item {
                    ColorPickerRow(
                        currentColor = uiState.customPrimaryColor,
                        onColorSelected = { settingsViewModel.setCustomPrimaryColor(it) }
                    )
                }

                // Accent color
                item {
                    Text(
                        text = stringResource(R.string.custom_theme_accent_color),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                item {
                    ColorPickerRow(
                        currentColor = uiState.customAccentColor,
                        onColorSelected = { settingsViewModel.setCustomAccentColor(it) }
                    )
                }

                // Background color
                item {
                    Text(
                        text = stringResource(R.string.custom_theme_bg_color),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                item {
                    ColorPickerRow(
                        currentColor = uiState.customBackgroundColor,
                        onColorSelected = { settingsViewModel.setCustomBackgroundColor(it) }
                    )
                }

                // Font size slider
                item {
                    Text(
                        text = stringResource(R.string.custom_theme_font_size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                item {
                    FontSizeSlider(
                        current = uiState.customFontSize,
                        onChange = { settingsViewModel.setCustomFontSize(it) }
                    )
                }

                // Corner radius slider
                item {
                    Text(
                        text = stringResource(R.string.custom_theme_corner_radius),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                item {
                    CornerRadiusSlider(
                        current = uiState.customCornerRadius,
                        onChange = { settingsViewModel.setCustomCornerRadius(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemePreviewCard(
    primaryColor: String,
    accentColor: String,
    backgroundColor: String,
    cornerRadius: Int
) {
    val primary = parseHexColor(primaryColor, MaterialTheme.colorScheme.primary)
    val accent = parseHexColor(accentColor, MaterialTheme.colorScheme.secondary)
    val bg = parseHexColor(backgroundColor, MaterialTheme.colorScheme.background)

    Surface(
        shape = RoundedCornerShape(cornerRadius.dp),
        color = bg,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Preview header
            Surface(
                shape = RoundedCornerShape((cornerRadius / 2).dp),
                color = primary
            ) {
                Text(
                    text = stringResource(R.string.custom_theme_preview_header),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            // Preview message bubbles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    shape = RoundedCornerShape((cornerRadius / 2).dp),
                    color = primary.copy(alpha = 0.15f),
                    modifier = Modifier.widthIn(max = 200.dp)
                ) {
                    Text(
                        text = stringResource(R.string.custom_theme_preview_user_msg),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Surface(
                    shape = RoundedCornerShape((cornerRadius / 2).dp),
                    color = accent.copy(alpha = 0.15f),
                    modifier = Modifier.widthIn(max = 200.dp)
                ) {
                    Text(
                        text = stringResource(R.string.custom_theme_preview_agent_msg),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            // Preview button
            Button(
                onClick = {},
                shape = RoundedCornerShape((cornerRadius / 2).dp),
                colors = ButtonDefaults.buttonColors(containerColor = primary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.custom_theme_preview_button))
            }
        }
    }
}

/**
 * Preset color palette row with hex input.
 */
@Composable
private fun ColorPickerRow(
    currentColor: String,
    onColorSelected: (String) -> Unit
) {
    val presetColors = listOf(
        "#185FA5", "#0F6E56", "#534AB7", "#993C1D",
        "#854F0B", "#3B6D11", "#993556", "#5F5E5A",
        "#E53935", "#1E88E5", "#43A047", "#FB8C00",
        "#8E24AA", "#00ACC1", "#F4511E", "#6D4C41"
    )

    var hexInput by remember(currentColor) { mutableStateOf(currentColor) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Preset color grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presetColors.take(8).forEach { color ->
                val c = parseHexColor(color, Color.Gray)
                val isSelected = currentColor.equals(color, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(c)
                        .then(
                            if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier
                        )
                        .clickable { onColorSelected(color) }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presetColors.drop(8).forEach { color ->
                val c = parseHexColor(color, Color.Gray)
                val isSelected = currentColor.equals(color, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(c)
                        .then(
                            if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier
                        )
                        .clickable { onColorSelected(color) }
                )
            }
        }

        // Hex input
        OutlinedTextField(
            value = hexInput,
            onValueChange = { hexInput = it },
            label = { Text(stringResource(R.string.custom_theme_hex_input)) },
            placeholder = { Text("#185FA5") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                IconButton(
                    onClick = {
                        if (hexInput.matches(Regex("^#?[0-9a-fA-F]{6,8}$"))) {
                            val normalized = if (hexInput.startsWith("#")) hexInput else "#$hexInput"
                            onColorSelected(normalized)
                        }
                    }
                ) {
                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.btn_save))
                }
            }
        )
    }
}

@Composable
private fun FontSizeSlider(
    current: String,
    onChange: (String) -> Unit
) {
    val sizes = listOf("small", "medium", "large", "xlarge")
    val sizeLabels = listOf(
        stringResource(R.string.font_small),
        stringResource(R.string.font_medium),
        stringResource(R.string.font_large),
        stringResource(R.string.font_xlarge)
    )
    val currentIndex = sizes.indexOf(current).coerceIn(0, sizes.size - 1)

    Column {
        Slider(
            value = currentIndex.toFloat(),
            onValueChange = { onChange(sizes[it.toInt()]) },
            valueRange = 0f..(sizes.size - 1).toFloat(),
            steps = sizes.size - 2,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            sizeLabels.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun CornerRadiusSlider(
    current: Int,
    onChange: (Int) -> Unit
) {
    Column {
        Slider(
            value = current.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..32f,
            steps = 7,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.custom_theme_radius_none),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                text = "${current}dp",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(R.string.custom_theme_radius_round),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
