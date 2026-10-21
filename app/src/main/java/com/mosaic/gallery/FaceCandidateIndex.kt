package com.mosaic.gallery

/** Exact metric search on unit vectors; triangle bounds prune only impossible matches. */
internal class FaceCandidateIndex(private val groups:List<GroupRules.Capsule>) {
    private data class Entry(val id:Long,val vector:FloatArray)
    private data class Node(val entry:Entry,val radius:Float,val near:Node?,val far:Node?)
    private val entries=groups.flatMap{g->g.prototypes.map{Entry(g.id,it.vector)}}
    private fun distance(a:FloatArray,b:FloatArray)=kotlin.math.sqrt((2f-2f*FaceVectors.cosine(a,b)).coerceAtLeast(0f))
    private fun build(values:List<Entry>):Node? {
        if(values.isEmpty())return null
        val centre=values.last();val sorted=values.dropLast(1).map{it to distance(centre.vector,it.vector)}.sortedBy{it.second}
        val middle=sorted.size/2;val radius=sorted.getOrNull(middle)?.second?:0f
        return Node(centre,radius,build(sorted.take(middle).map{it.first}),build(sorted.drop(middle).map{it.first}))
    }
    private val root=build(entries)
    fun neighbors(group:GroupRules.Capsule,floor:Float,keepGoing:()->Boolean):List<Long>? {
        val radius=kotlin.math.sqrt(2f-2f*floor)+.0001f;val found=hashSetOf<Long>()
        fun visit(node:Node?,query:FloatArray){
            if(node==null)return
            val d=distance(query,node.entry.vector)
            if(group.id!=node.entry.id && d<=radius && FaceVectors.cosine(query,node.entry.vector)>=floor)found+=node.entry.id
            if(d-radius<=node.radius)visit(node.near,query)
            if(d+radius>=node.radius)visit(node.far,query)
        }
        for(ref in group.prototypes){if(!keepGoing())return null;visit(root,ref.vector)}
        return found.sorted()
    }
    fun hasNeighbor(group:GroupRules.Capsule,floor:Float):Boolean {
        val radius=kotlin.math.sqrt(2f-2f*floor)+.0001f
        fun visit(node:Node?,query:FloatArray):Boolean {
            if(node==null)return false
            val d=distance(query,node.entry.vector)
            if(group.id!=node.entry.id && d<=radius && FaceVectors.cosine(query,node.entry.vector)>=floor)return true
            return (d-radius<=node.radius && visit(node.near,query)) || (d+radius>=node.radius && visit(node.far,query))
        }
        return group.prototypes.any{visit(root,it.vector)}
    }
    fun pairs(floor:Float,keepGoing:()->Boolean):List<Pair<Long,Long>>? {
        val result=mutableListOf<Pair<Long,Long>>()
        for(group in groups){val next=neighbors(group,floor,keepGoing)?:return null;next.filter{it>group.id}.forEach{result+=group.id to it}}
        return result.sortedWith(compareBy<Pair<Long,Long>>{it.first}.thenBy{it.second})
    }
}
