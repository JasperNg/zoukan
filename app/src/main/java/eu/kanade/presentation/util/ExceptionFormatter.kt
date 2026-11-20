package eu.kanade.presentation.util

import android.content.Context
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.util.system.isOnline
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.data.source.NoResultsException
import tachiyomi.domain.source.model.SourceNotInstalledException
import tachiyomi.i18n.MR
import tachiyomi.i18n.exception_http
import tachiyomi.i18n.exception_offline
import tachiyomi.i18n.exception_unknown_host
import tachiyomi.i18n.loader_not_implemented_error
import tachiyomi.i18n.no_results_found
import java.net.UnknownHostException

context(context: Context)
val Throwable.formattedMessage: String
    get() {
        when (this) {
            is HttpException -> return context.stringResource(MR.strings.exception_http, code)
            is UnknownHostException -> {
                return if (!context.isOnline()) {
                    context.stringResource(MR.strings.exception_offline)
                } else {
                    context.stringResource(MR.strings.exception_unknown_host, message ?: "")
                }
            }

            is NoResultsException -> return context.stringResource(MR.strings.no_results_found)
            is SourceNotInstalledException -> return context.stringResource(MR.strings.loader_not_implemented_error)
        }
        return when (val className = this::class.simpleName) {
            "Exception", "IOException" -> message ?: className
            else -> "$className: $message"
        }
    }
