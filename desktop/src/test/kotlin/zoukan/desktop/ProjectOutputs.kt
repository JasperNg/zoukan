package zoukan.desktop

import java.io.File

/** The class path entries that are the project's own output, as tests that scan compiled classes see them. */
object ProjectOutputs {

    /** The desktop app's classes and the jars of the project's own modules, not libraries. */
    fun isProjectOutput(file: File): Boolean {
        val root = File(System.getProperty("user.dir")).absoluteFile.parentFile
        val path = file.absoluteFile
        return path.startsWith(root) && !path.path.contains("${File.separator}test") &&
            (path.isDirectory || path.name.endsWith(".jar"))
    }

    /** The project output on the test class path. */
    fun onClassPath(): List<File> = System.getProperty("java.class.path")
        .split(File.pathSeparator)
        .map(::File)
        .filter(::isProjectOutput)
}
