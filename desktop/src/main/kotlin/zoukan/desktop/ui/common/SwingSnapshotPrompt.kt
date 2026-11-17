package zoukan.desktop.ui.common

import tachiyomi.i18n.MR
import zoukan.desktop.backup.SnapshotFailedPrompt
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * Asks in a Swing dialog, as the restore's other questions do, whether a change may go ahead
 * without the safety backup that failed. Cancel is the default; a dialog that cannot be shown
 * (no display) cancels.
 */
internal object SwingSnapshotPrompt : SnapshotFailedPrompt {
    override fun continueWithout(error: String): Boolean {
        val cancel = localized(MR.strings.action_cancel)
        var choice = JOptionPane.CLOSED_OPTION
        val ask = Runnable {
            choice = JOptionPane.showOptionDialog(
                null,
                localized(DMR.strings.safety_backup_failed_text, error),
                localized(DMR.strings.safety_backup_failed_title),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                arrayOf(localized(DMR.strings.continue_without_backup), cancel),
                cancel,
            )
        }
        if (SwingUtilities.isEventDispatchThread()) ask.run() else SwingUtilities.invokeAndWait(ask)
        return choice == 0
    }
}

/** What the user-started changes ask when their safety backup fails; tests replace it. */
internal var snapshotFailedPrompt: SnapshotFailedPrompt = SwingSnapshotPrompt
