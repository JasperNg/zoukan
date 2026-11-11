package zoukan.desktop.compat

import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.desc.Resource
import dev.icerock.moko.resources.desc.ResourceFormatted
import dev.icerock.moko.resources.desc.StringDesc

/** A moko string (MR, SYMR, KMR) in the system language, for desktop UI code. */
fun localized(resource: StringResource): String = StringDesc.Resource(resource).localized()

fun localized(resource: StringResource, vararg args: Any): String = StringDesc.ResourceFormatted(resource, *args).localized()
