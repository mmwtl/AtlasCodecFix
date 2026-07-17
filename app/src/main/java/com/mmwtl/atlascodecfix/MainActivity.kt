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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
                    onPortChange = viewModel::setAdbPort,
                    onConnect = viewModel::connectAdb,
                    onDisconnect = viewModel::disconnectAdb,
                    onVariantSelected = viewModel::selectVariant,
                    onRefresh = viewModel::refreshCurrentVariant,
                    onPreflight = viewModel::runPreflightCheck,
                    onDiagnostics = viewModel::runDiagnostics,
                    onAutoApplyChange = viewModel::setAutoApply,
                    onSkipCompatibilityCheckChange = viewModel::setSkipCompatibilityCheck,
                    onLoadCodecs = viewModel::loadAvailableCodecs,
                    onCodecHardwareChange = viewModel::setCodecHardwareFilter,
                    onCodecSoftwareChange = viewModel::setCodecSoftwareFilter,
                    onCodecAudioChange = viewModel::setCodecAudioFilter,
                    onCodecVideoChange = viewModel::setCodecVideoFilter,
                    onErrorNotificationsChange = ::setErrorNotificationsEnabled
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
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onVariantSelected: (HevcCodecFixVariant) -> Unit,
    onRefresh: () -> Unit,
    onPreflight: () -> Unit,
    onDiagnostics: () -> Unit,
    onAutoApplyChange: (Boolean) -> Unit,
    onSkipCompatibilityCheckChange: (Boolean) -> Unit,
    onLoadCodecs: () -> Unit,
    onCodecHardwareChange: (Boolean) -> Unit,
    onCodecSoftwareChange: (Boolean) -> Unit,
    onCodecAudioChange: (Boolean) -> Unit,
    onCodecVideoChange: (Boolean) -> Unit,
    onErrorNotificationsChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold
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

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.adbPortText,
                onValueChange = onPortChange,
                enabled = !state.isBusy,
                label = { Text(stringResource(R.string.adb_port)) },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = state.adbEnabled && !state.isBusy,
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
        }

        Section(title = stringResource(R.string.section_auto_profile)) {
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
                    Text(stringResource(R.string.apply_after_boot), fontWeight = FontWeight.Medium)
                    Text(
                        text = stringResource(R.string.selected_auto_variant, state.selectedVariant.title),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RectSwitch(
                    checked = state.autoApply,
                    enabled = state.adbEnabled || state.autoApply,
                    onCheckedChange = onAutoApplyChange
                )
            }

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
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
private fun StatusHeader(state: CodecFixScreenState) {
    val (label, color) = when (val connection = state.connectionState) {
        AdbConnectionState.Connected -> stringResource(R.string.adb_connected) to Color(0xFF22C55E)
        AdbConnectionState.Connecting -> stringResource(R.string.adb_connecting) to Color(0xFFF59E0B)
        AdbConnectionState.Disconnected -> stringResource(R.string.adb_disconnected) to Color(0xFF94A3B8)
        is AdbConnectionState.Error -> stringResource(R.string.adb_error, connection.message) to Color(0xFFEF4444)
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
            shape = RoundedCornerShape(8.dp),
            onClick = onLoadCodecs
        ) {
            Text(
                stringResource(
                    if (state.isCodecListLoading) R.string.collecting_short else R.string.show_codecs
                )
            )
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
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
fun Section(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
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
