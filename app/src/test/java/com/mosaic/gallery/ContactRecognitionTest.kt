package com.mosaic.gallery

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.CancellationSignal
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35])
class ContactRecognitionTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val face=FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,100f,.95f,"Anchor")
    private fun vector(cos:Float=1f)=FloatArray(128){if(it==0)cos else if(it==1)kotlin.math.sqrt(1-cos*cos)else 0f}
    private fun contact(id:Int=1)=ContactNames.Contact("content://com.android.contacts/contacts/lookup/key$id/$id","Name $id")
    private fun photo(id:Int=1,stamp:String="photo-1")=ContactRecognition.Photo(contact(id),stamp)
    private fun group(store:FaceStore,id:Int,score:Float=1f):Long {
        val record=PhotoRecord(id.toLong(),Uri.parse("content://contact-test/$id"),"$id.jpg",id*120_000L,400,300,"Camera")
        store.save(record,listOf(face));store.saveSignature(record.uri.toString(),0,vector(score))
        val people=PeopleStore(store)
        return people.record(people.members().first{it.key.uri==record.uri.toString()},GroupRules.Decision(seed=true,status="known"))!!
    }
    private fun reference(id:Int=1,score:Float=1f)=ContactRecognition.Reference(contact(id),vector(score))
    @Before fun before(){app.deleteDatabase("faces.db");app.getSharedPreferences("contact-recognition",0).edit().clear().commit();app.getSharedPreferences("automatic-people",0).edit().clear().commit();Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)}
    @After fun after(){app.deleteDatabase("faces.db")}
    @Test fun neverQueriesContactsWithoutPermission(){val provider=Contacts();ShadowContentResolver.registerProviderInternal(ContactsContractAuthority,provider);assertTrue(ContactRecognition.photos(app,CancellationSignal()).isEmpty());assertEquals(0,provider.calls)}
    @Test fun providerUsesNamesAndPhotosWithoutNumbersOrRemoteDirectory(){val provider=Contacts();ShadowContentResolver.registerProviderInternal(ContactsContractAuthority,provider);Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS);val result=ContactRecognition.photos(app,CancellationSignal());assertEquals(150,result.size);assertEquals("10|content://portraits/1|100",result.first().stamp);assertFalse(provider.projection!!.any{it.contains("number")});assertEquals("content://com.android.contacts/contacts",provider.uri.toString())}
    @Test fun noPhotoIsSkippedWithoutOpeningImage(){val provider=Contacts(false);ShadowContentResolver.registerProviderInternal(ContactsContractAuthority,provider);Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS);assertTrue(ContactRecognition.photos(app,CancellationSignal()).isEmpty())}
    @Test fun nullProviderResultIsNotACompletedEmptyAddressBook(){Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS);ShadowContentResolver.registerProviderInternal(ContactsContractAuthority,Contacts().apply{unavailable=true});assertThrows(IllegalStateException::class.java){ContactRecognition.photos(app,CancellationSignal())}}
    @Test fun unchangedPortraitReusesSignatureAcrossDatabaseReopen(){var reads=0;var encodes=0;fun scan(store:FaceStore)=ContactRecognition.scan(store,listOf(photo()),{true},read={reads++;byteArrayOf(1)},infer={encodes++;vector()})
        FaceStore(app).use{assertTrue(scan(it))};FaceStore(app).use{assertTrue(scan(it));assertNotNull(ContactRecognition.cached(it,contact().lookup)!!.vector)};assertEquals(1,reads);assertEquals(1,encodes)}
    @Test fun unchangedPixelsAfterNameOrPhoneEditNeverRepeatInference(){FaceStore(app).use{store->var encodes=0;val infer:(ByteArray)->FloatArray?={encodes++;vector()};ContactRecognition.scan(store,listOf(photo()),{true},read={byteArrayOf(1)},infer=infer);ContactRecognition.scan(store,listOf(photo(stamp="photo-2").copy(contact=contact().copy(name="New name"))),{true},read={byteArrayOf(1)},infer=infer);assertEquals(1,encodes);assertEquals("photo-2",ContactRecognition.cached(store,contact().lookup)!!.stamp)}}
    @Test fun changedPortraitIsEncodedOnce(){FaceStore(app).use{store->var encodes=0;ContactRecognition.scan(store,listOf(photo()),{true},read={byteArrayOf(1)},infer={encodes++;vector()});ContactRecognition.scan(store,listOf(photo(stamp="changed")),{true},read={byteArrayOf(2)},infer={encodes++;vector(.9f)});assertEquals(2,encodes);assertEquals(.9f,ContactRecognition.cached(store,contact().lookup)!!.vector!![0],.0001f)}}
    @Test fun unrecognisablePortraitIsCachedAsSkipped(){FaceStore(app).use{store->var encodes=0;repeat(4){ContactRecognition.scan(store,listOf(photo()),{true},read={byteArrayOf(1)},infer={encodes++;null})};assertEquals(1,encodes);assertEquals("skipped",ContactRecognition.cached(store,contact().lookup)!!.status)}}
    @Test fun transientErrorsBackOffAndStopAfterThreeAttempts(){FaceStore(app).use{store->var reads=0;for(time in listOf(0L,1L,60_000L,60_001L,360_000L,999_999L)){ContactRecognition.scan(store,listOf(photo()),{true},now=time,read={reads++;error("offline")},infer={vector()})};assertEquals(3,reads);assertEquals(3,ContactRecognition.cached(store,contact().lookup)!!.attempts);assertFalse(ContactRecognition.due(ContactRecognition.cached(store,contact().lookup),photo(),Long.MAX_VALUE));assertTrue(ContactRecognition.due(ContactRecognition.cached(store,contact().lookup),photo(stamp="changed"),0))}}
    @Test fun interruptedInferenceDoesNotCommitOrConsumeRetry(){FaceStore(app).use{store->var running=true;assertFalse(ContactRecognition.scan(store,listOf(photo()),{running},read={byteArrayOf(1)},infer={running=false;vector()}));assertNull(ContactRecognition.cached(store,contact().lookup))}}
    @Test fun boundedSweepResumesWithoutReprocessingCompletedContacts(){FaceStore(app).use{store->val photos=(1..7).map{photo(it)};var encodes=0;fun run()=ContactRecognition.scan(store,photos,{true},read={byteArrayOf(1)},infer={encodes++;vector()})
        assertFalse(run());assertEquals(4,encodes);assertTrue(run());assertEquals(7,encodes);assertTrue(run());assertEquals(7,encodes)}}
    @Test fun clearMatchLinksWholeExistingGroupWithoutAddingContactPhoto(){FaceStore(app).use{store->val id=group(store,1);val before=store.summary();assertEquals(1,ContactRecognition.match(store,listOf(reference()),{true}));assertEquals(contact(),PeopleStore(store).contacts()[id]);assertEquals("Name 1",PeopleStore(store).label(id));assertEquals(before,store.summary());assertEquals(0,ContactRecognition.match(store,listOf(reference()),{true}))}}
    @Test fun weakOrAmbiguousContactMatchDoesNotNamePerson(){FaceStore(app).use{store->group(store,1);assertEquals(0,ContactRecognition.match(store,listOf(reference(score=.85f)),{true}));assertEquals(0,ContactRecognition.match(store,listOf(reference(),reference(2,.96f)),{true}));assertTrue(PeopleStore(store).contacts().isEmpty())}}
    @Test fun singletonNeedsExceptionalMatchButTwoPhotosCanAgree(){FaceStore(app).use{store->val id=group(store,1);val people=PeopleStore(store);assertNull(ContactRecognition.choose(people.capsules().single(),listOf(reference(score=.90f))));val second=group(store,2);people.link(id,second,"attach","auto","Test group");assertEquals(contact(),ContactRecognition.choose(people.capsules().single(),listOf(reference(score=.90f))))}}
    @Test fun manualNameAndContactAreNeverOverridden(){FaceStore(app).use{store->val id=group(store,1);val people=PeopleStore(store);people.rename(id,"My chosen name");assertEquals(0,ContactRecognition.match(store,listOf(reference()),{true}));assertEquals("My chosen name",people.label(id));people.updatePerson(id,ContactNames.Choice("Different contact",contact(2)));assertEquals(0,ContactRecognition.match(store,listOf(reference()),{true}));assertEquals(contact(2),people.contacts()[id])}}
    @Test fun removingAutomaticContactLinkIsDurable(){FaceStore(app).use{store->val id=group(store,1);ContactRecognition.match(store,listOf(reference()),{true});PeopleStore(store).updatePerson(id,ContactNames.Choice("My name",null));store.writableDatabase.execSQL("UPDATE people SET label='' WHERE id=?",arrayOf(id));ContactRecognition.clear(store);assertEquals(0,ContactRecognition.match(store,listOf(reference()),{true}));assertTrue(PeopleStore(store).contacts().isEmpty())}}
    @Test fun samePhotoAndExplicitDifferentPersonVetoAutomaticContactLink(){FaceStore(app).use{store->val id=group(store,1);val second=group(store,2);val people=PeopleStore(store);people.updatePerson(id,ContactNames.Choice(contact().name,contact()));people.reject(id,second);assertFalse(people.autoContact(second,contact()));assertNull(people.contacts()[second])}}
    @Test fun samePhotoCannotReceiveTheSameContactOnTwoGroups(){FaceStore(app).use{store->
        val photo=PhotoRecord(1,Uri.parse("content://shared-photo/1"),"1.jpg",120_000L,400,300,"Camera")
        store.save(photo,listOf(face,face.copy(left=.6f,right=.9f)));repeat(2){store.saveSignature(photo.uri.toString(),it,vector())}
        val people=PeopleStore(store);val ids=people.members().map{people.record(it,GroupRules.Decision(seed=true,status="known"))!!}
        assertTrue(people.autoContact(ids[0],contact()));assertFalse(people.autoContact(ids[1],contact()))
    }}
    @Test fun partialContactSweepDoesNotAttachNameBeforeAllPortraitsAreChecked(){FaceStore(app).use{store->
        group(store,1);val provider=Contacts();ShadowContentResolver.registerProviderInternal(ContactsContractAuthority,provider);Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        val photos=ContactRecognition.photos(app,CancellationSignal())
        ContactRecognition.scan(store,photos,{true},limit=1,read={byteArrayOf(1)},infer={vector()})
        val result=ContactRecognition.run(app,store,{true})
        assertTrue(result.more);assertTrue(PeopleStore(store).contacts().isEmpty())
    }}
    @Test fun cancelledMatchDoesNotSaveAName(){FaceStore(app).use{store->group(store,1);assertEquals(0,ContactRecognition.match(store,listOf(reference()),{false}));assertTrue(PeopleStore(store).contacts().isEmpty())}}
    @Test fun disablingFeatureAndRevokingPermissionLeaveSavedGroupsIntact(){FaceStore(app).use{store->
        val id=group(store,1);ContactRecognition.scan(store,listOf(photo()),{true},read={byteArrayOf(1)},infer={vector()});PeopleStore(store).autoContact(id,contact())
        app.getSharedPreferences("contact-recognition",0).edit().putBoolean("enabled",false).commit()
        assertFalse(ContactRecognition.needsWork(app));assertFalse(ContactRecognition.run(app,store,{true}).more)
        assertNull(ContactRecognition.cached(store,contact().lookup));assertEquals(contact(),PeopleStore(store).contacts()[id])
        assertEquals(1,store.summary().faces)
    }}
    @Test fun removedLinkCannotReturnThroughLaterAutomaticFolderMerge(){FaceStore(app).use{store->
        val id=group(store,1);val people=PeopleStore(store);people.updatePerson(id,ContactNames.Choice(contact().name,null))
        val second=group(store,2);assertTrue(people.autoContact(second,contact()))
        PeopleGrouping.run(people,{true},reuseComparisons=true)
        assertEquals(2,people.capsules().size);assertNull(people.contacts()[id]);assertEquals(contact(),people.contacts()[second])
    }}
    @Test fun manuallyCorrectedFaceIsNotAutomaticallyNamed(){FaceStore(app).use{store->group(store,1);val people=PeopleStore(store);people.correct(setOf(people.members().single().key),create=true);assertEquals(0,ContactRecognition.match(store,listOf(reference()),{true}));assertTrue(people.contacts().isEmpty())}}
    @Test fun newGalleryReferenceReconsidersPreviouslyUnmatchedGroup(){FaceStore(app).use{store->val id=group(store,1);assertEquals(0,ContactRecognition.match(store,listOf(reference(score=.90f)),{true}));val second=group(store,2);PeopleStore(store).link(id,second,"attach","auto","Additional evidence");assertEquals(1,ContactRecognition.match(store,listOf(reference(score=.90f)),{true}))}}
    @Test fun clearResultsRemovesCachedPortraitSignatures(){FaceStore(app).use{store->group(store,1);ContactRecognition.scan(store,listOf(photo()),{true},read={byteArrayOf(1)},infer={vector()});store.clear();assertNull(ContactRecognition.cached(store,contact().lookup));assertEquals(0,store.summary().done)}}
    @Test fun schemaSevenUpgradePreservesPeopleAndAddsCache(){val path=app.getDatabasePath("faces.db");path.parentFile!!.mkdirs();SQLiteDatabase.openOrCreateDatabase(path,null).use{db->db.execSQL("CREATE TABLE people(id INTEGER PRIMARY KEY,label TEXT NOT NULL,contact_lookup TEXT,contact_name TEXT,name_rank INTEGER NOT NULL DEFAULT 0)");db.execSQL("INSERT INTO people(id,label) VALUES(7,'Saved name')");db.version=7};FaceStore(app).use{store->assertEquals(8,store.readableDatabase.version);store.readableDatabase.rawQuery("SELECT label,contact_auto_blocked FROM people WHERE id=7",null).use{assertTrue(it.moveToFirst());assertEquals("Saved name",it.getString(0));assertEquals(0,it.getInt(1))};assertNull(ContactRecognition.cached(store,contact().lookup))}}
    @Test fun contactChangesWakeQuietJobWithoutRegroupingRevision(){Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS,Manifest.permission.READ_MEDIA_IMAGES);val before=AutoPeople.revision(app);AutoPeople.watch(app);val watch=app.getSystemService(android.app.job.JobScheduler::class.java).getPendingJob(AutoPeople.WATCH)!!;assertTrue(watch.triggerContentUris!!.any{it.uri==android.provider.ContactsContract.Contacts.CONTENT_URI});assertEquals(before,AutoPeople.revision(app))}
    @Test fun contactOnlyWakeDoesNotInvalidateTheGalleryCache(){
        val controller=org.robolectric.Robolectric.buildService(AutoPeopleJob::class.java).create()
        val params=org.robolectric.util.ReflectionHelpers.newInstance(android.app.job.JobParameters::class.java)
        org.robolectric.util.ReflectionHelpers.setField(params,"jobId",AutoPeople.WATCH)
        org.robolectric.util.ReflectionHelpers.setField(params,"mTriggeredContentAuthorities",arrayOf(android.provider.ContactsContract.AUTHORITY))
        val before=GalleryData.version;controller.get().onStartJob(params);assertEquals(before,GalleryData.version)
        org.robolectric.util.ReflectionHelpers.setField(params,"mTriggeredContentAuthorities",arrayOf(android.provider.MediaStore.AUTHORITY))
        controller.get().onStartJob(params);assertTrue(GalleryData.version>before);controller.destroy()
    }
    @Test fun providerPortraitUriIsUsedBeforeLookupFallback(){
        val portrait=java.io.File(app.cacheDir,"contact-photo").apply{writeBytes(byteArrayOf(11,22,33))};var queries=0;var opened:Uri?=null
        ShadowContentResolver.registerProviderInternal("portraits",object:ContentProvider(){
            override fun onCreate()=true
            override fun openAssetFile(uri:Uri,mode:String):android.content.res.AssetFileDescriptor {opened=uri;return android.content.res.AssetFileDescriptor(android.os.ParcelFileDescriptor.open(portrait,android.os.ParcelFileDescriptor.MODE_READ_ONLY),0,portrait.length())}
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor?{queries++;return null}
            override fun getType(uri:Uri)="image/jpeg"
            override fun insert(uri:Uri,v:ContentValues?):Uri?=null
            override fun delete(uri:Uri,s:String?,a:Array<out String>?)=0
            override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
        })
        Shadows.shadowOf(app.contentResolver).registerInputStreamSupplier(Uri.parse("content://portraits/full/1")){opened=Uri.parse("content://portraits/full/1");portrait.inputStream()}
        assertArrayEquals(byteArrayOf(11,22,33),ContactRecognition.readPhoto(app,photo().copy(portraitUri="content://portraits/full/1",id=1)))
        assertEquals("content://portraits/full/1",opened.toString());assertEquals(0,queries)
    }
    @Test fun numericContactFallbackReadsThumbnailWhenFullPortraitIsUnavailable(){
        var queried:Uri?=null
        ShadowContentResolver.registerProviderInternal(ContactsContractAuthority,object:ContentProvider(){
            override fun onCreate()=true
            override fun openAssetFile(uri:Uri,mode:String):android.content.res.AssetFileDescriptor?=throw java.io.FileNotFoundException("No full portrait")
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor {queried=uri;return MatrixCursor(arrayOf("data15")).apply{addRow(arrayOf(byteArrayOf(3,4)))}}
            override fun getType(uri:Uri)="image/jpeg"
            override fun insert(uri:Uri,v:ContentValues?):Uri?=null
            override fun delete(uri:Uri,s:String?,a:Array<out String>?)=0
            override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
        })
        assertArrayEquals(byteArrayOf(3,4),ContactRecognition.readPhoto(app,photo().copy(id=1)));assertEquals("content://com.android.contacts/contacts/1/photo",queried.toString())
    }
    @Test fun oldSkippedPortraitIsRecheckedOnceAfterReaderUpgrade(){FaceStore(app).use{store->
        var encodes=0;fun run()=ContactRecognition.scan(store,listOf(photo()),{true},read={byteArrayOf(1)},infer={encodes++;vector()})
        run();store.writableDatabase.execSQL("UPDATE contact_signatures SET model=?,status='skipped',vector=NULL",arrayOf(FaceVectors.MODEL));run();run();assertEquals(2,encodes);assertEquals("done",ContactRecognition.cached(store,contact().lookup)!!.status)
    }}
    @Test fun usableContactThumbnailIsNotRejectedByGalleryResolutionScore(){
        val portrait=face.copy(left=0f,top=0f,right=.5f,bottom=.5f,score=.5f,authority="Support",landmarks=List(10){.3f})
        assertTrue(ContactRecognition.usablePortrait(120,120,portrait));assertFalse(ContactRecognition.usablePortrait(80,80,portrait));assertFalse(ContactRecognition.usablePortrait(120,120,portrait.copy(sharpness=2f)))
    }
    @Test fun clearContactMatchCanSeedAnUnassignedGalleryFace(){FaceStore(app).use{store->
        val record=PhotoRecord(1,Uri.parse("content://contact-seed/1"),"1.jpg",120000,400,300,"Camera");store.save(record,listOf(face));store.saveSignature(record.uri.toString(),0,vector())
        assertEquals(1,ContactRecognition.match(store,listOf(reference()),{true}));assertEquals(contact(),PeopleStore(store).contacts().values.single());assertEquals("known",PeopleStore(store).members().single().status);assertEquals(1,store.summary().faces)
        assertEquals(0,ContactRecognition.match(store,listOf(reference()),{true}))
    }}
    @Test fun contactRetryDeadlineIsNotSuppressedByCompletedSweepCache(){
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        val p=app.getSharedPreferences("contact-recognition",0);p.edit().putString("model",FaceVectors.MODEL+"|portrait-v2").putLong("checked",System.currentTimeMillis()).putLong("faces",AutoPeople.revision(app)).putLong("retry-at",System.currentTimeMillis()+60000).commit();assertFalse(ContactRecognition.needsWork(app))
        FaceStore(app).use{store->assertTrue(ContactRecognition.run(app,store,{true}).retryDelay!! in 1..60000)}
        p.edit().putLong("retry-at",System.currentTimeMillis()-1).commit();assertTrue(ContactRecognition.needsWork(app))
    }
    @Test fun unavailableAddressBookBacksOffWithoutFailingGalleryWork(){
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS);ShadowContentResolver.registerProviderInternal(ContactsContractAuthority,Contacts().apply{unavailable=true})
        FaceStore(app).use{store->val result=ContactRecognition.runSafe(app,store,{true});assertFalse(result.more);assertEquals(15*60_000L,result.retryDelay);assertFalse(ContactRecognition.needsWork(app));ContactRecognition.invalidate(app);assertTrue(ContactRecognition.needsWork(app))}
    }
    companion object{private const val ContactsContractAuthority="com.android.contacts"}
    class Contacts(private val withPhoto:Boolean=true):ContentProvider(){var calls=0;var unavailable=false;var projection:Array<out String>?=null;var uri:Uri?=null;override fun onCreate()=true;override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor?{calls++;this.uri=uri;this.projection=projection;if(unavailable)return null;return MatrixCursor(projection!!).apply{for(i in 1..150)addRow(arrayOf<Any?>(i,"key$i","Name $i",if(withPhoto)10 else null,if(withPhoto)"content://portraits/$i"else null,100))}};override fun getType(uri:Uri)="vnd.android.cursor.dir/contact";override fun insert(uri:Uri,values:ContentValues?):Uri?=null;override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=0;override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?)=0}
}
