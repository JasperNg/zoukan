package zoukan.desktop

import android.app.Application
import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import exh.pref.DelegateSourcePreferences
import exh.source.ExhPreferences
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.data.extension.repository.ExtensionStoreRepositoryImpl
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.interactor.AddExtensionStore
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.source.StubSourceRepositoryImpl
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.webkit.CefCookieSync
import xyz.nulldev.androidcompat.webkit.KcefWebViewProvider
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.exh.BuiltInSources
import zoukan.desktop.local.LocalSource
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.storage.ZoukanStorage
import zoukan.extension.DesktopExtensionInstaller
import zoukan.extension.DesktopExtensionLoader
import zoukan.extension.DesktopExtensionManager
import zoukan.extension.DesktopSourceManager
import zoukan.extension.ExtensionTrust
import java.util.concurrent.Executors

/**
 * The desktop app's Injekt module: the same types the Android AppModule registers, as far as the
 * desktop runtime has them, so shared code and extensions find them.
 */
class DesktopAppModule(
    private val app: Application,
    private val dirs: ZoukanDirs,
) : InjektModule {
    override fun InjektRegistrar.registerInjectables() {
        addSingleton(app)
        addSingleton(dirs)
        zoukan.desktop.local.Archives.cacheRoot = java.io.File(dirs.root, "cache/archives")

        addSingletonFactory<SecretStore> { SecretStore.forPlatform(dirs.secrets) }

        addSingletonFactory<PreferenceStore> {
            // The same file as Android's default SharedPreferences, so keys match
            DesktopPreferenceStore(
                get<Context>().getSharedPreferences("${PACKAGE_NAME}_preferences", Context.MODE_PRIVATE),
            )
        }
        addSingletonFactory { NetworkPreferences(get()) }
        addSingletonFactory { SecurityPreferences(get()) }
        addSingletonFactory { ZoukanStorage(dirs, get()) }
        addSingletonFactory { CbzPassword(get(), get()) }
        // Read by HttpSource and EnhancedHttpSource. Delegated sources are not wired on desktop yet.
        addSingletonFactory { DelegateSourcePreferences(get()) }
        addSingletonFactory { zoukan.desktop.exh.DelegatedSources(get(), get(), get()) }
        addSingletonFactory { zoukan.desktop.security.AppLock(get(), get()) }
        addSingletonFactory { zoukan.desktop.security.Incognito(get(), get(), get()) }
        addSingletonFactory { eu.kanade.domain.sync.SyncPreferences(get()) }
        addSingletonFactory { eu.kanade.tachiyomi.data.sync.SyncManager() }
        addSingletonFactory { eu.kanade.domain.source.service.SourcePreferences(get()) }

        addSingletonFactory<SqlDriver> { DesktopDatabase.createDriver(dirs.database, get()) }
        addSingletonFactory<Database> { DesktopDatabase.createDatabase(get()) }
        addSingletonFactory<DatabaseHandler> {
            // SQLDelight's JDBC driver gives each thread its own connection and begins
            // transactions DEFERRED, so two coroutines writing at once (a backup restore does)
            // fail with SQLITE_BUSY. On Android the driver serializes them; here all database work
            // runs on one thread instead.
            val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "zoukan-database").apply { isDaemon = true } }
                .asCoroutineDispatcher()
            AndroidDatabaseHandler(get(), get(), queryDispatcher = dispatcher, transactionDispatcher = dispatcher)
        }

        addSingletonFactory {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
        addSingletonFactory<ProtoBuf> { ProtoBuf }

        addSingletonFactory { NetworkHelper(app, get()) }

        addSingletonFactory { ExtensionStoreService(get(), get(), get()) }
        addSingletonFactory<ExtensionStoreRepository> { ExtensionStoreRepositoryImpl(get(), get()) }
        addSingletonFactory { GetExtensionStores(get()) }
        addSingletonFactory { AddExtensionStore(get()) }
        addSingletonFactory<StubSourceRepository> { StubSourceRepositoryImpl(get()) }

        addSingletonFactory { ExtensionTrust(get()) }
        addSingletonFactory { DesktopExtensionLoader(dirs.extensions, get()) }
        addSingletonFactory { DesktopExtensionInstaller(dirs.extensions) { get<NetworkHelper>().client } }
        addSingletonFactory {
            val getStores = get<GetExtensionStores>()
            DesktopExtensionManager(get(), get(), get(), get()) { getStores.get() }
        }
        addSingletonFactory {
            val storage = get<ZoukanStorage>()
            val password = get<CbzPassword>()
            // ZOUKAN -->
            val sourcePreferences = get<eu.kanade.domain.source.service.SourcePreferences>()
            LocalSource(
                folder = { storage.local },
                password = { password.get() },
                allowHiddenFolders = { sourcePreferences.allowLocalSourceHiddenFolders().get() },
            )
            // ZOUKAN <--
        }
        // ZOUKAN -->
        addSingletonFactory { ExhPreferences(get()) }
        addSingletonFactory { BuiltInSources(app, get()) }
        addSingletonFactory<SourceManager> {
            DesktopSourceManager(
                get(),
                get(),
                builtInSources = listOf(get<LocalSource>()),
                optionalSources = get<BuiltInSources>().optional(),
                transform = get<zoukan.desktop.exh.DelegatedSources>()::transform,
            ).apply {
                mergedSourceIds = { mangaId -> get<GetMergedReferencesById>().await(mangaId).map { it.mangaSourceId } }
            }
        }
        // ZOUKAN <--

        // Extensions' WebViews: give Chromium the app's cookies before a page loads
        addSingletonFactory<KcefWebViewProvider.InitBrowserHandler> {
            object : KcefWebViewProvider.InitBrowserHandler {
                override fun init(provider: KcefWebViewProvider) = CefCookieSync.copyToCef()
            }
        }
    }

    companion object {
        /** The package name extensions see, the same as the Android app's. */
        const val PACKAGE_NAME = "app.komikku"
    }
}
