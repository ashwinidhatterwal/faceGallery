package com.mosaic.gallery

import android.net.Uri

data class PhotoRecord(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateTakenMillis: Long,
    val width: Int,
    val height: Int,
    val album: String = "",
    val sizeBytes:Long=-1,
    val path:String="",
    val modifiedMillis:Long=0
)
