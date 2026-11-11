package zoukan.desktop.compat

import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.desc.Resource
import dev.icerock.moko.resources.desc.ResourceFormatted
import dev.icerock.moko.resources.desc.StringDesc

/** A moko string (MR, SYMR, KMR, DMR) in the system language, for desktop UI code. */
fun localized(resource: StringResource): String = StringDesc.Resource(resource).localized()

/** A formatted moko string; a null argument shows as "null", as it would in a string template. */
fun localized(resource: StringResource, vararg args: Any?): String =
    StringDesc.ResourceFormatted(resource, *args.map { it ?: "null" }.toTypedArray()).localized()
