package com.mosaic.gallery

/** A fixed worklist, with one regroup after all committed improvements. */
internal object RefinementSweep {
    fun <T> run(work:List<T>,keepGoing:()->Boolean,refine:(T)->Int,regroup:()->Unit) {
        var updated=false
        for(item in work){
            if(!keepGoing())return
            if(refine(item)>0)updated=true
        }
        // Interrupted commits remain dirty; a later session performs their grouping.
        if(updated && keepGoing())regroup()
    }
}
