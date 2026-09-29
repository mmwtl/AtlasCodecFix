package com.mmwtl.atlascodecfix

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource

class MainActivity : ComponentActivity() {
    private val viewModel: CodecFixViewModel by viewModels {
        CodecFixViewModel.Factory(application as CodecFixApp)
    }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val screenState by viewModel.state.collectAsState()
            AtlasCodecFixTheme {
                CodecFixScreen(
                    state = screenState,
                    onAdbEnabledChange = viewModel::setAdbEnabled,
                    onHostChange = viewModel::setAdbHost,
                    onAdbModeChange = viewModel::setAdbMode,
                    onPortChange = viewModel::setAdbPort,
                    onConnect = viewModel::connectAdb,
                    onDisconnect = viewModel::disconnectAdb,
                    onVariantSelected = viewModel::selectVariant,
                    onApply = viewModel::requestApplySelectedVariant,
                    onRefresh = viewModel::refreshCurrentVariant,
                    onPreflight = viewModel::runPreflightCheck,
                    onDiagnostics = viewModel::runDiagnostics,
                    onExportAnalysis = viewModel::exportAnalysisBundle,
                    onAutoApplyCodecFixChange = viewModel::setAutoApplyCodecFix,
                    onAutoApplyDelayChange = viewModel::setAutoApplyDelay,
                    onOpenAccessibilitySettings = ::openAccessibilitySettings,
                    onSkipCompatibilityCheckChange = viewModel::setSkipCompatibilityCheck,
                    onLoadCodecs = viewModel::loadAvailableCodecs,
                    onHideCodecs = viewModel::hideAvailableCodecs,
                    onCodecHardwareChange = viewModel::setCodecHardwareFilter,
                    onCodecSoftwareChange = viewModel::setCodecSoftwareFilter,
                    onCodecAudioChange = viewModel::setCodecAudioFilter,
                    onCodecVideoChange = viewModel::setCodecVideoFilter,
                    onErrorNotificationsChange = ::setErrorNotificationsEnabled,
                    onConfirmApply = viewModel::confirmApply,
                    onDismissConfirmation = viewModel::dismissApplyConfirmation
                )
            }
        }
    }

    private fun setErrorNotificationsEnabled(enabled: Boolean) {
        if (enabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setErrorNotificationsEnabled(enabled)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "Accessibility settings screen is unavailable", error)
        } catch (error: SecurityException) {
            Log.w(TAG, "Accessibility settings screen is unavailable", error)
        }

        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "System settings screen is unavailable", error)
            Toast.makeText(this, R.string.no_accessibility_settings, Toast.LENGTH_LONG).show()
        } catch (error: SecurityException) {
            Log.w(TAG, "System settings screen is unavailable", error)
            Toast.makeText(this, R.string.no_accessibility_settings, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        private const val TAG = "AtlasCodecFix"
    }
}

@Composable
private fun CodecFixScreen(
    state: CodecFixScreenState,
    onAdbEnabledChange: (Boolean) -> Unit,
    onHostChange: (String) -> Unit,
    onAdbModeChange: (AdbEndpointMode) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onVariantSelected: (HevcCodecFixVariant) -> Unit,
    onApply: () -> Unit,
    onRefresh: () -> Unit,
    onPreflight: () -> Unit,
    onDiagnostics: () -> Unit,
    onExportAnalysis: () -> Unit,
    onAutoApplyCodecFixChange: (Boolean) -> Unit,
    onAutoApplyDelayChange: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onSkipCompatibilityCheckChange: (Boolean) -> Unit,
    onLoadCodecs: () -> Unit,
    onHideCodecs: () -> Unit,
    onCodecHardwareChange: (Boolean) -> Unit,
    onCodecSoftwareChange: (Boolean) -> Unit,
    onCodecAudioChange: (Boolean) -> Unit,
    onCodecVideoChange: (Boolean) -> Unit,
    onErrorNotificationsChange: (Boolean) -> Unit,
    onConfirmApply: () -> Unit,
    onDismissConfirmation: () -> Unit
) {
    state.confirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = onDismissConfirmation,
            title = { Text(stringResource(R.string.risk_confirmation_title)) },
            text = {
                Text(
                    stringResource(
                        if (confirmation.reason == ConfirmationReason.EXPERIMENTAL) {
                            R.string.experimental_warning
                        } else {
                            R.string.risky_warning
                        },
                        confirmation.variant.title
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmApply) {
                    Text(stringResource(R.string.action_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissConfirmation) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(8.dp)
        )
    }

    var selectedTab by rememberSaveable { mutableStateOf(CodecFixTab.PROFILE) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.app_name),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            StatusHeader(state)
        }

        PrimaryTabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            CodecFixTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(stringResource(tab.titleRes), fontWeight = FontWeight.Medium) }
                )
            }
        }

        // A separate composition per tab gives each tab its own scroll position.
        key(selectedTab) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    CodecFixTab.PROFILE -> ProfileTab(
                        state = state,
                        onVariantSelected = onVariantSelected,
                        onApply = onApply,
                        onRefresh = onRefresh,
                        onOpenConnection = { selectedTab = CodecFixTab.CONNECTION }
                    )
                    CodecFixTab.CONNECTION -> ConnectionTab(
                        state = state,
                        onAdbEnabledChange = onAdbEnabledChange,
                        onHostChange = onHostChange,
                        onAdbModeChange = onAdbModeChange,
                        onPortChange = onPortChange,
                        onConnect = onConnect,
                        onDisconnect = onDisconnect
                    )
                    CodecFixTab.AUTOSTART -> AutostartTab(
                        state = state,
                        onAutoApplyCodecFixChange = onAutoApplyCodecFixChange,
                        onAutoApplyDelayChange = onAutoApplyDelayChange,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        onErrorNotificationsChange = onErrorNotificationsChange
                    )
                    CodecFixTab.DIAGNOSTICS -> DiagnosticsTab(
                        state = state,
                        onPreflight = onPreflight,
                        onDiagnostics = onDiagnostics,
                        onExportAnalysis = onExportAnalysis,
                        onSkipCompatibilityCheckChange = onSkipCompatibilityCheckChange,
                        onLoadCodecs = onLoadCodecs,
                        onHideCodecs = onHideCodecs,
                        onCodecHardwareChange = onCodecHardwareChange,
                        onCodecSoftwareChange = onCodecSoftwareChange,
                        onCodecAudioChange = onCodecAudioChange,
                        onCodecVideoChange = onCodecVideoChange
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileTab(
    state: CodecFixScreenState,
    onVariantSelected: (HevcCodecFixVariant) -> Unit,
    onApply: () -> Unit,
    onRefresh: () -> Unit,
    onOpenConnection: () -> Unit
) {
    Section(title = stringResource(R.string.section_profile)) {
        Text(
            text = stringResource(
                R.string.current_variant,
                state.currentVariant?.title ?: stringResource(R.string.variant_unknown)
            ),
            fontWeight = FontWeight.Medium
        )

        HevcCodecFixVariant.USER_VISIBLE.forEachIndexed { index, variant ->
            if (index > 0 && variant.experimental &&
                !HevcCodecFixVariant.USER_VISIBLE[index - 1].experimental
            ) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.experimental_profiles_separator),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            VariantButton(
                variant = variant,
                selected = state.selectedVariant == variant,
                enabled = !state.isBusy,
                onClick = { onVariantSelected(variant) }
            )
        }

        CodecFixScreenRules.profileConnectionHint(state.adbEnabled, state.connectionState)
            ?.let { hint ->
                ConnectionHintRow(hint = hint, onOpenConnection = onOpenConnection)
            }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = state.adbEnabled && !state.isBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(8.dp),
                onClick = onApply
            ) {
                Text(stringResource(R.string.action_apply), fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                enabled = state.adbEnabled && !state.isBusy,
                shape = RoundedCornerShape(8.dp),
                onClick = onRefresh
            ) {
                Text(stringResource(R.string.action_check))
            }
        }
    }
}

@Composable
private fun ConnectionHintRow(
    hint: ProfileConnectionHint,
    onOpenConnection: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = stringResource(hint.messageRes),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onOpenConnection) {
            Text(stringResource(R.string.action_open_connection))
        }
    }
}

@Composable
private fun ConnectionTab(
    state: CodecFixScreenState,
    onAdbEnabledChange: (Boolean) -> Unit,
    onHostChange: (String) -> Unit,
    onAdbModeChange: (AdbEndpointMode) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Section(title = stringResource(R.string.section_adb)) {
        SwitchSettingRow(
            title = stringResource(R.string.adb_helper),
            description = stringResource(R.string.adb_helper_description),
            checked = state.adbEnabled,
            enabled = !state.isBusy,
            onCheckedChange = onAdbEnabledChange
        )

        AdbModeSelector(
            selected = state.adbMode,
            enabled = !state.isBusy,
            onSelected = onAdbModeChange
        )

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.adbHostText,
            onValueChange = onHostChange,
            enabled = !state.isBusy,
            label = { Text(stringResource(R.string.adb_host)) },
            supportingText = { Text(stringResource(R.string.adb_host_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.adbPortText,
            onValueChange = onPortChange,
            enabled = !state.isBusy,
            label = { Text(stringResource(R.string.adb_port)) },
            supportingText = if (state.adbMode == AdbEndpointMode.TELNET) {
                { Text(stringResource(R.string.adb_port_telnet)) }
            } else {
                null
            },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = state.adbEnabled && !state.isBusy,
                colors = atlasButtonColors(),
                shape = RoundedCornerShape(8.dp),
                onClick = onConnect
            ) {
                Text(stringResource(R.string.action_connect))
            }
            OutlinedButton(
                enabled = !state.isBusy,
                shape = RoundedCornerShape(8.dp),
                onClick = onDisconnect
            ) {
                Text(stringResource(R.string.action_disconnect))
            }
        }
    }
}

@Composable
private fun AutostartTab(
    state: CodecFixScreenState,
    onAutoApplyCodecFixChange: (Boolean) -> Unit,
    onAutoApplyDelayChange: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onErrorNotificationsChange: (Boolean) -> Unit
) {
    Section(title = stringResource(R.string.section_auto_apply)) {
        SwitchSettingRow(
            title = stringResource(R.string.auto_codecfix),
            description = stringResource(
                R.string.selected_auto_variant,
                state.effectiveAutoApplyVariant.title
            ),
            checked = state.autoApplyCodecFix,
            enabled = (state.adbEnabled || state.autoApplyCodecFix) && !state.isBusy,
            onCheckedChange = onAutoApplyCodecFixChange
        )

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.autoApplyDelayText,
            onValueChange = onAutoApplyDelayChange,
            enabled = !state.isBusy,
            isError = !state.isAutoApplyDelayValid,
            label = { Text(stringResource(R.string.auto_apply_delay)) },
            supportingText = {
                Text(
                    stringResource(
                        if (state.isAutoApplyDelayValid) {
                            R.string.auto_apply_delay_description
                        } else {
                            R.string.auto_apply_delay_invalid
                        }
                    )
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Text(
            text = stringResource(R.string.auto_apply_accessibility_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isBusy,
            shape = RoundedCornerShape(8.dp),
            onClick = onOpenAccessibilitySettings
        ) {
            Text(stringResource(R.string.open_accessibility_settings))
        }
    }

    Section(title = stringResource(R.string.section_notifications)) {
        SwitchSettingRow(
            title = stringResource(R.string.error_notifications),
            description = stringResource(R.string.error_notifications_description),
            checked = state.errorNotificationsEnabled,
            enabled = !state.isBusy,
            onCheckedChange = onErrorNotificationsChange
        )
    }
}

@Composable
private fun DiagnosticsTab(
    state: CodecFixScreenState,
    onPreflight: () -> Unit,
    onDiagnostics: () -> Unit,
    onExportAnalysis: () -> Unit,
    onSkipCompatibilityCheckChange: (Boolean) -> Unit,
    onLoadCodecs: () -> Unit,
    onHideCodecs: () -> Unit,
    onCodecHardwareChange: (Boolean) -> Unit,
    onCodecSoftwareChange: (Boolean) -> Unit,
    onCodecAudioChange: (Boolean) -> Unit,
    onCodecVideoChange: (Boolean) -> Unit
) {
    Section(title = stringResource(R.string.section_device_checks)) {
        Text(
            text = stringResource(R.string.device_checks_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = state.adbEnabled && !state.isBusy,
            shape = RoundedCornerShape(8.dp),
            onClick = onPreflight
        ) {
            Text(stringResource(R.string.action_run_preflight))
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = state.adbEnabled && !state.isBusy,
            shape = RoundedCornerShape(8.dp),
            onClick = onDiagnostics
        ) {
            Text(stringResource(R.string.action_run_diagnostics))
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = state.adbEnabled && !state.isBusy,
            shape = RoundedCornerShape(8.dp),
            onClick = onExportAnalysis
        ) {
            Text(stringResource(R.string.action_export_analysis))
        }
    }

    CodecListSection(
        state = state,
        onLoadCodecs = onLoadCodecs,
        onHideCodecs = onHideCodecs,
        onCodecHardwareChange = onCodecHardwareChange,
        onCodecSoftwareChange = onCodecSoftwareChange,
        onCodecAudioChange = onCodecAudioChange,
        onCodecVideoChange = onCodecVideoChange
    )

    Section(
        title = stringResource(R.string.section_danger_zone),
        titleColor = MaterialTheme.colorScheme.error,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
    ) {
        SwitchSettingRow(
            title = stringResource(R.string.unsafe_mode),
            description = stringResource(R.string.unsafe_mode_description),
            checked = state.skipCompatibilityCheck,
            enabled = !state.isBusy,
            onCheckedChange = onSkipCompatibilityCheckChange
        )
    }
}

@Composable
private fun SwitchSettingRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RectSwitch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun AdbModeSelector(
    selected: AdbEndpointMode,
    enabled: Boolean,
    onSelected: (AdbEndpointMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(
            AdbEndpointMode.ATLAS,
            AdbEndpointMode.PREFACE,
            AdbEndpointMode.CUSTOM,
            AdbEndpointMode.TELNET
        ).forEach { mode ->
            AdbModeButton(
                modifier = Modifier.weight(1f),
                mode = mode,
                selected = selected == mode,
                enabled = enabled,
                onClick = onSelected
            )
        }
    }
}

@Composable
private fun AdbModeButton(
    modifier: Modifier,
    mode: AdbEndpointMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: (AdbEndpointMode) -> Unit
) {
    val title = when (mode) {
        AdbEndpointMode.ATLAS -> stringResource(R.string.adb_mode_atlas)
        AdbEndpointMode.PREFACE -> stringResource(R.string.adb_mode_preface)
        AdbEndpointMode.CUSTOM -> stringResource(R.string.adb_mode_custom)
        AdbEndpointMode.TELNET -> stringResource(R.string.adb_mode_telnet)
    }
    if (selected) {
        Button(
            modifier = modifier,
            enabled = enabled,
            colors = atlasButtonColors(),
            shape = RoundedCornerShape(8.dp),
            onClick = { onClick(mode) }
        ) {
            Text(title)
        }
    } else {
        OutlinedButton(
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(8.dp),
            onClick = { onClick(mode) }
        ) {
            Text(title)
        }
    }
}

@Composable
private fun StatusHeader(state: CodecFixScreenState) {
    val (label, color) = when (val connection = state.connectionState) {
        AdbConnectionState.Connected -> stringResource(R.string.adb_connected) to MaterialTheme.colorScheme.primary
        AdbConnectionState.Connecting -> stringResource(R.string.adb_connecting) to MaterialTheme.colorScheme.onSurfaceVariant
        AdbConnectionState.Disconnected -> stringResource(R.string.adb_disconnected) to MaterialTheme.colorScheme.outline
        is AdbConnectionState.Error -> stringResource(R.string.adb_error, connection.message) to MaterialTheme.colorScheme.error
    }

    val status = CodecFixScreenRules.headerStatus(state.status)
    // Collapse again whenever a new status arrives so the pinned header stays compact.
    var expanded by rememberSaveable(status) { mutableStateOf(false) }
    var overflowing by remember(status) { mutableStateOf(false) }
    val busyDescription = stringResource(R.string.status_busy)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.heightIn(min = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(
                modifier = Modifier
                    .width(18.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
            Text(
                modifier = Modifier.weight(1f),
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (state.isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(18.dp)
                        .semantics { contentDescription = busyDescription },
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp
                )
            }
        }

        status?.let { text ->
            // Indent to align with the connection label next to the indicator.
            Row(
                modifier = Modifier.padding(start = 28.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (expanded) {
                    Text(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        text = text,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        modifier = Modifier
                            .weight(1f)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        text = text,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { overflowing = it.hasVisualOverflow }
                    )
                }
                if (expanded || overflowing) {
                    TextButton(
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        onClick = { expanded = !expanded }
                    ) {
                        Text(
                            stringResource(
                                if (expanded) R.string.status_show_less else R.string.status_show_more
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CodecListSection(
    state: CodecFixScreenState,
    onLoadCodecs: () -> Unit,
    onHideCodecs: () -> Unit,
    onCodecHardwareChange: (Boolean) -> Unit,
    onCodecSoftwareChange: (Boolean) -> Unit,
    onCodecAudioChange: (Boolean) -> Unit,
    onCodecVideoChange: (Boolean) -> Unit
) {
    val filteredCodecs = state.codecs.filter { codec ->
        val accelerationVisible = when (codec.acceleration) {
            CodecAcceleration.HARDWARE -> state.showHardwareCodecs
            CodecAcceleration.SOFTWARE -> state.showSoftwareCodecs
            CodecAcceleration.UNKNOWN -> state.showHardwareCodecs && state.showSoftwareCodecs
        }
        val audioVisible = state.showAudioCodecs && codec.supportedTypes.any { it.startsWith("audio/") }
        val videoVisible = state.showVideoCodecs && codec.supportedTypes.any { it.startsWith("video/") }
        accelerationVisible && (audioVisible || videoVisible)
    }
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }

    Section(title = stringResource(R.string.section_codecs)) {
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isCodecListLoading,
            colors = atlasButtonColors(),
            shape = RoundedCornerShape(8.dp),
            onClick = onLoadCodecs
        ) {
            Text(
                stringResource(
                    if (state.isCodecListLoading) R.string.collecting_short else R.string.show_codecs
                )
            )
        }

        if (state.isCodecListVisible) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isCodecListLoading,
                shape = RoundedCornerShape(8.dp),
                onClick = onHideCodecs
            ) {
                Text(stringResource(R.string.hide_codecs))
            }

            state.codecListStatus?.takeIf { it.isNotBlank() }?.let { status ->
                Text(
                    text = stringResource(R.string.codecs_shown, status, filteredCodecs.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.codec_filters),
                    fontWeight = FontWeight.Medium
                )
                TextButton(onClick = { filtersExpanded = !filtersExpanded }) {
                    Text(
                        stringResource(
                            if (filtersExpanded) R.string.codec_filters_hide else R.string.codec_filters_show
                        )
                    )
                }
            }

            if (filtersExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterSwitchRow(
                        title = stringResource(R.string.filter_hardware),
                        checked = state.showHardwareCodecs,
                        onCheckedChange = onCodecHardwareChange
                    )
                    FilterSwitchRow(
                        title = stringResource(R.string.filter_software),
                        checked = state.showSoftwareCodecs,
                        onCheckedChange = onCodecSoftwareChange
                    )
                    FilterSwitchRow(
                        title = stringResource(R.string.filter_video),
                        checked = state.showVideoCodecs,
                        onCheckedChange = onCodecVideoChange
                    )
                    FilterSwitchRow(
                        title = stringResource(R.string.filter_audio),
                        checked = state.showAudioCodecs,
                        onCheckedChange = onCodecAudioChange
                    )
                }
            }

            filteredCodecs.forEach { codec ->
                CodecRow(codec)
            }
        }
    }
}

@Composable
private fun FilterSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontWeight = FontWeight.Medium)
        RectSwitch(
            checked = checked,
            enabled = true,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun CodecRow(codec: AvailableCodec) {
    val role = stringResource(if (codec.isEncoder) R.string.codec_encoder else R.string.codec_decoder)
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = codec.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${stringResource(codec.primaryKind.titleRes)} / " +
                    "${stringResource(codec.acceleration.titleRes)} / $role",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Text(
                text = codec.supportedTypes.joinToString(", "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun RectSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Switch(
        checked = checked,
        enabled = enabled,
        onCheckedChange = onCheckedChange
    )
}

@Composable
private fun atlasButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant,
    contentColor = MaterialTheme.colorScheme.onSurface
)

@Composable
fun Section(
    title: String,
    titleColor: Color = Color.Unspecified,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = border
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                modifier = Modifier.semantics { heading() },
                text = title,
                color = titleColor,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            content()
        }
    }
}

@Composable
fun VariantButton(
    variant: HevcCodecFixVariant,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = if (selected) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    } else {
        ButtonDefaults.outlinedButtonColors()
    }

    OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        colors = colors,
        shape = RoundedCornerShape(8.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(variant.title, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(variant.descriptionRes), fontSize = 12.sp)
        }
    }
}
