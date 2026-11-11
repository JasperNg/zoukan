package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.serialization.protobuf.ProtoBuf
import okio.source
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class BackupDecoder(
    private val context: Context,
    private val parser: ProtoBuf = Injekt.get(),
) {
    /**
     * Decode a potentially-gzipped backup.
     */
    fun decode(uri: Uri): Backup {
        return context.contentResolver.openInputStream(uri)!!.use { inputStream ->
            // ZOUKAN -->
            // The format lives in the shared backup module, so the desktop app reads it the same way
            try {
                BackupCodec.decode(inputStream.source(), parser)
            } catch (e: BackupCodec.InvalidBackupException) {
                val message = when (e.error) {
                    BackupCodec.Error.JSON -> MR.strings.invalid_backup_file_json
                    BackupCodec.Error.UNKNOWN -> MR.strings.invalid_backup_file_unknown
                }
                throw IOException(context.stringResource(message))
            }
            // ZOUKAN <--
        }
    }
}
