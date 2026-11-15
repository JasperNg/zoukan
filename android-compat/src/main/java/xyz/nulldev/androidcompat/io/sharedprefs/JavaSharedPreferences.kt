package xyz.nulldev.androidcompat.io.sharedprefs

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.content.SharedPreferences
import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.serialization.decodeValue
import com.russhwolf.settings.serialization.decodeValueOrNull
import com.russhwolf.settings.serialization.encodeValue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import xyz.nulldev.androidcompat.util.SafePath
import xyz.nulldev.ts.config.ApplicationRootDir
import java.util.Properties
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalSerializationApi::class, ExperimentalSettingsApi::class)
class JavaSharedPreferences(
    private val key: String,
) : SharedPreferences {
    companion object {
        private val logger = KotlinLogging.logger {}

        // ZOUKAN -->
        /**
         * Properties keep every value as text, so the type each value was written with is kept
         * beside it under this prefix. getAll() needs it: backups, like Android's getAll(), must see
         * an Int as an Int and a Set as a Set, not as text.
         */
        private const val TYPE_PREFIX = "__zoukan_type__."
        private const val TYPE_STRING = "string"
        private const val TYPE_STRING_SET = "stringset"
        private const val TYPE_INT = "int"
        private const val TYPE_LONG = "long"
        private const val TYPE_FLOAT = "float"
        private const val TYPE_DOUBLE = "double"
        private const val TYPE_BOOLEAN = "boolean"

        /** Whether [name] is one of the entries a Set stored under [key] is kept in: key.size, key.0, key.1... */
        private fun isSetEntry(
            key: String,
            name: String,
        ): Boolean {
            if (name.length <= key.length + 1 || !name.startsWith(key) || name[key.length] != '.') return false
            val suffix = name.substring(key.length + 1)
            return suffix == "size" || suffix.all { it in '0'..'9' }
        }

        /** The file the preferences named [key] are kept in (Context.getSharedPreferencesPath). */
        @JvmStatic
        fun fileFor(key: String): java.io.File =
            Path(
                ApplicationRootDir,
                "settings",
                "${SafePath.buildValidFilename(key)}.xml",
            ).toFile()

        /** Files with an apply() not yet written; flushed when the JVM shuts down. */
        private val unsaved = java.util.concurrent.ConcurrentHashMap.newKeySet<JavaSharedPreferences>()

        /** Background writers started by apply() that have not finished; tests wait for them. */
        private val writers = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()

        init {
            Runtime.getRuntime().addShutdownHook(Thread(::flushPendingSaves, "shared-preferences-flush"))
        }

        /**
         * Writes every change made with apply() that is not on disk yet. Runs at JVM shutdown, so a
         * change just before quitting is not lost.
         */
        @JvmStatic
        fun flushPendingSaves() {
            unsaved.toList().forEach { prefs ->
                // save() takes the file's lock, so this never interleaves with the background writer;
                // writing the latest snapshot again is harmless
                prefs.save()
                unsaved.remove(prefs)
            }
        }

        /**
         * Waits until every writer started by apply() has finished, then writes anything still
         * unsaved. For tests that delete the settings folder afterwards; not called by the app.
         * Throws [kotlinx.coroutines.TimeoutCancellationException] if a writer is still running
         * after [timeout].
         */
        @ForTestsOnly
        @JvmStatic
        fun awaitPendingSaves(timeout: Duration = 10.seconds) {
            // No file lock is held here: the writers take them, and joining while holding one
            // could deadlock
            runBlocking {
                withTimeout(timeout) {
                    while (writers.isNotEmpty()) writers.toList().joinAll()
                }
            }
            flushPendingSaves()
        }

        /** The number of apply() writers still running; for tests. */
        @ForTestsOnly
        @JvmStatic
        fun pendingWriterCount(): Int = writers.size
        // ZOUKAN <--
    }

    sealed class Action {
        data class Add(
            val key: String,
            val value: Any,
        ) : Action()

        data class Remove(
            val key: String,
        ) : Action()

        data object Clear : Action()
    }

    // ZOUKAN -->
    private var migrateToCodec = false
    // ZOUKAN <--

    private val file = fileFor(key).toPath()
    private val properties =
        Properties().also { properties ->
            try {
                // ZOUKAN -->
                // A save that an older version left half done (the file deleted, the new one
                // still in .tmp)
                val leftover = file.resolveSibling("${file.name}.tmp")
                if (!file.exists() && leftover.exists()) {
                    java.nio.file.Files.move(leftover, file)
                }
                // ZOUKAN <--
                if (file.exists()) {
                    // ZOUKAN -->
                    val stored = file.readBytes()
                    val codec = SettingsFileCodec.current
                    val plain = if (codec != null && codec.isEncoded(stored)) codec.decode(stored) else stored
                    // A plain file from before encryption is encrypted when it is read
                    migrateToCodec = codec != null && plain === stored
                    plain.inputStream().use { properties.loadFromXML(it) }
                    // ZOUKAN <--
                }
            } catch (e: Exception) {
                logger.error(e) { "Error loading settings from $key" }
                // ZOUKAN -->
                // The next save would replace a file that could not be read (a lost key, a damaged
                // file); keep it beside the new one
                runCatching {
                    file.copyTo(file.resolveSibling("${file.name}.unreadable"), overwrite = true)
                }
                // ZOUKAN <--
            }
        }
    private val preferences = PropertiesSettings(properties)

    // ZOUKAN -->
    init {
        if (migrateToCodec && !properties.isEmpty) save()
    }
    // ZOUKAN <--

    // ZOUKAN -->
    // As Android, listeners are held weakly: an extension's listener must not keep its class loader
    // alive after the extension is updated or removed. Guarded by itself; notifications use a copy.
    private val listeners =
        java.util.WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Any>()
    // ZOUKAN <--

    // TODO: 2021-05-29 Need to find a way to get this working with all pref types
    // ZOUKAN -->
    // Values with their own types, as Android returns them. Sets are stored as key.0, key.1 and
    // key.size; they come back as one Set. Values written before types were kept come back as text.
    override fun getAll(): MutableMap<String, *> =
        synchronized(properties) {
            val keys = preferences.keys
            val types =
                keys
                    .filter { it.startsWith(TYPE_PREFIX) }
                    .associate { it.removePrefix(TYPE_PREFIX) to preferences.getStringOrNull(it) }
            // A key.size is a Set's only when the key is typed as a Set, or (written before types
            // were kept) nothing types it otherwise and the indexed entries are all there
            val setKeys =
                keys
                    .filter { it.endsWith(".size") }
                    .map { it.removeSuffix(".size") }
                    .filter { base ->
                        val baseType = types[base]
                        baseType == TYPE_STRING_SET ||
                            (baseType == null && "$base.size" !in types && hasSetEntries(base, keys))
                    } +
                    types.filterValues { it == TYPE_STRING_SET }.keys
            val result = mutableMapOf<String, Any?>()
            types.forEach { (key, type) ->
                readTyped(key, type)?.let { result[key] = it }
            }
            setKeys
                .filter { it !in result }
                .forEach { key ->
                    readTyped(key, TYPE_STRING_SET)?.let { result[key] = it }
                }
            keys
                .filter { key ->
                    !key.startsWith(TYPE_PREFIX) &&
                        key !in result &&
                        setKeys.none { isSetEntry(it, key) }
                }.forEach { key -> result[key] = preferences.getStringOrNull(key) }
            result
        }

    private fun hasSetEntries(
        key: String,
        keys: Set<String>,
    ): Boolean {
        val size = preferences.getStringOrNull("$key.size")?.toIntOrNull() ?: return false
        return size >= 0 && (0 until size).all { "$key.$it" in keys }
    }

    private fun readTyped(
        key: String,
        type: String?,
    ): Any? =
        try {
            when (type) {
                TYPE_STRING_SET -> preferences.decodeValueOrNull(SetSerializer(String.serializer()), key)
                TYPE_INT -> preferences.getIntOrNull(key)
                TYPE_LONG -> preferences.getLongOrNull(key)
                TYPE_FLOAT -> preferences.getFloatOrNull(key)
                TYPE_DOUBLE -> preferences.getDoubleOrNull(key)
                TYPE_BOOLEAN -> preferences.getBooleanOrNull(key)
                else -> preferences.getStringOrNull(key)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not read $key in ${this.key} as $type" }
            preferences.getStringOrNull(key)
        }
    // ZOUKAN <--

    override fun getString(
        key: String,
        defValue: String?,
    ): String? =
        if (defValue != null) {
            preferences.getString(key, defValue)
        } else {
            preferences.getStringOrNull(key)
        }

    override fun getStringSet(
        key: String,
        defValues: Set<String>?,
    ): Set<String>? {
        try {
            return if (defValues != null) {
                preferences.decodeValue(SetSerializer(String.serializer()), key, defValues)
            } else {
                preferences.decodeValueOrNull(SetSerializer(String.serializer()), key)
            }
        } catch (_: SerializationException) {
            throw ClassCastException("$key was not a StringSet")
        }
    }

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = preferences.getInt(key, defValue)

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = preferences.getLong(key, defValue)

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = preferences.getFloat(key, defValue)

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = preferences.getBoolean(key, defValue)

    override fun contains(key: String): Boolean =
        key in preferences.keys /* ZOUKAN --> */ || "$key.size" in preferences.keys /* ZOUKAN <-- */

    // ZOUKAN -->
    // Called after the properties' lock is released, on the writer's thread. A listener that throws
    // is logged and the others still run: it must not fail the writer's apply() or commit().
    private fun notify(keys: List<String>) {
        if (keys.isEmpty()) return
        val current = synchronized(listeners) { listeners.keys.toList() }
        keys.forEach { key ->
            current.forEach { listener ->
                try {
                    listener.onSharedPreferenceChanged(this, key)
                } catch (e: Exception) {
                    logger.error(e) { "A listener of ${this.key} failed on $key" }
                } catch (e: LinkageError) {
                    logger.error(e) { "A listener of ${this.key} failed on $key" }
                }
            }
        }
    }

    /** What is stored for [key]: its value, its type and a Set's entries. Under the properties' lock. */
    private fun stateOf(key: String): Map<String, String?> =
        preferences.keys
            .filter { it == key || it == TYPE_PREFIX + key || isSetEntry(key, it) }
            .associateWith { preferences.getStringOrNull(it) }

    /** Removes the entries a Set stored under [key] is kept in (key.size, key.0, key.1...), and only those. */
    private fun removeSetEntries(key: String) {
        preferences.keys.forEach { name ->
            if (isSetEntry(key, name)) preferences.remove(name)
        }
    }

    /** Whether [key] holds a Set, or one written before types were kept; not a key.size of its own type. */
    private fun storedAsSet(key: String): Boolean {
        val storedType = preferences.getStringOrNull(TYPE_PREFIX + key)
        return storedType == TYPE_STRING_SET ||
            (storedType == null && "$key.size" in preferences.keys && "$TYPE_PREFIX$key.size" !in preferences.keys)
    }
    // ZOUKAN <--

    private fun saveAction(
        action: Action,
        changed: MutableList<String>,
    ) {
        when (action) {
            is Action.Add -> {
                // ZOUKAN -->
                val before = stateOf(action.key)
                // A value of another type replaces a Set: its entries go, or contains() and
                // getStringSet() would still find it, and a later remove() would leave them
                if (action.value !is Set<*> && storedAsSet(action.key)) removeSetEntries(action.key)
                // ZOUKAN <--
                when (val value = action.value) {
                    is Set<*> -> {
                        // ZOUKAN: the old value and Set entries go; a value of its own under
                        // key.<name> (domain.custom beside domain) stays
                        preferences.remove(action.key)
                        removeSetEntries(action.key)
                        @Suppress("UNCHECKED_CAST")
                        preferences.encodeValue(SetSerializer(String.serializer()), action.key, value as Set<String>)
                    }

                    is String -> {
                        preferences.putString(action.key, value)
                    }

                    is Int -> {
                        preferences.putInt(action.key, value)
                    }

                    is Long -> {
                        preferences.putLong(action.key, value)
                    }

                    is Float -> {
                        preferences.putFloat(action.key, value)
                    }

                    is Double -> {
                        preferences.putDouble(action.key, value)
                    }

                    is Boolean -> {
                        preferences.putBoolean(action.key, value)
                    }
                }
                // ZOUKAN -->
                val type =
                    when (action.value) {
                        is Set<*> -> TYPE_STRING_SET
                        is String -> TYPE_STRING
                        is Int -> TYPE_INT
                        is Long -> TYPE_LONG
                        is Float -> TYPE_FLOAT
                        is Double -> TYPE_DOUBLE
                        is Boolean -> TYPE_BOOLEAN
                        else -> null
                    }
                if (type != null) preferences.putString(TYPE_PREFIX + action.key, type)

                // As Android, writing the value a key already has notifies no one
                if (stateOf(action.key) != before) changed += action.key
                // ZOUKAN <--
            }

            is Action.Remove -> {
                // ZOUKAN -->
                val before = stateOf(action.key)
                val wasSet = storedAsSet(action.key)
                // ZOUKAN <--
                preferences.remove(action.key)
                // ZOUKAN -->
                preferences.remove(TYPE_PREFIX + action.key)
                // ZOUKAN <--
                /*
                 Set<String> are stored like
                 key.0 = value1
                 key.1 = value2
                 key.size = 2
                 ZOUKAN: only those entries go, not other keys that start with "key." (domain.custom
                 beside domain)
                 */
                if (wasSet) removeSetEntries(action.key)

                // ZOUKAN -->
                if (stateOf(action.key) != before) changed += action.key
                // ZOUKAN <--
            }

            Action.Clear -> {
                val keys = preferences.keys

                preferences.clear()

                keys.forEach { key ->
                    // ZOUKAN -->
                    if (key.startsWith(TYPE_PREFIX)) return@forEach
                    // ZOUKAN <--
                    changed += key

                    val isSetKey = key.endsWith(".size")
                    if (isSetKey) {
                        changed += key.removeSuffix(".size")
                    }
                }
            }
        }
    }

    /** Applies [actions] to the properties; returns the keys whose listeners are to be told. */
    private fun saveActions(actions: List<Action>): List<String> =
        synchronized(properties) {
            val changed = mutableListOf<String>()
            actions.forEach { saveAction(it, changed) }
            changed
        }

    private fun getSnapshot(): Properties =
        synchronized(properties) {
            Properties().apply { putAll(properties) }
        }

    @OptIn(ExperimentalAtomicApi::class)
    private var dirty = AtomicBoolean(false)

    @OptIn(ExperimentalAtomicApi::class)
    private var pendingAsyncSave = AtomicBoolean(false)

    private fun save(properties: Properties): Boolean =
        synchronized(file) {
            try {
                if (properties.isEmpty) {
                    file.deleteIfExists()
                } else {
                    file.createParentDirectories()

                    val tempFile = file.resolveSibling("${file.name}.tmp")

                    // ZOUKAN -->
                    val plain = java.io.ByteArrayOutputStream().also { properties.storeToXML(it, null) }.toByteArray()
                    tempFile.writeBytes(SettingsFileCodec.current?.encode(plain) ?: plain)
                    // ZOUKAN <--

                    // ZOUKAN -->
                    // Replace in one step: deleting first and then copying loses every setting
                    // if the app stops in between
                    try {
                        java.nio.file.Files.move(
                            tempFile,
                            file,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                        java.nio.file.Files.move(tempFile, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                    // ZOUKAN <--
                }

                return true
            } catch (e: Exception) {
                logger.error(e) { "Error saving settings $key" }
                return false
            }
        }

    private fun save(actions: List<Action>): Boolean {
        val changed = saveActions(actions)

        val saved = save()
        // ZOUKAN: outside the lock, so a listener that reads or writes these preferences cannot
        // deadlock, and one that throws does not fail the commit
        notify(changed)
        return saved
    }

    // ZOUKAN: the snapshot is taken under the file's lock, so a writer holding an older snapshot
    // cannot write it over a newer one that commit(), apply() or the shutdown flush already wrote
    private fun save(): Boolean = synchronized(file) { save(getSnapshot()) }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalAtomicApi::class)
    private fun startAsyncSaver() {
        if (!pendingAsyncSave.compareAndSet(false, true)) {
            return
        }

        // ZOUKAN: the job is kept so tests can wait for it (awaitPendingSaves)
        val job = GlobalScope.launch(Dispatchers.IO) {
            while (true) {
                dirty.store(false)

                save()

                if (!dirty.load()) {
                    // ZOUKAN: off the shutdown flush's list before the next apply() can start a
                    // writer, and back on it if this writer carries on
                    unsaved.remove(this@JavaSharedPreferences)
                    pendingAsyncSave.store(false)

                    if (dirty.load() && pendingAsyncSave.compareAndSet(false, true)) {
                        unsaved.add(this@JavaSharedPreferences)
                        continue
                    }

                    break
                }
            }
        }
        // ZOUKAN -->
        // Added before invokeOnCompletion, which runs at once if the job already finished, so a
        // fast writer is never left in the set
        writers.add(job)
        job.invokeOnCompletion { writers.remove(job) }
        // ZOUKAN <--
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun saveAsync(actions: List<Action>) {
        val changed = saveActions(actions)

        dirty.store(true)

        startAsyncSaver()

        // ZOUKAN -->
        // After the writer is started: a writer that is ending removes the file from the list
        // before it lets a new one start, so an add before that could be removed while this
        // change is still unwritten, and the shutdown flush would skip it
        unsaved.add(this)
        // ZOUKAN <--

        // ZOUKAN -->
        notify(changed)
        // ZOUKAN <--
    }

    override fun edit(): SharedPreferences.Editor = Editor(::save, ::saveAsync)

    class Editor(
        private val save: (actions: List<Action>) -> Boolean,
        private val saveAsync: (actions: List<Action>) -> Unit,
    ) : SharedPreferences.Editor {
        private val actions = mutableListOf<Action>()

        override fun putString(
            key: String,
            value: String?,
        ): SharedPreferences.Editor {
            actions +=
                if (value != null) {
                    Action.Add(key, value)
                } else {
                    Action.Remove(key)
                }
            return this
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor {
            actions +=
                if (values != null) {
                    Action.Add(key, values)
                } else {
                    Action.Remove(key)
                }
            return this
        }

        override fun putInt(
            key: String,
            value: Int,
        ): SharedPreferences.Editor {
            actions += Action.Add(key, value)
            return this
        }

        override fun putLong(
            key: String,
            value: Long,
        ): SharedPreferences.Editor {
            actions += Action.Add(key, value)
            return this
        }

        override fun putFloat(
            key: String,
            value: Float,
        ): SharedPreferences.Editor {
            actions += Action.Add(key, value)
            return this
        }

        override fun putBoolean(
            key: String,
            value: Boolean,
        ): SharedPreferences.Editor {
            actions += Action.Add(key, value)
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            actions += Action.Remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            actions.add(Action.Clear)
            return this
        }

        override fun commit(): Boolean = save(actions)

        override fun apply() {
            saveAsync(actions)
        }
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        // ZOUKAN: the value must not refer to the listener, or the weak key would never go
        synchronized(listeners) { listeners[listener] = Unit }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun deleteAll(): Boolean {
        save(listOf(Action.Clear))
        return true
    }
}
