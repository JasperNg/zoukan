package zoukan.desktop.sync

import android.app.Application
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.sync.SyncNotifier
import eu.kanade.tachiyomi.data.sync.service.SyncData
import eu.kanade.tachiyomi.data.sync.service.WebDavSyncService
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.e2e.FixtureFailure
import zoukan.desktop.e2e.FixtureServer
import zoukan.desktop.e2e.FixtureSyncServer
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testing.UserMessages
import zoukan.desktop.track.MapPreferenceStore
import java.util.concurrent.TimeUnit

/**
 * WebDAV sync (the shared WebDavSyncService, as SyncManager runs it) against the fixture sync server
 * under injected faults: the download of the remote library failing or arriving damaged, and the
 * upload failing. For each: the user gets the sync error notification, the next sync after the fault
 * is healed works, and the remote library (another device's data) is not replaced by anything built
 * from a reply the app could not read.
 *
 * This is the nearest seam: SyncManager.syncData also builds the backup from the database and
 * restores the merged one, which the restore fault tests cover (RestoreFaultsTest); its service
 * object is made inside doSync, so a test cannot hand it a client of its own.
 */
class SyncFaultsTest {
    /** Swapped in before each test and put back after it, also when [start] throws. */
    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val server = FixtureServer()
    private val sync = FixtureSyncServer(server)
    private val messages = UserMessages()
    private val preferences = SyncPreferences(MapPreferenceStore())

    private val davFile = "/dav/${sync.davFolder}/backup.proto"

    /** Another device's library, already on the server. */
    private val remote = Backup(backupManga = listOf(BackupManga(source = 1, url = "/remote", title = "From the other device")))
    private val remoteBytes = BackupCodec.encode(remote)

    private val local = SyncData(
        deviceId = "desktop",
        backup = Backup(backupManga = listOf(BackupManga(source = 1, url = "/local", title = "From this computer"))),
    )

    @BeforeEach
    fun start() {
        val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        Injekt.addSingleton<NetworkHelper>(mockk { every { plainClient } returns client })
        preferences.webDavUrl().set(sync.webDavUrl)
        preferences.webDavUsername().set(sync.user)
        preferences.webDavPassword().set(sync.password)
        preferences.webDavFolder().set(sync.davFolder)
        sync.seed(remoteBytes)
        sync.calls.clear()
    }

    @AfterEach
    fun close() {
        messages.close()
        sync.close()
        server.close()
    }

    private fun service() = WebDavSyncService(
        mockk<Application>(relaxed = true),
        Json { ignoreUnknownKeys = true },
        preferences,
        SyncNotifier(mockk<Application>(relaxed = true)),
        ProtoBuf,
    )

    private fun syncNow(): Backup? = runBlocking { service().doSync(local) }

    /** The sync failed in a way the user sees, and the remote library and the last ETag are as they were. */
    private fun failedAndKeptRemote(result: Backup?, etagBefore: String) {
        result.shouldBeNull()
        messages.awaitError()
        sync.remoteBackup shouldBe remoteBytes
        sync.etag shouldBe "v1"
        preferences.lastSyncEtag().get() shouldBe etagBefore
    }

    /** After the fault is healed a sync merges both libraries and uploads them. */
    private fun syncWorks() {
        server.clearFailures()
        val merged = syncNow().shouldNotBeNull()
        merged.backupManga.map { it.url }.toSet() shouldBe setOf("/local", "/remote")
        sync.etag shouldBe "v2"
        preferences.lastSyncEtag().get() shouldBe "v2"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("downloadFaults")
    fun `a remote library download that fails is a sync error, uploads nothing, and the next sync works`(name: String, failure: FixtureFailure) {
        server.fail(davFile, failure)

        failedAndKeptRemote(syncNow(), etagBefore = "")
        sync.calls.filter { it.method == "PUT" }.shouldBeEmpty()

        syncWorks()
    }

    @Test
    fun `an upload that fails is a sync error, keeps the remote library and the last ETag, and the next sync works`() {
        // The download goes through (a pass-through rule used once), the upload fails
        server.fail(davFile, FixtureFailure.Slow(0), times = 1)
        server.fail(davFile, FixtureFailure.Status(507, "Insufficient storage"))

        failedAndKeptRemote(syncNow(), etagBefore = "")

        syncWorks()
    }

    @Test
    @Disabled(
        "Known defect: a remote library that arrives damaged (or is not a backup) is treated as no remote " +
            "library: WebDavSyncService.pullSyncData returns (null, \"\") on a decode error, and pushSyncData " +
            "then uploads this computer's library without If-Match, replacing the other device's data. The " +
            "user is told the sync succeeded. The Android app does the same.",
    )
    fun `a damaged remote library is a sync error and is not overwritten`() {
        // Damaged once, in transit: the file on the server is good
        server.fail(davFile, FixtureFailure.Corrupt(fromByte = 0), times = 1)

        val result = syncNow()

        sync.calls.filter { it.method == "PUT" }.shouldBeEmpty()
        failedAndKeptRemote(result, etagBefore = "")
    }

    companion object {
        @JvmStatic
        fun downloadFaults() = listOf(
            arrayOf("HTTP 503", FixtureFailure.Status(503)),
            arrayOf("connection cut mid-body", FixtureFailure.Truncate(keepBytes = 10)),
            arrayOf("connection closed without an answer", FixtureFailure.Drop),
        )
    }
}
