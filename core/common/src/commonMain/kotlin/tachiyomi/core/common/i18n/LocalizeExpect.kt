// ZOUKAN -->
package tachiyomi.core.common.i18n

import android.content.Context
import dev.icerock.moko.resources.PluralsResource
import dev.icerock.moko.resources.StringResource

expect fun Context.stringResource(resource: StringResource): String

expect fun Context.stringResource(resource: StringResource, vararg args: Any): String

expect fun Context.pluralStringResource(resource: PluralsResource, count: Int): String

expect fun Context.pluralStringResource(resource: PluralsResource, count: Int, vararg args: Any): String
// ZOUKAN <--
