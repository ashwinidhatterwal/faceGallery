package com.mosaic.gallery

/** Local explicit feedback adjusts policy, never neural weights or identity probabilities. */
object PeopleCalibration {
    data class Sample(val a:String,val b:String,val label:Int,val score:Float)
    data class Policy(val anchor:Float=.80f,val support:Float=.80f,val merge:Float=.80f,val review:Float=.65f,val positives:Int=0,val negatives:Int=0,val agreement:Float=.72f,val groupMargin:Float=.04f,val faceMargin:Float=.06f)
    fun policy(samples:List<Sample>):Policy {
        fun scores(label:Int)=samples.filter{it.label==label && it.score.isFinite() && it.score in -1f..1f && it.a!=it.b}.distinctBy{setOf(it.a,it.b)}.map{it.score}.sorted()
        val positives=scores(1);val negatives=scores(0)
        val review=if(positives.size>=5)(positives[(positives.size-1)/10]-.04f).coerceIn(.55f,.65f)else .65f
        // Only explicit feedback adjusts bounded gates. High hard-negative scores tighten them.
        val positiveGate=if(positives.size>=8)(positives[(positives.size-1)/10]-.03f).coerceIn(.68f,.78f)else .72f
        val negativeGate=if(negatives.size>=8)(negatives[(negatives.size-1)*9/10]+.03f).coerceIn(.72f,.90f)else .68f
        val agreement=maxOf(positiveGate,negativeGate)
        val direct=maxOf(.80f,if(negatives.size>=8)negativeGate else .80f)
        return Policy(anchor=direct,support=direct,merge=direct,review=review,positives=positives.size,negatives=negatives.size,agreement=agreement)
    }
}
