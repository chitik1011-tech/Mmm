package dev.huecoder.md3agent.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.Delete
import com.composables.icons.materialsymbols.rounded.Key
import com.composables.icons.materialsymbols.rounded.Verified_user
import dev.huecoder.md3agent.R
import dev.huecoder.md3agent.data.settings.ApiFormat
import dev.huecoder.md3agent.ui.components.SaveAction
import dev.huecoder.md3agent.ui.components.SettingsScaffold
import dev.huecoder.md3agent.ui.components.appViewModel

@Composable
fun ProviderEditorScreen(providerId: String?, onDone: () -> Unit) {
    val viewModel = appViewModel(key = "provider-$providerId") { ProviderEditorViewModel(providerId, it.settings, it.modelCatalog, it.applicationScope, it.chatGPTOAuth) }
    val form by viewModel.form.collectAsStateWithLifecycle()
    val isDirty by viewModel.isDirty.collectAsStateWithLifecycle()
    val signInStatus by viewModel.chatGPTSignIn.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var headersOpen by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(if (form?.isNew == false) R.string.provider_title else R.string.provider_title_new),
        onBack = onDone,
        save = SaveAction(isDirty, form?.canSave == true) { viewModel.save(onDone) },
        actions = {
            if (form?.isNew == false) {
                IconButton(onClick = { viewModel.delete(onDone) }, shapes = IconButtonDefaults.shapes()) {
                    Icon(MaterialSymbols.Rounded.Delete, contentDescription = stringResource(R.string.provider_delete))
                }
            }
        },
    ) { padding ->
        val current = form ?: return@SettingsScaffold
        Column(Modifier.settingsPage(padding), verticalArrangement = Arrangement.spacedBy(EDITOR_GROUP_GAP)) {
            CardGroup {
                card { shape ->
                    FormatCard(
                        formats = ApiFormat.CHAT,
                        selected = current.format,
                        hint = stringResource(if (current.format == ApiFormat.CHATGPT) R.string.provider_chatgpt_format_hint else R.string.provider_format_hint),
                        onSelect = { format -> viewModel.edit { it.withFormat(format) } },
                        shape = shape,
                    )
                }
                if (current.format == ApiFormat.CHATGPT) {
                    card { shape ->
                        FieldsCard(shape) {
                            dev.huecoder.md3agent.ui.components.FormField(
                                value = current.name,
                                onValueChange = { value -> viewModel.edit { it.copy(name = value) } },
                                label = stringResource(R.string.provider_name),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    card { shape -> ChatGPTConnectCard(
                        isConnected = current.chatgptOAuth != null,
                        email = current.chatgptOAuth?.email,
                        status = signInStatus,
                        onSignIn = { viewModel.signInWithChatGPT { url -> context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())) } },
                        shape = shape,
                    ) }
                } else {
                    if (current.format == ApiFormat.OPENAI) {
                        card { shape -> DeepSeekSetupCard(
                            onConfigure = { viewModel.edit { it.copy(name = "DeepSeek", baseUrl = DEEPSEEK_BASE_URL) } },
                            onOpenKeys = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, DEEPSEEK_KEYS_URL.toUri())) },
                            shape = shape,
                        ) }
                    }
                    card { shape ->
                        EndpointCard(
                            name = current.name,
                            onName = { value -> viewModel.edit { it.copy(name = value) } },
                            baseUrl = current.baseUrl,
                            onBaseUrl = { value -> viewModel.edit { it.copy(baseUrl = value) } },
                            baseUrlError = current.baseUrlError,
                            defaultBaseUrl = current.format.defaultBaseUrl,
                            apiKey = current.apiKey,
                            onApiKey = { value -> viewModel.edit { it.copy(apiKey = value) } },
                            shape = shape,
                        )
                    }
                }
            }
            if (current.format != ApiFormat.CHATGPT) {
                CardGroup { headersCard(current.headers, headersOpen, { headersOpen = !headersOpen }) { headers -> viewModel.edit { it.copy(headers = headers) } } }
            }
        }
    }
}

@Composable
private fun DeepSeekSetupCard(onConfigure: () -> Unit, onOpenKeys: () -> Unit, shape: Shape) {
    SettingCard(
        icon = MaterialSymbols.Rounded.Key,
        title = stringResource(R.string.provider_deepseek_title),
        summary = stringResource(R.string.provider_deepseek_summary),
        shape = shape,
        indentBelow = false,
        below = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onConfigure) { Text(stringResource(R.string.provider_deepseek_configure)) }
                Button(onClick = onOpenKeys) { Text(stringResource(R.string.provider_deepseek_get_key)) }
            }
        },
    )
}

private const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
private const val DEEPSEEK_KEYS_URL = "https://platform.deepseek.com/api_keys"

@Composable
private fun ChatGPTConnectCard(isConnected: Boolean, email: String?, status: ChatGPTSignInStatus, onSignIn: () -> Unit, shape: Shape) {
    val connected = isConnected
    val connecting = status == ChatGPTSignInStatus.Connecting
    val error = (status as? ChatGPTSignInStatus.Failed)?.message
    SettingCard(
        icon = if (connected) MaterialSymbols.Rounded.Verified_user else MaterialSymbols.Rounded.Key,
        title = stringResource(if (connected) R.string.provider_chatgpt_connected else R.string.provider_chatgpt_sign_in_title),
        summary = when {
            error != null -> error
            connected -> email?.takeIf(String::isNotBlank) ?: stringResource(R.string.provider_chatgpt_connected)
            else -> stringResource(R.string.provider_chatgpt_sign_in_summary)
        },
        summaryColor = if (error != null) MaterialTheme.colorScheme.error else null,
        shape = shape,
        indentBelow = false,
        below = {
            if (connected) {
                OutlinedButton(onClick = onSignIn, enabled = !connecting) {
                    Text(stringResource(if (connecting) R.string.provider_chatgpt_connecting else R.string.provider_chatgpt_reconnect))
                }
            } else {
                Button(onClick = onSignIn, enabled = !connecting) {
                    Text(stringResource(if (connecting) R.string.provider_chatgpt_connecting else R.string.provider_chatgpt_sign_in))
                }
            }
        },
    )
}
