package com.mosaic.gallery

/** Display filter only: indexing, albums and recognition keep the complete accessible library. */
object CameraMedia {
    fun contains(photo:PhotoRecord):Boolean {
        val parent=photo.path.replace('\\','/').trimEnd('/').substringBeforeLast('/', "").substringAfterLast('/')
        return if(parent.isNotBlank())parent.equals("Camera",ignoreCase=true)
        else photo.album.equals("Camera",ignoreCase=true)
    }
}
