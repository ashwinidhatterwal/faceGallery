package com.mosaic.gallery

import android.net.Uri
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AutomaticAgreementTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val face=FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,120f,.95f,"Anchor")
    private fun key(id:Int)=GroupRules.Key("content://agreement/$id",0)
    private fun vector(angle:Double)=FloatArray(128){when(it){0->kotlin.math.cos(Math.toRadians(angle)).toFloat();1->kotlin.math.sin(Math.toRadians(angle)).toFloat();else->0f}}
    private fun member(id:Int,person:Long=1)=GroupRules.Member(key(id),face,id*120_000L,person,null,"known",1f,"",true)
    private fun capsule(id:Long,angle:Double,count:Int=2)=GroupRules.Capsule(id,mutableSetOf(id),(1..count).map{key(id.toInt()*10+it).uri}.toMutableSet(),mutableSetOf(),(1..count).map{GroupRules.Prototype(member(id.toInt()*10+it,id),vector(angle))}.toMutableList())
    private fun add(f:FaceStore,id:Int,angle:Double){val p=PhotoRecord(id.toLong(),Uri.parse(key(id).uri),"$id.jpg",id*120_000L,400,300,"Camera");f.save(p,listOf(face));f.saveSignature(key(id).uri,0,vector(angle))}
    private fun group(p:PeopleStore,vararg ids:Int):Long {p.correct(ids.map(::key).toSet(),create=true);return p.members().first{it.key==key(ids.first())}.person!!}
    @Before fun reset(){app.deleteDatabase("faces.db")}
    @After fun cleanup(){app.deleteDatabase("faces.db")}
    @Test fun repeatedIndependentPhotosAllowLowerSimilarityJoin(){val a=capsule(1,0.0);val b=capsule(2,41.0);assertFalse(GroupRules.canJoin(a,b));assertEquals(a to b,GroupRules.agreedPair(listOf(a,b),{_,_->true}))}
    @Test fun oneReferenceOnEitherSideCannotEstablishGroupAgreement(){assertNull(GroupRules.agreedPair(listOf(capsule(1,0.0,1),capsule(2,41.0)),{_,_->true}));assertNull(GroupRules.agreedPair(listOf(capsule(1,0.0),capsule(2,41.0,1)),{_,_->true}))}
    @Test fun duplicatedSourcePhotoDoesNotCountTwice(){val a=capsule(1,0.0,1);a.prototypes+=a.prototypes.first();assertEquals(-1f,GroupRules.agreement(a,capsule(2,41.0)),0f)}
    @Test fun equallyPlausiblePeopleRemainForConfirmation(){assertNull(GroupRules.agreedPair(listOf(capsule(1,0.0),capsule(2,41.0),capsule(3,-41.0)),{_,_->true}))}
    @Test fun plausibleSingletonCompetitorAlsoPreventsJoining(){assertNull(GroupRules.agreedPair(listOf(capsule(1,0.0),capsule(2,41.0),capsule(3,-39.0,1)),{_,_->true}))}
    @Test fun insufficientAgreementStaysSeparate(){assertNull(GroupRules.agreedPair(listOf(capsule(1,0.0),capsule(2,50.0)),{_,_->true}))}
    @Test fun samePhotoAndExplicitVetoOverrideRepeatedAgreement(){val a=capsule(1,0.0);val b=capsule(2,41.0);assertNull(GroupRules.agreedPair(listOf(a,b),{_,_->false}));b.photos+=a.photos.first();assertNull(GroupRules.agreedPair(listOf(a,b),{_,_->true}))}
    @Test fun cancellationDoesNotReturnAnAutomaticJoin(){assertNull(GroupRules.agreedPair(listOf(capsule(1,0.0),capsule(2,41.0)),{_,_->true},{false}))}
    @Test fun pendingFaceNeedsTwoReferencesAndClearRunnerUpMargin(){val row=member(99).copy(person=null,status="tentative");val a=capsule(1,41.0);val candidates=listOfNotNull(GroupRules.rank(vector(0.0),a));assertEquals(1L,GroupRules.decide(row,candidates).target)
        assertNull(GroupRules.decide(row,listOfNotNull(GroupRules.rank(vector(0.0),capsule(1,41.0,1)))).target)
        assertNull(GroupRules.decide(row,candidates+listOfNotNull(GroupRules.rank(vector(0.0),capsule(2,-42.0)))).target)
    }
    @Test fun shadowFaceNeverUsesCorroboration(){val row=member(99).copy(face=face.copy(authority="Shadow"));assertEquals("unknown",GroupRules.decide(row,listOfNotNull(GroupRules.rank(vector(0.0),capsule(1,41.0)))).status)}
    @Test fun automaticWholeFolderJoinPersistsAndCanBeSeparated(){var relation=0L;FaceStore(app).use{f->(1..4).forEach{add(f,it,if(it<=2)0.0 else 41.0)};val p=PeopleStore(f);val a=group(p,1,2);val b=group(p,3,4);p.rename(b,"Main person");PeopleGrouping.run(p,{true});assertEquals(4,p.capsules().single().photos.size);assertEquals("Main person",p.label(a));relation=p.relations().single{it.active && it.source=="auto" && it.type=="merge"}.id;assertTrue(p.relations().first{it.id==relation}.reason.contains("independent photos"))}
        FaceStore(app).use{f->val p=PeopleStore(f);assertEquals(1,p.capsules().size);p.unlink(relation);PeopleGrouping.run(p,{true});assertEquals(2,p.capsules().size);assertEquals(4,f.signatureSummary().ready)}
    }
    @Test fun differentContactsVetoLowerScoreAutomaticMerge(){FaceStore(app).use{f->(1..4).forEach{add(f,it,if(it<=2)0.0 else 41.0)};val p=PeopleStore(f);val a=group(p,1,2);val b=group(p,3,4);listOf(a,b).forEach{id->p.updatePerson(id,ContactNames.Choice("Name",ContactNames.Contact("content://com.android.contacts/contacts/lookup/key$id/$id","Name")))};PeopleGrouping.run(p,{true});assertEquals(2,p.capsules().size)}}
    @Test fun oneStrongPairCannotMasqueradeAsRepeatedAgreement(){val a=capsule(1,0.0);val b=capsule(2,41.0);b.prototypes[1]=b.prototypes[1].copy(vector=vector(100.0));assertTrue(GroupRules.agreement(a,b)<GroupRules.AGREEMENT);assertNull(GroupRules.agreedPair(listOf(a,b),{_,_->true}))}
    @Test fun threeIndependentPairsAutomaticallyResolveMoreDuplicates(){
        val a=capsule(1,0.0,3);val b=capsule(2,46.0,3)
        assertTrue(GroupRules.agreement(a,b)<.72f)
        assertEquals(a to b,GroupRules.agreedPair(listOf(a,b),{_,_->true}))
        assertNull(GroupRules.agreedPair(listOf(capsule(1,0.0),capsule(2,46.0)),{_,_->true}))
    }
    @Test fun burstPhotosCannotUnlockBroaderMerge(){
        val a=capsule(1,0.0,3);val b=capsule(2,46.0,3)
        for(g in listOf(a,b))for(i in g.prototypes.indices)g.prototypes[i]=g.prototypes[i].copy(member=g.prototypes[i].member.copy(time=1))
        assertNull(GroupRules.agreedPair(listOf(a,b),{_,_->true}))
    }
    @Test fun calibratedHardNegativesStillTightenBroaderMerges(){
        assertNull(GroupRules.agreedPair(listOf(capsule(1,0.0,3),capsule(2,46.0,3)),{_,_->true},policy=PeopleCalibration.Policy(agreement=.85f)))
    }
    @Test fun corroboratedCliqueResolvesCloseCompetingDuplicateFolders(){
        val groups=listOf(capsule(1,0.0),capsule(2,20.0),capsule(3,-20.0))
        assertNotNull(GroupRules.agreedPair(groups,{_,_->true}))
    }
    @Test fun distinctSavedNamesRequireExplicitMerge(){FaceStore(app).use{f->
        (1..4).forEach{add(f,it,if(it<=2)0.0 else 41.0)}
        val p=PeopleStore(f);p.rename(group(p,1,2),"Alice");p.rename(group(p,3,4),"Beth")
        PeopleGrouping.run(p,{true});assertEquals(2,p.capsules().size)
    }}
    @Test fun photoCanReverseWholeAutomaticJoinWithoutLosingManualFolders(){FaceStore(app).use{f->
        (1..4).forEach{add(f,it,if(it<=2)0.0 else 41.0)}
        val p=PeopleStore(f);val a=group(p,1,2);val b=group(p,3,4);p.rename(a,"Saved name")
        PeopleGrouping.run(p,{true});assertNotNull(p.automaticJoinFor(key(3)))
        p.undoAutomaticJoin(key(3));PeopleGrouping.run(p,{true})
        assertEquals(listOf(2,2),p.capsules().map{it.photos.size}.sorted())
        assertEquals("Saved name",p.label(a));assertEquals(4,f.signatureSummary().ready)
        assertTrue(p.relations().any{it.active && it.source=="user" && it.type=="attach"})
        assertNull(p.automaticJoinFor(key(3)))
    }}

    @Test fun userConfirmedAliasesStillAcceptAnUnnamedDuplicate(){FaceStore(app).use{f->
        (1..6).forEach{add(f,it,if(it<=4)0.0 else 41.0)}
        val p=PeopleStore(f);val a=group(p,1,2);val b=group(p,3,4);group(p,5,6)
        p.rename(a,"Alice");p.rename(b,"Nickname");p.merge(a,b)
        PeopleGrouping.run(p,{true});assertEquals(1,p.capsules().size);assertEquals(6,p.capsules().single().photos.size)
    }}

}
