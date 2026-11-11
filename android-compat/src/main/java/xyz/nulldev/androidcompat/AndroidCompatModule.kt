package xyz.nulldev.androidcompat

import android.content.Context
// ZOUKAN -->
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get
// ZOUKAN <--
import xyz.nulldev.androidcompat.androidimpl.CustomContext
import xyz.nulldev.androidcompat.androidimpl.FakePackageManager
import xyz.nulldev.androidcompat.info.ApplicationInfoImpl
import xyz.nulldev.androidcompat.io.AndroidFiles
import xyz.nulldev.androidcompat.pm.PackageController
import xyz.nulldev.androidcompat.service.ServiceSupport

/**
 * AndroidCompatModule
 */

// ZOUKAN -->
// Registered with Injekt instead of Koin
class AndroidCompatModule : InjektModule {
    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory { AndroidFiles() }

        addSingletonFactory { ApplicationInfoImpl(get()) }

        addSingletonFactory { ServiceSupport() }

        addSingletonFactory { FakePackageManager() }

        addSingletonFactory { PackageController() }

        addSingletonFactory { CustomContext() }

        addSingletonFactory<Context> { get<CustomContext>() }
    }
}
// ZOUKAN <--
