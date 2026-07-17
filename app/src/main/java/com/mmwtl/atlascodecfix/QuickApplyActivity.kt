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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
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
                    onApply = ::requestApply,
                    onPreflight = ::runPreflight,
                    onDiagnostics = ::runDiagnostics,
                    onConfirmApply = ::confirmApply,
                    onDismissConfirmation = ::dismissConfirmation
                )
            }
        }

        refreshCurrentVariant()
    }

    private fun refreshCurrentVariant() {
        lifecycleScope.launch {
            if (!appContainer.prefs.adbEnabled) {
                val status = getString(R.string.adb_disabled_message)
                notifyError(getString(R.string.error_title_adb_disabled), status)
                screenState = screenState.copy(
                    isBusy = false,
                    status = status
                )
                return@launch
            }

            screenState = screenState.copy(isBusy = true, status = getString(R.string.checking_current_profile))
            val detected = withContext(Dispatchers.IO) {
                appContainer.codecFixRepository.detectCurrentVariant()
            }
            screenState = screenState.copy(
                currentVariant = detected.variant,
                isBusy = false,
                status = if (detected.commandSuccess) {
                    null
                } else {
                    val status = detected.output.trim().takeLast(220)
                        .ifBlank { getString(R.string.check_not_completed) }
                    notifyError(getString(R.string.error_title_profile_check), status)
                    status
                }
            )
        }
    }

    private fun requestApply(variant: HevcCodecFixVariant) {
        if (variant.experimental && !appContainer.prefs.skipCompatibilityCheck) {
            screenState = screenState.copy(
                confirmation = ApplyConfirmation(variant, ConfirmationReason.EXPERIMENTAL),
                status = getString(R.string.experimental_warning_short, variant.title)
            )
            return
        }
        applyVariant(
            variant = variant,
            allowRisky = appContainer.prefs.skipCompatibilityCheck,
            allowExperimental = appContainer.prefs.skipCompatibilityCheck
        )
    }

    private fun runPreflight() {
        lifecycleScope.launch {
            if (!appContainer.prefs.adbEnabled) {
                val status = getString(R.string.adb_disabled_message)
                notifyError(getString(R.string.error_title_adb_disabled), status)
                screenState = screenState.copy(status = status)
                return@launch
            }

            screenState = screenState.copy(
                isBusy = true,
                status = getString(R.string.running_preflight)
            )
            val result = withContext(Dispatchers.IO) {
                appContainer.codecFixRepository.checkCompatibility()
            }
            val report = result.output.trim().takeLast(PREFLIGHT_REPORT_LIMIT)
                .ifBlank { getString(R.string.preflight_no_output) }
            if (!result.commandSuccess) {
                notifyError(getString(R.string.error_title_profile_check), report)
            }
            screenState = screenState.copy(
                currentVariant = result.variant ?: screenState.currentVariant,
                isBusy = false,
                status = report
            )
        }
    }

    private fun runDiagnostics() {
        lifecycleScope.launch {
            if (!appContainer.prefs.adbEnabled) {
                val status = getString(R.string.adb_disabled_message)
                notifyError(getString(R.string.error_title_adb_disabled), status)
                screenState = screenState.copy(status = status)
                return@launch
            }

            screenState = screenState.copy(
                isBusy = true,
                status = getString(R.string.running_diagnostics)
            )
            val result = withContext(Dispatchers.IO) {
                appContainer.codecFixRepository.collectDiagnostics()
            }
            val report = result.output.trim().takeLast(DIAGNOSTICS_REPORT_LIMIT)
                .ifBlank { getString(R.string.diagnostics_no_output) }
            if (!result.commandSuccess) {
                notifyError(getString(R.string.error_title_diagnostics), report)
            }
            screenState = screenState.copy(
                currentVariant = result.variant ?: screenState.currentVariant,
                isBusy = false,
                status = report
            )
        }
    }

    private fun confirmApply() {
        val confirmation = screenState.confirmation ?: return
        screenState = screenState.copy(confirmation = null)
        applyVariant(
            variant = confirmation.variant,
            allowRisky = true,
            allowExperimental = true
        )
    }

    private fun dismissConfirmation() {
        screenState = screenState.copy(confirmation = null)
    }

    private fun applyVariant(
        variant: HevcCodecFixVariant,
        allowRisky: Boolean,
        allowExperimental: Boolean
    ) {
        lifecycleScope.launch {
            if (!appContainer.prefs.adbEnabled) {
                val status = getString(R.string.adb_disabled_message)
                notifyError(getString(R.string.error_title_adb_disabled), status)
                screenState = screenState.copy(status = status)
                return@launch
            }

            screenState = screenState.copy(
                selectedVariant = variant,
                isBusy = true,
                status = getString(R.string.applying_variant, variant.title)
            )

            val result = withContext(Dispatchers.IO) {
                appContainer.codecFixRepository.applyVariant(
                    variant = variant,
                    allowRisky = allowRisky,
                    skipCompatibilityCheck = appContainer.prefs.skipCompatibilityCheck,
                    allowExperimental = allowExperimental
                )
            }

            if (!result.success &&
                !allowRisky &&
                result.compatibility?.status == HevcCodecFixCompatibilityStatus.RISKY
            ) {
                screenState = screenState.copy(
                    isBusy = false,
                    confirmation = ApplyConfirmation(variant, ConfirmationReason.RISKY),
                    status = result.compatibility.reason ?: getString(R.string.compatibility_not_confirmed)
                )
                return@launch
            }

            if (result.success) appContainer.prefs.selectedVariant = variant

            screenState = screenState.copy(
                selectedVariant = if (result.success) variant else appContainer.prefs.selectedVariant,
                currentVariant = result.detectedVariant,
                isBusy = false,
                status = if (result.success) {
                    getString(R.string.applied_variant, variant.title)
                } else {
                    val status = listOf(
                        result.runOutput,
                        result.detectOutput,
                        if (result.restoredToDefault) {
                            getString(R.string.automatic_restore_succeeded)
                        } else {
                            result.recoveryOutput
                        }
                    )
                        .filter(String::isNotBlank)
                        .joinToString("\n")
                        .trim()
                        .takeLast(260)
                        .ifBlank { getString(R.string.apply_failed) }
                    notifyError(getString(R.string.error_title_apply_failed, variant.title), status)
                    status
                }
            )
        }
    }

    private fun notifyError(title: String, message: String) {
        appContainer.errorNotifier.notify(title, message)
    }

    private companion object {
        private const val PREFLIGHT_REPORT_LIMIT = 2_000
        private const val DIAGNOSTICS_REPORT_LIMIT = 6_000
    }
}

private data class QuickApplyState(
    val selectedVariant: HevcCodecFixVariant = HevcCodecFixVariant.DEFAULT,
    val currentVariant: HevcCodecFixVariant? = null,
    val isBusy: Boolean = false,
    val status: String? = null,
    val confirmation: ApplyConfirmation? = null
)

private data class ApplyConfirmation(
    val variant: HevcCodecFixVariant,
    val reason: ConfirmationReason
)

private enum class ConfirmationReason {
    EXPERIMENTAL,
    RISKY
}

@Composable
private fun QuickApplyScreen(
    state: QuickApplyState,
    onDismiss: () -> Unit,
    onApply: (HevcCodecFixVariant) -> Unit,
    onPreflight: () -> Unit,
    onDiagnostics: () -> Unit,
    onConfirmApply: () -> Unit,
    onDismissConfirmation: () -> Unit
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
                text = stringResource(R.string.quick_title),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = stringResource(
                    R.string.current_variant,
                    state.currentVariant?.title ?: stringResource(R.string.variant_unknown)
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    selected = state.currentVariant == variant || state.selectedVariant == variant,
                    enabled = !state.isBusy,
                    onClick = { onApply(variant) }
                )
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isBusy,
                shape = RoundedCornerShape(8.dp),
                onClick = onPreflight
            ) {
                Text(stringResource(R.string.action_run_preflight))
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isBusy,
                shape = RoundedCornerShape(8.dp),
                onClick = onDiagnostics
            ) {
                Text(stringResource(R.string.action_run_diagnostics))
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

    state.confirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = onDismissConfirmation,
            title = { Text(stringResource(R.string.risk_confirmation_title)) },
            text = {
                Text(
                    when (confirmation.reason) {
                        ConfirmationReason.EXPERIMENTAL -> stringResource(
                            R.string.experimental_warning,
                            confirmation.variant.title
                        )
                        ConfirmationReason.RISKY -> stringResource(
                            R.string.risky_warning,
                            confirmation.variant.title
                        )
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmApply) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissConfirmation) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
