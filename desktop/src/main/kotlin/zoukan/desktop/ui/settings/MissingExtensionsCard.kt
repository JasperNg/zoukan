package zoukan.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.desktop.ui.settings.MissingExtensionsInstall.State
import zoukan.i18n.desktop.DMR

/**
 * The restore screen's results once a restore started: its progress, then the missing
 * extensions, the optional "Install missing extensions" action, its progress and its summary.
 */
@Composable
internal fun RestoreResults(
    restoreStatus: String?,
    outcome: RestoreOutcome?,
    missing: State,
    onInstall: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            restoreStatus != null && outcome == null -> {
                Busy(restoreStatus)
                return@Column
            }
            outcome == null -> return@Column
            outcome != RestoreOutcome.OK -> {
                Text(localized(DMR.strings.restore_failed), fontWeight = FontWeight.Bold)
                return@Column
            }
        }
        Text(localized(DMR.strings.restore_finished), fontWeight = FontWeight.Bold)
        MissingExtensionsResults(missing, onInstall)
    }
}

/**
 * The missing extensions, the "Install missing extensions" action, its progress and its summary.
 * The restore screen and Settings > Data and storage's own screen show it.
 */
@Composable
internal fun MissingExtensionsResults(missing: State, onInstall: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (missing) {
            State.Idle -> Unit
            State.Finding -> Busy(localized(DMR.strings.finding_missing_extensions))
            is State.Ready -> ReadyPlan(missing.plan, onInstall)
            is State.Installing -> {
                Text(localized(DMR.strings.missing_extensions_installing, missing.current.orEmpty(), missing.done + 1, missing.total))
                LinearProgressIndicator(
                    progress = { if (missing.total > 0) missing.done / missing.total.toFloat() else 1f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is State.Done -> Summary(missing.summary)
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
private fun ReadyPlan(plan: MissingExtensionsPlan, onInstall: () -> Unit) {
    if (plan.isEmpty) {
        Text(localized(DMR.strings.no_missing_extensions))
        return
    }
    var confirm by remember { mutableStateOf(false) }
    Text(localized(DMR.strings.missing_extensions_found, plan.toInstall.size, plan.unavailable.size))
    if (plan.needsTrust.isNotEmpty()) {
        Section(localized(DMR.strings.missing_extensions_need_trust), plan.needsTrust.map { it.extension.name })
    }
    if (plan.toInstall.isNotEmpty()) {
        Button(onClick = { confirm = true }, modifier = Modifier.testTag("install-missing-extensions")) {
            Text(localized(DMR.strings.install_missing_extensions))
        }
    } else if (plan.unavailable.isNotEmpty()) {
        Section(localized(DMR.strings.missing_extensions_unavailable), plan.unavailable.map { it.name })
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(localized(DMR.strings.install_missing_extensions)) },
            text = {
                Column(Modifier.verticalScrollWithBar(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Section(localized(DMR.strings.missing_extensions_to_install), plan.toInstall.map { it.label() })
                    if (plan.needsTrust.isNotEmpty()) {
                        Section(localized(DMR.strings.missing_extensions_need_trust), plan.needsTrust.map { it.label() })
                    }
                    if (plan.unavailable.isNotEmpty()) {
                        Section(localized(DMR.strings.missing_extensions_unavailable), plan.unavailable.map { it.name })
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        onInstall()
                    },
                    modifier = Modifier.testTag("install-missing-extensions-confirm"),
                ) { Text(localized(MR.strings.action_install)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(localized(MR.strings.action_cancel)) } },
        )
    }
}

@Composable
private fun Summary(summary: MissingExtensionsSummary) {
    if (summary.installed.isNotEmpty()) {
        Section(localized(DMR.strings.missing_extensions_installed_list), summary.installed)
    }
    if (summary.needsTrust.isNotEmpty()) {
        Section(localized(DMR.strings.missing_extensions_need_trust), summary.needsTrust)
    }
    if (summary.failed.isNotEmpty()) {
        Section(localized(DMR.strings.missing_extensions_failed), summary.failed.map { (name, reason) -> "$name: $reason" })
    }
    if (summary.unavailable.isNotEmpty()) {
        Section(localized(DMR.strings.missing_extensions_unavailable), summary.unavailable)
    }
}

@Composable
private fun Section(title: String, lines: List<String>) {
    Column {
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        lines.forEach { Text("- $it", style = MaterialTheme.typography.bodyMedium) }
    }
}

/** The extension's name and store, with Android's 18+ marker when its sources may be NSFW. */
private fun MissingExtensionsPlan.Candidate.label(): String {
    val nsfw = if (extension.isNsfw) " (${localized(MR.strings.ext_nsfw_short)})" else ""
    return "${extension.name}$nsfw · ${extension.store.name}"
}
