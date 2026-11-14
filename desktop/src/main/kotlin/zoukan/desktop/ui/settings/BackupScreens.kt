package zoukan.desktop.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.backup.BackupValidator
import zoukan.desktop.backup.DesktopBackups
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.LocalWindow
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.Toasts
import zoukan.i18n.desktop.DMR
import java.awt.FileDialog
import java.io.File

/**
 * Android's CreateBackupScreen: what goes in the backup (library entries and what belongs to them,
 * categories, saved searches and feeds, app, extension store, source and private settings), then
 * where to save it.
 */
object CreateBackupScreen : Screen {
    private fun readResolve(): Any = CreateBackupScreen

    override val key: ScreenKey = "create-backup"

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val window = LocalWindow.current?.window
        val model = rememberScreenModel { CreateBackupScreenModel() }
        val state by model.state.collectAsState()

        Column(Modifier.fillMaxSize()) {
            ScreenHeader(localized(MR.strings.pref_create_backup))
            ColumnWithAction(
                actionLabel = localized(MR.strings.action_create),
                actionEnabled = state.options.canCreate(),
                actionTag = "create-backup-action",
                onClickAction = {
                    if (backupStatusFlow.value == null) {
                        // Android's CreateDocument picker, with the backup's file name filled in
                        val file = chooseBackupFile(window)
                        if (file != null) {
                            model.createBackup(file)
                            navigator.pop()
                        }
                    } else {
                        Toasts.show(localized(MR.strings.backup_in_progress))
                    }
                },
            ) {
                item {
                    SectionCard(MR.strings.label_library) {
                        Options(BackupOptions.libraryOptions, state, model)
                    }
                }

                item {
                    SectionCard(MR.strings.label_settings) {
                        Options(BackupOptions.settingsOptions, state, model)
                    }
                }
            }
        }
    }

    @Composable
    private fun Options(
        options: ImmutableList<BackupOptions.Entry>,
        state: CreateBackupScreenModel.State,
        model: CreateBackupScreenModel,
    ) {
        options.forEach { option ->
            LabeledCheckbox(
                label = localized(option.label),
                checked = option.getter(state.options),
                onCheckedChange = {
                    model.toggle(option.setter, it)
                },
                enabled = option.enabled(state.options),
            )
        }
    }
}

class CreateBackupScreenModel : StateScreenModel<CreateBackupScreenModel.State>(State()) {

    fun toggle(setter: (BackupOptions, Boolean) -> BackupOptions, enabled: Boolean) {
        mutableState.update {
            it.copy(
                options = setter(it.options, enabled),
            )
        }
    }

    fun createBackup(file: File) {
        createBackup(file, state.value.options)
    }

    @Immutable
    data class State(
        val options: BackupOptions = BackupOptions(),
    )
}

/**
 * Android's RestoreBackupScreen for the backup at [path]: what to restore, and the sources and
 * trackers the backup uses that are not installed or signed in.
 */
class RestoreBackupScreen(private val path: String) : Screen {

    // Closed, so no key starts with another (see ScreenKeysTest)
    override val key: ScreenKey = "restore-backup($path)"

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { RestoreBackupScreenModel(File(path)) }
        val state by model.state.collectAsState()
        val restoreStatus by backupStatusFlow.collectAsState()
        val missing by MissingExtensionsInstall.state.collectAsState()

        Column(Modifier.fillMaxSize()) {
            ScreenHeader(localized(MR.strings.pref_restore_backup))
            // ZOUKAN: the screen stays open after the restore starts, to show its results and offer
            // "Install missing extensions" (desktop only); its action then closes it
            ColumnWithAction(
                actionLabel = localized(if (state.restoreStarted) MR.strings.action_close else MR.strings.action_restore),
                actionEnabled = state.restoreStarted || (state.canRestore && state.options.canRestore()),
                actionTag = "restore-backup-action",
                onClickAction = {
                    if (state.restoreStarted) navigator.pop() else model.startRestore()
                },
            ) {
                if (state.restoreStarted) {
                    item {
                        SectionCard {
                            RestoreResults(
                                restoreStatus = restoreStatus,
                                outcome = state.outcome,
                                missing = missing.forRestore(model),
                                onInstall = { MissingExtensionsInstall.install(model) },
                            )
                        }
                    }
                }
                if (state.canRestore && !state.restoreStarted) {
                    item {
                        SectionCard {
                            RestoreOptions.options.forEach { option ->
                                LabeledCheckbox(
                                    label = localized(option.label),
                                    checked = option.getter(state.options),
                                    onCheckedChange = {
                                        model.toggle(option.setter, it)
                                    },
                                )
                            }
                        }
                    }
                }

                if (state.error != null) {
                    errorMessageItem(state.error)
                }
            }
        }
    }

    private fun LazyListScope.errorMessageItem(
        error: Any?,
    ) {
        item {
            SectionCard {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val msg = buildAnnotatedString {
                        when (error) {
                            is MissingRestoreComponents -> {
                                appendLine(localized(MR.strings.backup_restore_content_full))
                                if (error.sources.isNotEmpty()) {
                                    appendLine()
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                        appendLine(localized(MR.strings.backup_restore_missing_sources))
                                    }
                                    error.sources.joinTo(
                                        this,
                                        separator = "\n- ",
                                        prefix = "- ",
                                    )
                                }
                                if (error.trackers.isNotEmpty()) {
                                    appendLine()
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                        appendLine(localized(MR.strings.backup_restore_missing_trackers))
                                    }
                                    error.trackers.joinTo(
                                        this,
                                        separator = "\n- ",
                                        prefix = "- ",
                                    )
                                }
                            }

                            is InvalidRestore -> {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    appendLine(localized(MR.strings.invalid_backup_file))
                                }
                                appendLine(error.file.toString())

                                appendLine()

                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    appendLine(localized(MR.strings.invalid_backup_file_error))
                                }
                                appendLine(error.message)
                            }

                            else -> {
                                appendLine(error.toString())
                            }
                        }
                    }

                    SelectionContainer {
                        Text(text = msg, modifier = Modifier.testTag("restore-backup-message"))
                    }
                }
            }
        }
    }
}

/**
 * "Install missing extensions" from Settings > Data and storage (desktop only), so it is not only
 * on the restore screen: finds the library's sources with no installed extension and offers to
 * install the extensions that bring them.
 */
object MissingExtensionsScreen : Screen {
    private fun readResolve(): Any = MissingExtensionsScreen

    override val key: ScreenKey = "missing-extensions"

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { MissingExtensionsScreenModel() }
        val missing by MissingExtensionsInstall.state.collectAsState()

        Column(Modifier.fillMaxSize()) {
            ScreenHeader(localized(DMR.strings.install_missing_extensions))
            ColumnWithAction(
                actionLabel = localized(MR.strings.action_close),
                actionEnabled = true,
                actionTag = "missing-extensions-close",
                onClickAction = { navigator.pop() },
            ) {
                item {
                    SectionCard {
                        MissingExtensionsResults(
                            missing = missing.forSettings(model),
                            onInstall = { MissingExtensionsInstall.install(model) },
                        )
                    }
                }
            }
        }
    }
}

/** The token that tags [MissingExtensionsInstall]'s results as this screen's. */
class MissingExtensionsScreenModel : ScreenModel {
    init {
        MissingExtensionsInstall.dismiss()
        MissingExtensionsInstall.find(this)
    }
}

class RestoreBackupScreenModel(
    private val file: File,
) : StateScreenModel<RestoreBackupScreenModel.State>(State()) {

    init {
        screenModelScope.launch { validate(file) }
        // A finished run's results belong to the restore that started it
        MissingExtensionsInstall.dismiss()
    }

    fun toggle(setter: (RestoreOptions, Boolean) -> RestoreOptions, enabled: Boolean) {
        mutableState.update {
            it.copy(
                options = setter(it.options, enabled),
            )
        }
    }

    fun startRestore() {
        if (state.value.restoreStarted) return
        val running = restoreBackup(file, state.value.options)
        if (running == null) {
            Toasts.show(localized(MR.strings.backup_in_progress))
            return
        }
        mutableState.update { it.copy(restoreStarted = true, outcome = null) }
        // restoreBackup shows its status and toasts until it ends; then, when it restored,
        // look for the missing extensions. This model is the token that tags them as this restore's.
        screenModelScope.launch {
            when (val outcome = running.await()) {
                RestoreOutcome.OK -> {
                    mutableState.update { it.copy(outcome = outcome) }
                    MissingExtensionsInstall.find(this@RestoreBackupScreenModel)
                }
                RestoreOutcome.FAILED -> mutableState.update { it.copy(outcome = outcome) }
                // The user stopped it before it changed anything: back to the options
                RestoreOutcome.CANCELLED -> mutableState.update { it.copy(restoreStarted = false, outcome = null) }
            }
        }
    }

    private suspend fun validate(file: File) {
        val results = try {
            withContext(Dispatchers.IO) {
                val backup = DesktopBackups(ZoukanRuntime.dirs.android).read(file)
                BackupValidator().validate(backup)
            }
        } catch (e: Exception) {
            setError(
                error = InvalidRestore(file, e.message.toString()),
                canRestore = false,
            )
            return
        }

        if (results.missingSources.isNotEmpty() || results.missingTrackers.isNotEmpty()) {
            setError(
                error = MissingRestoreComponents(file, results.missingSources, results.missingTrackers),
                canRestore = true,
            )
            return
        }

        setError(error = null, canRestore = true)
    }

    private fun setError(error: Any?, canRestore: Boolean) {
        mutableState.update {
            it.copy(
                error = error,
                canRestore = canRestore,
            )
        }
    }

    @Immutable
    data class State(
        val error: Any? = null,
        val canRestore: Boolean = false,
        val options: RestoreOptions = RestoreOptions(),
        val restoreStarted: Boolean = false,
        /** How the started restore ended; null while it runs. */
        val outcome: RestoreOutcome? = null,
    )
}

private data class MissingRestoreComponents(
    val file: File,
    val sources: List<String>,
    val trackers: List<String>,
)

private data class InvalidRestore(
    val file: File? = null,
    val message: String,
)

/** Asks where to save a backup, in the backup folder with the default name; null when cancelled. */
private fun chooseBackupFile(window: java.awt.Window?, dirs: ZoukanDirs = Injekt.get()): File? {
    val dialog = FileDialog(window as java.awt.Frame?, localized(MR.strings.pref_create_backup), FileDialog.SAVE).apply {
        directory = dirs.backups.apply { mkdirs() }.absolutePath
        file = DesktopBackups.defaultFileName()
        isVisible = true
    }
    val name = dialog.file ?: return null
    // The extension the Android app's restore picker looks for
    return File(dialog.directory, if (name.endsWith(".tachibk") || name.endsWith(".proto.gz")) name else "$name.tachibk")
}

/** presentation-core's LazyColumnWithAction: the list, and the screen's action button under it. */
@Composable
private fun ColumnWithAction(
    actionLabel: String,
    onClickAction: () -> Unit,
    actionEnabled: Boolean,
    actionTag: String,
    content: LazyListScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            content = content,
        )

        HorizontalDivider()

        Button(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .testTag(actionTag),
            enabled = actionEnabled,
            onClick = onClickAction,
        ) {
            Text(text = actionLabel)
        }
    }
}

/** presentation-core's SectionCard. */
@Composable
private fun SectionCard(
    titleRes: StringResource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (titleRes != null) {
        Text(
            modifier = Modifier.padding(horizontal = 32.dp),
            text = localized(titleRes),
            style = MaterialTheme.typography.titleSmall,
        )
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 16.dp,
                vertical = 8.dp,
            ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

/** presentation-core's LabeledCheckbox. */
@Composable
private fun LabeledCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                role = Role.Checkbox,
                onClick = {
                    if (enabled) {
                        onCheckedChange(!checked)
                    }
                },
            )
            .testTag("backup-option:$label"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )

        Text(text = label)
    }
}
