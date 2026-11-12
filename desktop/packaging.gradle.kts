// ZOUKAN -->
// Zoukan's installers (Phase 7 of the desktop port): an MSI for Windows x64 and a DMG for macOS on
// Apple Silicon, each with its own Java runtime. Applied from build.gradle.kts. RELEASING.md has
// the steps, the signing setup and the clean-install checks.
//
//   ./gradlew :desktop:createDistributable   the app folder (Zoukan/, Zoukan.app) for this OS
//   ./gradlew :desktop:packageMsi            Windows: build/packaging/installers/Zoukan-<v>-windows-x64.msi
//   ./gradlew :desktop:packageDmg            macOS: build/packaging/installers/Zoukan-<v>-macos-arm64.dmg
//   ./gradlew :desktop:notarizeDmg           macOS: notarize and staple the DMG
//   ./gradlew :desktop:packageInstaller      the installer for this OS
//
// The runtime comes from -Pzoukan.packageJdk=<JDK home> or ZOUKAN_PACKAGE_JDK (JetBrains Runtime 21
// for releases), else the JDK running Gradle. It must be a full JDK 21 with jmods/, jlink and
// jpackage.
//
// Signing (all optional; without it the build is unsigned and says so):
//   Windows  ZOUKAN_WINDOWS_SIGN_PFX + ZOUKAN_WINDOWS_SIGN_PASSWORD, or ZOUKAN_WINDOWS_SIGN_THUMBPRINT
//            (a certificate in the user's store, also tokens and cloud HSMs); ZOUKAN_WINDOWS_SIGNTOOL
//            when signtool.exe is not in the Windows SDK or on the PATH; ZOUKAN_WINDOWS_TIMESTAMP_URL
//   macOS    ZOUKAN_MAC_SIGN_IDENTITY ("Developer ID Application: Name (TEAMID)"), optional
//            ZOUKAN_MAC_KEYCHAIN; notarization: ZOUKAN_NOTARY_PROFILE (a notarytool keychain
//            profile) or ZOUKAN_NOTARY_APPLE_ID + ZOUKAN_NOTARY_PASSWORD + ZOUKAN_NOTARY_TEAM_ID
// Each also works as a Gradle property: zoukan.windows.signPfx, zoukan.mac.signIdentity, and so on.

import java.net.URI
import java.util.zip.ZipFile

val zoukanVersion = extra["zoukanVersion"] as String

@Suppress("UNCHECKED_CAST")
val zoukanJvmFlags = extra["zoukanJvmFlags"] as List<String>

val appName = "Zoukan"
val bundleId = "app.zoukan.desktop"

// Never change: it lets a newer MSI replace an installed one
val upgradeUuid = "6762535d-afc5-4a16-a385-a163ff7585fb"

val osName = System.getProperty("os.name").lowercase()
val isWindows = osName.startsWith("windows")
val isMac = osName.startsWith("mac")
val archId = when (val a = System.getProperty("os.arch").lowercase()) {
    "amd64", "x86_64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> a
}
val platformId = "${if (isWindows) {
    "windows"
} else if (isMac) {
    "macos"
} else {
    "linux"
}}-$archId"

fun setting(property: String, env: String): String? =
    (providers.gradleProperty(property).orNull ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val packageJdk: File = File(setting("zoukan.packageJdk", "ZOUKAN_PACKAGE_JDK") ?: System.getProperty("java.home"))
fun jdkTool(name: String) = File(packageJdk, "bin/$name${if (isWindows) ".exe" else ""}").absolutePath

val packagingDir = layout.buildDirectory.dir("packaging")
val inputDir = packagingDir.map { it.dir("input") }
val runtimeDir = packagingDir.map { it.dir("runtime") }
val appImageDir = packagingDir.map { it.dir("app") }
val installersDir = packagingDir.map { it.dir("installers") }

/** Runs a tool, failing the build with its output (secrets masked) when it fails. */
fun run(
    args: List<String>,
    secrets: List<String?> = emptyList(),
    env: Map<String, String> = emptyMap(),
    workingDir: File? = null,
): String {
    val process = ProcessBuilder(args).redirectErrorStream(true).apply {
        environment().putAll(env)
        workingDir?.let { directory(it) }
    }.start()
    val output = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()
    fun mask(text: String) = secrets.filterNotNull().filter { it.isNotEmpty() }.fold(text) { t, s -> t.replace(s, "***") }
    if (code != 0) {
        throw GradleException("${File(args.first()).name} failed ($code): ${mask(args.joinToString(" "))}\n${mask(output)}")
    }
    return output
}

// --- macOS signing ---------------------------------------------------------------------------

val macIdentity = setting("zoukan.mac.signIdentity", "ZOUKAN_MAC_SIGN_IDENTITY")
val macKeychain = setting("zoukan.mac.keychain", "ZOUKAN_MAC_KEYCHAIN")
val entitlements = file("packaging/macos/entitlements.plist")

fun codesign(target: File, options: Boolean = true) {
    val args = mutableListOf("/usr/bin/codesign", "--force", "--timestamp", "--sign", macIdentity!!)
    if (options) args += listOf("--options", "runtime", "--entitlements", entitlements.absolutePath)
    macKeychain?.let { args += listOf("--keychain", it) }
    args += target.absolutePath
    run(args)
}

/**
 * Notarization rejects unsigned native code anywhere in the app, also inside jars: sign every
 * Mach-O file (.dylib, .jnilib, and binaries without an extension) in a jar and put it back.
 */
fun signNativeCodeInJar(jar: File, workDir: File) {
    val natives = ZipFile(jar).use { zip ->
        zip.entries().toList().filter { entry ->
            !entry.isDirectory && !entry.name.endsWith(".class") &&
                zip.getInputStream(entry).use { isMachO(it.readNBytes(8)) }
        }.map { it.name }
    }
    if (natives.isEmpty()) return
    val dir = File(workDir, jar.nameWithoutExtension).apply {
        deleteRecursively()
        mkdirs()
    }
    ZipFile(jar).use { zip ->
        natives.forEach { name ->
            val out = File(dir, name).apply { parentFile.mkdirs() }
            zip.getInputStream(zip.getEntry(name)).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }
    natives.forEach { codesign(File(dir, it)) }
    // Put the signed files back at the same paths
    run(listOf("/usr/bin/zip", "-q", jar.absolutePath) + natives, workingDir = dir)
    logger.lifecycle("Zoukan: signed ${natives.size} native files in ${jar.name}")
}

/** Mach-O magic numbers; 0xcafebabe is also a class file, told apart by its small architecture count. */
fun isMachO(header: ByteArray): Boolean {
    if (header.size < 8) return false
    val magic = header.copyOfRange(0, 4).joinToString("") { "%02x".format(it) }
    val next = ((header[4].toInt() and 0xff) shl 24) or ((header[5].toInt() and 0xff) shl 16) or
        ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
    return when (magic) {
        "feedface", "feedfacf", "cefaedfe", "cffaedfe" -> true
        "cafebabe" -> next in 1..20
        else -> false
    }
}

// --- Windows signing -------------------------------------------------------------------------

val winPfx = setting("zoukan.windows.signPfx", "ZOUKAN_WINDOWS_SIGN_PFX")
val winPassword = setting("zoukan.windows.signPassword", "ZOUKAN_WINDOWS_SIGN_PASSWORD")
val winThumbprint = setting("zoukan.windows.signThumbprint", "ZOUKAN_WINDOWS_SIGN_THUMBPRINT")
val winTimestamp = setting("zoukan.windows.timestampUrl", "ZOUKAN_WINDOWS_TIMESTAMP_URL") ?: "http://timestamp.digicert.com"

// Whether this build's app ships signed: zoukanBuildInfo records it in the app's jar, so an
// installed build that has lost its signature can be told from one built without a certificate
// (zoukan.desktop.update.InstallerSignature). An ad hoc signed Mac app (no identity) is unsigned.
extra["zoukanSignedRelease"] = when {
    isWindows -> winPfx != null || winThumbprint != null
    isMac -> macIdentity != null
    else -> false
}

fun signtool(): String {
    setting("zoukan.windows.signtool", "ZOUKAN_WINDOWS_SIGNTOOL")?.let { return it }
    // The newest x64 signtool of an installed Windows SDK, else the PATH
    val kits = File(System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)", "Windows Kits/10/bin")
    return kits.listFiles()
        ?.filter { it.isDirectory && it.name.startsWith("10.") }
        ?.sortedByDescending { it.name }
        ?.map { File(it, "x64/signtool.exe") }
        ?.firstOrNull { it.isFile }
        ?.absolutePath
        ?: "signtool.exe"
}

fun signWindows(file: File) {
    if (winPfx == null && winThumbprint == null) {
        logger.warn("Zoukan: ${file.name} is not signed (no Windows code signing certificate configured; see RELEASING.md)")
        return
    }
    val args = mutableListOf(signtool(), "sign", "/fd", "SHA256", "/tr", winTimestamp, "/td", "SHA256", "/d", appName)
    if (winPfx != null) {
        args += listOf("/f", winPfx)
        winPassword?.let { args += listOf("/p", it) }
    } else {
        args += listOf("/sha1", winThumbprint!!)
    }
    args += file.absolutePath
    run(args, secrets = listOf(winPassword))
    logger.lifecycle("Zoukan: signed ${file.name}")
}

// --- Tasks -----------------------------------------------------------------------------------

/** The app's jar and its libraries, the input jpackage copies into the app. */
val prepareAppJars by tasks.registering(Sync::class) {
    group = "zoukan packaging"
    description = "Collects the app's jars for jpackage (and on macOS signs native code in them)"
    from(tasks.named("jar"))
    from(configurations.named("runtimeClasspath"))
    into(inputDir)
    if (isMac && macIdentity != null) {
        inputs.property("identity", macIdentity)
        doLast {
            val work = temporaryDir
            destinationDir.listFiles { f -> f.extension == "jar" }!!.forEach { signNativeCodeInJar(it, work) }
        }
    }
}

/** The bundled Java runtime: every module of the packaging JDK, since extensions may use any. */
val jlinkRuntime by tasks.registering {
    group = "zoukan packaging"
    description = "Builds the bundled Java runtime with jlink"
    val output = runtimeDir
    inputs.property("jdk", packageJdk.absolutePath)
    outputs.dir(output)
    doLast {
        val jmods = File(packageJdk, "jmods")
        if (!jmods.isDirectory) {
            throw GradleException("$packageJdk has no jmods folder; set -Pzoukan.packageJdk to a full JDK 21 (JetBrains Runtime 21 SDK, Temurin 21)")
        }
        val modules = jmods.listFiles { f -> f.name.endsWith(".jmod") }!!.map { it.name.removeSuffix(".jmod") }.sorted()
        val dir = output.get().asFile
        dir.deleteRecursively()
        run(
            listOf(
                jdkTool("jlink"), "--module-path", jmods.absolutePath, "--add-modules", modules.joinToString(","),
                "--strip-debug", "--no-header-files", "--no-man-pages", "--output", dir.absolutePath,
            ),
        )
        logger.lifecycle("Zoukan: runtime with ${modules.size} modules from $packageJdk")
    }
}

/** The app folder: Zoukan/ with Zoukan.exe on Windows, Zoukan.app on macOS, Zoukan/bin/Zoukan on Linux. */
val createDistributable by tasks.registering {
    group = "zoukan packaging"
    description = "Builds the app folder with jpackage"
    dependsOn(prepareAppJars, jlinkRuntime)
    val jar = tasks.named<Jar>("jar").flatMap { it.archiveFileName }
    inputs.dir(inputDir)
    inputs.dir(runtimeDir)
    inputs.files("packaging")
    inputs.property("version", zoukanVersion)
    inputs.property("identity", macIdentity.orEmpty())
    outputs.dir(appImageDir)
    doLast {
        val dest = appImageDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val args = mutableListOf(
            jdkTool("jpackage"), "--type", "app-image",
            "--dest", dest.absolutePath,
            "--input", inputDir.get().asFile.absolutePath,
            "--main-jar", jar.get(),
            "--main-class", "zoukan.desktop.MainKt",
            "--name", appName,
            "--app-version", zoukanVersion,
            "--vendor", appName,
            "--description", "Manga reader",
            "--copyright", "Komikku contributors, Apache License 2.0",
            "--runtime-image", runtimeDir.get().asFile.absolutePath,
        )
        (zoukanJvmFlags + listOf("-Dzoukan.packaged=true")).forEach { args += listOf("--java-options", it) }
        when {
            isWindows -> args += listOf("--icon", file("packaging/icons/zoukan.ico").absolutePath)
            isMac -> {
                args += listOf(
                    "--icon", file("packaging/icons/zoukan.icns").absolutePath,
                    "--mac-package-identifier", bundleId,
                    "--mac-package-name", appName,
                    "--mac-app-category", "public.app-category.books",
                    // Dark title bars follow the system setting
                    "--java-options", "-Dapple.awt.application.appearance=system",
                )
                if (macIdentity != null) {
                    // jpackage puts "Developer ID Application: " in front itself
                    args += listOf(
                        "--mac-sign",
                        "--mac-signing-key-user-name",
                        macIdentity.removePrefix("Developer ID Application: "),
                        "--mac-entitlements",
                        entitlements.absolutePath,
                    )
                    macKeychain?.let { args += listOf("--mac-signing-keychain", it) }
                }
            }
            else -> args += listOf("--icon", file("packaging/icons/zoukan.png").absolutePath)
        }
        run(args)
        when {
            isWindows -> signWindows(File(dest, "$appName/$appName.exe"))
            isMac && macIdentity == null ->
                logger.warn("Zoukan: $appName.app is not signed with a Developer ID (ZOUKAN_MAC_SIGN_IDENTITY not set); Gatekeeper will warn")
        }
        logger.lifecycle("Zoukan: app in $dest")
    }
}

/** WiX Toolset 3, which jpackage needs for MSIs: from the PATH, else downloaded once. */
val wixDir = layout.buildDirectory.dir("packaging/wix")
val downloadWix by tasks.registering {
    group = "zoukan packaging"
    description = "Downloads WiX Toolset 3.14 for jpackage, unless it is on the PATH"
    onlyIf { isWindows }
    outputs.dir(wixDir)
    doLast {
        val dir = wixDir.get().asFile
        if (File(dir, "candle.exe").isFile) return@doLast
        dir.mkdirs()
        val zip = File(temporaryDir, "wix314-binaries.zip")
        URI("https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip").toURL().openStream().use { input ->
            zip.outputStream().use { input.copyTo(it) }
        }
        copy {
            from(zipTree(zip))
            into(dir)
        }
    }
}

val packageMsi by tasks.registering {
    group = "zoukan packaging"
    description = "Builds the Windows installer (MSI) with jpackage and WiX"
    onlyIf("Windows only") { isWindows }
    dependsOn(createDistributable, downloadWix)
    inputs.dir(appImageDir)
    outputs.dir(installersDir)
    doLast {
        val dest = installersDir.get().asFile.apply { mkdirs() }
        dest.listFiles { f -> f.extension == "msi" }?.forEach { it.delete() }
        val pathWithWix = wixDir.get().asFile.absolutePath + File.pathSeparator + System.getenv("PATH").orEmpty()
        run(
            listOf(
                jdkTool("jpackage"), "--type", "msi",
                "--app-image", File(appImageDir.get().asFile, appName).absolutePath,
                "--dest", dest.absolutePath,
                "--name", appName,
                "--app-version", zoukanVersion,
                "--vendor", appName,
                "--description", "Manga reader",
                "--icon", file("packaging/icons/zoukan.ico").absolutePath,
                // Installs for the current user under %LOCALAPPDATA%, with no administrator
                // prompt; the data folder stays %APPDATA%\Zoukan
                "--win-per-user-install",
                "--win-menu", "--win-menu-group", appName,
                "--win-shortcut",
                "--win-upgrade-uuid", upgradeUuid,
            ),
            env = mapOf("PATH" to pathWithWix, "Path" to pathWithWix),
        )
        val msi = File(dest, "$appName-$zoukanVersion-$platformId.msi")
        File(dest, "$appName-$zoukanVersion.msi").renameTo(msi)
        signWindows(msi)
        logger.lifecycle("Zoukan: $msi")
    }
}

val packageDmg by tasks.registering {
    group = "zoukan packaging"
    description = "Builds the macOS disk image (DMG) with jpackage"
    onlyIf("macOS only") { isMac }
    dependsOn(createDistributable)
    inputs.dir(appImageDir)
    outputs.dir(installersDir)
    doLast {
        val dest = installersDir.get().asFile.apply { mkdirs() }
        dest.listFiles { f -> f.extension == "dmg" }?.forEach { it.delete() }
        // jpackage takes the mounted disk's icon from <name>-volume.icns in the resource folder,
        // not from the app's --icon; without it the volume shows Java's generic icon
        val resources = layout.buildDirectory.dir("packaging/dmg-resources").get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        file("packaging/icons/zoukan.icns").copyTo(File(resources, "$appName-volume.icns"))
        val args = mutableListOf(
            jdkTool("jpackage"), "--type", "dmg",
            "--resource-dir", resources.absolutePath,
            "--app-image", File(appImageDir.get().asFile, "$appName.app").absolutePath,
            "--dest", dest.absolutePath,
            "--name", appName,
            "--app-version", zoukanVersion,
            "--vendor", appName,
            "--mac-package-identifier", bundleId,
        )
        if (macIdentity != null) {
            args += listOf("--mac-sign", "--mac-signing-key-user-name", macIdentity.removePrefix("Developer ID Application: "))
            macKeychain?.let { args += listOf("--mac-signing-keychain", it) }
        }
        run(args)
        val dmg = File(dest, "$appName-$zoukanVersion-$platformId.dmg")
        File(dest, "$appName-$zoukanVersion.dmg").renameTo(dmg)
        if (macIdentity != null) codesign(dmg, options = false)
        logger.lifecycle("Zoukan: $dmg")
    }
}

val notarizeDmg by tasks.registering {
    group = "zoukan packaging"
    description = "Notarizes the DMG with Apple and staples the ticket"
    onlyIf("macOS only") { isMac }
    dependsOn(packageDmg)
    doLast {
        val profile = setting("zoukan.mac.notaryProfile", "ZOUKAN_NOTARY_PROFILE")
        val appleId = setting("zoukan.mac.notaryAppleId", "ZOUKAN_NOTARY_APPLE_ID")
        val password = setting("zoukan.mac.notaryPassword", "ZOUKAN_NOTARY_PASSWORD")
        val teamId = setting("zoukan.mac.notaryTeamId", "ZOUKAN_NOTARY_TEAM_ID")
        if (macIdentity == null) {
            throw GradleException("Notarization needs a signed build: set ZOUKAN_MAC_SIGN_IDENTITY")
        }
        val credentials = when {
            profile != null -> listOf("--keychain-profile", profile)
            appleId != null && password != null && teamId != null ->
                listOf("--apple-id", appleId, "--password", password, "--team-id", teamId)
            else -> throw GradleException("Set ZOUKAN_NOTARY_PROFILE, or ZOUKAN_NOTARY_APPLE_ID, ZOUKAN_NOTARY_PASSWORD and ZOUKAN_NOTARY_TEAM_ID")
        }
        val dmg = installersDir.get().asFile.listFiles { f -> f.extension == "dmg" }!!.single()
        val output = run(
            listOf("/usr/bin/xcrun", "notarytool", "submit", dmg.absolutePath, "--wait") + credentials,
            secrets = listOf(password),
        )
        logger.lifecycle(output)
        if (!output.contains("status: Accepted")) {
            val id = Regex("id: ([0-9a-f-]{36})").find(output)?.groupValues?.get(1)
            throw GradleException("Notarization was not accepted. Details: xcrun notarytool log $id <credentials>")
        }
        run(listOf("/usr/bin/xcrun", "stapler", "staple", dmg.absolutePath))
        logger.lifecycle(run(listOf("/usr/sbin/spctl", "--assess", "--type", "open", "--context", "context:primary-signature", "--verbose", dmg.absolutePath)))
        logger.lifecycle("Zoukan: notarized and stapled $dmg")
    }
}

tasks.register("packageInstaller") {
    group = "zoukan packaging"
    description = "Builds the installer for this OS: the MSI on Windows, the DMG on macOS"
    dependsOn(
        if (isWindows) {
            packageMsi
        } else if (isMac) {
            packageDmg
        } else {
            createDistributable
        },
    )
}
// ZOUKAN <--
