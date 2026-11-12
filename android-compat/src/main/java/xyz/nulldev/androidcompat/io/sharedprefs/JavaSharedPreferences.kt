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
import kotlinx.coroutines.launch
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
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

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

    private val file =
        Path(
            ApplicationRootDir,
            "settings",
            "${SafePath.buildValidFilename(key)}.xml",
        )
    private val properties =
        Properties().also { properties ->
            try {
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
        if (migrateToCodec && !properties.isEmpty) save(getSnapshot())
    }
    // ZOUKAN <--

    // ZOUKAN -->
    // Listeners come and go on the UI thread while changes notify them on the main looper, so the
    // map must allow both at once
    private val listeners =
        java.util.concurrent.ConcurrentHashMap<SharedPreferences.OnSharedPreferenceChangeListener, (String) -> Unit>()
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
            val setKeys =
                keys.filter { it.endsWith(".size") }.map { it.removeSuffix(".size") } +
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
                        setKeys.none { key.startsWith("$it.") }
                }.forEach { key -> result[key] = preferences.getStringOrNull(key) }
            result
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

    private fun notify(key: String) {
        listeners.forEach { (_, listener) ->
            listener(key)
        }
    }

    private fun saveAction(
        action: Action,
        shouldNotify: Boolean = true,
    ) {
        when (action) {
            is Action.Add -> {
                when (val value = action.value) {
                    is Set<*> -> {
                        saveAction(Action.Remove(action.key), shouldNotify = false)
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
                // ZOUKAN <--

                if (shouldNotify) notify(action.key)
            }

            is Action.Remove -> {
                preferences.remove(action.key)
                // ZOUKAN -->
                preferences.remove(TYPE_PREFIX + action.key)
                // ZOUKAN <--
                /*
                 Set<String> are stored like
                 key.0 = value1
                 key.1 = value2
                 key.size = 2
                 */
                preferences.keys.forEach { key ->
                    if (key.startsWith(action.key + ".")) {
                        preferences.remove(key)
                    }
                }

                if (shouldNotify) notify(action.key)
            }

            Action.Clear -> {
                val keys = preferences.keys

                preferences.clear()

                keys.forEach { key ->
                    // ZOUKAN -->
                    if (key.startsWith(TYPE_PREFIX)) return@forEach
                    // ZOUKAN <--
                    notify(key)

                    val isSetKey = key.endsWith(".size")
                    if (isSetKey) {
                        notify(key.removeSuffix(".size"))
                    }
                }
            }
        }
    }

    private fun saveActions(actions: List<Action>) =
        synchronized(properties) {
            actions.forEach(::saveAction)
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

                    file.deleteIfExists()
                    tempFile.copyTo(file)
                    tempFile.deleteExisting()
                }

                return true
            } catch (e: Exception) {
                logger.error(e) { "Error saving settings $key" }
                return false
            }
        }

    private fun save(actions: List<Action>): Boolean {
        val snapshot =
            synchronized(properties) {
                saveActions(actions)
                getSnapshot()
            }

        return save(snapshot)
    }

    private fun save(): Boolean = save(getSnapshot())

    @OptIn(DelicateCoroutinesApi::class, ExperimentalAtomicApi::class)
    private fun startAsyncSaver() {
        if (!pendingAsyncSave.compareAndSet(false, true)) {
            return
        }

        GlobalScope.launch(Dispatchers.IO) {
            while (true) {
                dirty.store(false)

                save(getSnapshot())

                if (!dirty.load()) {
                    pendingAsyncSave.store(false)

                    if (dirty.load() && pendingAsyncSave.compareAndSet(false, true)) {
                        continue
                    }

                    break
                }
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun saveAsync(actions: List<Action>) {
        saveActions(actions)

        dirty.store(true)

        startAsyncSaver()
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
        val javaListener: (String) -> Unit = {
            listener.onSharedPreferenceChanged(this, it)
        }
        listeners[listener] = javaListener
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }

    fun deleteAll(): Boolean {
        save(listOf(Action.Clear))
        return true
    }
}
