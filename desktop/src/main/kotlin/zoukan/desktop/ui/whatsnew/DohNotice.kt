package zoukan.desktop.ui.whatsnew

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import eu.kanade.tachiyomi.network.NetworkPreferences
import logcat.LogPriority
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import zoukan.desktop.app.runCatchingCancellable
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR

/**
 * Desktop only: DNS over HTTPS became on by default (Quad9) in this version, where it used to be off,
 * so someone who used Zoukan before is told once. Someone who chose a provider (off included) keeps
 * their choice and is not told; a new install starts with the default and is not told either.
 */
object DohNotice {
    /** What to do with the notice at this launch. */
    enum class Decision {
        /** Already handled at an earlier launch: nothing to do */
        NOTHING,

        /** Nothing to tell this user, ever: record it as handled */
        SKIP,

        /** Show it (and record it as handled, so it shows once) */
        SHOW,
    }

    /**
     * [handled]: the notice was shown or skipped before. [dohProviderStored]: the user has a stored
     * DNS over HTTPS choice, so the new default does not apply to them. [updatedFromEarlierVersion]:
     * an earlier version ran in this data folder (WhatsNew.updatedFrom). [onboardingDone]: the
     * first-run setup was finished or skipped, so this is not a new user.
     */
    fun decide(
        handled: Boolean,
        dohProviderStored: Boolean,
        updatedFromEarlierVersion: Boolean,
        onboardingDone: Boolean,
    ): Decision = when {
        handled -> Decision.NOTHING
        dohProviderStored -> Decision.SKIP
        !updatedFromEarlierVersion || !onboardingDone -> Decision.SKIP
        else -> Decision.SHOW
    }

    /** App state, so it never travels in a backup. */
    fun handled(store: PreferenceStore): Preference<Boolean> =
        store.getBoolean(Preference.appStateKey("zoukan_doh_notice_handled"), false)

    /**
     * Whether to show the notice at this launch, recording the decision. Never throws: when a setting
     * cannot be read or written, the notice is not shown (and may be shown at a later launch).
     */
    fun check(
        store: PreferenceStore,
        networkPreferences: NetworkPreferences,
        updatedFromEarlierVersion: Boolean,
        onboardingDone: Boolean,
    ): Boolean = runCatchingCancellable {
        val handled = handled(store)
        val decision = decide(
            handled = handled.get(),
            dohProviderStored = networkPreferences.dohProvider().isSet(),
            updatedFromEarlierVersion = updatedFromEarlierVersion,
            onboardingDone = onboardingDone,
        )
        if (decision != Decision.NOTHING) handled.set(true)
        decision == Decision.SHOW
    }.getOrElse {
        logcat(LogPriority.WARN, it) { "Could not check the DNS over HTTPS notice" }
        false
    }
}

/** Tells someone who used Zoukan before that DNS over HTTPS is now on, with a way to its setting. */
@Composable
fun DohNoticeDialog(onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.doh_notice_title)) },
        text = { Text(localized(DMR.strings.doh_notice_text)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_ok)) } },
        dismissButton = { TextButton(onClick = onOpenSettings) { Text(localized(DMR.strings.doh_notice_open_settings)) } },
    )
}
