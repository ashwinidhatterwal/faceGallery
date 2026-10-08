package com.mosaic.gallery

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import kotlin.math.sqrt

/** Full-precision, versioned vectors. Similarity is a ranking score, never a probability. */
object FaceVectors {
    const val DIMENSIONS=128
    const val MODEL="mobilefacenet-35e2fc14-align5-tta-f32-v1"
    fun normalize(values:FloatArray):FloatArray {
        require(values.size==DIMENSIONS && values.all{it.isFinite()}){"Invalid signature"}
        val norm=sqrt(values.sumOf{it.toDouble()*it});require(norm>1e-12){"Empty signature"}
        return FloatArray(values.size){(values[it]/norm).toFloat()}
    }
    fun pack(values:FloatArray):ByteArray=ByteBuffer.allocate(DIMENSIONS*4).order(ByteOrder.LITTLE_ENDIAN).apply{normalize(values).forEach{putFloat(it)}}.array()
    fun unpack(bytes:ByteArray):FloatArray {
        require(bytes.size==DIMENSIONS*4){"Invalid signature size"}
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return normalize(FloatArray(DIMENSIONS){buffer.float})
    }
    fun cosine(a:FloatArray,b:FloatArray):Float {
        require(a.size==DIMENSIONS && b.size==DIMENSIONS)
        return a.indices.sumOf{a[it].toDouble()*b[it]}.toFloat().coerceIn(-1f,1f)
    }
    /** Later phases can build a representative from quality-weighted, confirmed evidence. */
    fun prototype(vectors:List<Pair<FloatArray,Float>>):FloatArray {
        require(vectors.isNotEmpty());val sum=FloatArray(DIMENSIONS)
        vectors.forEach{(vector,weight)->require(weight.isFinite() && weight>0);val unit=normalize(vector);unit.indices.forEach{sum[it]+=unit[it]*weight}}
        return normalize(sum)
    }
    data class Match(val uri:String,val ordinal:Int,val face:FaceObservation,val similarity:Float)
    /** Bounded top-k: O(k) memory while the database streams candidates. */
    class Search(query:FloatArray,private val sourceUri:String,private val limit:Int=20) {
        private val unit=normalize(query)
        private val heap=PriorityQueue<Match>(compareBy<Match>{it.similarity}.thenBy{it.uri}.thenBy{it.ordinal})
        init{require(limit>0)}
        fun offer(uri:String,ordinal:Int,face:FaceObservation,vector:FloatArray){
            if(uri==sourceUri || face.authority=="Shadow")return
            val match=Match(uri,ordinal,face,cosine(unit,normalize(vector)))
            if(heap.size<limit)heap.add(match)else if(match.similarity>heap.peek()!!.similarity){heap.poll();heap.add(match)}
        }
        fun results():List<Match> = heap.sortedWith(compareByDescending<Match>{it.similarity}.thenBy{it.uri}.thenBy{it.ordinal})
    }
}
