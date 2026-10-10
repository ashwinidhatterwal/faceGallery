package com.mosaic.gallery

import android.Manifest
import android.content.*
import android.database.*
import android.net.Uri
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class IdentityLinksTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val face=FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,120f,.95f,"Anchor")
    private fun key(id:Int,ordinal:Int=0)=GroupRules.Key("content://identity/$id",ordinal)
    private fun vector(cos:Float=1f)=FloatArray(128){if(it==0)cos else if(it==1)kotlin.math.sqrt(1-cos*cos)else 0f}
    private fun add(faces:FaceStore,id:Int,cos:Float=1f,count:Int=1):PhotoRecord {
        val p=PhotoRecord(id.toLong(),Uri.parse(key(id).uri),"$id.jpg",id*120_000L,400,300,"Camera")
        faces.save(p,List(count){face});repeat(count){faces.saveSignature(key(id,it).uri,it,vector(cos))};return p
    }
    private fun seed(people:PeopleStore,key:GroupRules.Key)=people.record(people.members().first{it.key==key},GroupRules.Decision(seed=true,status="known"))!!
    private fun contact(id:Int,name:String="Ashwini")=ContactNames.Contact("content://com.android.contacts/contacts/lookup/key$id/$id",name)
    @Before fun reset(){app.deleteDatabase("faces.db");Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)}
    @After fun cleanup(){app.deleteDatabase("faces.db")}
    @Test fun knownGroupCanSuggestAnotherPersonAndConfirmationCombinesEveryPhoto(){FaceStore(app).use{faces->
        (1..4).forEach{add(faces,it,if(it<3)1f else .75f)};val p=PeopleStore(faces)
        p.correct(setOf(key(1),key(2)),create=true);p.correct(setOf(key(3),key(4)),create=true)
        val main=p.components()[p.members().first{it.key==key(1)}.person]!!;p.rename(main,"Ashwini")
        val suggestion=PhotoPeople.read(p,key(3).uri,(1..4).map{key(it).uri}.toSet()).single();assertNull(suggestion.person);assertEquals(main,suggestion.suggestion!!.capsule.id)
        p.confirmIdentity(key(3),main);assertEquals(1,p.capsules().size);assertEquals(4,p.capsules().single().photos.size);assertEquals("Ashwini",p.label(main));assertEquals(4,faces.signatureSummary().ready)
        p.unlink(p.relations().last{it.active && it.type=="merge"}.id);assertEquals(2,p.capsules().size)
    }}
    @Test fun explicitChoiceCombinesWholeGroupEvenWithoutSimilarity(){FaceStore(app).use{f->
        add(f,1);add(f,2,0f);add(f,3,0f);val p=PeopleStore(f);val main=seed(p,key(1));p.correct(setOf(key(2),key(3)),create=true)
        p.confirmIdentity(key(2),main);assertEquals(3,p.capsules().single().photos.size)
    }}
    @Test fun selectedMainNameWinsWithoutDeletingOtherNames(){FaceStore(app).use{f->
        add(f,1);add(f,2,.75f);val p=PeopleStore(f);val source=seed(p,key(1));val main=seed(p,key(2));p.rename(source,"Old folder");p.rename(main,"Main person")
        p.confirmIdentity(key(1),main);assertEquals("Main person",p.label(source));assertEquals(setOf("Old folder","Main person"),p.names().values.toSet())
    }}
    @Test fun samePhotoConflictCannotPartiallyMergeFolders(){FaceStore(app).use{f->
        add(f,1,count=2);val p=PeopleStore(f);val a=seed(p,key(1));seed(p,key(1,1));assertTrue(runCatching{p.confirmIdentity(key(1,1),a)}.isFailure);assertEquals(2,p.capsules().size);assertTrue(p.relations().isEmpty())
    }}
    @Test fun userSeparationSuppressesKnownGroupQuestion(){FaceStore(app).use{f->
        add(f,1);add(f,2,.75f);val p=PeopleStore(f);val a=seed(p,key(1));val b=seed(p,key(2));p.reject(a,b);assertNull(PhotoPeople.read(p,key(2).uri,setOf(key(1).uri,key(2).uri)).single().suggestion)
    }}
    @Test fun reconciliationRevisitsPairsSkippedBeforeLaterMerge(){FaceStore(app).use{f->
        add(f,1);add(f,2,.5f);add(f,3,kotlin.math.cos(Math.toRadians(25.0)).toFloat());val p=PeopleStore(f);(1..3).forEach{seed(p,key(it))};PeopleGrouping.run(p,{true},false);assertEquals(1,p.capsules().size)
    }}
    @Test fun referencePoolIsBoundedAndKeepsAppearanceVariation(){
        val members=(1..100).map{GroupRules.Member(key(it),face.copy(score=1f-it/1000f),it.toLong(),1,null,"known",1f,"",true)}
        assertTrue(GroupRules.referenceCandidates(members).size<=24)
        val pool=members.take(7).mapIndexed{i,m->GroupRules.Prototype(m,vector(if(i==6).6f else 1f))};val selected=GroupRules.diverseReferences(pool)
        assertEquals(6,selected.size);assertTrue(selected.any{it.member.key==key(7)});assertEquals(6,selected.map{it.member.key.uri}.distinct().size)
    }
    @Test fun contactAssociationPersistsAcrossDatabaseReopen(){var id=0L;FaceStore(app).use{f->add(f,1);val p=PeopleStore(f);id=seed(p,key(1));p.updatePerson(id,ContactNames.Choice("Ashwini",contact(1)))}
        FaceStore(app).use{f->assertEquals(contact(1),PeopleStore(f).contacts()[id]);assertEquals("Ashwini",PeopleStore(f).label(id))}
    }
    @Test fun selectingSameContactCombinesGroupsWithoutVisualMatch(){FaceStore(app).use{f->
        add(f,1);add(f,2,0f);val p=PeopleStore(f);val a=seed(p,key(1));val b=seed(p,key(2));p.updatePerson(a,ContactNames.Choice("Ashwini",contact(1)));p.updatePerson(b,ContactNames.Choice("Ashwini",contact(1)))
        assertEquals(1,p.capsules().size);assertEquals(2,p.capsules().single().photos.size);assertEquals(contact(1),p.contacts()[p.capsules().single().id])
    }}
    @Test fun equalNamesWithDifferentContactsNeverMergeAutomatically(){FaceStore(app).use{f->
        add(f,1);add(f,2);val p=PeopleStore(f);val a=seed(p,key(1));val b=seed(p,key(2));p.updatePerson(a,ContactNames.Choice("Ashwini",contact(1)));p.updatePerson(b,ContactNames.Choice("Ashwini",contact(2)));PeopleGrouping.run(p,{true});assertEquals(2,p.capsules().size);assertNull(PhotoPeople.read(p,key(1).uri,setOf(key(1).uri,key(2).uri)).single().suggestion)
    }}
    @Test fun removingLinkDoesNotRemoveNameOrPhotos(){FaceStore(app).use{f->
        add(f,1);val p=PeopleStore(f);val id=seed(p,key(1));p.updatePerson(id,ContactNames.Choice("Ashwini",contact(1)));p.updatePerson(id,ContactNames.Choice("Studio",null));assertTrue(p.contacts().isEmpty());assertEquals("Studio",p.label(id));assertEquals(1,p.capsules().single().photos.size)
    }}
    @Test fun conflictRollsBackContactAndNameChangesTogether(){FaceStore(app).use{f->
        add(f,1,count=2);val p=PeopleStore(f);val a=seed(p,key(1));val b=seed(p,key(1,1));p.updatePerson(a,ContactNames.Choice("Ashwini",contact(1)));p.rename(b,"Separate")
        assertTrue(runCatching{p.updatePerson(b,ContactNames.Choice("Ashwini",contact(1)))}.isFailure);assertEquals("Separate",p.label(b));assertNull(p.contacts()[b]);assertEquals(2,p.capsules().size)
    }}
    @Test fun revokingContactsPermissionRetainsSavedAssociation(){FaceStore(app).use{f->
        add(f,1);val p=PeopleStore(f);val a=seed(p,key(1));p.updatePerson(a,ContactNames.Choice("Ashwini",contact(1)));p.syncContacts(app);assertEquals(contact(1),p.contacts()[a])
    }}
    @Test fun lookupResolvesMovedContactAndUpdatesItsName(){
        val provider=MovedContact();ShadowContentResolver.registerProviderInternal("com.android.contacts",provider);Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        FaceStore(app).use{f->add(f,1);val p=PeopleStore(f);val a=seed(p,key(1));p.updatePerson(a,ContactNames.Choice("Ashwini",contact(1)));p.syncContacts(app);assertEquals("New name",p.label(a));assertEquals("content://com.android.contacts/contacts/lookup/new-key/99",p.contacts()[a]!!.lookup)}
        assertTrue(provider.uri!!.path!!.contains("lookup/key1/1"))
    }
    @Test fun contactRefreshPreservesCustomAlias(){
        ShadowContentResolver.registerProviderInternal("com.android.contacts",MovedContact());Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        FaceStore(app).use{f->add(f,1);val p=PeopleStore(f);val a=seed(p,key(1));p.updatePerson(a,ContactNames.Choice("Studio owner",contact(1)));p.syncContacts(app);assertEquals("Studio owner",p.label(a));assertEquals("New name",p.contacts()[a]!!.name)}
    }
    @Test fun schemaFourMigrationKeepsSignaturesGroupsAndNames(){var id=0L;FaceStore(app).use{f->add(f,1);val p=PeopleStore(f);id=seed(p,key(1));p.rename(id,"Ashwini");val db=f.writableDatabase;listOf("contact_lookup","contact_name","name_rank").forEach{db.execSQL("ALTER TABLE people DROP COLUMN $it")};db.version=4}
        FaceStore(app).use{f->val p=PeopleStore(f);assertEquals(9,f.writableDatabase.version);assertEquals("Ashwini",p.label(id));assertEquals(1,f.signatureSummary().ready);assertEquals(1,p.capsules().size);assertTrue(p.contacts().isEmpty())}
    }
    @Test fun invalidContactLookupCannotBeSaved(){FaceStore(app).use{f->add(f,1);val p=PeopleStore(f);val a=seed(p,key(1));assertTrue(runCatching{p.updatePerson(a,ContactNames.Choice("Ashwini",ContactNames.Contact("https://example.com/","Ashwini")))}.isFailure);assertTrue(p.contacts().isEmpty())}}
    class MovedContact:ContentProvider(){var uri:Uri?=null;override fun onCreate()=true;override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor{this.uri=uri;return MatrixCursor(arrayOf("_id","lookup","display_name")).apply{addRow(arrayOf<Any>(99,"new-key","New name"))}};override fun getType(uri:Uri)="vnd.android.cursor.item/contact";override fun insert(uri:Uri,v:ContentValues?):Uri?=null;override fun delete(uri:Uri,s:String?,a:Array<out String>?)=0;override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0}
}
