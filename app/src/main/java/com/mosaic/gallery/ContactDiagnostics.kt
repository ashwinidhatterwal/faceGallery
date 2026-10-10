package com.mosaic.gallery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Cached evidence only. Names are local UI opt-in; exported entries use opaque tags. */
object ContactDiagnostics {
    fun entries(context:Context,faces:FaceStore,names:Boolean=false):JSONArray {
        val store=PeopleStore(faces);val rows=store.members();val roots=store.components(rows)
        val groups=store.capsules(rows,roots);val links=store.contacts(roots)
        val named=store.established(roots);val blocked=store.contactAutomationBlocked()
        val floor=IdentitySuggestions.CONTACT_FLOOR
        val query=if(ContactRecognition.permitted(context))runCatching{ContactRecognition.photos(context,android.os.CancellationSignal(),manual=true,includeMissing=true)}else null
        val live=query?.getOrDefault(emptyList()).orEmpty()
        val result=JSONArray();val seen=mutableSetOf<String>()
        faces.readableDatabase.rawQuery("SELECT lookup,name,stamp,model,status,reason,detail,attempts,next_time,vector FROM contact_signatures ORDER BY name,lookup",null).use { c->
            while(c.moveToNext()) {
                val lookup=c.getString(0);seen+=lookup
                val entry=JSONObject().put("contact_tag",ContactRecognition.hash(lookup.toByteArray()).take(12))
                    .put("provider_listing_checked",query?.isSuccess==true).put("status",c.getString(4)).put("reason",c.getString(5)).put("processed_model",c.getString(3))
                    .put("attempts",c.getInt(7)).put("retry_at_ms",c.getLong(8))
                    .put("linked_group_ids",JSONArray(links.filterValues{it.lookup==lookup}.keys.toList()))
                if(names)entry.put("name",live.firstOrNull{it.contact.lookup==lookup}?.contact?.name?:c.getString(1))
                if(c.getString(6).isNotBlank())entry.put("portrait_checks",runCatching{JSONObject(c.getString(6))}.getOrDefault(JSONObject()))
                else entry.put("portrait_checks_recorded",false)
                live.firstOrNull{it.contact.lookup==lookup}?.let{entry.put("portrait_changed_since_scan",it.stamp!=c.getString(2));entry.put("provider_has_photo",it.hasPortrait)}
                val eligible=c.getString(4)=="done" && !c.isNull(9) && ContactRecognition.referenceModel(c.getString(3)) && ContactRecognition.permitted(context)
                entry.put("reference_eligible",eligible).put("automatic_naming_eligible",eligible && ContactRecognition.automaticEligible(c.getString(6)))
                if(eligible) {
                    val vector=FaceVectors.unpack(c.getBlob(9))
                    val ranked=groups.mapNotNull{group->group.prototypes.map{FaceVectors.cosine(vector,it.vector)}.filter{it.isFinite()}.maxOrNull()?.let{group to it}}.sortedByDescending{it.second}
                    val best=ranked.firstOrNull()
                    entry.put("suggestion_floor",floor).put("cached_gallery_groups_compared",ranked.size)
                    if(best!=null){
                        entry.put("best_cached_group_id",best.first.id).put("best_cached_similarity",best.second)
                            .put("best_group_named",best.first.id in named).put("best_group_auto_blocked",best.first.leaves.any{it in blocked})
                            .put("comparison_result",if(best.second<floor)"below_suggestion_floor"else"candidate_above_floor")
                    }else entry.put("comparison_result","no_gallery_reference_signatures")
                    // Actual picker filtering additionally checks access, explicit separations,
                    // existing links, and top-three ranking. This is evidence, not a UI promise.
                }
                result.put(entry)
            }
        }
        live.filter{it.contact.lookup !in seen}.forEach{photo->
            val entry=JSONObject().put("contact_tag",ContactRecognition.hash(photo.contact.lookup.toByteArray()).take(12)).put("linked_group_ids",JSONArray())
                .put("provider_listing_checked",true).put("provider_has_photo",photo.hasPortrait).put("status",if(photo.hasPortrait)"not_processed"else"no_provider_photo")
                .put("reason",if(photo.hasPortrait)"awaiting_portrait_scan"else"contact_provider_has_no_photo")
            if(names)entry.put("name",photo.contact.name)
            result.put(entry)
        }
        return result
    }
    fun explanation(entry:JSONObject):String {
        val checks=entry.optJSONObject("portrait_checks")?.optJSONArray("failed_checks")
        val failures=if(checks==null)emptyList()else (0 until checks.length()).map{checks.getString(it).replace('_',' ')}
        return when(entry.optString("status")) {
            "no_provider_photo"->"Android contacts exposes no photo for this contact"
            "not_processed"->"Waiting for portrait processing"
            "error"->"Read or processing error: ${entry.optString("reason")} · attempt ${entry.optInt("attempts")}/3"
            "skipped"->if(failures.isNotEmpty())"Rejected: ${failures.joinToString(", ")}"else when(entry.optString("reason")) {
                "single_face_not_detected"->"Exactly one usable face was not detected"
                "portrait_alignment"->"Facial landmarks could not be aligned safely"
                else->"Rejected by the portrait quality checks; this earlier scan has no individual measurements"
            }
            "done"->if(entry.optJSONObject("portrait_checks")?.optBoolean("suggestion_only")==true)"Small portrait · confirmation-only name suggestions"else if(!entry.optBoolean("reference_eligible"))"Saved portrait unavailable for suggestions: check permission, consent and model"else if(entry.has("best_cached_similarity"))"Usable portrait · best cached similarity %.3f · suggestion floor %.3f".format(java.util.Locale.ROOT,entry.getDouble("best_cached_similarity"),entry.getDouble("suggestion_floor"))else "Usable portrait · no gallery reference signatures available"
            else->"No processing result recorded"
        }
    }
}
