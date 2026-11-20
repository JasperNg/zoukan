Looking to report an issue/bug or make a feature request? Please use the [issue forms](https://github.com/JasperNg/zoukan/issues/new/choose).

---

Thanks for your interest in contributing to Zoukan!


# Code contributions

Pull requests are welcome!

If you're interested in taking on [an open issue](https://github.com/JasperNg/zoukan/issues), please comment on it so others are aware.
You do not need to ask for permission nor an assignment.

## Prerequisites

Before you start, please note that the ability to use following technologies is **required** and that existing contributors will not actively teach them to you.

- [Kotlin](https://kotlinlang.org/) and [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) for the desktop app
- Basic [Android development](https://developer.android.com/), since much of the code is shared with the Android app

### Tools

- JDK 21 and [IntelliJ IDEA](https://www.jetbrains.com/idea/) or [Android Studio](https://developer.android.com/studio)
- Windows x64 or an Apple Silicon Mac to run and test the desktop app

Before opening a pull request, run `./gradlew spotlessCheck :desktop:build` (see [desktop/README.md](desktop/README.md)).
The desktop modules build without an Android SDK; avoid running root `./gradlew build` unless you also have `ANDROID_HOME` configured for the shared Android app.
The [CI workflow](.github/workflows/ci.yml) runs the same desktop checks on every pull request (plus the Android compile and unit tests).
The live end-to-end tests (`-Pzoukan.e2e=true`) are not part of it and run only locally.

Every pull request's checks, however few tests the change touches, include the wiring tests:
`:desktop:build` runs them through `check`, or run them alone with `./gradlew :desktop:wiringTest`.
They catch code that compiles but crashes when it runs: `InjektRegistrationsTest` looks up every
type the code gets from Injekt, and `ScreenSmokeTest` opens every screen and dialog. They need no
network and use a throwaway data folder.

### Changing a dependency

Every downloaded library and build plugin is checked against a SHA-256 checksum in
`gradle/verification-metadata.xml`, and the Gradle wrapper against `distributionSha256Sum`. After you
add or update a dependency, refresh the checksums for all three operating systems (the desktop runtime
and its native libraries differ per OS) and commit the changed file with the dependency change:

```
./gradlew --write-verification-metadata sha256 spotlessCheck :desktop:build :desktop:createDistributable --continue
./gradlew -I gradle/resolve-desktop-classpaths.init.gradle.kts --write-verification-metadata sha256 -Pzoukan.targetPlatform=linux-x64 :desktop:resolveDesktopClasspaths
./gradlew -I gradle/resolve-desktop-classpaths.init.gradle.kts --write-verification-metadata sha256 -Pzoukan.targetPlatform=macos-arm64 :desktop:resolveDesktopClasspaths
```

The init script in the last two registers `:desktop:resolveDesktopClasspaths`, a task that resolves the
`runtimeClasspath`, `testRuntimeClasspath`, `compileClasspath` and `testCompileClasspath`
configurations of `:desktop`. The `zoukan.targetPlatform` property
(`windows-x64`, `macos-arm64` or `linux-x64`) exists only for this; it does not build another OS's app.

A build that fails with "Dependency verification failed" means a checksum is missing or an artifact
changed. Checksums are recorded for `windows-x64`, `macos-arm64` and `linux-x64`; building on
another architecture (such as Intel macOS or ARM Linux) needs its platform checksums added first. If
a checksum is missing, regenerate as above. If an artifact changed under the same version, find out
why before touching the file: regenerating the checksum would accept the changed artifact.

## Getting help

- Open an [issue](https://github.com/JasperNg/zoukan/issues) or a draft pull request with your question.

# Translations

The shared strings come from Komikku, which translates them on [Weblate](https://hosted.weblate.org/engage/komikku-app/). See [Komikku's guide](https://komikku-app.github.io/docs/contribute#translation).


# Forks

Forks are allowed so long as they abide by [the project's LICENSE](https://github.com/JasperNg/zoukan/blob/master/LICENSE) and the Mozilla Public License 2.0 for `android-compat/`.

When creating a fork of the desktop app, remember to:

- To avoid confusion with Zoukan:
    - Change the app name and icons in [`desktop/packaging.gradle.kts`](desktop/packaging.gradle.kts) and `desktop/packaging/icons/`
    - Point the updater at your own repository with `-Pzoukan.releaseRepo=owner/name` (or change the default in [`desktop/build.gradle.kts`](desktop/build.gradle.kts))
    - Replace [`desktop/src/main/resources/zoukan-release-key.pem`](desktop/src/main/resources/zoukan-release-key.pem) with your own Ed25519 release signing public key and set `ZOUKAN_RELEASE_SIGNING_KEY` in your repository secrets
- To avoid installation conflicts:
    - Change the macOS `bundleId` and Windows `upgradeUuid` in [`desktop/packaging.gradle.kts`](desktop/packaging.gradle.kts)
