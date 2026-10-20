package dev.dsh.nativeapp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Human-readable name for a picked content Uri, falling back to the last path segment. */
internal fun displayNameOf(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()
    return fromProvider
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "attachment"
}

/** Read a picked Uri fully into memory; DSH uploads go through JSON base64. */
internal fun readBytesOf(context: Context, uri: Uri): ByteArray =
    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
        .getOrNull()
        ?: ByteArray(0)
