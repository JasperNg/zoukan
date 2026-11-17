package zoukan.desktop.app

import java.awt.Desktop
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.Transferable
import java.io.File
import java.net.URI
import kotlin.system.exitProcess

/**
 * What the app does outside its own window, in one place: open a web page or a folder in the
 * system's apps, ask for a file or a folder with the system's picker, put something on the
 * clipboard, start an update's installer and quit. The app goes through [SystemEffects.current].
 *
 * A test puts a recorder in its place (the button crawl does), so that a click opens no browser,
 * blocks on no modal picker and never ends the test's JVM. The checks before an effect stay with
 * the caller and run in tests too: [ExternalLinks] passes on only web addresses, and the update's
 * installer is checked by Updates.install, which [installUpdate] calls.
 */
internal interface SystemEffects {
    /** Opens [uri], already checked to be a web address, in the system browser. */
    fun browse(uri: URI)

    /** Opens [folder] in Explorer or Finder. */
    fun openFolder(folder: File)

    /** The system's file picker; the chosen file, or null when cancelled. */
    fun pickFile(request: FileRequest): File?

    /** The system's folder picker, starting in [start]; the chosen folder, or null when cancelled. */
    fun pickFolder(window: Window?, title: String, start: File): File?

    /** Puts [contents] on the system clipboard. */
    fun copy(contents: Transferable)

    /** Starts the downloaded installer [file]; [quit] ends the app when the installer replaces it. */
    fun installUpdate(file: File, quit: () -> Unit)

    /** Ends the process with [code]. */
    fun exit(code: Int)

    companion object {
        /** The app's effects; tests replace them and put [Native] back when they are done. */
        @Volatile
        var current: SystemEffects = Native
    }

    /** The real effects: the system browser, file manager, pickers, clipboard and installer. */
    object Native : SystemEffects {
        override fun browse(uri: URI) = Desktop.getDesktop().browse(uri)

        override fun openFolder(folder: File) = Desktop.getDesktop().open(folder)

        override fun pickFile(request: FileRequest): File? {
            val mode = if (request.save) FileDialog.SAVE else FileDialog.LOAD
            val dialog = when (val owner = request.window) {
                is Frame -> FileDialog(owner, request.title, mode)
                is Dialog -> FileDialog(owner, request.title, mode)
                else -> FileDialog(null as Frame?, request.title, mode)
            }.apply {
                request.directory?.let { directory = it.absolutePath }
                request.fileName?.let { file = it }
                request.accept?.let { accept -> setFilenameFilter { _, name -> accept(name) } }
                isVisible = true
            }
            val name = dialog.file ?: return null
            return File(dialog.directory, name)
        }

        /** The native picker on macOS, Swing's on Windows and Linux (AWT's FileDialog picks only files there). */
        override fun pickFolder(window: Window?, title: String, start: File): File? {
            if (zoukan.desktop.Platform.current == zoukan.desktop.Platform.MACOS) {
                System.setProperty("apple.awt.fileDialogForDirectories", "true")
                try {
                    return pickFile(FileRequest(window, title, save = false, directory = start))
                } finally {
                    System.setProperty("apple.awt.fileDialogForDirectories", "false")
                }
            }
            val chooser = javax.swing.JFileChooser(start).apply {
                fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                dialogTitle = title
            }
            return if (chooser.showOpenDialog(window) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }

        override fun copy(contents: Transferable) = Toolkit.getDefaultToolkit().systemClipboard.setContents(contents, null)

        override fun installUpdate(file: File, quit: () -> Unit) = zoukan.desktop.update.Updates.install(file, quit)

        override fun exit(code: Int): Unit = exitProcess(code)
    }
}

/**
 * A request for the system's file picker: open ([save] false) or save, with the folder it starts
 * in, the name it suggests and which file names it shows ([accept]).
 */
internal data class FileRequest(
    val window: Window?,
    val title: String,
    val save: Boolean,
    val directory: File? = null,
    val fileName: String? = null,
    val accept: ((String) -> Boolean)? = null,
)
