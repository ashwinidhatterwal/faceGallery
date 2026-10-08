package com.mosaic.gallery

import android.net.Uri
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35])
class PeopleGroupingTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val face=FaceObservation(.1f,.1f,.3f,.4f,0f,0f,0f,120f,.95f,"Anchor")
    private fun vector(cos:Float=1f)=FloatArray(128){if(it==0)cos else if(it==1)kotlin.math.sqrt(1-cos*cos)else 0f}
    private fun member(id:Int,authority:String="Anchor",time:Long=id*120_000L)=GroupRules.Member(GroupRules.Key("content://people/$id",0),face.copy(authority=authority),time,id.toLong(),null,"known",1f,"",true)
    private fun capsule(id:Long,vararg members:GroupRules.Member)=GroupRules.Capsule(id,mutableSetOf(id),members.map{it.key.uri}.toMutableSet(),members.filter{it.face.authority=="Anchor"}.map{it.key.uri}.toMutableSet(),members.map{GroupRules.Prototype(it,vector())}.toMutableList())
    private fun add(store:FaceStore,id:Int,authority:String="Anchor",count:Int=1):PhotoRecord {
        val photo=PhotoRecord(id.toLong(),Uri.parse("content://people/$id"),"$id.jpg",id*120_000L,400,300,"Camera",1000,"/storage/$id.jpg",100)
        store.save(photo,List(count){face.copy(authority=authority)});repeat(count){store.saveSignature(photo.uri.toString(),it,vector())};return photo
    }
    @Before fun reset(){app.deleteDatabase("faces.db")}
    @After fun cleanup(){app.deleteDatabase("faces.db")}
    @Test fun unchangedUnresolvedFacesDoNotTriggerASecondPass(){FaceStore(app).use{faces->
        add(faces,1,"Support");val store=PeopleStore(faces)
        PeopleGrouping.run(store,{true})
        var matchingPhases=0;var processed=0
        PeopleGrouping.run(store,{true},phase={if(it=="Matching faces…")matchingPhases++}){_,_->processed++}
        assertEquals(1,matchingPhases);assertEquals(1,processed)
        assertEquals("tentative",store.members().single().status)
        // A new clear reference must still resolve that previously uncertain face.
        add(faces,2)
        PeopleGrouping.run(store,{true})
        assertEquals(2,store.members().count{it.status=="known"})
    }}
    @Test fun onlyClearFacesSeedPeople(){
        assertTrue(GroupRules.decide(member(1),emptyList()).seed)
        assertFalse(GroupRules.decide(member(1,"Support"),emptyList()).seed)
        assertEquals("unknown",GroupRules.decide(member(1,"Shadow"),emptyList()).status)
    }
    @Test fun supportJoinsAbovePointEightWithoutBecomingPrototype(){
        val one=capsule(1,member(1));val candidate=GroupRules.rank(vector(),one)!!
        assertNotNull(GroupRules.decide(member(3,"Support"),listOf(candidate)).target)
        val two=capsule(1,member(1),member(2))
        assertNotNull(GroupRules.decide(member(3,"Support"),listOf(GroupRules.rank(vector(),two)!!)).target)
        assertTrue(GroupRules.select(listOf(member(1,"Support"))).isEmpty())
    }
    @Test fun highestMatchAbovePointEightWins(){
        val a=GroupRules.Candidate(capsule(1,member(1)),.9f,.9f,1)
        val b=GroupRules.Candidate(capsule(2,member(2)),.86f,.86f,2)
        val result=GroupRules.decide(member(3),listOf(a,b));assertEquals(1L,result.target);assertFalse(result.seed);assertEquals("known",result.status)
    }
    @Test fun samePhotoFacesCannotJoin(){
        val group=capsule(1,member(1));val candidates=listOf(GroupRules.rank(vector(),group)!!)
        assertTrue(GroupRules.decide(member(1),candidates).seed)
        assertNull(GroupRules.decide(member(1,"Support"),candidates).target)
    }
    @Test fun prototypesAreBoundedDistinctAndPreservePoseAndTime(){
        val input=(1..20).map{member(it).copy(face=face.copy(yaw=it.toFloat(),score=1-it*.01f))}
        val selected=GroupRules.select(input+input[0].copy(key=GroupRules.Key(input[0].key.uri,1)))
        assertEquals(6,selected.size);assertEquals(6,selected.map{it.key.uri}.distinct().size)
        assertTrue(selected.any{it.key.uri==member(20).key.uri});assertTrue(selected.any{it.key.uri==member(1).key.uri})
    }
    @Test fun mergeUsesClearSimilarityAndRejectsPhotoOverlap(){
        val a=capsule(1,member(1),member(2));val b=capsule(3,member(3),member(4))
        assertTrue(GroupRules.canJoin(a,b));assertTrue(GroupRules.canJoin(a,capsule(3,member(3))))
        assertTrue(GroupRules.canJoin(a,capsule(3,member(3,time=0),member(4,time=0))))
        assertTrue(GroupRules.canJoin(a,capsule(3,member(3,time=10),member(4,time=20))))
        assertFalse(GroupRules.canJoin(a,capsule(3,member(1),member(4))))
    }
    @Test fun groupingResumesWithoutDuplicatingKnownMembership(){FaceStore(app).use{faces->
        add(faces,1);add(faces,2);val store=PeopleStore(faces);var running=true
        PeopleGrouping.run(store,{running}){_,_->running=false};assertEquals(1,store.members().count{it.status=="known"})
        PeopleGrouping.run(store,{true});assertEquals(2,store.members().count{it.status=="known"});assertEquals(1,store.capsules().size)
        val relations=store.relations().size;PeopleGrouping.run(store,{true});assertEquals(relations,store.relations().size)
    }}
    @Test fun pausedGroupingDoesNotWriteAssignments(){FaceStore(app).use{faces->
        add(faces,1);PeopleGrouping.run(PeopleStore(faces),{false});assertTrue(PeopleStore(faces).capsules().isEmpty())
    }}
    @Test fun undoJoinSurvivesRegroupAndDatabaseReopen(){
        FaceStore(app).use{faces->add(faces,1);add(faces,2);val store=PeopleStore(faces);PeopleGrouping.run(store,{true});store.unlink(store.relations().single().id);assertEquals(2,store.capsules().size)}
        FaceStore(app).use{faces->val store=PeopleStore(faces);PeopleGrouping.run(store,{true});assertEquals(2,store.capsules().size);assertTrue(store.relations().any{it.active && it.type=="cannot" && it.source=="user"})}
    }
    @Test fun separateClearFaceCreatesPersistentNegative(){FaceStore(app).use{faces->
        add(faces,1);add(faces,2);val store=PeopleStore(faces);PeopleGrouping.run(store,{true});store.separate(GroupRules.Key("content://people/2",0));assertEquals(2,store.capsules().size)
        PeopleGrouping.run(store,{true});assertEquals(2,store.capsules().size)
    }}
    @Test fun samePhotoGraphVetoRepairsInvalidJoin(){FaceStore(app).use{faces->
        add(faces,1,count=2);val store=PeopleStore(faces);val rows=store.pending();val a=store.record(rows[0],GroupRules.Decision(seed=true,status="known"))!!;val b=store.record(rows[1],GroupRules.Decision(seed=true,status="known"))!!
        store.link(a,b,"merge","auto","test");assertEquals(2,store.capsules().size);assertTrue(store.relations().single().active);store.components(repair=true);assertFalse(store.relations().single().active)
    }}
    @Test fun resetKeepsSignaturesAndClearRemovesEverything(){FaceStore(app).use{faces->
        add(faces,1);val store=PeopleStore(faces);PeopleGrouping.run(store,{true});val id=store.capsules().single().id;store.rename(id,"  Alice  ");assertEquals("Alice",store.label(id))
        store.reset();assertEquals(1,faces.signatureSummary().ready);assertTrue(store.capsules().isEmpty());PeopleGrouping.run(store,{true});faces.clear();assertTrue(store.members().isEmpty());assertTrue(store.relations().isEmpty());assertTrue(store.names().isEmpty())
    }}
    @Test fun revokedPhotosCascadeMembership(){FaceStore(app).use{faces->
        add(faces,1);PeopleGrouping.run(PeopleStore(faces),{true});faces.retain(emptyList());assertTrue(PeopleStore(faces).members().isEmpty());assertTrue(PeopleStore(faces).capsules().isEmpty())
    }}
    @Test fun schemaTwoMigratesWithoutLosingSignatures(){
        FaceStore(app).use{faces->add(faces,1);val db=faces.writableDatabase;db.execSQL("DROP TABLE membership");db.execSQL("DROP TABLE relations");db.execSQL("DROP TABLE people");db.version=2}
        FaceStore(app).use{faces->assertEquals(1,faces.signatureSummary().ready);assertEquals(1,PeopleStore(faces).pending().size);PeopleGrouping.run(PeopleStore(faces),{true});assertEquals(1,PeopleStore(faces).capsules().size)}
    }
    @Test fun obsoleteGroupingAndEncoderDoNotExposeKnownAssignments(){FaceStore(app).use{faces->
        add(faces,1);val store=PeopleStore(faces);PeopleGrouping.run(store,{true});faces.writableDatabase.execSQL("UPDATE membership SET model='old'");assertTrue(store.capsules().isEmpty());assertEquals(1,store.pending().size)
        faces.writableDatabase.execSQL("UPDATE embeddings SET model='old'");assertTrue(store.pending().isEmpty())
    }}
    private fun establishedPair(store:PeopleStore,start:Int):Long {
        val rows=store.pending().associateBy{it.key.uri};val first=store.record(rows["content://people/$start"]!!,GroupRules.Decision(seed=true,status="known"))!!
        store.record(rows["content://people/${start+1}"]!!,GroupRules.Decision(target=first,status="known"));return first
    }
    @Test fun independentEstablishedGroupsMergeAndUndoPreservesObservations(){FaceStore(app).use{faces->
        (1..4).forEach{add(faces,it)};val store=PeopleStore(faces);establishedPair(store,1);establishedPair(store,3)
        assertEquals(2,store.capsules().size);PeopleGrouping.run(store,{true});assertEquals(1,store.capsules().size)
        store.unlink(store.relations().first{it.type=="merge"}.id);assertEquals(2,store.capsules().size);assertEquals(4,faces.signatureSummary().ready)
        PeopleGrouping.run(store,{true});assertEquals(2,store.capsules().size)
    }}
    @Test fun distinctSavedNamesStaySeparateUntilUserJoinsTheirGroups(){FaceStore(app).use{faces->
        (1..4).forEach{add(faces,it)};val store=PeopleStore(faces);val a=establishedPair(store,1);val b=establishedPair(store,3)
        store.rename(a,"Alice");store.rename(b,"Bob");PeopleGrouping.run(store,{true});assertEquals(2,store.capsules().size);store.merge(a,b);assertEquals(1,store.capsules().size);assertEquals(setOf("Alice","Bob"),store.names().values.toSet())
    }}
    @Test fun repeatedIndependentCooccurrenceAddsNegativeButSinglePhotoDoesNot(){FaceStore(app).use{faces->
        add(faces,1,count=2);add(faces,2,count=2);val store=PeopleStore(faces)
        val rows=store.pending().associateBy{it.key};fun row(id:Int,ordinal:Int)=rows[GroupRules.Key("content://people/$id",ordinal)]!!
        val a=store.record(row(1,0),GroupRules.Decision(seed=true,status="known"))!!
        val b=store.record(row(1,1),GroupRules.Decision(seed=true,status="known"))!!
        store.recordCooccurrence();assertTrue(store.relations().isEmpty())
        store.record(row(2,0),GroupRules.Decision(target=a,status="known"));store.record(row(2,1),GroupRules.Decision(target=b,status="known"))
        store.recordCooccurrence();assertTrue(store.relations().any{it.active && it.type=="cannot" && it.source=="auto"})
    }}

}
