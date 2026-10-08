package com.mosaic.gallery

import android.net.Uri
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35])
class PeopleCorrectionsTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val face=FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,120f,.95f,"Anchor")
    private fun vector()=FloatArray(128){if(it==0)1f else 0f}
    private fun key(id:Int,ordinal:Int=0)=GroupRules.Key("content://corrections/$id",ordinal)
    private fun add(faces:FaceStore,id:Int,count:Int=1,authority:String="Anchor",ready:Boolean=true):PhotoRecord {
        val p=PhotoRecord(id.toLong(),Uri.parse(key(id).uri),"$id.jpg",id*120_000L,400,300,"Camera",1000,"/storage/$id.jpg",100)
        faces.save(p,List(count){face.copy(authority=authority)});if(ready)repeat(count){faces.saveSignature(p.uri.toString(),it,vector())};return p
    }
    @Before fun reset(){app.deleteDatabase("faces.db")}
    @After fun cleanup(){app.deleteDatabase("faces.db")}
    @Test fun splitSeveralFacesKeepsRemainingPersonTogether(){FaceStore(app).use{faces->
        (1..5).forEach{add(faces,it)};val store=PeopleStore(faces);PeopleGrouping.run(store,{true});assertEquals(1,store.capsules().size)
        store.correct(setOf(key(1),key(3)),create=true);assertEquals(listOf(2,3),store.capsules().map{it.photos.size}.sorted())
        PeopleGrouping.run(store,{true});assertEquals(2,store.capsules().size);assertEquals(5,faces.signatureSummary().ready)
    }}
    @Test fun undoSplitRestoresOriginalMembershipAndNegatives(){FaceStore(app).use{faces->
        (1..3).forEach{add(faces,it)};val store=PeopleStore(faces);PeopleGrouping.run(store,{true});val before=store.members().associate{it.key to it.person}
        store.correct(setOf(key(1),key(2)),create=true);assertTrue(store.canUndo());store.undoCorrection();assertFalse(store.canUndo());assertEquals(before,store.members().associate{it.key to it.person});assertEquals(1,store.capsules().size)
        assertFalse(store.relations().any{it.active && it.type=="cannot"})
    }}
    @Test fun notThisPersonStaysExcludedThroughRegroupingAndReopen(){
        FaceStore(app).use{faces->add(faces,1);add(faces,2);val store=PeopleStore(faces);PeopleGrouping.run(store,{true});store.correct(setOf(key(1)),exclude=true)}
        FaceStore(app).use{faces->val store=PeopleStore(faces);PeopleGrouping.run(store,{true});val member=store.members().first{it.key==key(1)};assertEquals("excluded",member.status);assertTrue(member.manual);assertNull(member.person);store.undoCorrection();assertEquals(1,store.capsules().size)}
    }
    @Test fun weakFacesCanBeHumanAssignedButCannotBecomeAutomaticReferences(){FaceStore(app).use{faces->
        add(faces,1,authority="Shadow",ready=false);val store=PeopleStore(faces);store.correct(setOf(key(1)),create=true)
        assertEquals("known",store.members().single().status);assertTrue(store.members().single().manual);assertTrue(store.capsules().single().prototypes.isEmpty());assertTrue(store.capsules().single().anchors.isEmpty())
    }}
    @Test fun assignUnknownFacesToExistingPersonAndUndo(){FaceStore(app).use{faces->
        add(faces,1);add(faces,2,authority="Support");faces.saveSignature(key(2).uri,0,FloatArray(128){if(it==0).75f else if(it==1)kotlin.math.sqrt(1-.75f*.75f)else 0f});val store=PeopleStore(faces);PeopleGrouping.run(store,{true});val person=store.capsules().single().id
        store.correct(setOf(key(2)),target=person);assertEquals(2,store.capsules().single().photos.size);assertEquals(2,store.capsules().single().prototypes.size)
        store.undoCorrection();assertEquals("tentative",store.members().first{it.key==key(2)}.status);assertEquals(1,store.capsules().single().prototypes.size)
    }}
    @Test fun invalidSamePhotoAssignmentRollsBackWholeBatch(){FaceStore(app).use{faces->
        add(faces,1,2);add(faces,2);val store=PeopleStore(faces);PeopleGrouping.run(store,{true});val destination=store.capsules().first{key(1).uri in it.photos}.id
        val before=store.members().map{it.key to it.person};assertTrue(runCatching{store.correct(setOf(key(1,1),key(2)),target=destination)}.isFailure)
        assertEquals(before,store.members().map{it.key to it.person});assertFalse(store.canUndo())
    }}
    @Test fun twoFacesInSamePhotoCannotSeedOnePerson(){FaceStore(app).use{faces->
        add(faces,1,2);val store=PeopleStore(faces);assertTrue(runCatching{store.correct(setOf(key(1),key(1,1)),create=true)}.isFailure);assertTrue(store.capsules().isEmpty())
    }}
    @Test fun staleSelectionMakesNoPartialCorrection(){FaceStore(app).use{faces->
        add(faces,1);val store=PeopleStore(faces);assertTrue(runCatching{store.correct(setOf(key(1),key(99)),create=true)}.isFailure);assertTrue(store.capsules().isEmpty());assertFalse(store.canUndo())
    }}
    @Test fun correctionHistoryIsUndoableInReverseOrder(){FaceStore(app).use{faces->
        add(faces,1);val store=PeopleStore(faces);PeopleGrouping.run(store,{true});val original=store.members().single().person
        store.correct(setOf(key(1)),exclude=true);store.correct(setOf(key(1)),create=true);store.undoCorrection();assertEquals("excluded",store.members().single().status)
        store.undoCorrection();assertEquals(original,store.members().single().person);assertFalse(store.canUndo())
    }}
    @Test fun revokedAccessClearsFaceHistoryAndCalibrationSamples(){FaceStore(app).use{faces->
        add(faces,1);add(faces,2);val store=PeopleStore(faces);PeopleGrouping.run(store,{true});store.correct(setOf(key(1)),exclude=true);assertEquals(1,store.policy().negatives)
        faces.retain(emptyList());assertFalse(store.canUndo());assertEquals(0,store.policy().negatives);assertTrue(store.members().isEmpty())
    }}
    @Test fun resetRemovesCorrectionsButKeepsFaceSignatures(){FaceStore(app).use{faces->
        add(faces,1);add(faces,2);val store=PeopleStore(faces);PeopleGrouping.run(store,{true});store.correct(setOf(key(1)),exclude=true);store.reset();assertFalse(store.canUndo());assertEquals(0,store.policy().negatives);assertEquals(2,faces.signatureSummary().ready)
    }}
    @Test fun userMergeAndRejectionFeedCalibration(){FaceStore(app).use{faces->
        (1..3).forEach{add(faces,it)};val store=PeopleStore(faces);val ids=store.pending().map{store.record(it,GroupRules.Decision(seed=true,status="known"))!!}
        store.reject(ids[0],ids[1]);assertEquals(1,store.policy().negatives);store.merge(ids[0],ids[2]);assertEquals(1,store.policy().positives)
    }}
    @Test fun insufficientFeedbackDoesNotChangeThresholds(){
        val p=PeopleCalibration.policy((1..4).map{PeopleCalibration.Sample("$it","x",0,.95f)})
        assertEquals(.80f,p.anchor,0f);assertEquals(.80f,p.merge,0f)
    }
    @Test fun negativeSamplesDoNotOverrideRequestedFixedThreshold(){
        val p=PeopleCalibration.policy((1..6).map{PeopleCalibration.Sample("$it","x",0,.99f)})
        assertEquals(.80f,p.anchor,0f);assertEquals(.80f,p.support,0f);assertEquals(.80f,p.merge,0f)
    }
    @Test fun positiveFeedbackOnlyBroadensReviewSuggestions(){
        val p=PeopleCalibration.policy((1..6).map{PeopleCalibration.Sample("$it","x",1,.60f)})
        assertEquals(.56f,p.review,.0001f);assertEquals(.80f,p.anchor,0f);assertEquals(.80f,p.merge,0f)
    }
    @Test fun repeatedOrInvalidEvidenceCannotCalibrate(){
        val samples=List(20){PeopleCalibration.Sample("a","b",0,.99f)}+PeopleCalibration.Sample("x","x",0,.99f)+PeopleCalibration.Sample("z","w",0,Float.NaN)
        val p=PeopleCalibration.policy(samples);assertEquals(1,p.negatives);assertEquals(.80f,p.anchor,0f)
    }
    @Test fun calibratedPolicyDoesNotBypassStrongUniqueEvidenceRule(){
        val member=GroupRules.Member(key(1),face,1,null,null,"unknown",0f,"",true)
        val ref=member.copy(key=key(2),person=2);val capsule=GroupRules.Capsule(2,mutableSetOf(2),mutableSetOf(key(2).uri),mutableSetOf(key(2).uri),mutableListOf(GroupRules.Prototype(ref,vector())))
        val candidate=GroupRules.Candidate(capsule,.9f,.9f,2)
        assertNotNull(GroupRules.decide(member,listOf(candidate)).target)
        assertNull(GroupRules.decide(member,listOf(candidate),PeopleCalibration.Policy(anchor=.94f)).target)
    }
    @Test fun migrationFromSchemaThreeKeepsGroupsNamesAndSignatures(){
        FaceStore(app).use{faces->add(faces,1);val store=PeopleStore(faces);PeopleGrouping.run(store,{true});store.rename(store.capsules().single().id,"Alice");val db=faces.writableDatabase
            db.execSQL("DROP TABLE face_edits");db.execSQL("DROP TABLE correction_samples");db.execSQL("ALTER TABLE membership DROP COLUMN manual");db.execSQL("ALTER TABLE relations DROP COLUMN batch");db.execSQL("ALTER TABLE people DROP COLUMN contact_lookup");db.execSQL("ALTER TABLE people DROP COLUMN contact_name");db.execSQL("ALTER TABLE people DROP COLUMN name_rank");db.version=3}
        FaceStore(app).use{faces->val store=PeopleStore(faces);assertEquals("Alice",store.label(store.capsules().single().id));assertEquals(1,faces.signatureSummary().ready);store.correct(setOf(key(1)),exclude=true);assertTrue(store.canUndo())}
    }
    @Test fun prunedHistoryDoesNotReuseOldRelationBatch(){FaceStore(app).use{faces->
        val photos=(1..3).map{add(faces,it)};val store=PeopleStore(faces);PeopleGrouping.run(store,{true});store.correct(setOf(key(1)),create=true)
        faces.retain(photos.drop(1));store.correct(setOf(key(2)),create=true);store.undoCorrection()
        faces.writableDatabase.rawQuery("SELECT active FROM relations WHERE batch=1 AND type='cannot'",null).use{c->assertTrue(c.moveToFirst());assertEquals(1,c.getInt(0))}
    }}

}
