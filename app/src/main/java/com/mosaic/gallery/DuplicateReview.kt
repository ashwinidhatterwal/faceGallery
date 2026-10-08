package com.mosaic.gallery

import java.util.PriorityQueue

/** Review ranking only. Time proximity must never authorize an identity merge. */
object DuplicateReview {
    data class Suggestion(val a:Long,val b:Long,val score:Float,val reason:String)
    private const val EVENT_MS=30*60_000L
    fun evidence(a:GroupRules.Capsule,b:GroupRules.Capsule,cutoff:Float=.65f):Suggestion? {
        if(a.photos.intersect(b.photos).isNotEmpty())return null
        val matches=buildList {for(x in a.prototypes)for(y in b.prototypes)add(Triple(x,y,FaceVectors.cosine(x.vector,y.vector)))}.sortedByDescending{it.third}
        val best=matches.firstOrNull()?:return null
        if(best.third<cutoff)return null
        val independent=matches.firstOrNull{it.first.member.key.uri!=best.first.member.key.uri && it.second.member.key.uri!=best.second.member.key.uri && it.third>=cutoff}
        val near=matches.any{it.third>=cutoff && it.first.member.time>0 && it.second.member.time>0 && kotlin.math.abs(it.first.member.time-it.second.member.time)<=EVENT_MS}
        val score=best.third+(if(independent!=null).02f else 0f)+(if(near).01f else 0f)
        return Suggestion(a.id,b.id,score,"Similar clear references${if(independent!=null)" · multiple photos"else" · limited evidence"}${if(near)" · close capture times"else""}. Confirm from the photos.")
    }
    fun find(groups:List<GroupRules.Capsule>,negatives:List<PeopleStore.Relation>,limit:Int=60,policy:PeopleCalibration.Policy=PeopleCalibration.Policy(),keepGoing:()->Boolean={true}):List<Suggestion> {
        if(limit<=0)return emptyList()
        val heap=PriorityQueue<Suggestion>(compareBy<Suggestion>{it.score}.thenByDescending{it.a}.thenByDescending{it.b})
        for(i in groups.indices)for(j in i+1 until groups.size){
            if(!keepGoing())return emptyList()
            val a=groups[i];val b=groups[j]
            if(negatives.any{it.active && it.type=="cannot" && ((it.a in a.leaves && it.b in b.leaves)||(it.b in a.leaves && it.a in b.leaves))})continue
            val suggestion=evidence(a,b,policy.review)?:continue
            heap.add(suggestion);if(heap.size>limit)heap.poll()
        }
        return heap.toList().sortedWith(compareByDescending<Suggestion>{it.score}.thenBy{it.a}.thenBy{it.b})
    }
}
