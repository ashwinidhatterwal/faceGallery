package com.mosaic.gallery

/** Retry once per unchanged photo/model; cancellation never consumes the retry. */
object SignatureRefinement {
    internal var graphBuilds=0L;private set
    private var cachedToken="";private var cached=emptyMap<String,Set<GroupRules.Key>>()
    fun candidates(store:FaceStore,photos:List<PhotoRecord>,limit:Int=12,ignoreBackoff:Boolean=false):Map<PhotoRecord,Set<GroupRules.Key>> = FaceWork.read{synchronized(this){candidatesLocked(store,photos,limit,ignoreBackoff)}}
    private fun candidatesLocked(store:FaceStore,photos:List<PhotoRecord>,limit:Int,ignoreBackoff:Boolean):Map<PhotoRecord,Set<GroupRules.Key>> {
        val access=photos.associateBy{it.uri.toString()}
        val unrefined=mutableSetOf<GroupRules.Key>()
        store.readableDatabase.rawQuery("SELECT f.uri,f.ordinal FROM faces f JOIN photos p ON p.uri=f.uri LEFT JOIN membership m ON m.uri=f.uri AND m.ordinal=f.ordinal WHERE p.status='done' AND f.authority!='Shadow' AND COALESCE(m.manual,0)=0 AND COALESCE(m.status,'unknown')!='excluded' AND NOT EXISTS(SELECT 1 FROM signature_retries r WHERE r.uri=f.uri AND r.ordinal=f.ordinal AND r.model=?)",arrayOf("hq1-${FaceVectors.MODEL}")).use{c->while(c.moveToNext())if(c.getString(0) in access)unrefined+=GroupRules.Key(c.getString(0),c.getInt(1))}
        if(unrefined.isEmpty())return emptyMap()
        val generation=store.evidenceRevision()
        val saved=store.readableDatabase.rawQuery("SELECT payload FROM recognition_refinement_plan WHERE id=1 AND generation=?",arrayOf(generation.toString())).use{if(it.moveToFirst())it.getString(0)else null}
        val token=store.readableDatabase.path+":"+generation+":"+saved?.hashCode()
        if(saved!=null && token!=cachedToken){
            val array=org.json.JSONArray(saved);cached=buildMap<String,Set<GroupRules.Key>>{for(i in 0 until array.length()){val entry=array.getJSONArray(i);val uri=entry.getString(0);put(uri,(1 until entry.length()).map{GroupRules.Key(uri,entry.getInt(it))}.toSet())}};cachedToken=token
        }
        if(saved==null){
            graphBuilds++
            val people=PeopleStore(store);val rows=people.members();val roots=people.components(rows);val groups=people.capsules(rows,roots)
            val provisional=IdentityEvidence.provisional(groups,rows,people.names().keys+people.contacts(roots).keys)
            cached=rows.filter{!it.manual && it.face.authority!="Shadow" && it.status!="excluded" && (!it.ready || it.status=="tentative" || (roots[it.person]?:it.person) in provisional)}.groupBy{it.key.uri}.mapValues{(_,v)->v.map{it.key}.toSet()}
            val payload=org.json.JSONArray(cached.map{(uri,keys)->listOf<Any>(uri)+keys.sortedBy{it.ordinal}.map{it.ordinal}}).toString()
            FaceWork.write{if(store.evidenceRevision()==generation)store.writableDatabase.execSQL("INSERT OR REPLACE INTO recognition_refinement_plan VALUES(1,?,?)",arrayOf<Any>(generation,payload))}
            cachedToken=store.readableDatabase.path+":"+generation+":"+payload.hashCode()
        }
        return photos.mapNotNull{photo->cached[photo.uri.toString()]?.filter{it in unrefined && (ignoreBackoff || store.needsRefinement(it))}?.toSet()?.takeIf{it.isNotEmpty()}?.let{photo to it}}.take(limit).toMap()
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
            result.exceptionOrNull()?.let{if(it is DetectorUnavailable)throw it}
            FaceWork.write{
                if(canCommit()){
                    result.getOrNull()?.let{vector->store.saveSignature(uri,ordinal,vector);store.refinedObservation(key,face);updated++}
                    if(result.isSuccess)store.refined(key)else store.refinementFailed(key)
                }
            }
        };return updated
    }
}
