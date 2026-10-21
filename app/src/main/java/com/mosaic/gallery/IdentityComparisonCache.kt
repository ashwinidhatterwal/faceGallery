package com.mosaic.gallery

import java.util.BitSet
import java.util.LinkedHashMap

/** Session-only reuse: compact rejected pairs, bounded positive evidence, invalidation on every join. */
class IdentityComparisonCache(ids:List<Long>,private val floor:Float,private val checkpoint:android.util.AtomicFile?=null,private val generation:Long=0) {
    companion object {
        private val reusable=object:LinkedHashMap<Pair<Long,Long>,Evidence>(256,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Pair<Long,Long>,Evidence>?)=size>100_000}
        fun fingerprint(group:GroupRules.Capsule):Long {
            var key=group.id*31+group.photos.sorted().hashCode();key=key*31+group.leaves.sorted().hashCode()
            for(ref in group.prototypes){key=key*31+ref.member.hashCode();key=key*31+ref.vector.contentHashCode()};return key
        }
    }
    private var fingerprints=emptyMap<Long,Long>();private var tree:FaceCandidateIndex?=null;private var rowId:Long?=null;private var row=emptyList<Long>()
    fun prepare(groups:List<GroupRules.Capsule>){
        val next=groups.associate{it.id to fingerprint(it)}
        if(fingerprints.isNotEmpty() && next!=fingerprints){val changed=(fingerprints.keys+next.keys).filter{fingerprints[it]!=next[it]};invalidate(*changed.toLongArray())}
        fingerprints=next;if(tree==null)tree=FaceCandidateIndex(groups)
    }
    fun neighbors(group:GroupRules.Capsule,keepGoing:()->Boolean):List<Long>? {
        if(rowId!=group.id){row=tree!!.neighbors(group,floor,keepGoing)?.filter{it>group.id}?:return null;rowId=group.id}
        return row
    }
    fun pairs(groups:List<GroupRules.Capsule>,keepGoing:()->Boolean):List<Pair<Long,Long>>? {prepare(groups);return tree!!.pairs(floor,keepGoing)}
    data class Evidence(val best:Float,val agreement:Float,val corroborated:Float=-1f)
    private val index=ids.withIndex().associate{it.value to it.index};private val width=ids.size
    private val rejected=BitSet()
    private val positive=object:LinkedHashMap<Pair<Long,Long>,Evidence>(128,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Pair<Long,Long>,Evidence>?)=size>20_000}
    private fun slot(a:Long,b:Long):Int? {if(width>5000)return null;val i=index[a]?:return null;val j=index[b]?:return null;val n=minOf(i,j).toLong()*width+maxOf(i,j);return n.takeIf{it<=Int.MAX_VALUE}?.toInt()}
    fun get(a:Long,b:Long,compute:()->Evidence):Evidence {
        val key=minOf(a,b) to maxOf(a,b);positive[key]?.let{return it}
        val slot=slot(a,b);if(slot!=null && rejected[slot])return Evidence(-1f,-1f)
        val token=fingerprints[a]?.let{x->fingerprints[b]?.let{y->minOf(x,y) to maxOf(x,y)}}
        val saved=token?.let{synchronized(reusable){reusable[it]}}
        return (saved?:compute().also{value->if(token!=null)synchronized(reusable){reusable[token]=value}}).also{if(it.best<floor && slot!=null)rejected.set(slot)else positive[key]=it}
    }
    data class Match(val a:Long,val b:Long,val score:Float,val eligible:Boolean,val direct:Boolean)
    var i=0;var j=0
    val rankings=mutableMapOf<Long,MutableList<Match>>()
    init {
        runCatching{checkpoint?.openRead()?.use{stream->
            val data=org.json.JSONObject(stream.bufferedReader().readText());if(data.getLong("generation")!=generation)return@use
            val pair=data.getJSONArray("cursor");i=pair.getInt(0);j=pair.getInt(1)
            val matches=data.getJSONArray("matches");for(n in 0 until matches.length()){val m=matches.getJSONArray(n);offer(Match(m.getLong(0),m.getLong(1),m.getDouble(2).toFloat(),m.getBoolean(3),m.getBoolean(4)))}
        }}.onFailure{resetScan()}
    }
    fun offer(match:Match){for(id in listOf(match.a,match.b)){
        val list=rankings.getOrPut(id){mutableListOf()};list.removeAll{it.a==match.a && it.b==match.b};list+=match;list.sortByDescending{it.score};while(list.size>2)list.removeAt(list.lastIndex)
    }}
    fun saveScan(){val file=checkpoint?:return
        val matches=rankings.values.flatten().distinct();val data=org.json.JSONObject().put("generation",generation).put("cursor",org.json.JSONArray(listOf(i,j))).put("matches",org.json.JSONArray(matches.map{listOf(it.a,it.b,it.score,it.eligible,it.direct)}))
        var stream:java.io.FileOutputStream?=null;runCatching{stream=file.startWrite();stream!!.write(data.toString().toByteArray());file.finishWrite(stream)}.onFailure{file.failWrite(stream)}
    }
    fun resetScan(){i=0;j=0;rankings.clear();tree=null;rowId=null;row=emptyList();checkpoint?.delete()}
    fun invalidate(vararg ids:Long){
        resetScan();val changed=ids.toSet();positive.keys.removeAll{it.first in changed || it.second in changed}
        for(id in ids)for(other in index.keys)slot(id,other)?.let{rejected.clear(it)}
    }
}
