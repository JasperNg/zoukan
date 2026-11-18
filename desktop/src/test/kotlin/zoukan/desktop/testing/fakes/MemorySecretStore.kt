package zoukan.desktop.testing.fakes

import zoukan.desktop.secrets.SecretStore
import java.util.concurrent.ConcurrentHashMap

/** A secret store in memory, for tests. [values] is the stored state, which a test may read or change. */
class MemorySecretStore(override val name: String = "test secret store") : SecretStore {
    val values: MutableMap<String, String> = ConcurrentHashMap()

    override fun get(key: String): String? = values[key]

    override fun set(key: String, value: String) {
        values[key] = value
    }

    override fun delete(key: String) {
        values.remove(key)
    }
}
