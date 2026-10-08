package com.mosaic.gallery

/** Display identity is explicit. Suggestions never promote an automatic group to a name. */
object IdentitySuggestions {
    data class Choice(val id:Long,val name:String,val reference:GroupRules.Prototype?,val score:Float,val sharedPhotos:Set<String>)
    data class Result(val member:GroupRules.Member,val person:Long?,val name:String,val choices:List<Choice>,val suggestions:List<Choice>)
    fun read(store:PeopleStore,key:GroupRules.Key,accessible:Set<String>,allowChange:Boolean=false):Result? {
        val rows=store.members();val member=rows.firstOrNull{it.key==key && it.key.uri in accessible}?:return null
        val roots=store.components(rows);val established=store.established(roots);val labels=store.labels(roots)
        val person=member.person?.takeIf{member.status=="known"}?.let{roots[it]?:it}
        if(!allowChange && person in established)return Result(member,person,labels[person].orEmpty(),emptyList(),emptyList())
        val groups=store.capsules(rows,roots);val source=groups.firstOrNull{it.id==person}
        val vector=if(member.ready)store.vector(key)else null
        val negatives=store.relations().filter{it.active && it.type=="cannot" && it.source=="user"}
        val choices=groups.filter{target->target.id in established && target.id!=person && target.photos.any{it in accessible} &&
            (allowChange || source==null || (!store.contactConflict(source.leaves,target.leaves) &&
                negatives.none{(it.a in source.leaves && it.b in target.leaves)||(it.b in source.leaves && it.a in target.leaves)}))
        }.map{target->
            val visible=GroupRules.Capsule(target.id,target.leaves,target.photos,target.anchors,target.prototypes.filter{it.member.key.uri in accessible}.toMutableList())
            val evidence=buildList{vector?.let{GroupRules.rank(it,visible)?.let(::add)};source?.takeUnless{allowChange}?.prototypes?.filter{it.member.key.uri in accessible}?.forEach{GroupRules.rank(it.vector,visible)?.let(::add)}}
            val best=evidence.maxByOrNull{it.score}
            val reference=visible.prototypes.firstOrNull{it.member.person==best?.witness}?:visible.prototypes.maxByOrNull{it.member.face.score}
            Choice(target.id,labels[target.id].orEmpty(),reference,best?.score?:-1f,(source?.takeUnless{allowChange}?.photos?:setOf(key.uri)).intersect(target.photos))
        }.sortedWith(compareByDescending<Choice>{it.score}.thenBy{it.name})
        return Result(member,null,"",choices,choices.filter{it.score>=store.policy().review}.take(3))
    }
}
