package com.mmwtl.atlascodecfix

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CodecFixViewModel(
    private val app: CodecFixApp
) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<CodecFixScreenState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            app.adbClient.connectionState.collect { connectionState ->
                _state.update { it.copy(connectionState = connectionState) }
            }
        }
        if (app.prefs.adbEnabled) {
            connectAdb()
            refreshCurrentVariant()
        }
    }

    fun setAdbEnabled(enabled: Boolean) {
        app.prefs.adbEnabled = enabled
        _state.update { it.copy(adbEnabled = enabled) }
        if (enabled) {
            connectAdb()
        } else {
            AutoApplyScheduler.cancel(app)
            disconnectAdb()
        }
    }

    fun setAdbHost(text: String) {
        val sanitized = text.trim().take(MAX_HOST_LENGTH)
        _state.update { it.copy(adbHostText = sanitized) }
        if (sanitized.isNotBlank()) app.prefs.adbHost = sanitized
    }

    fun setAdbPort(text: String) {
        val sanitized = text.filter(Char::isDigit).take(5)
        _state.update { it.copy(adbPortText = sanitized) }
        val port = sanitized.toIntOrNull()?.takeIf { it in 1..65535 } ?: return
        app.prefs.adbPort = port
    }

    fun connectAdb() {
        viewModelScope.launch {
            val endpoint = _state.value
            val port = endpoint.adbPortText.toIntOrNull()
            if (endpoint.adbHostText.isBlank() || port == null || port !in 1..65535) {
                _state.update { it.copy(status = text(R.string.adb_endpoint_invalid)) }
                return@launch
            }

            app.adbClient.disconnect()
            val connected = app.adbClient.connect()
            val status = text(if (connected) R.string.adb_connected else R.string.adb_connect_failed)
            if (!connected) notifyError(text(R.string.error_title_adb_connection), status)
            _state.update { it.copy(status = status) }
        }
    }

    fun disconnectAdb() {
        viewModelScope.launch {
            app.adbClient.disconnect()
            _state.update { it.copy(status = text(R.string.adb_disconnected)) }
        }
    }

    fun selectVariant(variant: HevcCodecFixVariant) {
        app.prefs.selectedVariant = variant
        var autoApplyDisabled = false
        if (variant.experimental && app.prefs.autoApply && !app.prefs.skipCompatibilityCheck) {
            app.prefs.autoApply = false
            AutoApplyScheduler.cancel(app)
            autoApplyDisabled = true
        }
        _state.update {
            it.copy(
                selectedVariant = variant,
                autoApply = if (autoApplyDisabled) false else it.autoApply,
                status = if (autoApplyDisabled) {
                    text(R.string.auto_disabled_for_experimental, variant.title)
                } else {
                    it.status
                }
            )
        }
    }

    fun setAutoApply(enabled: Boolean) {
        viewModelScope.launch {
            val current = _state.value
            if (enabled && !current.adbEnabled) {
                _state.update { it.copy(autoApply = false, status = text(R.string.enable_adb_first)) }
                return@launch
            }
            if (enabled && current.selectedVariant.experimental && !current.skipCompatibilityCheck) {
                _state.update {
                    it.copy(
                        autoApply = false,
                        status = text(R.string.auto_requires_unsafe, current.selectedVariant.title)
                    )
                }
                return@launch
            }

            if (enabled && !current.skipCompatibilityCheck) {
                _state.update { it.copy(isBusy = true, status = text(R.string.checking_compatibility)) }
                val compatibility = app.codecFixRepository.checkCompatibility()
                if (!compatibility.autoApplyAllowed) {
                    val status = compatibility.output.trim().takeLast(STATUS_TEXT_LIMIT)
                        .ifBlank { text(R.string.auto_unavailable) }
                    app.prefs.autoApply = false
                    notifyError(text(R.string.error_title_auto_unavailable), status)
                    _state.update { it.copy(autoApply = false, isBusy = false, status = status) }
                    return@launch
                }
            }

            app.prefs.autoApply = enabled
            app.prefs.autoApplyRetryCount = 0
            if (!enabled) AutoApplyScheduler.cancel(app)
            _state.update {
                it.copy(
                    autoApply = enabled,
                    isBusy = false,
                    status = if (enabled) {
                        text(R.string.auto_enabled_for, it.selectedVariant.title)
                    } else {
                        text(R.string.auto_disabled)
                    }
                )
            }
        }
    }

    fun setSkipCompatibilityCheck(enabled: Boolean) {
        app.prefs.skipCompatibilityCheck = enabled
        var autoApplyDisabled = false
        if (!enabled && app.prefs.autoApply && app.prefs.selectedVariant.experimental) {
            app.prefs.autoApply = false
            AutoApplyScheduler.cancel(app)
            autoApplyDisabled = true
        }
        _state.update {
            it.copy(
                skipCompatibilityCheck = enabled,
                autoApply = if (autoApplyDisabled) false else it.autoApply,
                status = when {
                    autoApplyDisabled -> text(R.string.unsafe_disabled_auto_disabled)
                    enabled -> text(R.string.unsafe_enabled)
                    else -> text(R.string.compatibility_enabled)
                }
            )
        }
    }

    fun setErrorNotificationsEnabled(enabled: Boolean) {
        app.prefs.errorNotificationsEnabled = enabled
        _state.update {
            it.copy(
                errorNotificationsEnabled = enabled,
                status = if (enabled) {
                    text(R.string.notifications_enabled)
                } else {
                    text(R.string.notifications_disabled)
                }
            )
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        app.prefs.errorNotificationsEnabled = granted
        _state.update {
            it.copy(
                errorNotificationsEnabled = granted,
                status = if (granted) {
                    text(R.string.notifications_enabled)
                } else {
                    text(R.string.notification_permission_denied)
                }
            )
        }
    }

    fun refreshCurrentVariant() {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, status = text(R.string.checking_current_fix)) }
            val detected = app.codecFixRepository.detectCurrentVariant()
            val status = if (detected.commandSuccess) {
                text(
                    R.string.current_variant,
                    detected.variant?.title ?: text(R.string.variant_unknown)
                )
            } else {
                detected.output.trim().takeLast(STATUS_TEXT_LIMIT)
                    .ifBlank { text(R.string.check_not_completed) }
            }
            if (!detected.commandSuccess) notifyError(text(R.string.error_title_fix_check), status)
            _state.update {
                it.copy(
                    currentVariant = detected.variant,
                    isBusy = false,
                    status = status
                )
            }
        }
    }

    fun runPreflightCheck() {
        viewModelScope.launch {
            if (!_state.value.adbEnabled) {
                _state.update { it.copy(status = text(R.string.adb_disabled_message)) }
                return@launch
            }

            _state.update { it.copy(isBusy = true, status = text(R.string.running_preflight)) }
            val result = app.codecFixRepository.checkCompatibility()
            val report = result.output.trim().takeLast(PREFLIGHT_REPORT_LIMIT)
                .ifBlank { text(R.string.preflight_no_output) }
            if (!result.commandSuccess) {
                notifyError(text(R.string.error_title_profile_check), report)
            }
            _state.update {
                it.copy(
                    currentVariant = result.variant ?: it.currentVariant,
                    isBusy = false,
                    status = report
                )
            }
        }
    }

    fun runDiagnostics() {
        viewModelScope.launch {
            if (!_state.value.adbEnabled) {
                _state.update { it.copy(status = text(R.string.adb_disabled_message)) }
                return@launch
            }

            _state.update { it.copy(isBusy = true, status = text(R.string.running_diagnostics)) }
            val result = app.codecFixRepository.collectDiagnostics()
            val report = result.output.trim().takeLast(DIAGNOSTICS_REPORT_LIMIT)
                .ifBlank { text(R.string.diagnostics_no_output) }
            if (!result.commandSuccess) {
                notifyError(text(R.string.error_title_diagnostics), report)
            }
            _state.update {
                it.copy(
                    currentVariant = result.variant ?: it.currentVariant,
                    isBusy = false,
                    status = report
                )
            }
        }
    }

    fun loadAvailableCodecs() {
        viewModelScope.launch {
            _state.update {
                it.copy(isCodecListLoading = true, codecListStatus = text(R.string.collecting_codecs))
            }
            val result = withContext(Dispatchers.Default) {
                runCatching { collectAvailableCodecs() }
            }
            result
                .onSuccess { codecs ->
                    _state.update {
                        it.copy(
                            codecs = codecs,
                            isCodecListLoading = false,
                            codecListStatus = text(R.string.codecs_found, codecs.size)
                        )
                    }
                }
                .onFailure { t ->
                    val status = t.message ?: t.javaClass.simpleName
                    notifyError(text(R.string.error_title_codec_list), status)
                    _state.update { it.copy(isCodecListLoading = false, codecListStatus = status) }
                }
        }
    }

    fun setCodecHardwareFilter(enabled: Boolean) = _state.update { it.copy(showHardwareCodecs = enabled) }
    fun setCodecSoftwareFilter(enabled: Boolean) = _state.update { it.copy(showSoftwareCodecs = enabled) }
    fun setCodecAudioFilter(enabled: Boolean) = _state.update { it.copy(showAudioCodecs = enabled) }
    fun setCodecVideoFilter(enabled: Boolean) = _state.update { it.copy(showVideoCodecs = enabled) }

    private fun initialState(): CodecFixScreenState {
        val prefs = app.prefs
        return CodecFixScreenState(
            adbEnabled = prefs.adbEnabled,
            adbHostText = prefs.adbHost,
            adbPortText = prefs.adbPort.toString(),
            autoApply = prefs.autoApply,
            skipCompatibilityCheck = prefs.skipCompatibilityCheck,
            errorNotificationsEnabled = prefs.errorNotificationsEnabled,
            selectedVariant = prefs.selectedVariant
        )
    }

    private fun collectAvailableCodecs(): List<AvailableCodec> {
        return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .map { info ->
                AvailableCodec(
                    name = info.name,
                    supportedTypes = info.supportedTypes.map(String::lowercase).sorted(),
                    isEncoder = info.isEncoder,
                    acceleration = info.accelerationKind()
                )
            }
            .sortedWith(
                compareBy<AvailableCodec> { it.primaryKind.sortOrder }
                    .thenBy { it.acceleration.sortOrder }
                    .thenBy { it.name.lowercase() }
            )
    }

    private fun notifyError(title: String, message: String) {
        app.errorNotifier.notify(title, message)
    }

    private fun text(@StringRes resource: Int, vararg arguments: Any): String {
        return app.getString(resource, *arguments)
    }

    class Factory(
        private val app: CodecFixApp
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CodecFixViewModel::class.java))
            return CodecFixViewModel(app) as T
        }
    }

    private companion object {
        private const val MAX_HOST_LENGTH = 253
        private const val PREFLIGHT_REPORT_LIMIT = 2_000
        private const val DIAGNOSTICS_REPORT_LIMIT = 6_000
        private const val STATUS_TEXT_LIMIT = 220
    }
}

data class CodecFixScreenState(
    val adbEnabled: Boolean = false,
    val adbHostText: String = "localhost",
    val adbPortText: String = "5555",
    val autoApply: Boolean = false,
    val skipCompatibilityCheck: Boolean = false,
    val errorNotificationsEnabled: Boolean = false,
    val selectedVariant: HevcCodecFixVariant = HevcCodecFixVariant.DEFAULT,
    val currentVariant: HevcCodecFixVariant? = null,
    val codecs: List<AvailableCodec> = emptyList(),
    val isCodecListLoading: Boolean = false,
    val codecListStatus: String? = null,
    val showHardwareCodecs: Boolean = true,
    val showSoftwareCodecs: Boolean = true,
    val showAudioCodecs: Boolean = true,
    val showVideoCodecs: Boolean = true,
    val connectionState: AdbConnectionState = AdbConnectionState.Disconnected,
    val isBusy: Boolean = false,
    val status: String? = null
)

data class AvailableCodec(
    val name: String,
    val supportedTypes: List<String>,
    val isEncoder: Boolean,
    val acceleration: CodecAcceleration
) {
    val primaryKind: CodecMediaKind
        get() = when {
            supportedTypes.any { it.startsWith("video/") } -> CodecMediaKind.VIDEO
            supportedTypes.any { it.startsWith("audio/") } -> CodecMediaKind.AUDIO
            else -> CodecMediaKind.OTHER
        }
}

enum class CodecAcceleration(
    @param:StringRes val titleRes: Int,
    val sortOrder: Int
) {
    HARDWARE(R.string.codec_hardware, 0),
    SOFTWARE(R.string.codec_software, 1),
    UNKNOWN(R.string.codec_acceleration_unknown, 2)
}

enum class CodecMediaKind(
    @param:StringRes val titleRes: Int,
    val sortOrder: Int
) {
    VIDEO(R.string.codec_video, 0),
    AUDIO(R.string.codec_audio, 1),
    OTHER(R.string.codec_other, 2)
}

private fun MediaCodecInfo.accelerationKind(): CodecAcceleration {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        return when {
            isHardwareAccelerated -> CodecAcceleration.HARDWARE
            isSoftwareOnly -> CodecAcceleration.SOFTWARE
            else -> CodecAcceleration.UNKNOWN
        }
    }

    val lowerName = name.lowercase()
    return when {
        lowerName.startsWith("omx.google.") ||
            lowerName.startsWith("c2.android.") ||
            lowerName.startsWith("c2.google.") ||
            lowerName.startsWith("omx.ffmpeg.") -> CodecAcceleration.SOFTWARE
        lowerName.contains("qcom") ||
            lowerName.contains("qti") ||
            lowerName.contains("mtk") ||
            lowerName.contains("exynos") ||
            lowerName.contains("hisi") -> CodecAcceleration.HARDWARE
        else -> CodecAcceleration.UNKNOWN
    }
}
