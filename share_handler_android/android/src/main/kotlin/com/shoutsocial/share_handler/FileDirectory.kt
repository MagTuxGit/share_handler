package com.shoutsocial.share_handler

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import android.webkit.MimeTypeMap
import android.util.Log


object FileDirectory {

    private const val TAG = "FileDirectory"

    /** Subfolder of the cache dir that receives copies of shared content. */
    private const val SHARED_DIR = "share_handler"

    /**
     * Get a file path from a Uri. This will get the the path for Storage Access
     * Framework Documents, as well as the _data field for the MediaStore and
     * other file-based ContentProviders.
     *
     * Only `content://` Uris from other apps are accepted. A `file://` Uri, or a
     * content Uri served by this app's own providers, would make this app hand
     * its own private files to the caller as if the sender had shared them.
     *
     * @param context The context.
     * @param uri The Uri to query.
     * @author paulburke
     */
    fun getAbsolutePath(context: Context, uri: Uri): String? {
        if (!"content".equals(uri.scheme, ignoreCase = true)) {
            Log.w(TAG, "Rejected shared Uri with scheme ${uri.scheme}")
            return null
        }
        if (isOwnProvider(context, uri)) {
            Log.w(TAG, "Rejected shared Uri served by this app")
            return null
        }

        // DocumentProvider
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val type = split[0]

                return if ("primary".equals(type, ignoreCase = true)) {
                    context.getExternalFilesDir(null).toString() + "/" + split[1]
                } else {
                    getDataColumn(context, uri, null, null)
                }
            } else if (isDownloadsDocument(uri)) {
                return try {
                    val id = DocumentsContract.getDocumentId(uri)
                    val contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), java.lang.Long.valueOf(id))

                    getDataColumn(context, contentUri, null, null)
                } catch (exception: Exception) {
                    getDataColumn(context, uri, null, null)
                }
            } else if (isMediaDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val type = split[0]

                var contentUri: Uri? = null
                when (type) {
                    "image" -> contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }

                if (contentUri == null) return null

                val selection = "_id=?"
                val selectionArgs = arrayOf(split[1])
                return getDataColumn(context, contentUri, selection, selectionArgs)
            }// MediaProvider
            // DownloadsProvider
        } else {
            return getDataColumn(context, uri, null, null)
        }

        return null
    }

    /**
     * Get the value of the data column for this Uri. This is useful for
     * MediaStore Uris, and other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri The Uri to query.
     * @param selection (Optional) Filter used in the query.
     * @param selectionArgs (Optional) Selection arguments used in the query.
     * @return The value of the _data column, which is typically a file path.
     */
    private fun getDataColumn(context: Context, uri: Uri, selection: String?,
                              selectionArgs: Array<String>?): String? {

        if (uri.authority != null && selection==null) {
            var cursor: Cursor? = null
            val column = "_display_name"
            val projection = arrayOf(column)
            // A fresh folder per share: the name below comes from the sender, so it
            // must never resolve to an existing file, and two shares with the same
            // name must not overwrite each other.
            val targetDir = File(context.cacheDir, "$SHARED_DIR/${UUID.randomUUID()}")
            if (!targetDir.mkdirs()) {
                Log.w(TAG, "Could not create a folder for the shared file")
                return null
            }
            var targetFile: File? = null
            try {
                cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(column)
                    targetFile = safeFileIn(targetDir, cursor.getString(columnIndex))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the shared file name", e)
            } finally {
                cursor?.close()
            }

            if (targetFile == null) {
                val mimeType = context.contentResolver.getType(uri)
                val prefix = with(mimeType ?: "") {
                    when {
                        startsWith("image") -> "IMG"
                        startsWith("video") -> "VID"
                        else -> "FILE"
                    }
                }
                val type = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                targetFile = File(targetDir, "${prefix}_${Date().time}.$type")
            }
            val uri = selectionArgs?.let { args -> uri.buildUpon().appendPath(args.first()).build() } ?: uri
            try {
                val input = context.contentResolver.openInputStream(uri)
                if (input == null) {
                    targetDir.deleteRecursively()
                    return null
                }
                input.use {
                    FileOutputStream(targetFile).use { fileOut ->
                        it.copyTo(fileOut)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Could not copy the shared file", e)
                targetDir.deleteRecursively()
                return null
            } catch (e: SecurityException) {
                Log.w(TAG, "No access to the shared file", e)
                targetDir.deleteRecursively()
                return null
            }
            return targetFile.path
        }

        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(column)

        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(columnIndex)
            }
        } finally {
            cursor?.close()
        }
        return null
    }


    /**
     * A file named [rawName] inside [dir], or null when the name is unusable.
     *
     * The name is chosen by the sending app (`_display_name`), so a name such as
     * `../shared_prefs/x.xml` would otherwise write outside [dir]. Only its last
     * path segment is kept, and the result must still resolve inside [dir].
     */
    private fun safeFileIn(dir: File, rawName: String?): File? {
        val name = rawName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            ?: return null
        if (name.isEmpty() || name == "." || name == ".." || name.contains('\u0000')) {
            Log.w(TAG, "Rejected shared file name")
            return null
        }
        val file = File(dir, name)
        return try {
            if (file.canonicalFile.parentFile == dir.canonicalFile) {
                file
            } else {
                Log.w(TAG, "Rejected shared file name outside the share folder")
                null
            }
        } catch (e: IOException) {
            null
        }
    }

    /** Whether [uri] is served by one of this app's own content providers. */
    private fun isOwnProvider(context: Context, uri: Uri): Boolean {
        val authority = uri.authority ?: return false
        val provider = context.packageManager.resolveContentProvider(authority, 0) ?: return false
        return provider.packageName == context.packageName
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    private fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }
}