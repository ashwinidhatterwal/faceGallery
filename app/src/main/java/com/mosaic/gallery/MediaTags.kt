package com.mosaic.gallery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Separate local metadata: changing tags never invalidates face signatures or schedules recognition. */
object MediaTags {
    const val PREFIX="@tag:"
    data class Snapshot(val names:Map<String,String>,val media:Map<String,Set<String>>,val places:Map<String,String>) {
        fun labels(uri:String)=media[uri].orEmpty().mapNotNull(names::get)
        fun matches(uri:String,album:String)=album.removePrefix(PREFIX) in media[uri].orEmpty()
    }
    private var scope="";private var saved:Snapshot?=null
    @Volatile var version=0L;private set
    fun key(name:String)=Normalizer.normalize(name.trim().replace(Regex("\\s+")," "),Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    @Synchronized fun read(context:Context):Snapshot {
        val next=context.filesDir.toString();if(saved!=null && scope==next)return saved!!
        val json=JSONObject(context.getSharedPreferences("media-tags",0).getString("data","{}")!!)
        val names=json.optJSONObject("names")?:JSONObject();val media=json.optJSONObject("media")?:JSONObject();val places=json.optJSONObject("places")?:JSONObject()
        val data=Snapshot(names.keys().asSequence().associateWith{names.getString(it)},media.keys().asSequence().associateWith{uri->media.getJSONArray(uri).let{a->(0 until a.length()).map{a.getString(it)}.toSet()}},places.keys().asSequence().associateWith{places.getString(it)})
        scope=next;saved=data;return data
    }
    /** Apply a delta, preserving each selected photo's other tags and concurrent edits. */
    @Synchronized fun update(context:Context,uris:Set<String>,add:Set<String>,remove:Set<String>,place:String?=null) {
        if(uris.isEmpty())return
        val old=read(context);val names=old.names.toMutableMap();val media=old.media.toMutableMap();val places=old.places.toMutableMap()
        val additions=add.map{it.trim().replace(Regex("\\s+")," ")}.filter{it.isNotEmpty() && it.length<=40}.associateBy(::key)
        additions.forEach{(k,n)->names.putIfAbsent(k,n)}
        for(uri in uris){val next=(media[uri].orEmpty()-remove.map(::key).toSet())+additions.keys;if(next.isEmpty())media.remove(uri)else media[uri]=next
            if(place!=null){if(place.trim().isEmpty())places.remove(uri)else places[uri]=place.trim().take(100)}
        }
        // Keep previously used names available as suggestions even after the last assignment is removed.
        val next=Snapshot(names.toMap(),media.toMap(),places.toMap());if(next==old)return
        val json=JSONObject().put("names",JSONObject(names as Map<*,*>)).put("places",JSONObject(places as Map<*,*>)).put("media",JSONObject().apply{media.forEach{(uri,tags)->put(uri,JSONArray(tags.toList()))}})
        context.getSharedPreferences("media-tags",0).edit().putString("data",json.toString()).apply();saved=next;version++
    }
    fun forget(context:Context,uris:Set<String>){val old=read(context);update(context,uris,emptySet(),uris.flatMap{old.media[it].orEmpty()}.toSet(),"")}
    fun albumTitle(context:Context,album:String)=read(context).names[album.removePrefix(PREFIX)].orEmpty()
}
