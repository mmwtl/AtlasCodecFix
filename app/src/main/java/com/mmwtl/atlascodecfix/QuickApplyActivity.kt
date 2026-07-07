package com.mmwtl.atlascodecfix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickApplyActivity : ComponentActivity() {
    private val appContainer: CodecFixApp
        get() = application as CodecFixApp

    private var screenState by mutableStateOf(QuickApplyState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        screenState = screenState.copy(selectedVariant = appContainer.prefs.selectedVariant)

        setContent {
            AtlasCodecFixTheme {
                QuickApplyScreen(
                    state = screenState,
                    onDismiss = ::finish,
                    onApply = ::applyVariant
                )
            }
        }

        refreshCurrentVariant()
    }

    private fun refreshCurrentVariant() {
        lifecycleScope.launch {
            if (!appContainer.prefs.adbEnabled) {
                val status = "ADB выключен. Включите его в основном приложении."
                notifyError("ADB выключен", status)
                screenState = screenState.copy(
                    isBusy = false,
                    status = status
                )
                return@launch
            }

            screenState = screenState.copy(isBusy = true, status = "Проверяю текущий профиль")
            val detected = withContext(Dispatchers.IO) {
                appContainer.codecFixRepository.detectCurrentVariant()
            }
            screenState = screenState.copy(
                currentVariant = detected.variant,
                isBusy = false,
                status = if (detected.commandSuccess) {
                    null
                } else {
                    val status = detected.output.trim().takeLast(220).ifBlank { "Проверка не выполнена" }
                    notifyError("Проверка профиля", status)
                    status
                }
            )
        }
    }

    private fun applyVariant(variant: HevcCodecFixVariant) {
        lifecycleScope.launch {
            if (!appContainer.prefs.adbEnabled) {
                val status = "ADB выключен. Включите его в основном приложении."
                notifyError("ADB выключен", status)
                screenState = screenState.copy(status = status)
                return@launch
            }

            screenState = screenState.copy(
                selectedVariant = variant,
                isBusy = true,
                status = "Применяю ${variant.title}"
            )
            appContainer.prefs.selectedVariant = variant

            val result = withContext(Dispatchers.IO) {
                appContainer.codecFixRepository.applyVariant(
                    variant = variant,
                    skipCompatibilityCheck = appContainer.prefs.skipCompatibilityCheck
                )
            }

            screenState = screenState.copy(
                currentVariant = result.detectedVariant,
                isBusy = false,
                status = if (result.success) {
                    "Применено: ${variant.title}"
                } else {
                    val status = listOf(result.runOutput, result.detectOutput)
                        .joinToString("\n")
                        .trim()
                        .takeLast(260)
                        .ifBlank { "Не удалось применить профиль" }
                    notifyError("Не удалось применить ${variant.title}", status)
                    status
                }
            )
        }
    }

    private fun notifyError(title: String, message: String) {
        appContainer.errorNotifier.notify(title, message)
    }
}

private data class QuickApplyState(
    val selectedVariant: HevcCodecFixVariant = HevcCodecFixVariant.DEFAULT,
    val currentVariant: HevcCodecFixVariant? = null,
    val isBusy: Boolean = false,
    val status: String? = null
)

@Composable
private fun QuickApplyScreen(
    state: QuickApplyState,
    onDismiss: () -> Unit,
    onApply: (HevcCodecFixVariant) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .clickable(onClick = onDismiss)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.background)
                .clickable(onClick = {})
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Профиль кодеков",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "Текущий вариант: ${state.currentVariant?.title ?: "не определён"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )

            HevcCodecFixVariant.USER_VISIBLE.forEach { variant ->
                VariantButton(
                    variant = variant,
                    selected = state.currentVariant == variant || state.selectedVariant == variant,
                    enabled = !state.isBusy,
                    onClick = { onApply(variant) }
                )
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
}
