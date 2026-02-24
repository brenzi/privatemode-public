package ai.privatemode.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ai.privatemode.android.data.repository.ChatRepository
import ai.privatemode.android.whisper.WhisperManager
import ai.privatemode.android.whisper.WhisperModelState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: ChatRepository,
    private val whisperManager: WhisperManager,
) : ViewModel() {

    val apiKey: StateFlow<String?> = repository.apiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val serverUrl: StateFlow<String> = repository.serverUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val sttEnabled: StateFlow<Boolean> = repository.preferences.sttEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val whisperModelState: StateFlow<WhisperModelState> = whisperManager.modelState

    fun updateApiKey(key: String) {
        viewModelScope.launch {
            repository.setApiKey(key)
        }
    }

    fun updateServerUrl(url: String) {
        viewModelScope.launch {
            repository.setServerUrl(url)
        }
    }

    fun clearAllChats() {
        viewModelScope.launch {
            repository.clearAllChats()
        }
    }

    fun setSttEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.preferences.setSttEnabled(enabled)
            if (enabled) {
                whisperManager.initialize()
                if (whisperManager.modelState.value is WhisperModelState.NotDownloaded) {
                    whisperManager.downloadModel()
                }
            } else {
                whisperManager.deleteModel()
            }
        }
    }

    fun downloadWhisperModel() {
        viewModelScope.launch {
            whisperManager.downloadModel()
        }
    }

    class Factory(
        private val repository: ChatRepository,
        private val whisperManager: WhisperManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(repository, whisperManager) as T
        }
    }
}
