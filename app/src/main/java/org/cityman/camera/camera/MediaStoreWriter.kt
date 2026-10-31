package org.cityman.camera.camera

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes photos and videos to DCIM/Camera through MediaStore (no storage permission needed). */
class MediaStoreWriter(context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    fun baseName(prefix: String, time: Long = System.currentTimeMillis()): String =
        prefix + "_" + stamp.format(Date(time))

    /** Inserts a pending entry; the caller writes with [write] and then calls [publish]. */
    fun createImage(name: String, mime: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
    }

    fun createVideo(name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
    }

    fun write(uri: Uri, block: (OutputStream) -> Unit) {
        resolver.openOutputStream(uri, "w")!!.use(block)
    }

    fun openFd(uri: Uri) = resolver.openFileDescriptor(uri, "rw")!!

    fun publish(uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    fun discard(uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    /** Most recent photo or video this app saved, for the gallery thumbnail after a restart. */
    fun latest(): Pair<Uri, Boolean>? {
        val image = query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "image/jpeg")
        val video = query(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), null)
        return listOfNotNull(image?.let { Triple(it.first, it.second, false) }, video?.let { Triple(it.first, it.second, true) })
            .maxByOrNull { it.second }
            ?.let { it.first to it.third }
    }

    private fun query(collection: Uri, mime: String?): Pair<Uri, Long>? {
        val selection = buildString {
            append(MediaStore.MediaColumns.OWNER_PACKAGE_NAME).append("=?")
            if (mime != null) append(" AND ").append(MediaStore.MediaColumns.MIME_TYPE).append("=?")
        }
        val args = listOfNotNull(PACKAGE, mime).toTypedArray()
        return runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
                selection, args,
                MediaStore.MediaColumns.DATE_ADDED + " DESC",
            )?.use { c ->
                if (c.moveToFirst()) Uri.withAppendedPath(collection, c.getLong(0).toString()) to c.getLong(1) else null
            }
        }.getOrNull()
    }

    fun thumbnail(uri: Uri, video: Boolean): Bitmap? = runCatching {
        resolver.loadThumbnail(uri, android.util.Size(256, 256), null)
    }.getOrNull()

    companion object {
        const val PACKAGE = "org.cityman.camera"

        /** Small, upright bitmap from JPEG bytes for the gallery button. */
        fun thumbnailFromJpeg(bytes: ByteArray): Bitmap? {
            val rotation = runCatching {
                when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }.getOrDefault(0)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 192 && bounds.outHeight / (sample * 2) >= 192) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            if (rotation == 0) return bmp
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
    }
}
