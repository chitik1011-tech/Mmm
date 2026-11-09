package dev.huecoder.md3agent.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.huecoder.md3agent.R
import dev.huecoder.md3agent.data.settings.ApiFormat
import dev.huecoder.md3agent.data.settings.ChatGPTOAuthSession
import dev.huecoder.md3agent.data.settings.HeaderEntry
import dev.huecoder.md3agent.data.settings.ProviderProfile
import dev.huecoder.md3agent.data.settings.SettingsRepository
import dev.huecoder.md3agent.data.models.ModelCatalog
import dev.huecoder.md3agent.llm.auth.ChatGPTOAuth
import dev.huecoder.md3agent.ui.text.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ProviderForm(
    val id: String,
    val isNew: Boolean,
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val headers: List<HeaderEntry> = emptyList(),
    val format: ApiFormat = ApiFormat.OPENAI,
    val chatgptOAuth: ChatGPTOAuthSession? = null,
) {
    fun withFormat(next: ApiFormat) = if (next == ApiFormat.CHATGPT) {
        copy(format = next, name = name.ifBlank { "ChatGPT" }, baseUrl = next.defaultBaseUrl, apiKey = "", headers = emptyList())
    } else {
        copy(format = next, baseUrl = next.adoptBaseUrl(baseUrl))
    }

    val baseUrlError: UiText?
        get() = if (format == ApiFormat.CHATGPT || baseUrl.isBlank() || baseUrl.trim().toHttpUrlOrNull() != null) null else UiText.Resource(R.string.provider_base_url_invalid)

    val canSave: Boolean
        get() = name.isNotBlank() && (format == ApiFormat.CHATGPT || baseUrl.isNotBlank() && baseUrlError == null)

    fun toProfile() = ProviderProfile(
        id = id,
        name = name.trim(),
        baseUrl = if (format == ApiFormat.CHATGPT) ApiFormat.CHATGPT.defaultBaseUrl else baseUrl.trim(),
        apiKey = if (format == ApiFormat.CHATGPT) "" else apiKey.trim(),
        extraHeaders = if (format == ApiFormat.CHATGPT) emptyList() else headers.filter { it.name.isNotBlank() },
        format = format,
        chatgptOAuth = chatgptOAuth,
    )
}

sealed interface ChatGPTSignInStatus {
    data object Idle : ChatGPTSignInStatus
    data object Connecting : ChatGPTSignInStatus
    data class Failed(val message: String) : ChatGPTSignInStatus
}

class ProviderEditorViewModel(
    private val providerId: String?,
    private val settings: SettingsRepository,
    private val catalog: ModelCatalog,
    private val appScope: CoroutineScope,
    private val chatGPTOAuth: ChatGPTOAuth,
) : ViewModel() {
    private val draft = FormDraft<ProviderForm>(viewModelScope)
    private val _chatGPTSignIn = MutableStateFlow<ChatGPTSignInStatus>(ChatGPTSignInStatus.Idle)
    val form: StateFlow<ProviderForm?> = draft.value
    val chatGPTSignIn: StateFlow<ChatGPTSignInStatus> = _chatGPTSignIn.asStateFlow()

    val isDirty: StateFlow<Boolean> = draft.isDirty

    init {
        viewModelScope.launch {
            val existing = settings.current().provider(providerId)
            draft.load(if (existing == null) {
                ProviderForm(id = settings.newProviderId(), isNew = true)
            } else {
                ProviderForm(
                    id = existing.id,
                    isNew = false,
                    name = existing.name,
                    baseUrl = existing.baseUrl,
                    apiKey = existing.apiKey,
                    headers = existing.extraHeaders,
                    format = existing.format,
                    chatgptOAuth = existing.chatgptOAuth,
                )
            })
        }
    }

    fun edit(transform: (ProviderForm) -> ProviderForm) = draft.edit(transform)

    fun save(onSaved: () -> Unit) {
        val current = draft.current?.takeIf { it.canSave } ?: return
        viewModelScope.launch {
            val profile = current.toProfile()
            settings.upsertProvider(profile)
            draft.markSaved()
            appScope.launch { catalog.refreshQuietly(profile) }
            onSaved()
        }
    }

    fun signInWithChatGPT(openBrowser: (String) -> Unit) {
        val current = draft.current?.takeIf { it.format == ApiFormat.CHATGPT && it.canSave } ?: return
        viewModelScope.launch {
            _chatGPTSignIn.value = ChatGPTSignInStatus.Connecting
            val pendingProfile = current.toProfile()
            try {
                settings.upsertProvider(pendingProfile)
                draft.applyPersisted { it.copy(isNew = false) }
                val session = chatGPTOAuth.signIn(pendingProfile, openBrowser)
                val connected = pendingProfile.copy(chatgptOAuth = session)
                settings.upsertProvider(connected)
                draft.applyPersisted { it.copy(isNew = false, chatgptOAuth = session) }
                _chatGPTSignIn.value = ChatGPTSignInStatus.Idle
                appScope.launch { catalog.refreshQuietly(connected) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _chatGPTSignIn.value = ChatGPTSignInStatus.Failed(error.message ?: "ChatGPT sign-in failed")
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = providerId ?: return onDeleted()
        viewModelScope.launch {
            settings.deleteProvider(id)
            catalog.forget(id)
            onDeleted()
        }
    }
}
