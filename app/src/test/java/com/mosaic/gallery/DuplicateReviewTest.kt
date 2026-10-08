package com.mosaic.gallery

import android.net.Uri
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35])
class DuplicateReviewTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val face=FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,120f,.95f,"Anchor")
    private fun vector(cos:Float=1f)=FloatArray(128){if(it==0)cos else if(it==1)kotlin.math.sqrt(1-cos*cos)else 0f}
    private fun capsule(id:Long,cos:Float=1f,time:Long=id*120_000L)=GroupRules.Capsule(id,mutableSetOf(id),mutableSetOf("photo:$id"),mutableSetOf("photo:$id"),mutableListOf(GroupRules.Prototype(GroupRules.Member(GroupRules.Key("photo:$id",0),face,time,id,null,"known",1f,"",true),vector(cos))))
    @Before fun reset(){app.deleteDatabase("faces.db")}
    @After fun cleanup(){app.deleteDatabase("faces.db")}
    private fun add(faces:FaceStore,id:Int,count:Int=1,authority:String="Anchor"):PhotoRecord {
        val photo=PhotoRecord(id.toLong(),Uri.parse("content://review/$id"),"$id.jpg",id*120_000L,400,300,"Camera",1000,"/storage/$id.jpg",100)
        faces.save(photo,List(count){face.copy(authority=authority)});repeat(count){faces.saveSignature(photo.uri.toString(),it,vector())};return photo
    }
    private fun seed(store:PeopleStore,uri:String,ordinal:Int=0)=store.record(store.pending().first{it.key==GroupRules.Key(uri,ordinal)},GroupRules.Decision(seed=true,status="known"))!!
    @Test fun timeContextCannotCreateSuggestionWithoutFaceEvidence(){
        assertNull(DuplicateReview.evidence(capsule(1),capsule(2,.4f)))
    }
    @Test fun timeContextRanksButDoesNotAuthorizeMerge(){
        val near=DuplicateReview.evidence(capsule(1),capsule(2,.7f))!!
        val far=DuplicateReview.evidence(capsule(1),capsule(2,.7f,time=100_000_000L))!!
        assertTrue(near.score>far.score);assertFalse(GroupRules.canJoin(capsule(1),capsule(2,.7f)))
    }
    @Test fun unknownCaptureTimeAddsNoEventBonus(){
        val result=DuplicateReview.evidence(capsule(1,time=0),capsule(2,.7f))!!
        assertEquals(.7f,result.score,.0001f);assertFalse(result.reason.contains("close capture"))
    }
    @Test fun sharedPhotoNeverProducesDuplicateSuggestion(){
        val a=capsule(1);val b=capsule(2);b.photos.addAll(a.photos);assertNull(DuplicateReview.evidence(a,b))
    }
    @Test fun userNegativeFiltersSuggestion(){
        val negative=PeopleStore.Relation(1,1,2,"cannot","user",true,"")
        assertTrue(DuplicateReview.find(listOf(capsule(1),capsule(2)),listOf(negative)).isEmpty())
    }
    @Test fun queueIsBoundedAndDeterministic(){
        val groups=(1L..20L).map{capsule(it)};val a=DuplicateReview.find(groups,emptyList(),5)
        assertEquals(5,a.size);assertEquals(a,DuplicateReview.find(groups,emptyList(),5));assertTrue(DuplicateReview.find(groups,emptyList(),0).isEmpty())
    }
    @Test fun cancellationReturnsNoPartialReviewQueue(){
        var calls=0;assertTrue(DuplicateReview.find((1L..5L).map{capsule(it)},emptyList(),keepGoing={++calls<3}).isEmpty())
    }
    @Test fun manualMergePersistsAndCombinesReferenceSetsWithoutChangingVectors(){
        FaceStore(app).use{faces->add(faces,1);add(faces,2);val store=PeopleStore(faces);val a=seed(store,"content://review/1");val b=seed(store,"content://review/2")
            store.rename(a,"Alice");store.merge(a,b);assertEquals(1,store.capsules().size);assertEquals(2,store.capsules().single().prototypes.size);assertEquals(2,faces.signatureSummary().ready)
        }
        FaceStore(app).use{faces->val store=PeopleStore(faces);assertEquals(1,store.capsules().size);assertEquals("Alice",store.label(store.capsules().single().id));PeopleGrouping.run(store,{true});assertEquals(1,store.capsules().size)}
    }
    @Test fun manualMergeCanBeUndoneAndRegroupCannotRestoreIt(){FaceStore(app).use{faces->
        add(faces,1);add(faces,2);val store=PeopleStore(faces);val a=seed(store,"content://review/1");val b=seed(store,"content://review/2");store.merge(a,b)
        store.unlink(store.relations().first{it.source=="user" && it.type=="merge"}.id);PeopleGrouping.run(store,{true});assertEquals(2,store.capsules().size);assertTrue(DuplicateReview.find(store.capsules(),store.relations()).isEmpty())
    }}
    @Test fun differentPeopleCorrectionBlocksBothManualAndAutomaticJoin(){FaceStore(app).use{faces->
        add(faces,1);add(faces,2);val store=PeopleStore(faces);val a=seed(store,"content://review/1");val b=seed(store,"content://review/2");store.reject(a,b)
        assertTrue(runCatching{store.merge(a,b)}.isFailure);assertEquals(2,store.capsules().size)
    }}
    @Test fun manualSamePhotoMergeIsRejectedAtomically(){FaceStore(app).use{faces->
        add(faces,1,2);val store=PeopleStore(faces);val a=seed(store,"content://review/1");val b=seed(store,"content://review/1",1)
        assertTrue(runCatching{store.merge(a,b)}.isFailure);assertTrue(store.relations().isEmpty());assertEquals(2,store.capsules().size)
    }}
    @Test fun deletedGroupCannotBeManuallyMerged(){FaceStore(app).use{faces->
        val p=add(faces,1);add(faces,2);val store=PeopleStore(faces);val a=seed(store,"content://review/1");val b=seed(store,"content://review/2");faces.retain(listOf(p));assertTrue(runCatching{store.merge(a,b)}.isFailure)
    }}
    @Test fun repeatedUserMergeDoesNotDuplicateRelations(){FaceStore(app).use{faces->
        add(faces,1);add(faces,2);val store=PeopleStore(faces);val a=seed(store,"content://review/1");val b=seed(store,"content://review/2");store.merge(a,b);store.merge(a,b);assertEquals(1,store.relations().size)
    }}
    @Test fun secondPassRevisitsSupportAfterSecondAnchorArrives(){FaceStore(app).use{faces->
        add(faces,1);add(faces,2,authority="Support");faces.saveSignature("content://review/2",0,vector(.75f));val store=PeopleStore(faces);PeopleGrouping.run(store,{true});assertEquals("tentative",store.members().first{it.key.uri=="content://review/2"}.status)
        add(faces,3);faces.saveSignature("content://review/3",0,vector(.9f));PeopleGrouping.run(store,{true});assertEquals("known",store.members().first{it.key.uri=="content://review/2"}.status)
    }}
}
