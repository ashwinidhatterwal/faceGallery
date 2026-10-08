package com.mosaic.gallery

import android.content.Context
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Read-only search metadata. No signatures, face crops or new persisted photo index. */
object PeopleSearch {
    private val cache=PeopleCache<Pair<List<PhotoRecord>,Index>>()
    fun cachedRead(context:Context,photos:List<PhotoRecord>):Index {
        cache.get(context)?.takeIf{it.first==photos}?.let{return it.second}
        val revision=PeopleData.version;return read(context,photos).also{cache.put(context,revision,photos to it)}
    }
    data class Query(val text:String="",val person:Long?=null,val from:LocalDate?=null,val through:LocalDate?=null) {
        init { require(from==null || through==null || from<=through) { "Start date must be before the end date" } }
    }
    data class Index(val people:Map<String,Set<Long>>,val labels:Map<Long,String>,val roots:Map<Long,Long>)
    fun read(context:Context,photos:List<PhotoRecord>?=null):Index=FaceStore(context).use { faces->
        val db=faces.writableDatabase;db.beginTransactionNonExclusive()
        try {
            val valid=photos?.let{available->val pending=faces.pending(available).map{it.uri.toString()}.toHashSet();available.map{it.uri.toString()}.filter{it !in pending}.toHashSet()}
            val store=PeopleStore(faces);val members=store.members().filter{valid==null || it.key.uri in valid};val roots=store.components(members)
            val people=members.filter{it.status=="known" && it.person!=null}.groupBy{it.key.uri}
                .mapValues{(_,rows)->rows.map{roots[it.person]?:it.person!!}.toSet()}
            val live=people.values.flatten().toSet()
            val provisional=IdentityEvidence.provisional(store.capsules(members,roots),members,store.names().keys+store.contacts(roots).keys)
            Index(people,store.labels(roots).filterKeys{it in live && it !in provisional},roots)
        } finally { db.endTransaction() }
    }
    private fun normalized(value:String)=Normalizer.normalize(value,Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    fun filter(photos:List<PhotoRecord>,index:Index,query:Query,zone:ZoneId=ZoneId.systemDefault(),keepGoing:()->Boolean={true}):List<PhotoRecord> {
        val words=normalized(query.text).trim().split(Regex("\\s+")).filter{it.isNotEmpty()}
        val person=query.person?.let{index.roots[it]?:it}
        val start=query.from?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        val end=query.through?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        // Build name text once per photo, not once per search token.
        return buildList {
            val seen=HashSet<String>()
            for(photo in photos) {
                if(!keepGoing())return emptyList()
                val uri=photo.uri.toString();if(!seen.add(uri))continue
                val ids=index.people[uri].orEmpty()
                if(person!=null && person !in ids)continue
                val time=photo.dateTakenMillis
                if((start!=null || end!=null) && (time<=0 || start!=null && time<start || end!=null && time>=end))continue
                if(words.isNotEmpty()) {
                    val text=normalized(photo.displayName+" "+photo.album+" "+ids.joinToString(" "){index.labels[it].orEmpty()})
                    if(!words.all{text.contains(it)})continue
                }
                add(photo)
            }
        }
    }
}
