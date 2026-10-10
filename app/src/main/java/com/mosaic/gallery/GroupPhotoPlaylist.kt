package com.mosaic.gallery

import android.content.Context

/** A bounded metadata handoff; never put a whole library into an Intent. */
object GroupPhotoPlaylist {
    private var tagRevision=-1L
    private val cache=PeopleCache<Pair<String,List<PhotoRecord>>>()
    fun remember(context:Context,photos:List<PhotoRecord>):String {
        val token=java.util.UUID.randomUUID().toString()
        cache.put(context,PeopleData.version,token to photos.toList());tagRevision=MediaTags.version
        return token
    }
    fun read(context:Context,token:String?):List<PhotoRecord>? =
        token?.let{cache.get(context)?.takeIf{it.first==token && tagRevision==MediaTags.version}?.second}
}
