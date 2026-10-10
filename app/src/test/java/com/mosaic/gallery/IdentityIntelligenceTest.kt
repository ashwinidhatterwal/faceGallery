package com.mosaic.gallery

import android.net.Uri
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class IdentityIntelligenceTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val face=FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,120f,.95f,"Anchor")
    private fun key(id:Int)=GroupRules.Key("content://intelligence/$id",0)
    private fun vector(angle:Double)=FloatArray(128){when(it){0->kotlin.math.cos(Math.toRadians(angle)).toFloat();1->kotlin.math.sin(Math.toRadians(angle)).toFloat();else->0f}}
    private fun member(id:Int,angle:Double=0.0,manual:Boolean=false)=GroupRules.Prototype(GroupRules.Member(key(id),face,id*120_000L,1,null,"known",1f,"",true,manual),vector(angle))
    private fun cap(id:Long,refs:List<GroupRules.Prototype>)=GroupRules.Capsule(id,mutableSetOf(id),refs.map{it.member.key.uri}.toMutableSet(),refs.map{it.member.key.uri}.toMutableSet(),refs.toMutableList())
    private fun add(f:FaceStore,id:Int,angle:Double):PhotoRecord {val p=PhotoRecord(id.toLong(),Uri.parse(key(id).uri),"$id.jpg",id*120_000L,400,300,"Camera");f.save(p,listOf(face));f.saveSignature(key(id).uri,0,vector(angle));return p}
    private fun seed(p:PeopleStore,id:Int,target:Long?=null)=p.record(p.members().first{it.key==key(id)},GroupRules.Decision(seed=target==null,target=target,status="known"))!!
    @Before fun reset(){app.deleteDatabase("faces.db")}
    @After fun cleanup(){app.deleteDatabase("faces.db")}
    @Test fun outlierCannotBecomeAppearanceReference(){val selected=IdentityEvidence.references((1..8).map{member(it)}+member(9,100.0));assertFalse(selected.any{it.member.key==key(9)});assertEquals(6,selected.size)}
    @Test fun folderConfirmationKeepsCurrentAppearancesButDoesNotConfirmFutureGuesses(){FaceStore(app).use{f->
        (1..5).forEach{add(f,it,if(it<5)0.0 else 100.0)};val p=PeopleStore(f);val main=seed(p,1)
        (2..4).forEach{seed(p,it,main)};val other=seed(p,5);p.merge(main,other)
        assertTrue(p.members().all{it.manual});assertTrue(p.capsules().single().prototypes.any{it.member.key==key(5)})
        add(f,6,0.0);seed(p,6,main);assertFalse(p.members().first{it.key==key(6)}.manual)
        PeopleGrouping.run(p,{true});assertEquals(1,p.capsules().size);assertTrue(p.capsules().single().prototypes.any{it.member.key==key(5)})
    }}
    @Test fun explicitlyConfirmedAppearanceIsRetained(){val selected=IdentityEvidence.references((1..4).map{member(it)}+member(5,100.0,true));assertTrue(selected.any{it.member.key==key(5)})}
    @Test fun confirmedUsableSideViewCanRepresentButWeakFaceCannot(){val side=member(2,60.0,true).let{it.copy(member=it.member.copy(face=face.copy(authority="Support")))};val weak=member(3,80.0,true).let{it.copy(member=it.member.copy(face=face.copy(authority="Shadow")))};val refs=IdentityEvidence.references(listOf(member(1),side,weak));assertTrue(refs.any{it.member.key==key(2)});assertFalse(refs.any{it.member.key==key(3)})}
    @Test fun diverseLargeHistoryHasBoundedAdaptiveBudget(){val selected=IdentityEvidence.references((1..24).map{member(it,(it-12)*3.0)});assertEquals(12,selected.size);assertEquals(12,selected.map{it.member.key.uri}.distinct().size)}
    @Test fun copiedBurstsDoNotCountAsIndependentEvidence(){val a=member(1).member;val b=member(2).member.copy(time=a.time+1000);assertFalse(IdentityEvidence.independent(a,b));assertTrue(IdentityEvidence.independent(a,b.copy(face=face.copy(yaw=20f))))}
    @Test fun repetitionWithinOneBurstCannotAutoMerge(){val a=cap(1,listOf(member(1),member(2).copy(member=member(2).member.copy(time=121000))));val b=cap(2,listOf(member(3,41.0),member(4,41.0).copy(member=member(4).member.copy(time=361000))));assertNull(GroupRules.agreedPair(listOf(a,b),{_,_->true}))}
    @Test fun highButAmbiguousFaceMatchRemainsUnassigned(){val query=member(9).member;val a=GroupRules.Candidate(cap(1,listOf(member(1))),.90f,.90f,1);val b=GroupRules.Candidate(cap(2,listOf(member(2))),.89f,.89f,2);assertNull(GroupRules.decide(query,listOf(a,b)).target)}
    @Test fun qualityChangesTheEvidenceRequired(){val query=member(9).member.copy(face=face.copy(score=.5f,authority="Support"));val candidate=GroupRules.Candidate(cap(1,listOf(member(1))),.81f,-1f,1);assertNull(GroupRules.decide(query,listOf(candidate)).target)}
    @Test fun explicitFeedbackAdjustsBoundedPolicy(){val positive=PeopleCalibration.policy((1..8).map{PeopleCalibration.Sample("p$it","q$it",1,.70f)});assertEquals(.68f,positive.agreement,.0001f);val negative=PeopleCalibration.policy((1..8).map{PeopleCalibration.Sample("p$it","q$it",0,.85f)});assertEquals(.88f,negative.merge,.0001f);assertEquals(.88f,negative.agreement,.0001f)}
    @Test fun fewSamplesCannotRelaxThePolicy(){val p=PeopleCalibration.policy((1..7).map{PeopleCalibration.Sample("p$it","q$it",1,.60f)});assertEquals(.72f,p.agreement,0f)}
    @Test fun doubtfulProfilesWaitButClearNewPeopleAppear(){val a=cap(1,listOf(member(1)));val b=cap(2,listOf(member(2,48.0)));assertEquals(setOf(1L,2L),IdentityEvidence.provisional(listOf(a,b),listOf(a.prototypes[0].member,b.prototypes[0].member),emptySet()));assertTrue(IdentityEvidence.provisional(listOf(a,cap(2,listOf(member(2,90.0)))),emptyList(),emptySet()).isEmpty());assertFalse(IdentityEvidence.provisional(listOf(a,b),emptyList(),setOf(1)).contains(1))}
    @Test fun independentReferencesEstablishIdentityWithoutUserInput(){val a=cap(1,listOf(member(1),member(2,20.0)));assertTrue(IdentityEvidence.established(a,a.prototypes.map{it.member},emptySet()))}
    @Test fun automaticBadAttachmentMovesToAConsistentFolder(){FaceStore(app).use{f->(1..4).forEach{add(f,it,if(it<3)0.0 else 90.0)};val p=PeopleStore(f);val a=seed(p,1);seed(p,2,a);seed(p,3,a);val b=seed(p,4);PeopleGrouping.run(p,{true});assertEquals(2,p.capsules().size);assertEquals(p.components()[b],p.components()[p.members().first{it.key==key(3)}.person]);assertEquals(0,p.policy().positives)}}
    @Test fun sharedUsableAssignmentCanBeRepairedWithoutCuttingItsFolder(){FaceStore(app).use{f->(1..4).forEach{add(f,it,if(it<3)0.0 else 90.0)};f.writableDatabase.execSQL("UPDATE faces SET authority='Support' WHERE uri=?",arrayOf(key(3).uri));val p=PeopleStore(f);val a=seed(p,1);seed(p,2,a);seed(p,3,a);val b=seed(p,4);p.rename(a,"Alice");PeopleGrouping.run(p,{true});assertEquals("Alice",p.label(a));assertEquals(2,p.capsules().first{a in it.leaves}.photos.size);assertEquals(p.components()[b],p.components()[p.members().first{it.key==key(3)}.person])}}
    @Test fun explicitAssignmentsNeverGetAutomaticallyRepaired(){FaceStore(app).use{f->(1..4).forEach{add(f,it,if(it<3)0.0 else 90.0)};val p=PeopleStore(f);val a=seed(p,1);seed(p,2,a);seed(p,3,a);seed(p,4);f.writableDatabase.execSQL("UPDATE membership SET manual=1 WHERE uri=?",arrayOf(key(3).uri));assertEquals(0,p.repairAssignments{true});assertEquals(p.components()[a],p.components()[p.members().first{it.key==key(3)}.person])}}
    @Test fun cancelledRepairDoesNotWrite(){FaceStore(app).use{f->add(f,1,0.0);val p=PeopleStore(f);seed(p,1);assertEquals(0,p.repairAssignments{false});assertEquals(1,p.members().size)}}
    @Test fun failedRefinementPreservesVectorAndIsAttemptedOnce(){FaceStore(app).use{f->add(f,1,0.0);assertEquals(0,SignatureRefinement.run(f,key(1).uri,listOf(face),setOf(key(1)),{true}){null});assertEquals(1f,f.signature(key(1).uri,0)!![0],0f);assertFalse(f.needsRefinement(key(1)));var calls=0;SignatureRefinement.run(f,key(1).uri,listOf(face),setOf(key(1)),{true}){calls++;vector(90.0)};assertEquals(0,calls)}}
    @Test fun cancelledRefinementDoesNotConsumeRetry(){FaceStore(app).use{f->add(f,1,0.0);var running=true;SignatureRefinement.run(f,key(1).uri,listOf(face),setOf(key(1)),{running}){running=false;vector(90.0)};assertTrue(f.needsRefinement(key(1)));assertEquals(1f,f.signature(key(1).uri,0)!![0],0f)}}
    @Test fun refinementsAndGroupsSurviveAdditiveMigration(){FaceStore(app).use{f->add(f,1,0.0);seed(PeopleStore(f),1);f.writableDatabase.execSQL("DROP TABLE signature_retries");f.writableDatabase.version=5};FaceStore(app).use{f->assertEquals(11,f.writableDatabase.version);assertEquals(1,PeopleStore(f).capsules().size);assertTrue(f.needsRefinement(key(1)))}}
    @Test fun unsupportedAutomaticFolderJoinIsRepairedButUserJoinIsPreserved(){FaceStore(app).use{f->(1..4).forEach{add(f,it,if(it<3)0.0 else 90.0)};val p=PeopleStore(f);val a=seed(p,1);seed(p,2,a);val b=seed(p,3);seed(p,4,b);p.link(a,b,"merge","auto","old match");assertEquals(1,p.repairJoins{true});assertEquals(2,p.capsules().size);p.merge(a,b);assertEquals(0,p.repairJoins{true});assertEquals(1,p.capsules().size)}}
    @Test fun comparisonCacheReusesBothPositiveAndNegativeEvidenceAndInvalidatesJoins(){
        val cache=IdentityComparisonCache(listOf(1,2,3),.68f);var calls=0
        fun read(a:Long,b:Long,score:Float)=cache.get(a,b){calls++;IdentityComparisonCache.Evidence(score,score)}
        read(1,2,.75f);read(2,1,.75f);read(1,3,.4f);read(3,1,.4f);assertEquals(2,calls)
        cache.invalidate(1);assertEquals(.9f,read(1,3,.9f).best,0f);assertEquals(3,calls)
    }
    @Test fun successfulRefinementUpdatesQualityWithoutChangingFaceMembership(){FaceStore(app).use{f->add(f,1,0.0);val p=PeopleStore(f);val person=seed(p,1);f.writableDatabase.execSQL("UPDATE faces SET authority='Support' WHERE uri=?",arrayOf(key(1).uri));assertEquals(1,SignatureRefinement.run(f,key(1).uri,listOf(face.copy(score=.99f)),setOf(key(1)),{true}){vector(90.0)});assertEquals(person,p.members().single().person);assertEquals("Anchor",f.observations(key(1).uri).single().authority);assertEquals(.99f,f.observations(key(1).uri).single().score,0f);assertEquals(1f,f.signature(key(1).uri,0)!![1],.001f)}}
    @Test fun weakRefinementCannotReplaceTheOldSignature(){FaceStore(app).use{f->add(f,1,0.0);var calls=0;assertEquals(0,SignatureRefinement.run(f,key(1).uri,listOf(face.copy(authority="Shadow")),setOf(key(1)),{true}){calls++;vector(90.0)});assertEquals(0,calls);assertEquals(1f,f.signature(key(1).uri,0)!![0],0f);assertEquals("Shadow",f.observations(key(1).uri).single().authority)}}
    @Test fun diagnosticsContainNoContactLinksOrFaceVectors(){FaceStore(app).use{f->add(f,1,0.0);val p=PeopleStore(f);seed(p,1);val report=RecognitionMetrics.report(p);assertTrue(report.contains("pending_profiles"));assertFalse(report.contains("lookup"));assertFalse(report.contains("vector"))}}
}
