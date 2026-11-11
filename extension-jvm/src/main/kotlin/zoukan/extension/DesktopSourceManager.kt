package zoukan.extension

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.ConcurrentHashMap

/**
 * The desktop SourceManager: the built-in sources (the local source, merged manga, E-Hentai), the
 * sources of the loaded extensions, plus stubs for sources whose extension is missing.
 *
 * [optionalSources] are built-in sources that come and go with settings, as AndroidSourceManager
 * adds E-Hentai and ExHentai only when they are turned on.
 *
 * [transform] lets the app swap an extension's source for its own enhanced version (or drop it),
 * as AndroidSourceManager delegates MangaDex, NHentai and others.
 */
class DesktopSourceManager(
    private val extensionManager: DesktopExtensionManager,
    private val sourceRepository: StubSourceRepository,
    private val builtInSources: List<Source> = emptyList(),
    private val optionalSources: Flow<List<Source>> = flowOf(emptyList()),
    private val transform: (Source) -> Source? = { it },
) : SourceManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val sourcesMapFlow = MutableStateFlow<Map<Long, Source>>(builtInSources.associateBy { it.id })

    private val stubSourcesMap = ConcurrentHashMap<Long, StubSource>()

    override val sources: Flow<List<Source>> = sourcesMapFlow.map { it.values.toList() }

    init {
        scope.launch {
            extensionManager.installedExtensionsFlow.combine(optionalSources) { extensions, optional ->
                extensions to optional
            }.collect { (extensions, optional) ->
                val extensionSources = extensions.values.flatMap { it.sources }.mapNotNull(transform)
                val map = (builtInSources + optional + extensionSources).associateBy { it.id }
                sourcesMapFlow.value = map
                extensionSources.forEach { source ->
                    runCatching { sourceRepository.upsertStubSource(source.id, source.lang, source.name) }
                }
                if (extensionManager.isInitialized.value) _isInitialized.value = true
            }
        }
        scope.launch {
            extensionManager.isInitialized.collect { if (it) _isInitialized.value = true }
        }
        scope.launch {
            sourceRepository.subscribeAll().collect { stubs ->
                stubs.forEach { stubSourcesMap[it.id] = it }
            }
        }
    }

    override fun get(sourceKey: Long): Source? = sourcesMapFlow.value[sourceKey]

    override fun getOrStub(sourceKey: Long): Source = sourcesMapFlow.value[sourceKey]
        ?: stubSourcesMap.getOrPut(sourceKey) {
            runBlocking { sourceRepository.getStubSource(sourceKey) } ?: StubSource(sourceKey, "", "")
        }

    override fun getAll(): List<Source> = sourcesMapFlow.value.values.toList()

    override fun getOnlineSources(): List<HttpSource> = sourcesMapFlow.value.values.filterIsInstance<HttpSource>()

    override fun getVisibleOnlineSources(): List<HttpSource> = getOnlineSources()

    override fun getVisibleSources(): List<Source> = getAll()

    override suspend fun getMergedSources(mangaId: Long): List<Source> =
        mergedSourceIds(mangaId).distinct().map { getOrStub(it) }

    /** Set by the app: the source ids a merged entry's references use. */
    var mergedSourceIds: suspend (mangaId: Long) -> List<Long> = { emptyList() }

    override fun getStubSources(): List<StubSource> {
        val online = sourcesMapFlow.value.keys
        return stubSourcesMap.values.filterNot { it.id in online }
    }
}
