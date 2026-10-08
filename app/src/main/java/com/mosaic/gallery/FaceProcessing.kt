package com.mosaic.gallery

/** Commits one photo atomically; interrupted inference remains pending for the next scan. */
object FaceProcessing{
    fun run(store:FaceStore,pending:List<PhotoRecord>,keepGoing:()->Boolean,
        detect:(PhotoRecord)->List<FaceObservation>,canCommit:()->Boolean=keepGoing,progress:(Int)->Unit={}){
        for((index,photo) in pending.withIndex()){
            if(!keepGoing())break
            val result=runCatching{detect(photo)}
            if(!canCommit())break
            result.onSuccess{store.save(photo,it)}.onFailure{store.save(photo,emptyList(),it.javaClass.simpleName)}
            progress(index+1)
        }
    }
}
