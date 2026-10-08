package com.mosaic.gallery

/** Retry once per unchanged photo/model; cancellation never consumes the retry. */
object SignatureRefinement {
    fun candidates(store:FaceStore,photos:List<PhotoRecord>,limit:Int=12):Map<PhotoRecord,Set<GroupRules.Key>> {
        val people=PeopleStore(store);val rows=people.members();val groups=people.capsules(rows);val roots=people.components(rows)
        val provisional=IdentityEvidence.provisional(groups,rows,people.names().keys+people.contacts(roots).keys)
        val wanted=rows.filter{!it.manual && it.face.authority!="Shadow" && it.status!="excluded" &&
            (!it.ready || it.status=="tentative" || (roots[it.person]?:it.person) in provisional) && store.needsRefinement(it.key)}.groupBy{it.key.uri}
        return photos.filter{it.uri.toString() in wanted}.take(limit).associateWith{wanted[it.uri.toString()].orEmpty().map{m->m.key}.toSet()}
    }
    fun run(store:FaceStore,uri:String,faces:List<FaceObservation>,keys:Set<GroupRules.Key>,keepGoing:()->Boolean,canCommit:()->Boolean=keepGoing,encode:(FaceObservation)->FloatArray?):Int {
        var updated=0
        for((ordinal,face) in faces.withIndex()){
            if(!keepGoing())break
            val key=GroupRules.Key(uri,ordinal);if(key !in keys || !store.needsRefinement(key))continue
            if(face.authority=="Shadow"){store.refinedObservation(key,face);store.refined(key);continue}
            val result=runCatching{encode(face)?.let{FaceVectors.normalize(it)}}
            if(!canCommit())break
            // A failed retry preserves the existing signature and is not repeated at every opening.
            result.getOrNull()?.let{vector->store.saveSignature(uri,ordinal,vector);store.refinedObservation(key,face);updated++}
            if(result.isSuccess)store.refined(key)else store.refinementFailed(key)
        };return updated
    }
}
