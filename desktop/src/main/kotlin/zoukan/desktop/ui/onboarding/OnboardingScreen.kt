package zoukan.desktop.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.interactor.GetFavorites
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.ExternalLinks
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.LocalWindow
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.InfoScreen
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.settings.StorageLocationPreference
import zoukan.desktop.ui.settings.ThemeSection
import zoukan.desktop.ui.settings.pickAndRestoreBackup
import zoukan.desktop.ui.whatsnew.UpdatedDialog
import zoukan.desktop.ui.whatsnew.WhatsNew
import zoukan.desktop.ui.whatsnew.WhatsNewScreen

/** Komikku's getting started guide, which Android's onboarding links to; it covers Zoukan too. */
const val GETTING_STARTED_URL = "https://komikku-app.github.io/docs/guides/getting-started"

/**
 * What the app shows once at launch, as Android's MainActivity: the first-run setup until it has
 * been finished, else "Updated to …" on the first launch after an update.
 */
@Composable
fun FirstRun(preferences: DesktopPreferences, navigator: () -> Navigator?) {
    val onboardingDone by preferences.shownOnboardingFlow().changes().collectAsState(preferences.shownOnboardingFlow().get())
    // Read once: whether this launch follows an update, and whether to set up a new install
    var updatedDialog by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val updated = WhatsNew.updatedFrom(preferences) != null
        if (!preferences.shownOnboardingFlow().get()) {
            // Someone who used Zoukan before the setup existed already has a library: no setup for them
            val hasLibrary = withContext(Dispatchers.IO) {
                runCatching { Injekt.get<GetFavorites>().await().isNotEmpty() }.getOrDefault(false)
            }
            if (hasLibrary) preferences.shownOnboardingFlow().set(true)
        }
        updatedDialog = updated && preferences.shownOnboardingFlow().get()
        checked = true
    }
    if (!checked) return
    if (!onboardingDone) {
        OnboardingScreen(onComplete = { preferences.shownOnboardingFlow().set(true) })
    } else if (updatedDialog) {
        UpdatedDialog(
            onDismiss = { updatedDialog = false },
            onOpenWhatsNew = {
                updatedDialog = false
                navigator()?.push(WhatsNewScreen())
            },
        )
    }
}

/**
 * Android's onboarding: the theme, the storage location and the guides, one step at a time.
 * Android's permissions step has nothing to ask for on a desktop. It covers the window, so the app
 * behind it cannot be used until it is finished; Settings > Advanced opens it again.
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit, onClose: (() -> Unit)? = null) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val steps = remember { OnboardingStep.entries }
    val last = step == steps.lastIndex
    val window = LocalWindow.current?.window
    Surface(
        Modifier.fillMaxSize().testTag("onboarding")
            // Clicks do not reach the app behind
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        color = MaterialTheme.colorScheme.background,
    ) {
        InfoScreen(
            icon = Icons.Outlined.RocketLaunch,
            headingText = "Welcome!",
            subtitleText = "Let's set some things up first. You can always change these in Settings later too.",
            acceptText = if (last) "Get started" else "Next",
            canAccept = steps[step].isComplete(),
            onAcceptClick = { if (last) onComplete() else step++ },
            secondaryButton = {
                when {
                    step > 0 -> TextButton(onClick = { step-- }) { Text("Back") }
                    onClose != null -> TextButton(onClick = onClose) { Text("Close") }
                }
            },
        ) {
            Surface(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val forward = targetState > initialState
                        (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
                    },
                    label = "onboardingStep",
                ) { shown ->
                    when (steps[shown]) {
                        OnboardingStep.Theme -> Column(Modifier.padding(vertical = 8.dp)) { ThemeSection() }
                        OnboardingStep.Storage -> StorageStep()
                        OnboardingStep.Guides -> GuidesStep(
                            onRestoreBackup = {
                                if (pickAndRestoreBackup(window)) {
                                    onComplete()
                                    // The restore's progress shows in Settings > Data and storage
                                    zoukan.desktop.ui.settings.openSettingsCategory(zoukan.desktop.ui.settings.SettingsCategory.Data)
                                    HomeScreen.selectTab(HomeTab.Settings)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

enum class OnboardingStep {
    Theme,
    Storage,
    Guides,
    ;

    /** Whether Next can be pressed: the storage step needs a folder Zoukan can write to. */
    fun isComplete(): Boolean = when (this) {
        Storage -> runCatching { Injekt.get<ZoukanStorage>().root.let { it.mkdirs() || it.isDirectory } }.getOrDefault(false)
        else -> true
    }
}

@Composable
private fun StorageStep() {
    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Select a folder where Zoukan will store chapter downloads, automatic backups and local source entries. " +
                "The folder below is fine for most people; a folder on a larger disk helps with many downloads.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        StorageLocationPreference()
    }
}

@Composable
private fun GuidesStep(onRestoreBackup: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("New to Zoukan? We recommend checking out the getting started guide.", style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = { runCatching { ExternalLinks.browse(GETTING_STARTED_URL) }.onFailure { Toasts.error("Could not open the page", it) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Getting started guide") }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(
            "Coming from Komikku, Mihon, TachiyomiSY or Zoukan on another computer? Restore a backup to bring your library over.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onRestoreBackup, modifier = Modifier.fillMaxWidth()) { Text("Restore backup") }
    }
}

/** Settings > Advanced > Onboarding guide: the setup again, over the app, as on Android. */
object Onboarding {
    val reopen = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/** The setup reopened from Settings; it does not change whether the first run is finished. */
@Composable
fun ReopenedOnboarding() {
    val open by Onboarding.reopen.collectAsState()
    if (open) OnboardingScreen(onComplete = { Onboarding.reopen.value = false }, onClose = { Onboarding.reopen.value = false })
}
