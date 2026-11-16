package zoukan.desktop.ui.settings

import tachiyomi.i18n.MR
import zoukan.desktop.backup.StorePrompt
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR

/**
 * Asks whether a restore may add the extension stores a backup lists, in a Swing dialog. True adds
 * them, false restores without them, null cancels the restore.
 */
internal object SwingStorePrompt : StorePrompt {
    override fun confirm(stores: List<String>): Boolean? {
        val shown = stores.take(8).map { it.take(140) } + listOfNotNull(localized(DMR.strings.and_more, stores.size - 8).takeIf { stores.size > 8 })
        val message = if (stores.size == 1) {
            localized(DMR.strings.backup_adds_one_store, shown.joinToString("\n"))
        } else {
            localized(DMR.strings.backup_adds_stores, stores.size, shown.joinToString("\n"))
        }
        var choice = javax.swing.JOptionPane.CANCEL_OPTION
        val ask = Runnable {
            choice = javax.swing.JOptionPane.showOptionDialog(
                null,
                message,
                localized(MR.strings.pref_restore_backup),
                javax.swing.JOptionPane.YES_NO_CANCEL_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE,
                null,
                arrayOf(localized(MR.strings.action_add), localized(DMR.strings.restore_without), localized(MR.strings.action_cancel)),
                localized(DMR.strings.restore_without),
            )
        }
        if (javax.swing.SwingUtilities.isEventDispatchThread()) ask.run() else javax.swing.SwingUtilities.invokeAndWait(ask)
        return when (choice) {
            0 -> true
            1 -> false
            else -> null
        }
    }
}
