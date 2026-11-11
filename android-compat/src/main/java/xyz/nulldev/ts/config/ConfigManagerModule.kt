package xyz.nulldev.ts.config

// ZOUKAN -->
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton

// Registered with Injekt instead of Koin
class ConfigManagerModule : InjektModule {
    override fun InjektRegistrar.registerInjectables() {
        addSingleton<ConfigManager>(GlobalConfigManager)
    }
}
// ZOUKAN <--
