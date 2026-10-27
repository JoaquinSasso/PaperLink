package com.joasasso.paperlink.ui.common

import androidx.annotation.StringRes
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.backup.BackupException
import com.joasasso.paperlink.data.files.ImportException
import com.joasasso.paperlink.data.repository.LinkNotFoundException

/** Mensaje para el usuario según el error. */
@StringRes
fun Throwable.messageRes(): Int = when (this) {
    is ImportException.InsufficientSpace -> R.string.error_no_space
    is ImportException.SourceUnreadable -> R.string.error_source_unreadable
    is LinkNotFoundException -> R.string.error_code_not_found
    is BackupException.InvalidArchive -> R.string.error_backup_invalid_archive
    is BackupException.CorruptBackup -> R.string.error_backup_corrupt
    is BackupException.AuthRequired -> R.string.error_backup_auth_required
    is BackupException.NoRemoteBackup -> R.string.error_backup_not_found
    is BackupException.NetworkError -> R.string.error_backup_network
    else -> R.string.error_generic
}
