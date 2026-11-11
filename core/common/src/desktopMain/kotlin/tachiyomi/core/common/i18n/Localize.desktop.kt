// ZOUKAN -->
package tachiyomi.core.common.i18n

import android.content.Context
import dev.icerock.moko.resources.PluralsResource
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.desc.Plural
import dev.icerock.moko.resources.desc.PluralFormatted
import dev.icerock.moko.resources.desc.Resource
import dev.icerock.moko.resources.desc.ResourceFormatted
import dev.icerock.moko.resources.desc.StringDesc

// On desktop the strings come from the JVM resources; the Context is not needed.

actual fun Context.stringResource(resource: StringResource): String =
    StringDesc.Resource(resource).localized()

actual fun Context.stringResource(resource: StringResource, vararg args: Any): String =
    StringDesc.ResourceFormatted(resource, *args).localized()

actual fun Context.pluralStringResource(resource: PluralsResource, count: Int): String =
    StringDesc.Plural(resource, count).localized()

actual fun Context.pluralStringResource(resource: PluralsResource, count: Int, vararg args: Any): String =
    StringDesc.PluralFormatted(resource, count, *args).localized()
// ZOUKAN <--
