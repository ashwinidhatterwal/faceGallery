package com.mosaic.gallery

import android.Manifest
import android.net.Uri
import android.os.CancellationSignal
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ContactSuggestionsTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val face=FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,100f,.95f,"Anchor")
    private fun key(id:Int)=GroupRules.Key("content://suggestions/$id",0)
    private fun vector(cos:Float=1f)=FloatArray(128){if(it==0)cos else if(it==1)kotlin.math.sqrt(1-cos*cos)else 0f}
    private fun contact(id:Int=1)=ContactNames.Contact("content://com.android.contacts/contacts/lookup/suggest$id/$id","Contact $id")
    private fun add(store:FaceStore,id:Int,cos:Float=1f){store.save(PhotoRecord(id.toLong(),Uri.parse(key(id).uri),"$id.jpg",id*120_000L,400,300,"Camera"),listOf(face));store.saveSignature(key(id).uri,0,vector(cos))}
    private fun seed(p:PeopleStore,id:Int)=p.record(p.members().first{it.key==key(id)},GroupRules.Decision(seed=true,status="known"))!!
    private fun portrait(f:FaceStore,id:Int=1,cos:Float=.8f){ContactRecognition.scan(f,listOf(ContactRecognition.Photo(contact(id),"portrait")),{true},read={byteArrayOf(id.toByte())},infer={vector(cos)})}
    @Before fun before(){app.deleteDatabase("faces.db");RecognitionConsent.accept(app);Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS);FaceJobs.publish(FaceJobs.State());app.getSharedPreferences("contact-recognition",0).edit().clear().commit();app.getSharedPreferences("automatic-people",0).edit().clear().commit()}
    @After fun after(){app.deleteDatabase("faces.db")}
    @Test fun uncertainContactAppearsWithoutNamingOrAddingPhotos(){FaceStore(app).use{f->add(f,1);portrait(f);val p=PeopleStore(f);val before=f.summary();assertEquals(0,ContactRecognition.match(f,listOf(ContactRecognition.Reference(contact(),vector(.8f))),{true}));val data=IdentitySuggestions.read(p,key(1),setOf(key(1).uri))!!;assertEquals(contact(),data.suggestions.single().contact);assertEquals("Contact 1",PhotoPeople.read(p,key(1).uri,setOf(key(1).uri)).single().suggestedName);assertTrue(p.names().isEmpty());assertEquals(before,f.summary())}}
    @Test fun manuallyNamedAndContactSuggestionsShareRanking(){FaceStore(app).use{f->add(f,1);add(f,2,.75f);val p=PeopleStore(f);val named=seed(p,2);p.rename(named,"My friend");portrait(f,cos=.8f);val data=IdentitySuggestions.read(p,key(1),setOf(key(1).uri,key(2).uri))!!;assertEquals(listOf("Contact 1","My friend"),data.suggestions.map{it.name});assertEquals(named,data.suggestions.last().id)}}
    @Test fun linkedContactIsOneGroupChoiceAndKeepsCustomName(){FaceStore(app).use{f->add(f,1);add(f,2,.75f);val p=PeopleStore(f);val named=seed(p,2);p.updatePerson(named,ContactNames.Choice("Papa",contact()));portrait(f,cos=.8f);val data=IdentitySuggestions.read(p,key(1),setOf(key(1).uri,key(2).uri))!!;assertEquals(1,data.suggestions.size);assertEquals(named,data.suggestions.single().id);assertEquals("Papa",data.suggestions.single().name);assertNull(data.suggestions.single().contact)}}
    @Test fun explicitDifferentPersonCannotReappearAsContact(){FaceStore(app).use{f->add(f,1);add(f,2);val p=PeopleStore(f);val a=seed(p,1);val b=seed(p,2);p.updatePerson(b,ContactNames.Choice("Papa",contact()));p.reject(a,b);portrait(f);assertTrue(IdentitySuggestions.read(p,key(1),setOf(key(1).uri,key(2).uri))!!.suggestions.isEmpty())}}
    @Test fun revokedPermissionAndWeakPortraitDoNotSuggestContacts(){FaceStore(app).use{f->add(f,1);portrait(f,cos=.71f);val p=PeopleStore(f);assertTrue(IdentitySuggestions.read(p,key(1),setOf(key(1).uri))!!.suggestions.isEmpty());portrait(f,2,.8f);Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS);assertTrue(IdentitySuggestions.read(p,key(1),setOf(key(1).uri))!!.suggestions.isEmpty())}}
    @Test fun existingNamedGroupIsNeverPromptedOrRenamed(){FaceStore(app).use{f->add(f,1);val p=PeopleStore(f);val named=seed(p,1);p.rename(named,"My name");portrait(f);val data=IdentitySuggestions.read(p,key(1),setOf(key(1).uri))!!;assertEquals(named,data.person);assertTrue(data.suggestions.isEmpty());assertEquals("My name",p.label(named))}}
    @Test fun excludedFacesHaveNoSuggestions(){FaceStore(app).use{f->add(f,1);portrait(f);val p=PeopleStore(f);p.correct(setOf(key(1)),exclude=true);assertTrue(IdentitySuggestions.read(p,key(1),setOf(key(1).uri))!!.suggestions.isEmpty())}}
    @Test fun acceptingContactSuggestionNamesWholeTemporaryGroup(){FaceStore(app).use{f->add(f,1);add(f,2);val p=PeopleStore(f);p.correct(setOf(key(1),key(2)),create=true);portrait(f);val choice=IdentitySuggestions.read(p,key(1),setOf(key(1).uri,key(2).uri))!!.suggestions.single();p.nameFace(key(1),ContactNames.Choice(choice.name,choice.contact));assertEquals(1,p.capsules().size);assertEquals(contact(),p.contacts().values.single());assertEquals("Contact 1",p.labels(p.components()).values.single())}}
    @Test fun suggestionReadReusesPortraitAndDoesNotMutateCaches(){FaceStore(app).use{f->add(f,1);var inferred=0;val photos=listOf(ContactRecognition.Photo(contact(),"portrait"));ContactRecognition.scan(f,photos,{true},read={byteArrayOf(1)},infer={inferred++;vector(.8f)});val p=PeopleStore(f);repeat(4){IdentitySuggestions.read(p,key(1),setOf(key(1).uri));PhotoPeople.read(p,key(1).uri,setOf(key(1).uri));ContactRecognition.scan(f,photos,{true},read={error("must reuse")},infer={error("must reuse")})};assertEquals(1,inferred)}}
    @Test fun manualEmptySweepWorksWhilePausedWithoutChangingAutomaticPreferences(){
        AutoPeople.pause(app);app.getSharedPreferences("contact-recognition",0).edit().putBoolean("enabled",false).commit()
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("com.android.contacts",EmptyContacts())
        val auto=app.getSharedPreferences("automatic-people",0).all.toMap()
        FaceStore(app).use{f->assertFalse(ContactRecognition.run(app,f,{true},CancellationSignal(),manual=true).more)}
        assertEquals(auto,app.getSharedPreferences("automatic-people",0).all);assertFalse(ContactRecognition.enabled(app));assertFalse(AutoPeople.enabled(app))
    }
    @Test fun clearingContactCacheInvalidatesSuggestionsOnlyWhenThereWasWork(){FaceStore(app).use{f->add(f,1);portrait(f);val before=PeopleData.version;ContactRecognition.clear(f);assertTrue(PeopleData.version>before);val after=PeopleData.version;ContactRecognition.clear(f);assertEquals(after,PeopleData.version);assertTrue(IdentitySuggestions.read(PeopleStore(f),key(1),setOf(key(1).uri))!!.suggestions.isEmpty())}}
    @Test fun contactSuggestionPickerAcceptsOnlyAfterTap(){
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup();val activity=controller.get()
        FaceStore(app).use{f->add(f,1);portrait(f)}
        val data=FaceStore(app).use{IdentitySuggestions.read(PeopleStore(it),key(1),setOf(key(1).uri))!!}
        var changes=0;val names=PeopleNames(activity)
        val chooser=IdentityChooser(activity,names,{action,_->FaceStore(app).use{action(PeopleStore(it))};changes++},{fail("Must not open a temporary group")})
        IdentityChooser::class.java.getDeclaredMethod("render",GroupRules.Key::class.java,IdentitySuggestions.Result::class.java,android.graphics.Bitmap::class.java,List::class.java,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(chooser,key(1),data,null,listOf(null),false)
        assertEquals(0,changes)
        IdentityChooser::class.java.getDeclaredMethod("select",GroupRules.Key::class.java,IdentitySuggestions.Choice::class.java,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(chooser,key(1),data.suggestions.single(),false)
        assertEquals(1,changes);FaceStore(app).use{assertEquals(contact(),PeopleStore(it).contacts().values.single())}
        chooser.close();names.close();controller.destroy()
    }
    @Test fun disabledAutomaticPassPreservesManuallySyncedPortraits(){FaceStore(app).use{f->add(f,1);portrait(f);app.getSharedPreferences("contact-recognition",0).edit().putBoolean("enabled",false).commit();assertFalse(ContactRecognition.run(app,f,{true}).more);assertNotNull(ContactRecognition.cached(f,contact().lookup));assertEquals(contact(),IdentitySuggestions.read(PeopleStore(f),key(1),setOf(key(1).uri))!!.suggestions.single().contact);assertFalse(ContactRecognition.enabled(app))}}
    @Test fun batchProfileNamesMatchPickerAndDoNotSaveNames(){FaceStore(app).use{f->add(f,1);add(f,2,.75f);val p=PeopleStore(f);val source=seed(p,1);val named=seed(p,2);p.rename(named,"Alice");portrait(f);val visible=setOf(key(1).uri,key(2).uri);val hints=IdentitySuggestions.profileNames(p,mapOf(source to key(1),named to key(2)),visible);assertEquals(mapOf(source to "Contact 1"),hints);assertEquals(IdentitySuggestions.read(p,key(1),visible)!!.suggestions.first().name,hints[source]);assertEquals(setOf(named),p.names().keys);assertTrue(p.contacts().isEmpty())}}
    @Test fun cancelledProfileHintsDoNotPublishPartialResults(){FaceStore(app).use{f->add(f,1);portrait(f);val p=PeopleStore(f);val source=seed(p,1);assertThrows(java.util.concurrent.CancellationException::class.java){IdentitySuggestions.profileNames(p,mapOf(source to key(1)),setOf(key(1).uri)){false}};assertTrue(p.names().isEmpty())}}
    @Test fun peopleGridShowsQuestionNameWhileNamedAndUnmatchedProfilesKeepTheirLabels(){
        var unmatched=0L
        val photos=(1..3).map{id->PhotoRecord(id.toLong(),Uri.parse(key(id).uri),"$id.jpg",id*120_000L,400,300,"Camera")}
        FaceStore(app).use{f->add(f,1);add(f,2,.75f);add(f,3,-1f);val p=PeopleStore(f);p.correct(setOf(key(1)),create=true);val named=seed(p,2);p.rename(named,"Alice");p.correct(setOf(key(3)),create=true);unmatched=p.members().first{it.key==key(3)}.person!!;portrait(f)}
        val read=PeopleActivity.Companion.javaClass.declaredMethods.first{it.name=="readData"}.apply{isAccessible=true}
        val alive:()->Boolean={true};val data=read.invoke(PeopleActivity.Companion,app,photos,CancellationSignal(),false,alive)
        val c=Robolectric.buildActivity(PeopleActivity::class.java).create();val activity=c.get()
        PeopleActivity::class.java.getDeclaredMethod("renderData",data.javaClass).apply{isAccessible=true}.invoke(activity,data)
        val cards=PeopleActivity::class.java.getDeclaredField("profiles").apply{isAccessible=true}.get(activity) as List<*>
        val titles=cards.map{card->card!!.javaClass.getDeclaredField("title").apply{isAccessible=true}.get(card) as String}.toSet()
        assertEquals(setOf("Contact 1?","Alice","Person $unmatched"),titles)
        FaceStore(app).use{f->assertEquals(listOf("Alice"),PeopleStore(f).names().values.toList());assertTrue(PeopleStore(f).contacts().isEmpty())}
        c.destroy()
    }
    private class EmptyContacts:android.content.ContentProvider(){override fun onCreate()=true;override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?)=android.database.MatrixCursor(projection?:emptyArray());override fun getType(uri:Uri):String?=null;override fun insert(uri:Uri,values:android.content.ContentValues?):Uri?=null;override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=0;override fun update(uri:Uri,values:android.content.ContentValues?,selection:String?,args:Array<out String>?)=0}
}
