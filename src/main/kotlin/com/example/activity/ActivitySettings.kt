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
        get() = ownKey().ifBlank { reelKey() }
        set(value) {
            val trimmed = value.trim()
            PasswordSafe.instance.setPassword(credentials, trimmed.ifBlank { null })
        }

    /**
     * Only the sources this feature owns, with no cross-feature fallback.
     *
     * The Reel settings append this to their own candidate list, so the two must never call
     * each other's aggregate: that would recurse forever. This is the half that is safe to
     * call from over there.
     */
    fun ownKey(): String = resolveKey(
        stored = PasswordSafe.instance.getPassword(credentials),
        environment = System.getenv(ENV_VAR),
        file = keyFile()
    )

    /** Our own sources as (key, label) pairs, in trust order, for the Reel's candidate list. */
    fun ownKeyCandidates(): List<Pair<String, String>> = listOfNotNull(
        PasswordSafe.instance.getPassword(credentials)?.trim()?.takeIf { it.isNotBlank() }
            ?.let { it to "the key saved in Settings | Tools | Nexus" },
        System.getenv(ENV_VAR)?.trim()?.takeIf { it.isNotBlank() }?.let { it to ENV_VAR },
        keyFile()?.let { resolveKey(null, null, it) }?.takeIf { it.isNotBlank() }
            ?.let { it to KEY_FILE_DISPLAY }
    )

    /**
     * The Reel's own chain: its keychain entry, OPENAI_KEY, and any project .env file.
     * Guarded, so the Activity tab still works if that feature is absent or its service fails.
     */
    private fun reelKey(): String = runCatching {
        com.example.yasinreel.settings.ReelSettings.getInstance()
            .ownCandidates().firstOrNull()?.key.orEmpty()
    }.getOrDefault("")

    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    fun client(): OpenAiClient = OpenAiClient(apiKey, model)

    /** Where the key came from, so the settings panel can say so instead of showing a blank field. */
    fun apiKeySource(): String = when {
        !PasswordSafe.instance.getPassword(credentials).isNullOrBlank() -> "the IDE password safe"
        !System.getenv(ENV_VAR).isNullOrBlank() -> "the $ENV_VAR environment variable"
        keyFile()?.isNotBlank() == true -> KEY_FILE_DISPLAY
        else -> runCatching {
            com.example.yasinreel.settings.ReelSettings.getInstance()
                .ownCandidates().firstOrNull()?.source?.let { "the Reel's $it" }
        }.getOrNull() ?: "nowhere yet"
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

/** Settings | Tools | Nexus. */
class ActivityConfigurable : Configurable {
    private val settings = ActivitySettings.getInstance()
    private val keyField = JBPasswordField()
    private val modelField = JBTextField()
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Nexus"

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
