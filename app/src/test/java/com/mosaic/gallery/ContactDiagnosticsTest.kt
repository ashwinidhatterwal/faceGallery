package com.mosaic.gallery

import android.Manifest
import android.net.Uri
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ContactDiagnosticsTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val face=FaceObservation(.1f,.1f,.8f,.8f,0f,0f,0f,100f,.95f,"Anchor",FaceAlignment.template.map{it/112f})
    private val contact=ContactNames.Contact("content://com.android.contacts/contacts/lookup/private/123","Private name")
    private val photo=ContactRecognition.Photo(contact,"same")
    private val vector=FloatArray(128){if(it==0)1f else 0f}
    @Before fun before(){app.deleteDatabase("faces.db");RecognitionConsent.accept(app);Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)}
    @After fun after(){app.deleteDatabase("faces.db")}
    @Test fun qualityReasonsExplainEachExistingGate(){
        assertTrue(ContactRecognition.portraitFailures(200,200,face).isEmpty())
        val failed=ContactRecognition.portraitFailures(50,50,face.copy(sharpness=2f,landmarks=emptyList(),yaw=50f,pitch=40f,roll=50f))
        assertEquals(setOf("face_too_small","blurred_face","missing_five_landmarks","yaw_too_large","pitch_too_large","roll_too_large"),failed.toSet())
        assertFalse(ContactRecognition.usablePortrait(50,50,face));assertTrue(ContactRecognition.usablePortrait(200,200,face))
    }
    @Test fun rejectionDetailsSurviveAndUnchangedPortraitDoesNotRepeat(){FaceStore(app).use{f->
        val detail="{\"failed_checks\":[\"missing_five_landmarks\"],\"landmark_values\":6}"
        ContactRecognition.scan(f,listOf(photo),{true},read={byteArrayOf(1)},infer={throw ContactRecognition.PortraitRejected("portrait_quality_or_landmarks",detail)})
        assertEquals(detail,ContactRecognition.cached(f,contact.lookup)!!.detail)
        repeat(3){assertTrue(ContactRecognition.scan(f,listOf(photo),{true},read={error("Must reuse")},infer={error("Must reuse")}))}
        val entry=ContactDiagnostics.entries(app,f).getJSONObject(0)
        assertTrue(ContactDiagnostics.explanation(entry).contains("missing five landmarks"))
        assertFalse(entry.toString().contains(contact.name));assertFalse(entry.toString().contains(contact.lookup));assertFalse(entry.toString().contains("vector"))
        assertEquals(contact.name,ContactDiagnostics.entries(app,f,true).getJSONObject(0).getString("name"))
    }}
    @Test fun acceptedPortraitReportsInsufficientSimilarityWithoutChangingNames(){FaceStore(app).use{f->
        val uri=Uri.parse("content://diagnostics/1");f.save(PhotoRecord(1,uri,"photo.jpg",1,400,300,"Camera"),listOf(face));f.saveSignature(uri.toString(),0,FloatArray(128){if(it==1)1f else 0f})
        val store=PeopleStore(f);store.record(store.members().single(),GroupRules.Decision(seed=true,status="known"))
        ContactRecognition.scan(f,listOf(photo),{true},read={byteArrayOf(1)},infer={vector})
        val entry=ContactDiagnostics.entries(app,f).getJSONObject(0)
        assertEquals("below_suggestion_floor",entry.getString("comparison_result"));assertEquals(0.0,entry.getDouble("best_cached_similarity"),.001)
        assertTrue(store.names().isEmpty());assertTrue(store.contacts().isEmpty())
    }}
    @Test fun groupEvidenceSuggestsContactWhenSelectedFaceHasNoSignature(){FaceStore(app).use{f->
        val keys=(1..2).map{GroupRules.Key("content://diagnostics/$it",0)}
        keys.forEachIndexed{i,k->f.save(PhotoRecord(i.toLong()+1,Uri.parse(k.uri),"photo.jpg",i.toLong(),400,300,"Camera"),listOf(face));f.saveSignature(k.uri,0,vector)}
        val store=PeopleStore(f);store.correct(keys.toSet(),create=true)
        f.writableDatabase.delete("embeddings","uri=?",arrayOf(keys.first().uri))
        ContactRecognition.scan(f,listOf(photo),{true},read={byteArrayOf(1)},infer={vector})
        assertFalse(store.members().first{it.key==keys.first()}.ready)
        assertEquals(contact,IdentitySuggestions.read(store,keys.first(),keys.map{it.uri}.toSet())!!.suggestions.single().contact)
        assertTrue(IdentitySuggestions.read(store,keys.first(),setOf(keys.first().uri))!!.suggestions.isEmpty())
    }}
    @Test fun versionNineMigrationPreservesCachedResults(){
        FaceStore(app).use{f->ContactRecognition.scan(f,listOf(photo),{true},read={byteArrayOf(1)},infer={vector});f.writableDatabase.execSQL("ALTER TABLE contact_signatures RENAME TO saved_contact_signatures");f.writableDatabase.execSQL("CREATE TABLE contact_signatures AS SELECT lookup,name,stamp,digest,model,status,vector,attempts,next_time,reason FROM saved_contact_signatures");f.writableDatabase.execSQL("DROP TABLE saved_contact_signatures");f.writableDatabase.version=9}
        FaceStore(app).use{f->val cached=ContactRecognition.cached(f,contact.lookup)!!;assertEquals("done",cached.status);assertEquals("",cached.detail);assertFalse(ContactRecognition.due(cached,photo,1));assertEquals(10,f.readableDatabase.version)}
    }
    @Test fun providerPhotoAvailabilityIsReportedWithoutExportingNames(){
        val provider=object:android.content.ContentProvider(){
            override fun onCreate()=true
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?)=android.database.MatrixCursor(p!!).apply{addRow(arrayOf<Any?>(123L,"private",contact.name,null,null,1L));addRow(arrayOf<Any?>(124L,"new","New portrait",9L,"content://photo/9",1L))}
            override fun getType(uri:Uri):String?=null
            override fun insert(uri:Uri,v:android.content.ContentValues?):Uri?=error("No writes")
            override fun update(uri:Uri,v:android.content.ContentValues?,s:String?,a:Array<out String>?)=error("No writes")
            override fun delete(uri:Uri,s:String?,a:Array<out String>?)=error("No writes")
        }
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("com.android.contacts",provider)
        FaceStore(app).use { f->
            val entries=ContactDiagnostics.entries(app,f)
            assertEquals(2,entries.length());assertEquals("no_provider_photo",entries.getJSONObject(0).getString("status"));assertEquals("not_processed",entries.getJSONObject(1).getString("status"))
            assertFalse(entries.toString().contains(contact.name));assertFalse(entries.toString().contains("New portrait"));assertFalse(entries.toString().contains("content://"))
            assertEquals(contact.name,ContactDiagnostics.entries(app,f,true).getJSONObject(0).getString("name"))
            assertEquals(0,ContactRecognition.photos(app,android.os.CancellationSignal(),manual=true).count{!it.hasPortrait})
        }
    }
}
