package com.streamvault.app.ui.screens.provider

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.data.remote.xtream.XtreamAuthenticationException
import com.streamvault.data.remote.xtream.XtreamNetworkException
import com.streamvault.data.remote.xtream.XtreamParsingException
import com.streamvault.data.remote.xtream.XtreamRequestException
import com.streamvault.data.remote.xtream.XtreamResponseTooLargeException
import com.streamvault.data.security.CredentialDecryptionException
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.DriveAuthState
import com.streamvault.domain.manager.DriveBackupSyncManager
import com.streamvault.domain.manager.ProviderCredentials
import com.streamvault.domain.model.Result as DomainResult
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.ImportBackup
import com.streamvault.domain.usecase.ImportBackupCommand
import com.streamvault.domain.usecase.ImportBackupResult
import com.streamvault.domain.usecase.InspectBackupCommand
import com.streamvault.domain.usecase.InspectBackupResult
import com.streamvault.domain.usecase.M3uProviderSetupCommand
import com.streamvault.domain.usecase.StalkerProviderSetupCommand
import com.streamvault.domain.usecase.ValidateAndAddProvider
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import com.streamvault.domain.usecase.XtreamProviderSetupCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

@HiltViewModel
class ProviderSetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val providerRepository: ProviderRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val validateAndAddProvider: ValidateAndAddProvider,
    private val importBackup: ImportBackup,
    private val driveBackupSyncManager: DriveBackupSyncManager,
) : ViewModel() {

    enum class OnboardingCompletion {
        NONE,
        READY,
        SAVED_RESUMING
    }

    enum class SetupSourceType {
        XTREAM,
        STALKER,
        M3U
    }

    private val _uiState = MutableStateFlow(ProviderSetupState())
    val uiState: StateFlow<ProviderSetupState> = _uiState.asStateFlow()
    private val _knownLocalM3uUrls = MutableStateFlow<Set<String>>(emptySet())
    val knownLocalM3uUrls: StateFlow<Set<String>> = _knownLocalM3uUrls.asStateFlow()

    init {
        viewModelScope.launch {
            providerRepository.getActiveProvider().collect { provider ->
                if (provider != null) {
                    _uiState.update { it.copy(hasExistingProvider = true) }
                }
            }
        }
        viewModelScope.launch {
            providerRepository.getProviders().collect { providers ->
                _knownLocalM3uUrls.value = providers
                    .mapNotNull { provider ->
                        provider.m3uUrl.takeIf { it.startsWith("file://") }
                    }
                    .toSet()
            }
        }
        viewModelScope.launch {
            driveBackupSyncManager.authState.collect { state ->
                _uiState.update {
                    it.copy(driveSignedIn = state is DriveAuthState.SignedIn)
                }
            }
        }

        // === CARGA AUTOMÁTICA DE giri2.m3u DESDE ASSETS ===
        viewModelScope.launch {
            // Solo si no hay ningún proveedor M3U local configurado todavía
            val alreadyHasLocalM3u = _knownLocalM3uUrls.value.isNotEmpty()
            if (!alreadyHasLocalM3u) {
                try {
                    // Leer el archivo desde la carpeta assets
                    val m3uContent = context.assets.open("giri2.m3u").bufferedReader().use { it.readText() }
                    val localUri = "file:///android_asset/giri2.m3u"

                    // Crear el comando para agregar el proveedor M3U
                    val command = M3uProviderSetupCommand(
                        url = localUri,
                        name = "Mi Lista IPTV",
                        httpUserAgent = "",
                        httpHeaders = "",
                        epgSyncMode = ProviderEpgSyncMode.DISABLED,
                        m3uVodClassificationEnabled = false,
                        existingProviderId = null
                    )

                    // Ejecutar la adición del proveedor
                    validateAndAddProvider.addM3u(
                        command = command,
                        onProgress = { /* progreso opcional */ }
                    )

                    // Actualizar estado local y UI
                    _knownLocalM3uUrls.value = setOf(localUri)
                    _uiState.update {
                        it.copy(
                            onboardingCompletion = OnboardingCompletion.READY,
                            setupSourceType = SetupSourceType.M3U
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        // === FIN CARGA AUTOMÁTICA ===
    }

    fun beginDriveSignIn(launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>) {
        viewModelScope.launch {
            when (val request = driveBackupSyncManager.beginSignIn()) {
                is DomainResult.Success -> {
                    val intent = request.data.intent as? android.content.Intent ?: return@launch
                    runCatching { launcher.launch(intent) }
                }
                is DomainResult.Error -> {
                    _uiState.update {
                        it.copy(error = "Drive sign-in unavailable: ${request.message}")
                    }
                }
                is DomainResult.Loading -> Unit
            }
        }
    }

    fun completeDriveSignIn(intentData: android.content.Intent?) {
        viewModelScope.launch {
            when (val signIn = driveBackupSyncManager.completeSignIn(intentData)) {
                is DomainResult.Success -> Unit
                is DomainResult.Error -> {
                    _uiState.update {
                        it.copy(error = "Drive sign-in failed: ${signIn.message}")
                    }
                }
                is DomainResult.Loading -> Unit
            }
        }
    }

    fun importBackupFromDrive() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImportingBackup = true,
                    syncProgress = "Downloading from Drive...",
                    validationError = null,
                    error = null
                )
            }
            when (val pullResult = driveBackupSyncManager.pullBackup()) {
                is DomainResult.Success -> {
                    // Best-effort companion fetch (M3). Failures are non-fatal.
                    val credentials = (driveBackupSyncManager.pullCredentials() as? DomainResult.Success)?.data
                    _uiState.update {
                        it.copy(
                            isImportingBackup = false,
                            pendingDriveCredentials = credentials,
                        )
                    }
                    inspectBackup(pullResult.data.localUriString)
                }
                is DomainResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isImportingBackup = false,
                            syncProgress = null,
                            error = "Drive pull failed: ${pullResult.message}"
                        )
                    }
                }
                is DomainResult.Loading -> Unit
            }
        }
    }

    private suspend fun applyPendingDriveCredentials() {
        val pending = _uiState.value.pendingDriveCredentials.orEmpty()
        if (pending.isEmpty()) return
        pending.forEach { cred ->
            providerRepository.updateProviderPassword(
                serverUrl = cred.serverUrl,
                username = cred.username,
                cleartextPassword = cred.password,
            )
        }
        _uiState.update { it.copy(pendingDriveCredentials = null) }
    }

    fun loadProvider(id: Long) {
        viewModelScope.launch {
            val provider = providerRepository.getProvider(id)
            if (provider != null) {
                _uiState.update {
                    it.copy(
                        isEditing = true,
                        existingProviderId = id,
                        name = provider.name,
                        serverUrl = provider.serverUrl,
                        username = provider.username,
                        password = "",
                        m3uUrl = provider.m3uUrl,
                        httpUserAgent = provider.httpUserAgent,
                        httpHeaders = provider.httpHeaders,
                        stalkerMacAddress = provider.stalkerMacAddress,
                        stalkerAuthMode = provider.stalkerAuthMode,
                        stalkerDeviceProfile = provider.stalkerDeviceProfile,
                        stalkerDeviceTimezone = provider.stalkerDeviceTimezone,
                        stalkerDeviceLocale = provider.stalkerDeviceLocale,
                        stalkerSerialNumber = provider.stalkerSerialNumber,
                        stalkerDeviceId = provider.stalkerDeviceId,
                        stalkerDeviceId2 = provider.stalkerDeviceId2,
                        stalkerSignature = provider.stalkerSignature,
                        epgSyncMode = provider.epgSyncMode,
                        xtreamLiveSyncMode = provider.xtreamLiveSyncMode,
                        hasCustomizedEpgSyncMode = true,
                        m3uVodClassificationEnabled = provider.m3uVodClassificationEnabled,
                        selectedTab = when (provider.type) {
                            ProviderType.XTREAM_CODES -> 0
                            ProviderType.STALKER_PORTAL -> 1
                            ProviderType.M3U -> 2
                        },
                        m3uTab = if (provider.m3uUrl.startsWith("file://")) 1 else 0
                    )
                }
            }
        }
    }

    fun updateM3uTab(tab: Int) {
        _uiState.update { it.copy(m3uTab = tab) }
    }

    fun updateM3uVodClassificationEnabled(enabled: Boolean) {
        _uiState.update { it.copy(m3uVodClassificationEnabled = enabled) }
    }

    fun updateEpgSyncMode(mode: ProviderEpgSyncMode) {
        _uiState.update { it.copy(epgSyncMode = mode, hasCustomizedEpgSyncMode = true) }
    }

    fun updateXtreamLiveSyncMode(mode: ProviderXtreamLiveSyncMode) {
        _uiState.update { it.copy(xtreamLiveSyncMode = mode) }
    }

    fun applySourceDefaults(sourceType: SetupSourceType) {
        _uiState.update { current ->
            if (current.isEditing || current.hasCustomizedEpgSyncMode) {
                current
            } else {
                current.copy(
                    epgSyncMode = defaultEpgSyncModeFor(sourceType)
                )
            }
        }
    }

    fun loginStalker(
        portalUrl: String,
        macAddress: String,
        authMode: StalkerAuthMode,
        username: String,
        password: String,
        name: String,
        deviceProfile: String,
        timezone: String,
        locale: String,
        serialNumber: String = "",
        deviceId: String = "",
        deviceId2: String = "",
        signature: String = ""
    ) {
        _uiState.update {
            it.copy(
                validationError = null,
                error = null,
                completionWarning = null,
                onboardingCompletion = OnboardingCompletion.NONE,
                loginSuccess = false
            )
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, validationError = null, syncProgress = "Connecting...") }
            val existingId = if (_uiState.value.isEditing) _uiState.value.existingProviderId else null

            when (val result = validateAndAddProvider.loginStalker(
                StalkerProviderSetupCommand(
                    portalUrl = portalUrl,
                    macAddress = macAddress,
                    authMode = authMode,
                    username = username,
                    password = password,
                    name = name,
                    deviceProfile = deviceProfile,
                    timezone = timezone,
                    locale = locale,
                    serialNumber = serialNumber,
                    deviceId = deviceId,
                    deviceId2 = deviceId2,
                    signature = signature,
                    epgSyncMode = _uiState.value.epgSyncMode,
                    existingProviderId = existingId
                ),
                onProgress = { msg -> _uiState.update { it.copy(syncProgress = msg) } }
            )) {
                is ValidateAndAddProviderResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = true,
                            onboardingCompletion = OnboardingCompletion.READY,
                            createdProviderId = result.provider.id,
                            error = null,
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.SavedWithWarning -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = false,
                            onboardingCompletion = OnboardingCompletion.SAVED_RESUMING,
                            createdProviderId = result.provider.id,
                            error = null,
                            validationError = null,
                            completionWarning = result.warning,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.ValidationError -> {
                    _uiState.update {
                        it.copy(isLoading = false, validationError = result.message, error = null, syncProgress = null)
                    }
                }
                is ValidateAndAddProviderResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = mapStalkerLoginError(result),
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
            }
        }
    }

    fun loginXtream(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        httpUserAgent: String,
        httpHeaders: String
    ) {
        _uiState.update {
            it.copy(
                validationError = null,
                error = null,
                completionWarning = null,
                onboardingCompletion = OnboardingCompletion.NONE,
                loginSuccess = false
            )
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, validationError = null, syncProgress = "Connecting...") }
            val existingId = if (_uiState.value.isEditing) _uiState.value.existingProviderId else null

            when (val result = validateAndAddProvider.loginXtream(
                XtreamProviderSetupCommand(
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    name = name,
                    httpUserAgent = httpUserAgent,
                    httpHeaders = httpHeaders,
                    xtreamFastSyncEnabled = false,
                    epgSyncMode = _uiState.value.epgSyncMode,
                    xtreamLiveSyncMode = _uiState.value.xtreamLiveSyncMode,
                    existingProviderId = existingId
                ),
                onProgress = { msg -> _uiState.update { it.copy(syncProgress = msg) } }
            )) {
                is ValidateAndAddProviderResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = true,
                            onboardingCompletion = OnboardingCompletion.READY,
                            createdProviderId = result.provider.id,
                            error = null,
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.SavedWithWarning -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = false,
                            onboardingCompletion = OnboardingCompletion.SAVED_RESUMING,
                            createdProviderId = result.provider.id,
                            error = null,
                            validationError = null,
                            completionWarning = result.warning,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.ValidationError -> {
                    _uiState.update {
                        it.copy(isLoading = false, validationError = result.message, error = null, syncProgress = null)
                    }
                }
                is ValidateAndAddProviderResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = mapXtreamLoginError(result),
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
            }
        }
    }

    fun addM3u(url: String, name: String, httpUserAgent: String, httpHeaders: String) {
        _uiState.update {
            it.copy(
                validationError = null,
                error = null,
                completionWarning = null,
                onboardingCompletion = OnboardingCompletion.NONE,
                loginSuccess = false
            )
        }

        if (url.isBlank()) {
            _uiState.update {
                it.copy(validationError = if (_uiState.value.m3uTab == 0) "Please enter M3U URL" else "Please select a file")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, validationError = null, syncProgress = "Validating...") }
            val existingId = if (_uiState.value.isEditing) _uiState.value.existingProviderId else null

            when (val result = validateAndAddProvider.addM3u(
                M3uProviderSetupCommand(
                    url = url,
                    name = name,
                    httpUserAgent = httpUserAgent,
                    httpHeaders = httpHeaders,
                    epgSyncMode = _uiState.value.epgSyncMode,
                    m3uVodClassificationEnabled = _uiState.value.m3uVodClassificationEnabled,
                    existingProviderId = existingId
                ),
                onProgress = { msg -> _uiState.update { it.copy(syncProgress = msg) } }
            )) {
                is ValidateAndAddProviderResult.Success -> {
                    val activeCombinedProfileId = if (existingId == null) {
                        (combinedM3uRepository.getActiveLiveSource().first()
                            as? ActiveLiveSource.CombinedM3uSource)?.profileId
                    } else {
                        null
                    }
                    val activeCombinedProfileName = activeCombinedProfileId?.let { profileId ->
                        combinedM3uRepository.getProfile(profileId)?.name
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = true,
                            onboardingCompletion = OnboardingCompletion.READY,
                            createdProviderId = result.provider.id,
                            showAttachToCombinedDialog = activeCombinedProfileId != null,
                            attachCombinedProfileId = activeCombinedProfileId,
                            attachCombinedProfileName = activeCombinedProfileName,
                            error = null,
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.SavedWithWarning -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = false,
                            onboardingCompletion = OnboardingCompletion.SAVED_RESUMING,
                            createdProviderId = result.provider.id,
                            error = null,
                            validationError = null,
                            completionWarning = result.warning,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.ValidationError -> {
                    _uiState.update {
                        it.copy(isLoading = false, validationError = result.message, error = null, syncProgress = null)
                    }
                }
                is ValidateAndAddProviderResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.message,
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
            }
        }
    }

    private fun mapStalkerLoginError(result: ValidateAndAddProviderResult.Error): String {
        return when (val cause = result.cause) {
            is SocketTimeoutException -> "Connection timeout (server slow or unreachable)"
            is UnknownHostException -> "Cannot resolve portal URL"
            is ConnectException -> "Connection refused by server"
            is NoRouteToHostException -> "No route to host"
            is SSLException, is SSLPeerUnverifiedException, is CertificateException -> "SSL/TLS error: invalid certificate"
            is InterruptedIOException -> "Operation interrupted"
            is XtreamAuthenticationException -> "Invalid credentials"
            is XtreamResponseTooLargeException -> "Response too large"
            is XtreamParsingException -> "Invalid server response"
            is XtreamNetworkException -> "Network error"
            is XtreamRequestException -> "Request error"
            is CredentialDecryptionException -> "Credential decryption error"
            else -> result.message ?: "Login failed"
        }
    }

    private fun mapXtreamLoginError(result: ValidateAndAddProviderResult.Error): String {
        return when (val cause = result.cause) {
            is SocketTimeoutException -> "Connection timeout (server slow or unreachable)"
            is UnknownHostException -> "Cannot resolve server URL"
            is ConnectException -> "Connection refused by server"
            is NoRouteToHostException -> "No route to host"
            is SSLException, is SSLPeerUnverifiedException, is CertificateException -> "SSL/TLS error: invalid certificate"
            is InterruptedIOException -> "Operation interrupted"
            is XtreamAuthenticationException -> "Invalid credentials (wrong username/password)"
            is XtreamResponseTooLargeException -> "Response too large"
            is XtreamParsingException -> "Invalid server response (not a valid Xtream Codes API)"
            is XtreamNetworkException -> "Network error"
            is XtreamRequestException -> "Request error"
            is CredentialDecryptionException -> "Credential decryption error"
            else -> result.message ?: "Login failed"
        }
    }

    private fun defaultEpgSyncModeFor(sourceType: SetupSourceType): ProviderEpgSyncMode {
        return when (sourceType) {
            SetupSourceType.XTREAM -> ProviderEpgSyncMode.XTREAM
            SetupSourceType.STALKER -> ProviderEpgSyncMode.XTREAM
            SetupSourceType.M3U -> ProviderEpgSyncMode.DISABLED
        }
    }
}
