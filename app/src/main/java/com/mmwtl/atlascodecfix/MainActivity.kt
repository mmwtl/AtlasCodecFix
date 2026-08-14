package com.mmwtl.atlascodecfix

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        StatusHeader(state)

        Section(title = stringResource(R.string.section_adb)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.adb_helper), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.adb_helper_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RectSwitch(
                    checked = state.adbEnabled,
                    enabled = !state.isBusy,
                    onCheckedChange = onAdbEnabledChange
                )
            }

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

            AdbModeSelector(
                selected = state.adbMode,
                enabled = !state.isBusy,
                onSelected = onAdbModeChange
            )

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.adbPortText,
                onValueChange = onPortChange,
                enabled = !state.isBusy && state.adbMode != AdbEndpointMode.TELNET,
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

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = state.adbEnabled && !state.isBusy,
                    colors = atlasButtonColors(),
                    shape = RoundedCornerShape(8.dp),
                    onClick = onApply
                ) {
                    Text(stringResource(R.string.action_apply))
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

        Section(title = stringResource(R.string.section_auto_apply)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.auto_codecfix), fontWeight = FontWeight.Medium)
                    Text(
                        text = stringResource(
                            R.string.selected_auto_variant,
                            state.effectiveAutoApplyVariant.title
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RectSwitch(
                    checked = state.autoApplyCodecFix,
                    enabled = (state.adbEnabled || state.autoApplyCodecFix) && !state.isBusy,
                    onCheckedChange = onAutoApplyCodecFixChange
                )
            }

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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.unsafe_mode), fontWeight = FontWeight.Medium)
                    Text(
                        text = stringResource(R.string.unsafe_mode_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RectSwitch(
                    checked = state.skipCompatibilityCheck,
                    enabled = !state.isBusy,
                    onCheckedChange = onSkipCompatibilityCheckChange
                )
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

        Section(title = stringResource(R.string.section_errors)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.error_notifications), fontWeight = FontWeight.Medium)
                    Text(
                        text = stringResource(R.string.error_notifications_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RectSwitch(
                    checked = state.errorNotificationsEnabled,
                    enabled = !state.isBusy,
                    onCheckedChange = onErrorNotificationsChange
                )
            }
        }

        state.status?.takeIf { it.isNotBlank() }?.let { status ->
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
            ) {
                Text(
                    modifier = Modifier.padding(14.dp),
                    text = status,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AdbModeSelector(
    selected: AdbEndpointMode,
    enabled: Boolean,
    onSelected: (AdbEndpointMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AdbModeButton(
                modifier = Modifier.weight(1f),
                mode = AdbEndpointMode.ATLAS,
                selected = selected == AdbEndpointMode.ATLAS,
                enabled = enabled,
                onClick = onSelected
            )
            AdbModeButton(
                modifier = Modifier.weight(1f),
                mode = AdbEndpointMode.PREFACE,
                selected = selected == AdbEndpointMode.PREFACE,
                enabled = enabled,
                onClick = onSelected
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AdbModeButton(
                modifier = Modifier.weight(1f),
                mode = AdbEndpointMode.CUSTOM,
                selected = selected == AdbEndpointMode.CUSTOM,
                enabled = enabled,
                onClick = onSelected
            )
            AdbModeButton(
                modifier = Modifier.weight(1f),
                mode = AdbEndpointMode.TELNET,
                selected = selected == AdbEndpointMode.TELNET,
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
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
        Text(text = label, color = MaterialTheme.colorScheme.onSurface)
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

            state.codecListStatus?.takeIf { it.isNotBlank() }?.let { status ->
                Text(
                    text = stringResource(R.string.codecs_shown, status, filteredCodecs.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
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
