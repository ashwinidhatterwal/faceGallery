package com.mosaic.gallery

import android.Manifest
import android.app.Activity
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.CancellationSignal
import android.view.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PeopleProfilesTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val face=FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,100f,.95f,"Anchor")
    private fun vector(cos:Float=1f)=FloatArray(128){if(it==0)cos else if(it==1)kotlin.math.sqrt(1-cos*cos)else 0f}
    private fun key(id:Int)=GroupRules.Key("content://profiles/$id",0)
    private fun add(store:FaceStore,id:Int,ready:Boolean=true,authority:String="Anchor"):PhotoRecord {
        val p=PhotoRecord(id.toLong(),Uri.parse(key(id).uri),"$id.jpg",id*120_000L,400,300,"Camera")
        store.save(p,listOf(face.copy(authority=authority)));if(ready)store.saveSignature(p.uri.toString(),0,vector());return p
    }
    @Before fun reset(){app.deleteDatabase("faces.db");Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS);app.getSharedPreferences("people-names",0).edit().clear().commit()}
    @After fun cleanup(){app.deleteDatabase("faces.db")}
    @Test fun circularFaceOutlineIsUsed(){val crop=FaceCrop(app);GalleryStyle.roundFace(crop);assertTrue(crop.clipToOutline);assertEquals(GradientDrawable.OVAL,(crop.background as GradientDrawable).shape)}
    @Test fun profileFolderUsesWholePhotoAdapter(){val c=Robolectric.buildActivity(PeopleActivity::class.java,Intent(app,PeopleActivity::class.java).putExtra("person",3L)).create();val grid=PeopleActivity::class.java.getDeclaredField("grid").apply{isAccessible=true}.get(c.get()) as androidx.recyclerview.widget.RecyclerView;assertTrue(grid.adapter is PhotoGridAdapter);c.destroy()}
    @Test fun faceCorrectionViewRemainsAvailable(){val c=Robolectric.buildActivity(PeopleActivity::class.java,Intent(app,PeopleActivity::class.java).putExtra("person",3L).putExtra("reviewFaces",true)).create();val grid=PeopleActivity::class.java.getDeclaredField("grid").apply{isAccessible=true}.get(c.get()) as androidx.recyclerview.widget.RecyclerView;assertFalse(grid.adapter is PhotoGridAdapter);c.destroy()}
    @Test fun searchOpensProfilesInsteadOfAllPhotos(){val c=Robolectric.buildActivity(MainActivity::class.java).create();MainActivity::class.java.getDeclaredMethod("switchPage",String::class.java).apply{isAccessible=true}.invoke(c.get(),"Search");val next=Shadows.shadowOf(c.get()).nextStartedActivity;assertEquals(PeopleActivity::class.java.name,next.component!!.className);assertTrue(next.getBooleanExtra("search",false));c.destroy()}
    @Test fun strictPointEightBoundaryAndPoorSecondReferenceDoNotBlockHigherMatch(){
        val row=GroupRules.Member(key(1),face,1,null,null,"unknown",0f,"",true)
        val ref=row.copy(key=key(2),person=2);val group=GroupRules.Capsule(2,mutableSetOf(2),mutableSetOf(key(2).uri),mutableSetOf(key(2).uri),mutableListOf(GroupRules.Prototype(ref,vector())))
        assertNull(GroupRules.decide(row,listOf(GroupRules.Candidate(group,.80f,.6f,2))).target)
        assertEquals(2L,GroupRules.decide(row,listOf(GroupRules.Candidate(group,.801f,.6f,2))).target)
    }
    @Test fun unknownFaceGetsQuestionCandidateAndConfirmationPersists(){FaceStore(app).use{faces->
        add(faces,1);add(faces,2);val people=PeopleStore(faces);val first=people.members().first{it.key==key(1)};val id=people.record(first,GroupRules.Decision(seed=true,status="known"))!!;people.rename(id,"Ashwini")
        val before=PhotoPeople.read(people,key(2).uri,setOf(key(1).uri,key(2).uri)).single();assertNull(before.person);assertEquals(id,before.suggestion!!.capsule.id);assertEquals("Ashwini",before.suggestedName)
        people.correct(setOf(key(2)),id);val after=PhotoPeople.read(people,key(2).uri,setOf(key(1).uri,key(2).uri)).single();assertEquals(id,after.person);assertEquals("Ashwini",after.name);assertNull(after.suggestion)
        people.undoCorrection();assertNull(PhotoPeople.read(people,key(2).uri,setOf(key(1).uri,key(2).uri)).single().person)
    }}
    @Test fun namingUnknownWeakFaceCreatesNamedPersonWithoutAPrototype(){FaceStore(app).use{faces->add(faces,1,false,"Shadow");val people=PeopleStore(faces);people.nameFace(key(1),"अंजली");val result=PhotoPeople.read(people,key(1).uri,setOf(key(1).uri)).single();assertEquals("अंजली",result.name);assertTrue(people.capsules().single().prototypes.isEmpty());assertTrue(people.members().single().manual)}}
    @Test fun editingRecognizedFaceNameUpdatesItsFolderWithoutSplitting(){FaceStore(app).use{faces->add(faces,1);add(faces,2);val people=PeopleStore(faces);PeopleGrouping.run(people,{true});people.nameFace(key(2),"Ashwini");assertEquals(1,people.capsules().size);assertEquals("Ashwini",PhotoPeople.read(people,key(1).uri,setOf(key(1).uri,key(2).uri)).single().name)}}
    @Test fun inaccessibleReferenceDoesNotSuggestPerson(){FaceStore(app).use{faces->add(faces,1);add(faces,2);val people=PeopleStore(faces);people.record(people.members().first{it.key==key(1)},GroupRules.Decision(seed=true,status="known"));assertNull(PhotoPeople.read(people,key(2).uri,setOf(key(2).uri)).single().suggestion)}}
    @Test fun contactsAreNotQueriedWithoutPermission(){val provider=Contacts();ShadowContentResolver.registerProviderInternal("com.android.contacts",provider);assertTrue(ContactNames.read(app,"Ash").isEmpty());assertEquals(0,provider.calls)}
    @Test fun contactNamesUseLocalDirectoryAndNoNumbers(){val provider=Contacts();ShadowContentResolver.registerProviderInternal("com.android.contacts",provider);Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS);assertEquals(listOf("Ashwini","अंजली"),ContactNames.read(app,"Ash").map{it.name});assertEquals("0",provider.uri!!.getQueryParameter("directory"));assertArrayEquals(arrayOf("_id","lookup","display_name"),provider.projection);Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS);assertTrue(ContactNames.read(app,"Ash").isEmpty());assertEquals(1,provider.calls)}
    @Test fun contactPermissionRequestLeavesManualNamingAvailable(){val c=Robolectric.buildActivity(Activity::class.java).setup();val editor=PeopleNames(c.get());editor.show(""){};assertFalse(editor.requestingPermission);(PeopleNames::class.java.getDeclaredField("contacts").apply{isAccessible=true}.get(editor) as android.widget.Button).performClick();assertTrue(editor.requestingPermission);assertArrayEquals(arrayOf(Manifest.permission.READ_CONTACTS),Shadows.shadowOf(c.get()).lastRequestedPermission.requestedPermissions);editor.permissionResult(PeopleNames.PERMISSION);assertFalse(editor.requestingPermission);editor.close();c.destroy()}
    class Contacts:ContentProvider(){var calls=0;var uri:Uri?=null;var projection:Array<out String>?=null;override fun onCreate()=true;override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {calls++;this.uri=uri;this.projection=projection;return MatrixCursor(arrayOf("_id","lookup","display_name")).apply{addRow(arrayOf<Any>(1,"a","Ashwini"));addRow(arrayOf<Any>(1,"a","Ashwini"));addRow(arrayOf<Any>(2,"b","अंजली"))}};override fun getType(uri:Uri)="vnd.android.cursor.dir/contact";override fun insert(uri:Uri,values:ContentValues?):Uri?=null;override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=0;override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?)=0}
}
