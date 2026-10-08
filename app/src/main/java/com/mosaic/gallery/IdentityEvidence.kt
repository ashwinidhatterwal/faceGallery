package com.mosaic.gallery

import kotlin.math.abs

/** Cheap evidence on existing vectors. Automatic decisions are never training labels. */
object IdentityEvidence {
    fun reference(member:GroupRules.Member)=member.ready && (member.face.authority=="Anchor" || (member.manual && member.face.authority=="Support" && member.face.score>=.65f))
    fun independent(a:GroupRules.Member,b:GroupRules.Member)=a.key.uri!=b.key.uri &&
        ((a.time>0 && b.time>0 && abs(a.time-b.time)>=GroupRules.INDEPENDENT_MS) || abs(a.face.yaw-b.face.yaw)>=15f)
    fun references(pool:List<GroupRules.Prototype>):MutableList<GroupRules.Prototype> {
        val clear=pool.filter{reference(it.member)}.distinctBy{it.member.key.uri}
        if(clear.isEmpty())return mutableListOf()
        // A medoid selects consistent evidence before appearance diversity; manual labels are retained.
        val centre=clear.maxWith(compareBy<GroupRules.Prototype>{p->clear.count{FaceVectors.cosine(p.vector,it.vector)>=.60f}}.thenBy{it.member.face.score})
        val trusted=clear.filter{it.member.manual || FaceVectors.cosine(it.vector,centre.vector)>=.60f}.toMutableList()
        val chosen=mutableListOf(centre);trusted.remove(centre)
        val budget=if(clear.size>=12 && trusted.any{FaceVectors.cosine(it.vector,centre.vector)<.92f})12 else 6
        while(chosen.size<budget && trusted.isNotEmpty()){
            val next=trusted.maxWith(compareBy<GroupRules.Prototype>{p->1f-chosen.maxOf{FaceVectors.cosine(p.vector,it.vector)}}.thenBy{it.member.face.score})
            chosen+=next;trusted.remove(next)
        };return chosen
    }
    fun established(group:GroupRules.Capsule,rows:List<GroupRules.Member>,named:Set<Long>):Boolean {
        if(group.leaves.any{it in named} || rows.any{it.person in group.leaves && it.manual})return true
        return group.prototypes.any{a->group.prototypes.any{b->independent(a.member,b.member) && FaceVectors.cosine(a.vector,b.vector)>=.72f}}
    }
    fun provisional(groups:List<GroupRules.Capsule>,rows:List<GroupRules.Member>,named:Set<Long>):Set<Long> {
        val confirmed=named+rows.filter{it.manual}.mapNotNull{it.person}
        return groups.filterNot{group->
            established(group,emptyList(),confirmed) || (group.prototypes.any{it.member.face.score>=.85f} &&
                groups.filter{it.id!=group.id}.none{other->group.prototypes.any{p->GroupRules.rank(p.vector,other)?.score?.let{it>=.60f}==true}})
        }.map{it.id}.toSet()
    }
    fun penalty(member:GroupRules.Member)=((.75f-member.face.score).coerceAtLeast(0f)*.10f).coerceAtMost(.03f)
}
