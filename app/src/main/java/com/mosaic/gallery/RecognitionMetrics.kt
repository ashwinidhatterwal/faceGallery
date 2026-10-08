package com.mosaic.gallery

import org.json.JSONArray
import org.json.JSONObject

/** Diagnostic export has no images, contact links or vectors. Accuracy needs separate true labels. */
object RecognitionMetrics {
    fun report(store:PeopleStore,rows:List<GroupRules.Member> = store.members(),roots:Map<Long,Long> = store.components(rows),groups:List<GroupRules.Capsule> = store.capsules(rows,roots),provisional:Set<Long> = IdentityEvidence.provisional(groups,rows,store.names().keys+store.contacts(roots).keys)):String {
        val faces=JSONArray()
        rows.forEach{row->val root=row.person?.let{roots[it]?:it};faces.put(JSONObject().put("uri",row.key.uri).put("ordinal",row.key.ordinal).put("group",root?:JSONObject.NULL).put("status",row.status).put("visible",root!=null && root !in provisional).put("manual",row.manual).put("quality",row.face.score).put("similarity",row.score).put("reason",row.reason))}
        val overlap=rows.groupBy{it.key.uri}.values.sumOf{photo->photo.indices.sumOf{i->(i+1 until photo.size).count{j->FaceAlignment.overlap(photo[i].face,photo[j].face)>=.85f}}}
        return JSONObject().put("possible_duplicate_detections",overlap).put("schema",1).put("model",FaceVectors.MODEL).put("faces",faces).put("profiles",groups.size-provisional.size).put("pending_profiles",provisional.size).put("unassigned",rows.count{it.person==null && it.status!="excluded"}).put("active_automatic_joins",store.relations().count{it.active && it.type!="cannot" && it.source=="auto"}).put("cannot_links",store.relations().count{it.active && it.type=="cannot"}).toString(2)
    }
}
