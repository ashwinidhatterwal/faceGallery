package com.mosaic.gallery

/** Suggestions require confirmation. Saved contact portraits never become gallery media. */
object IdentitySuggestions {
    const val CONTACT_FLOOR=.60f
    data class Choice(val id:Long,val name:String,val reference:GroupRules.Prototype?,val score:Float,val sharedPhotos:Set<String>,val contact:ContactNames.Contact?=null,val hintContact:ContactNames.Contact?=contact)
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
    fun profileNames(store:PeopleStore,covers:Map<Long,GroupRules.Key>,accessible:Set<String>,keepGoing:()->Boolean={true}):Map<Long,String> = profileNamesCached(store,covers,accessible,keepGoing=keepGoing)
    fun profileNamesCached(store:PeopleStore,covers:Map<Long,GroupRules.Key>,accessible:Set<String>,rows:List<GroupRules.Member>?=null,roots:Map<Long,Long>?=null,groups:List<GroupRules.Capsule>?=null,keepGoing:()->Boolean={true}):Map<Long,String> {
        if(covers.isEmpty())return emptyMap()
        val reader=Reader(store,accessible,rows,roots,groups)
        return buildMap{for((id,key) in covers){
            if(!keepGoing())throw java.util.concurrent.CancellationException()
            reader.read(key,false)?.suggestions?.firstOrNull()?.name?.let{put(id,it)}
        }}
    }
    private class Reader(val store:PeopleStore,val accessible:Set<String>,savedRows:List<GroupRules.Member>?=null,savedRoots:Map<Long,Long>?=null,savedGroups:List<GroupRules.Capsule>?=null) {
        val rows=savedRows?:store.members();val roots=savedRoots?:store.components(rows);val established=store.established(roots);val labels=store.labels(roots)
        val groups by lazy{savedGroups?:store.capsules(rows,roots)};val contacts=store.contacts(roots)
        val references by lazy{store.contactReferences()}
        val negatives=store.relations().filter{it.active && it.type=="cannot" && it.source=="user"}
        val cutoff=store.policy().review
        val rejected=store.contactHintRejections()
        private data class Target(val person:Long?,val key:GroupRules.Key?,val vectors:List<FloatArray>)
        // Compute once per batch: each contact proposes only its closest unnamed group.
        private val contactTargets by lazy {
            val targets=groups.filter{it.id !in established}.map{g->Target(g.id,null,g.prototypes.filter{it.member.key.uri in accessible}.map{it.vector})}
            references.associate{ref->ref.contact.lookup to targets.mapNotNull{t->t.vectors.map{FaceVectors.cosine(it,ref.vector)}.filter{it.isFinite()}.maxOrNull()?.let{t to it}}
                .sortedWith(compareByDescending<Pair<Target,Float>>{it.second}.thenBy{it.first.person?:Long.MAX_VALUE}.thenBy{it.first.key?.uri.orEmpty()}.thenBy{it.first.key?.ordinal?:0}).firstOrNull()?.takeIf{it.second>=CONTACT_FLOOR}}
        }
        fun read(key:GroupRules.Key,allowChange:Boolean,suggestions:Boolean=true):Result? {
            val member=rows.firstOrNull{it.key==key && it.key.uri in accessible}?:return null
            val person=member.person?.takeIf{member.status=="known"}?.let{roots[it]?:it}
            if(!allowChange && person in established)return Result(member,person,labels[person].orEmpty(),emptyList(),emptyList())
            if(!suggestions || member.status=="excluded")return Result(member,null,"",emptyList(),emptyList())
            val source=groups.firstOrNull{it.id==person}
            val sourceKeys=if(source==null || allowChange)setOf(key)else rows.filter{it.person in source.leaves}.map{it.key}.toSet()
            fun veto(lookup:String)=sourceKeys.any{lookup in rejected[it].orEmpty()}
            val vector=if(member.ready)store.vector(key)else null
            val choices=groups.filter{target->target.id in established && target.id!=person && contacts[target.id]?.lookup?.let{!veto(it)}!=false && target.photos.any{it in accessible} &&
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
                    if(!score.isFinite() || score<CONTACT_FLOOR || veto(ref.contact.lookup))continue
                    if(!allowChange && person!=null){
                        val target=contactTargets[ref.contact.lookup]?.first?:continue
                        if(target.person!=person)continue
                    }
                    val linked=contacts.filterValues{it.lookup==ref.contact.lookup}.keys
                    if(person in linked)continue
                    val index=choices.indexOfFirst{it.id in linked}
                    if(index>=0){val old=choices[index];choices[index]=old.copy(score=maxOf(old.score,score),hintContact=ref.contact);continue}
                    // A blocked or inaccessible linked folder must not reappear as a raw contact.
                    if(linked.isNotEmpty())continue
                    if(!allowChange && contacts[person]?.lookup?.let{it!=ref.contact.lookup}==true)continue
                    choices+=Choice(0,ref.contact.name,null,score,emptySet(),ref.contact)
                }
            }
            val sorted=choices.sortedWith(compareByDescending<Choice>{it.score}.thenBy{it.name})
            return Result(member,null,"",sorted,sorted.filter{it.score>=if(it.hintContact!=null)CONTACT_FLOOR else cutoff}.take(3))
        }
    }
}
