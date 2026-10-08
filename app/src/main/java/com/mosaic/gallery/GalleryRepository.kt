package com.mosaic.gallery

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore

class GalleryRepository(private val context: Context) {
    data class Result(val photos: List<PhotoRecord>, val unreadableVolumes: Int)
    fun loadPhotos(signal: CancellationSignal): Result {
        val collections = if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(context)
            .sorted().map { MediaStore.Images.Media.getContentUri(it) }
            else listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,MediaStore.Images.Media.SIZE,MediaStore.Images.Media.DATA,MediaStore.Images.Media.DATE_MODIFIED)
        val photos = ArrayList<PhotoRecord>()
        var failures = 0
        collections.forEach { collection ->
            signal.throwIfCanceled()
            val result = runCatching {
                checkNotNull(context.contentResolver.query(collection, projection, null, null, null, signal)).use { cursor ->
                    while (cursor.moveToNext()) {
                        signal.throwIfCanceled()
                        val id = cursor.getLong(0)
                        photos += PhotoRecord(id, ContentUris.withAppendedId(collection, id), cursor.getString(1).orEmpty(),
                            cursor.getLong(2).takeIf { it > 0 } ?: cursor.getLong(3) * 1000L,
                            cursor.getInt(4), cursor.getInt(5), cursor.getString(6).orEmpty(),cursor.getLong(7),cursor.getString(8).orEmpty(),cursor.getLong(9)*1000L)
                    }
                }
            }
            result.onFailure {
                signal.throwIfCanceled()
                if (it is SecurityException) throw it
                failures++
            }
        }
        check(collections.isNotEmpty() && failures < collections.size) { "No photo storage could be read" }
        return Result(photos.distinctBy { it.uri }.sortedWith(compareByDescending<PhotoRecord> { it.dateTakenMillis }
            .thenBy { it.uri.toString() }), failures)
    }
}
