package com.mosaic.gallery

/** Suggestions require confirmation. Saved contact portraits never become gallery media. */
object IdentitySuggestions {
    data class Choice(val id:Long,val name:String,val reference:GroupRules.Prototype?,val score:Float,val sharedPhotos:Set<String>,val contact:ContactNames.Contact?=null)
    data class Result(val member:GroupRules.Member,val person:Long?,val name:String,val choices:List<Choice>,val suggestions:List<Choice>)
    fun read(store:PeopleStore,key:GroupRules.Key,accessible:Set<String>,allowChange:Boolean=false):Result? = store.snapshot{Reader(store,accessible).read(key,allowChange)}
    fun photo(store:PeopleStore,uri:String,accessible:Set<String>,suggestions:Boolean=true):List<PhotoPeople.Face> {
        val reader=Reader(store,accessible)
        return reader.rows.filter{it.key.uri==uri && it.key.uri in accessible}.sortedBy{it.key.ordinal}.mapNotNull{row->
            val data=reader.read(row.key,false,suggestions)?:return@mapNotNull null
            val identity=row.person?.takeIf{row.status=="known"}?.let{reader.roots[it]?:it}
            val hint=data.suggestions.firstOrNull()
            val candidate=hint?.takeIf{it.contact==null}?.let{choice->reader.groups.firstOrNull{it.id==choice.id}?.let{GroupRules.Candidate(it,choice.score,0f,choice.reference?.member?.person?:choice.id)}}
            PhotoPeople.Face(row,data.person,data.name,candidate,hint?.name,reader.contacts[identity],identity)
        }
    }
    /** Batch profile labels share one graph/reference read and use no new inference. */
    fun profileNames(store:PeopleStore,covers:Map<Long,GroupRules.Key>,accessible:Set<String>,keepGoing:()->Boolean={true}):Map<Long,String> {
        if(covers.isEmpty())return emptyMap()
        val reader=Reader(store,accessible)
        return buildMap{for((id,key) in covers){
            if(!keepGoing())throw java.util.concurrent.CancellationException()
            reader.read(key,false)?.suggestions?.firstOrNull()?.name?.let{put(id,it)}
        }}
    }
    private class Reader(val store:PeopleStore,val accessible:Set<String>) {
        val rows=store.members();val roots=store.components(rows);val established=store.established(roots);val labels=store.labels(roots)
        val groups by lazy{store.capsules(rows,roots)};val contacts=store.contacts(roots)
        val references by lazy{store.contactReferences()}
        val negatives=store.relations().filter{it.active && it.type=="cannot" && it.source=="user"}
        val cutoff=store.policy().review
        fun read(key:GroupRules.Key,allowChange:Boolean,suggestions:Boolean=true):Result? {
            val member=rows.firstOrNull{it.key==key && it.key.uri in accessible}?:return null
            val person=member.person?.takeIf{member.status=="known"}?.let{roots[it]?:it}
            if(!allowChange && person in established)return Result(member,person,labels[person].orEmpty(),emptyList(),emptyList())
            if(!suggestions || member.status=="excluded")return Result(member,null,"",emptyList(),emptyList())
            val source=groups.firstOrNull{it.id==person}
            val vector=if(member.ready)store.vector(key)else null
            val choices=groups.filter{target->target.id in established && target.id!=person && target.photos.any{it in accessible} &&
                (allowChange || source==null || (!store.contactConflict(source.leaves,target.leaves) &&
                    negatives.none{(it.a in source.leaves && it.b in target.leaves)||(it.b in source.leaves && it.a in target.leaves)}))
            }.map{target->
                val visible=GroupRules.Capsule(target.id,target.leaves,target.photos,target.anchors,target.prototypes.filter{it.member.key.uri in accessible}.toMutableList())
                val evidence=buildList{vector?.let{GroupRules.rank(it,visible)?.let(::add)};source?.takeUnless{allowChange}?.prototypes?.filter{it.member.key.uri in accessible}?.forEach{GroupRules.rank(it.vector,visible)?.let(::add)}}
                val best=evidence.maxByOrNull{it.score}
                val reference=visible.prototypes.firstOrNull{it.member.person==best?.witness}?:visible.prototypes.maxByOrNull{it.member.face.score}
                Choice(target.id,labels[target.id].orEmpty(),reference,best?.score?:-1f,(source?.takeUnless{allowChange}?.photos?:setOf(key.uri)).intersect(target.photos))
            }.toMutableList()
            val contactEvidence=listOfNotNull(vector)+(source?.takeUnless{allowChange}?.prototypes?.filter{it.member.key.uri in accessible}?.map{it.vector}.orEmpty())
            if(contactEvidence.isNotEmpty()){
                for(ref in references){
                    val score=contactEvidence.maxOf{FaceVectors.cosine(it,ref.vector)}
                    // Contact portraits have less gallery context: keep a higher suggestion floor.
                    if(!score.isFinite() || score<maxOf(.72f,cutoff))continue
                    val linked=contacts.filterValues{it.lookup==ref.contact.lookup}.keys
                    if(person in linked)continue
                    val index=choices.indexOfFirst{it.id in linked}
                    if(index>=0){val old=choices[index];choices[index]=old.copy(score=maxOf(old.score,score));continue}
                    // A blocked or inaccessible linked folder must not reappear as a raw contact.
                    if(linked.isNotEmpty())continue
                    if(!allowChange && contacts[person]?.lookup?.let{it!=ref.contact.lookup}==true)continue
                    choices+=Choice(0,ref.contact.name,null,score,emptySet(),ref.contact)
                }
            }
            val sorted=choices.sortedWith(compareByDescending<Choice>{it.score}.thenBy{it.name})
            return Result(member,null,"",sorted,sorted.filter{it.score>=cutoff}.take(3))
        }
    }
}
