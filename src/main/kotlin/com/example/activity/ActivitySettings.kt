package com.example.activity

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Where the activity summariser gets its configuration. The API key lives in the IDE's PasswordSafe
 * (never in the project or in this repository); everything else is ordinary plugin state.
 */
@Service(Service.Level.APP)
@State(name = "CodeVisualizerActivity", storages = [Storage("codeVisualizer.xml")])
class ActivitySettings : PersistentStateComponent<ActivitySettings.State> {

    class State {
        var model: String = OpenAiClient.DEFAULT_MODEL
        var maxCommits: Int = 500
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(loaded: State) { state = loaded }

    var model: String
        get() = state.model.ifBlank { OpenAiClient.DEFAULT_MODEL }
        set(value) { state.model = value.trim().ifBlank { OpenAiClient.DEFAULT_MODEL } }

    val maxCommits: Int get() = state.maxCommits.coerceIn(20, 2000)

    /**
     * The key, from the password safe if the user entered one in Settings, otherwise from the
     * environment or [keyFile]. Writing always goes to the password safe, which is the only one of
     * the three that is encrypted — the fallbacks exist so the plugin can be set up without the
     * settings dialog, and the file is read-only as far as this class is concerned.
     */
    var apiKey: String
        get() = resolveKey(
            stored = PasswordSafe.instance.getPassword(credentials),
            environment = System.getenv(ENV_VAR),
            file = keyFile()
        )
        set(value) {
            val trimmed = value.trim()
            PasswordSafe.instance.setPassword(credentials, trimmed.ifBlank { null })
        }

    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    fun client(): OpenAiClient = OpenAiClient(apiKey, model)

    /** Where the key came from, so the settings panel can say so instead of showing a blank field. */
    fun apiKeySource(): String = when {
        !PasswordSafe.instance.getPassword(credentials).isNullOrBlank() -> "the IDE password safe"
        !System.getenv(ENV_VAR).isNullOrBlank() -> "the $ENV_VAR environment variable"
        keyFile()?.isNotBlank() == true -> KEY_FILE_DISPLAY
        else -> "nowhere yet"
    }

    companion object {
        private const val ENV_VAR = "OPENAI_API_KEY"
        const val KEY_FILE_DISPLAY = "~/.code-visualizer/openai-key"

        private val credentials = CredentialAttributes(generateServiceName("Code Visualizer", "openai"))

        fun getInstance(): ActivitySettings = service()

        /** The fallback key file, or null when it is missing or unreadable. */
        fun keyFile(): String? = runCatching {
            java.io.File(System.getProperty("user.home"), ".code-visualizer/openai-key")
                .takeIf { it.isFile }
                ?.readText()
        }.getOrNull()

        /**
         * First non-blank wins. Kept pure so the precedence is covered by tests; a key file
         * containing a trailing newline or a `OPENAI_API_KEY=` prefix still works.
         */
        fun resolveKey(stored: String?, environment: String?, file: String?): String =
            sequenceOf(stored, environment, file)
                .map { it?.trim().orEmpty().removePrefix("$ENV_VAR=").trim().trim('"', '\'') }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
    }
}

/** Settings | Tools | Code Visualizer. */
class ActivityConfigurable : Configurable {
    private val settings = ActivitySettings.getInstance()
    private val keyField = JBPasswordField()
    private val modelField = JBTextField()
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Code Visualizer"

    override fun createComponent(): JComponent {
        reset()
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("OpenAI API key:"), keyField, true)
            .addComponentToRightColumn(JBLabel("Currently read from ${settings.apiKeySource()}."))
            .addComponentToRightColumn(JBLabel("Saved to the IDE password safe. Falls back to OPENAI_API_KEY, then ${ActivitySettings.KEY_FILE_DISPLAY}."))
            .addLabeledComponent(JBLabel("Model:"), modelField, true)
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .also { panel = it }
    }

    override fun isModified(): Boolean =
        String(keyField.password) != settings.apiKey || modelField.text != settings.model

    override fun apply() {
        settings.apiKey = String(keyField.password)
        settings.model = modelField.text
    }

    override fun reset() {
        keyField.text = settings.apiKey
        modelField.text = settings.model
    }

    override fun disposeUIResources() { panel = null }
}
