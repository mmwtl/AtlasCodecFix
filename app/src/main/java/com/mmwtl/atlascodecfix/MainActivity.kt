package com.mmwtl.atlascodecfix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val appContainer: CodecFixApp
        get() = application as CodecFixApp

    private var screenState by mutableStateOf(CodecFixScreenState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = appContainer.prefs
        screenState = screenState.copy(
            adbEnabled = prefs.adbEnabled,
            adbPortText = prefs.adbPort.toString(),
            autoApply = prefs.autoApply,
            selectedVariant = prefs.selectedVariant
        )

        lifecycleScope.launch {
            appContainer.adbClient.connectionState.collect { state ->
                screenState = screenState.copy(connectionState = state)
            }
        }

        setContent {
            MaterialTheme(colorScheme = appColors) {
                CodecFixScreen(
                    state = screenState,
                    onAdbEnabledChange = ::setAdbEnabled,
                    onPortChange = ::setAdbPort,
                    onConnect = ::connectAdb,
                    onDisconnect = ::disconnectAdb,
                    onVariantSelected = ::selectVariant,
                    onRefresh = ::refreshCurrentVariant,
                    onAutoApplyChange = ::setAutoApply
                )
            }
        }

        if (prefs.adbEnabled) {
            connectAdb()
            refreshCurrentVariant()
        }
    }

    private fun setAdbEnabled(enabled: Boolean) {
        appContainer.prefs.adbEnabled = enabled
        screenState = screenState.copy(adbEnabled = enabled)
        if (enabled) connectAdb() else disconnectAdb()
    }

    private fun setAdbPort(text: String) {
        val sanitized = text.filter(Char::isDigit).take(5)
        screenState = screenState.copy(adbPortText = sanitized)
        val port = sanitized.toIntOrNull()?.takeIf { it in 1..65535 } ?: return
        appContainer.prefs.adbPort = port
    }

    private fun connectAdb() {
        lifecycleScope.launch {
            val connected = withContext(Dispatchers.IO) { appContainer.adbClient.connect() }
            screenState = screenState.copy(
                status = if (connected) "ADB подключён" else "Не удалось подключиться к ADB"
            )
        }
    }

    private fun disconnectAdb() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { appContainer.adbClient.disconnect() }
            screenState = screenState.copy(status = "ADB отключён")
        }
    }

    private fun selectVariant(variant: HevcCodecFixVariant) {
        appContainer.prefs.selectedVariant = variant
        screenState = screenState.copy(selectedVariant = variant)
    }

    private fun setAutoApply(enabled: Boolean) {
        lifecycleScope.launch {
            if (enabled) {
                screenState = screenState.copy(isBusy = true, status = "Проверяю совместимость")
                val compatibility = withContext(Dispatchers.IO) {
                    appContainer.codecFixRepository.checkCompatibility()
                }
                if (!compatibility.autoApplyAllowed) {
                    appContainer.prefs.autoApply = false
                    screenState = screenState.copy(
                        autoApply = false,
                        isBusy = false,
                        status = compatibility.output.trim().takeLast(220)
                            .ifBlank { "Автоприменение недоступно для этого устройства" }
                    )
                    return@launch
                }
            }

            appContainer.prefs.autoApply = enabled
            screenState = screenState.copy(
                autoApply = enabled,
                isBusy = false,
                status = if (enabled) {
                    "Автоприменение включено для ${screenState.selectedVariant.title}"
                } else {
                    "Автоприменение выключено"
                }
            )
        }
    }

    private fun refreshCurrentVariant() {
        lifecycleScope.launch {
            screenState = screenState.copy(isBusy = true, status = "Проверяю текущий фикс")
            val detected = withContext(Dispatchers.IO) {
                appContainer.codecFixRepository.detectCurrentVariant()
            }
            screenState = screenState.copy(
                currentVariant = detected.variant,
                isBusy = false,
                status = if (detected.commandSuccess) {
                    "Текущий вариант: ${detected.variant?.title ?: "не определён"}"
                } else {
                    detected.output.trim().takeLast(220).ifBlank { "Проверка не выполнена" }
                }
            )
        }
    }

}

private data class CodecFixScreenState(
    val adbEnabled: Boolean = false,
    val adbPortText: String = "5555",
    val autoApply: Boolean = false,
    val selectedVariant: HevcCodecFixVariant = HevcCodecFixVariant.DEFAULT,
    val currentVariant: HevcCodecFixVariant? = null,
    val connectionState: AdbConnectionState = AdbConnectionState.Disconnected,
    val isBusy: Boolean = false,
    val status: String? = null
)

@Composable
private fun CodecFixScreen(
    state: CodecFixScreenState,
    onAdbEnabledChange: (Boolean) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onVariantSelected: (HevcCodecFixVariant) -> Unit,
    onRefresh: () -> Unit,
    onAutoApplyChange: (Boolean) -> Unit
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
            text = "Atlas Codec Fix",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold
        )

        StatusHeader(state)

        Section(title = "ADB подключение") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ADB helper", fontWeight = FontWeight.Medium)
                    Text("Подключение к локальному adbd", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RectSwitch(
                    checked = state.adbEnabled,
                    enabled = !state.isBusy,
                    onCheckedChange = onAdbEnabledChange
                )
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.adbPortText,
                onValueChange = onPortChange,
                enabled = !state.isBusy,
                label = { Text("Порт ADB") },
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
                    Text("Подключить")
                }
                OutlinedButton(
                    enabled = !state.isBusy,
                    shape = RoundedCornerShape(8.dp),
                    onClick = onDisconnect
                ) {
                    Text("Отключить")
                }
            }
        }

        Section(title = "Профиль автоприменения") {
            Text(
                text = "Текущий вариант: ${state.currentVariant?.title ?: "не определён"}",
                fontWeight = FontWeight.Medium
            )

            HevcCodecFixVariant.USER_VISIBLE.forEach { variant ->
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
                    Text("Проверить")
                }
            }
        }

        Section(title = "Автоприменение") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Применять после загрузки", fontWeight = FontWeight.Medium)
                    Text(
                        text = "Будет использован выбранный вариант: ${state.selectedVariant.title}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RectSwitch(
                    checked = state.autoApply,
                    enabled = state.adbEnabled || state.autoApply,
                    onCheckedChange = onAutoApplyChange
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
        AdbConnectionState.Connected -> "ADB подключён" to Color(0xFF22C55E)
        AdbConnectionState.Connecting -> "ADB подключается" to Color(0xFFF59E0B)
        AdbConnectionState.Disconnected -> "ADB отключён" to Color(0xFF94A3B8)
        is AdbConnectionState.Error -> "ADB ошибка: ${connection.message}" to Color(0xFFEF4444)
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
fun RectSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val trackColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        checked -> MaterialTheme.colorScheme.primary
        else -> Color(0xFF3A3A3A)
    }
    val thumbColor = if (checked) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .width(46.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(trackColor)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(4.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(thumbColor)
        )
    }
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
            Text(variant.description, fontSize = 12.sp)
        }
    }
}

val appColors = darkColorScheme(
    primary = Color(0xFF7893A0),
    onPrimary = Color(0xFF071014),
    background = Color(0xFF171717),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF262626),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF333333),
    onSurfaceVariant = Color(0xFFD4D4D4),
    outline = Color(0xFF737373)
)
