package com.mosaic.gallery

import android.content.Context
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Read-only search metadata. No signatures, face crops or new persisted photo index. */
object PeopleSearch {
    private val cache=PeopleCache<Pair<List<PhotoRecord>,Index>>()
    private var tagRevision=-1L
    fun cachedRead(context:Context,photos:List<PhotoRecord>):Index {
        cache.get(context)?.takeIf{it.first==photos}?.let{entry->
            if(tagRevision==MediaTags.version)return entry.second
            val tags=MediaTags.read(context)
            return entry.second.copy(tags=tags.media.mapValues{(uri,_)->tags.labels(uri)},places=tags.places).also{cache.put(context,PeopleData.version,photos to it);tagRevision=MediaTags.version}
        }
        val revision=PeopleData.version;val tags=MediaTags.version;return (PeopleActivity.searchIndex(context,photos)?:read(context,photos)).also{cache.put(context,revision,photos to it);tagRevision=tags}
    }
    data class Query(val text:String="",val person:Long?=null,val from:LocalDate?=null,val through:LocalDate?=null) {
        init { require(from==null || through==null || from<=through) { "Start date must be before the end date" } }
    }
    private data class Corpus(val photos:List<PhotoRecord>,val zone:String,val locale:String,val day:Long,val words:Map<String,String>)
    data class Index(val people:Map<String,Set<Long>>,val labels:Map<Long,String>,val roots:Map<Long,Long>,val tags:Map<String,List<String>> = emptyMap(),val places:Map<String,String> = emptyMap()) {
        @Volatile internal var corpus:Any?=null
    }
    private fun corpus(photos:List<PhotoRecord>,index:Index,zone:ZoneId,keepGoing:()->Boolean):Map<String,String>? {
        val locale=Locale.getDefault();val systemZone=ZoneId.systemDefault();val scope=locale.toLanguageTag()+":"+systemZone.id;val day=LocalDate.now().toEpochDay()
        (index.corpus as? Corpus)?.takeIf{it.photos==photos && it.zone==zone.id && it.locale==scope && it.day==day}?.let{return it.words}
        val formatter=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd MMMM d yyyy EEEE HH:mm h:mm a",locale)
        val month=java.time.format.DateTimeFormatter.ofPattern("MMMM",Locale.ENGLISH)
        val days=mutableMapOf<Long,String>();val result=HashMap<String,String>(photos.size)
        for(photo in photos){
            if(!keepGoing())return null
            val uri=photo.uri.toString();if(uri in result)continue;val time=photo.dateTakenMillis
            val date=if(time<=0)""else java.time.Instant.ofEpochMilli(time).atZone(zone).let{d->
                d.format(formatter)+" "+d.format(month)+" "+days.getOrPut(d.withZoneSameInstant(systemZone).toLocalDate().toEpochDay()){GalleryDates.label(time)}
            }
            result[uri]=normalized(photo.displayName+" "+photo.album+" "+index.people[uri].orEmpty().joinToString(" "){index.labels[it].orEmpty()}+" "+index.tags[uri].orEmpty().joinToString(" ")+" "+index.places[uri].orEmpty()+" "+date)
        }
        index.corpus=Corpus(photos,zone.id,scope,day,result);return result
    }
    fun read(context:Context,photos:List<PhotoRecord>?=null):Index=FaceStore(context).use { faces->
        faces.snapshot {
            val valid=photos?.let{available->val pending=faces.pending(available).map{it.uri.toString()}.toHashSet();available.map{it.uri.toString()}.filter{it !in pending}.toHashSet()}
            val store=PeopleStore(faces);val members=store.members().filter{valid==null || it.key.uri in valid};val roots=store.components(members)
            val people=members.filter{it.status=="known" && it.person!=null}.groupBy{it.key.uri}
                .mapValues{(_,rows)->rows.map{roots[it.person]?:it.person!!}.toSet()}
            val live=people.values.flatten().toSet()
            val provisional=IdentityEvidence.provisional(store.capsules(members,roots),members,store.names().keys+store.contacts(roots).keys)
            val tags=MediaTags.read(context)
            Index(people,store.labels(roots).filterKeys{it in live && it !in provisional},roots,tags.media.mapValues{(uri,_)->tags.labels(uri)},tags.places)
        }
    }
    private fun normalized(value:String)=Normalizer.normalize(value,Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    fun filter(photos:List<PhotoRecord>,index:Index,query:Query,zone:ZoneId=ZoneId.systemDefault(),keepGoing:()->Boolean={true}):List<PhotoRecord> {
        val words=normalized(query.text).trim().split(Regex("\\s+")).filter{it.isNotEmpty()}
        val searchable=if(words.isEmpty())emptyMap()else corpus(photos,index,zone,keepGoing)?:return emptyList()
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
                    val text=searchable[uri].orEmpty()
                    if(!words.all{text.contains(it)})continue
                }
                add(photo)
            }
        }
    }
}
