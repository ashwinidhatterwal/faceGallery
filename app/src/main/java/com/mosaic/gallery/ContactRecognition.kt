package com.mosaic.gallery

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.CancellationSignal
import android.provider.ContactsContract
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Contact portraits are references, not gallery photos. Never insert them into the face graph. */
object ContactRecognition {
    private const val POLICY="contacts-v2"
    private const val PORTRAIT_MODEL=FaceVectors.MODEL+"|portrait-v3"
    private const val PREVIOUS_PORTRAIT_MODEL=FaceVectors.MODEL+"|portrait-v2"
    private const val CHECK_INTERVAL=15*60_000L
    data class Photo(val contact:ContactNames.Contact,val stamp:String,val portraitUri:String?=null,val id:Long=0)
    data class Reference(val contact:ContactNames.Contact,val vector:FloatArray)
    data class Cached(val stamp:String,val digest:String,val vector:FloatArray?,val status:String,val attempts:Int,val next:Long,val reason:String="")
    data class Result(val more:Boolean,val retryDelay:Long?)
    fun create(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE IF NOT EXISTS contact_signatures(lookup TEXT PRIMARY KEY,name TEXT NOT NULL,stamp TEXT NOT NULL,digest TEXT NOT NULL,model TEXT NOT NULL,status TEXT NOT NULL,vector BLOB,attempts INTEGER NOT NULL,next_time INTEGER NOT NULL,reason TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE IF NOT EXISTS contact_matches(person INTEGER PRIMARY KEY REFERENCES people(id) ON DELETE CASCADE,token TEXT NOT NULL)")
        createMatchCache(db)
    }
    fun createMatchCache(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE IF NOT EXISTS contact_face_matches(uri TEXT NOT NULL,ordinal INTEGER NOT NULL,token TEXT NOT NULL,PRIMARY KEY(uri,ordinal),FOREIGN KEY(uri,ordinal) REFERENCES faces(uri,ordinal) ON DELETE CASCADE)")
    }
    internal class PortraitRejected(val code:String):Exception(code)
    private fun prefs(c:Context)=c.getSharedPreferences("contact-recognition",0)
    fun enabled(c:Context)=prefs(c).getBoolean("enabled",true)
    fun allowed(c:Context)=c.checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED
    fun permitted(c:Context)=RecognitionConsent.allowed(c) && allowed(c)
    fun available(c:Context)=permitted(c) && enabled(c)
    /** Read saved signatures only; opening a picker never queries or encodes portraits. */
    fun references(c:Context,db:SQLiteDatabase):List<Reference> {
        if(!permitted(c))return emptyList()
        return buildList{db.rawQuery("SELECT lookup,name,vector FROM contact_signatures WHERE status='done' AND vector IS NOT NULL AND model IN (?,?)",arrayOf(PORTRAIT_MODEL,PREVIOUS_PORTRAIT_MODEL)).use{cursor->
            while(cursor.moveToNext()){val contact=ContactNames.Contact(cursor.getString(0),cursor.getString(1));if(ContactNames.valid(contact.lookup))add(Reference(contact,FaceVectors.unpack(cursor.getBlob(2))))}
        }}
    }
    fun setEnabled(c:Context,value:Boolean){
        prefs(c).edit().putBoolean("enabled",value).remove("checked").remove("provider-retry-at").apply()
        if(value)AutoPeople.ensure(c,1_000)
        else {val app=c.applicationContext;Thread({FaceWork.write{if(!enabled(app))FaceStore(app).use(::clear)}},"contact-cache-clear").start()}
    }
    fun invalidate(c:Context){prefs(c).edit().remove("checked").remove("provider-retry-at").apply()}
    fun needsWork(c:Context):Boolean = available(c) && System.currentTimeMillis()>=prefs(c).getLong("provider-retry-at",0) && (prefs(c).getLong("provider-retry-at",0)>0 || prefs(c).getString("model","")!=PORTRAIT_MODEL || System.currentTimeMillis()-prefs(c).getLong("checked",0)>=CHECK_INTERVAL || prefs(c).getLong("faces",-1)!=AutoPeople.revision(c) || prefs(c).getLong("retry-at",0).let{it>0 && System.currentTimeMillis()>=it})
    fun clear(store:FaceStore){val removed=store.writableDatabase.delete("contact_signatures",null,null);store.writableDatabase.delete("contact_matches",null,null);store.writableDatabase.delete("contact_face_matches",null,null);if(removed>0)PeopleData.changed()}
    /** Aggregate local diagnostics only: no portrait pixels, contact identities or vectors. */
    fun diagnostics(c:Context,store:FaceStore):org.json.JSONObject {
        val counts=org.json.JSONObject();val reasons=org.json.JSONObject()
        store.readableDatabase.rawQuery("SELECT status,reason,COUNT(*) FROM contact_signatures GROUP BY status,reason",null).use{cursor->
            while(cursor.moveToNext()){
                val status=cursor.getString(0);val reason=cursor.getString(1);val count=cursor.getInt(2)
                counts.put(status,counts.optInt(status)+count)
                if(reason.isNotBlank())reasons.put(reason,reasons.optInt(reason)+count)
            }
        }
        return org.json.JSONObject().put("contacts_permission",allowed(c)).put("contact_matching_enabled",enabled(c))
            .put("recognition_consent",RecognitionConsent.allowed(c)).put("background_enabled",AutoPeople.enabled(c))
            .put("portrait_policy",PORTRAIT_MODEL).put("last_complete_check_ms",prefs(c).getLong("checked",0))
            .put("provider_retry_at_ms",prefs(c).getLong("provider-retry-at",0)).put("portraits",counts).put("rejection_reasons",reasons)
    }
    /** One local query, no phone numbers, remote directories, or contact-count limit. */
    fun photos(c:Context,signal:CancellationSignal,manual:Boolean=false):List<Photo> {
        if(!(if(manual)permitted(c)else available(c)))return emptyList()
        val columns=arrayOf(ContactsContract.Contacts._ID,ContactsContract.Contacts.LOOKUP_KEY,ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,ContactsContract.Contacts.PHOTO_ID,ContactsContract.Contacts.PHOTO_URI,ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)
        return c.contentResolver.query(ContactsContract.Contacts.CONTENT_URI,columns,null,null,ContactsContract.Contacts._ID+" ASC",signal)?.use{cursor->buildList{
            while(cursor.moveToNext()){
                signal.throwIfCanceled()
                val key=cursor.getString(1);val name=cursor.getString(2)?.trim().orEmpty()
                if(key.isNullOrBlank() || name.isEmpty())continue
                val photoIndex=cursor.getColumnIndex(ContactsContract.Contacts.PHOTO_ID)
                val uriIndex=cursor.getColumnIndex(ContactsContract.Contacts.PHOTO_URI)
                val timeIndex=cursor.getColumnIndex(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)
                val photo=if(photoIndex>=0 && !cursor.isNull(photoIndex))cursor.getString(photoIndex)else ""
                val uri=if(uriIndex>=0 && !cursor.isNull(uriIndex))cursor.getString(uriIndex)else ""
                if(photo.isEmpty() && uri.isEmpty())continue
                val time=if(timeIndex>=0 && !cursor.isNull(timeIndex))cursor.getString(timeIndex)else ""
                add(Photo(ContactNames.Contact(ContactsContract.Contacts.getLookupUri(cursor.getLong(0),key).toString(),name),"$photo|$uri|$time",uri.takeIf{it.isNotBlank()},cursor.getLong(0)))
            }
        }} ?: error("Contacts temporarily unavailable")
    }
    fun cached(store:FaceStore,lookup:String):Cached?=store.readableDatabase.rawQuery("SELECT stamp,digest,vector,status,attempts,next_time,model,reason FROM contact_signatures WHERE lookup=?",arrayOf(lookup)).use{c->
        if(!c.moveToFirst())null
        else if(c.getString(6)!=PORTRAIT_MODEL && !(c.getString(6)==PREVIOUS_PORTRAIT_MODEL && c.getString(3)=="done" && !c.isNull(2)))null
        else Cached(c.getString(0),c.getString(1),if(c.isNull(2))null else FaceVectors.unpack(c.getBlob(2)),c.getString(3),c.getInt(4),c.getLong(5),c.getString(7))
    }
    fun due(old:Cached?,photo:Photo,now:Long)=old==null || old.stamp!=photo.stamp || (old.status=="error" && old.attempts<3 && now>=old.next)
    private fun save(store:FaceStore,photo:Photo,value:Cached){
        store.writableDatabase.insertWithOnConflict("contact_signatures",null,ContentValues().apply{
            put("lookup",photo.contact.lookup);put("name",photo.contact.name);put("stamp",value.stamp);put("digest",value.digest);put("model",PORTRAIT_MODEL);put("status",value.status);put("vector",value.vector?.let(FaceVectors::pack));put("attempts",value.attempts);put("next_time",value.next);put("reason",value.reason)
        },SQLiteDatabase.CONFLICT_REPLACE)
        PeopleData.changed()
    }
    fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    /** Prefer the provider's full portrait URI; lookup URIs are identity links, not image paths. */
    internal fun readPhoto(c:Context,photo:Photo,signal:CancellationSignal=CancellationSignal()):ByteArray {
        fun read(stream:java.io.InputStream?):ByteArray?=stream?.use{
            val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){signal.throwIfCanceled();val count=it.read(buffer);if(count<0)break;require(output.size()+count<=8*1024*1024){"Contact photo too large"};output.write(buffer,0,count)}
            output.toByteArray().takeIf{it.isNotEmpty()}
        }
        photo.portraitUri?.let{uri->
            signal.throwIfCanceled()
            runCatching{read(c.contentResolver.openInputStream(Uri.parse(uri)))}.getOrNull()?.let{return it}
        }
        signal.throwIfCanceled()
        val numeric=if(photo.id>0)android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI,photo.id)
            else ContactsContract.Contacts.lookupContact(c.contentResolver,Uri.parse(photo.contact.lookup))?:error("Contact is temporarily unavailable")
        runCatching{read(ContactsContract.Contacts.openContactPhotoInputStream(c.contentResolver,numeric,true))}.getOrNull()?.let{return it}
        signal.throwIfCanceled()
        return read(ContactsContract.Contacts.openContactPhotoInputStream(c.contentResolver,numeric,false))?:error("Contact photo temporarily unavailable")
    }
    internal fun usablePortrait(width:Int,height:Int,face:FaceObservation):Boolean {
        val side=minOf((face.right-face.left)*width,(face.bottom-face.top)*height)
        return side>=48 && face.sharpness>=8 && face.landmarks.size==10 && kotlin.math.abs(face.yaw)<=35 && kotlin.math.abs(face.pitch)<=30 && kotlin.math.abs(face.roll)<=40
    }
    private fun encode(data:ByteArray,detector:FaceEngine,encoder:FaceEncoder):FloatArray? {
        val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))){decoder,info,_->
            val ratio=minOf(1.0,1200.0/maxOf(info.size.width,info.size.height),kotlin.math.sqrt(1_500_000.0/(info.size.width.toLong()*info.size.height)))
            decoder.setTargetSize(maxOf(1,(info.size.width*ratio).toInt()),maxOf(1,(info.size.height*ratio).toInt()));decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try{
            val face=detector.detectBitmap(bitmap,requireSingle=true).singleOrNull()?:throw PortraitRejected("single_face_not_detected")
            if(!usablePortrait(bitmap.width,bitmap.height,face))throw PortraitRejected("portrait_quality_or_landmarks")
            val aligned=FaceAlignment.portraitCrop(bitmap,face)?:throw PortraitRejected("portrait_alignment")
            return try{encoder.encode(aligned)}finally{aligned.recycle()}
        }finally{bitmap.recycle()}
    }
    /** Testable bounded sweep. Failure consumes a retry; skipped portraits never repeat inference. */
    fun scan(store:FaceStore,photos:List<Photo>,keepGoing:()->Boolean,now:Long=System.currentTimeMillis(),limit:Int=4,
        read:(Photo)->ByteArray,infer:(ByteArray)->FloatArray?):Boolean {
        var processed=0
        for(photo in photos){
            if(!keepGoing())return false
            val old=cached(store,photo.contact.lookup)
            if(!due(old,photo,now)){
                store.writableDatabase.execSQL("UPDATE contact_signatures SET name=? WHERE lookup=? AND name!=?",arrayOf(photo.contact.name,photo.contact.lookup,photo.contact.name))
                if(android.database.DatabaseUtils.longForQuery(store.readableDatabase,"SELECT changes()",null)>0)PeopleData.changed()
                continue
            }
            if(processed++>=limit)return false
            var digest=""
            val result=runCatching{
                val data=read(photo);digest=hash(data)
                // A name/phone change updates the provider timestamp too. Reuse identical pixels.
                if(old!=null && old.digest==digest && old.status!="error")old.copy(stamp=photo.stamp)
                else {val vector=infer(data);Cached(photo.stamp,digest,vector,if(vector==null)"skipped"else"done",0,0,if(vector==null)"unusable_portrait"else "")}
            }
            if(!keepGoing())return false
            result.onSuccess{save(store,photo,it)}.onFailure{
                if(it is PortraitRejected)save(store,photo,Cached(photo.stamp,digest,null,"skipped",0,0,it.code))
                else {
                    val attempts=if(old?.stamp==photo.stamp)old.attempts+1 else 1
                    save(store,photo,Cached(photo.stamp,"",null,"error",attempts,now+if(attempts==1)60_000L else 5*60_000L,it.javaClass.simpleName))
                }
            }
        }
        return true
    }
    /** Both identity ambiguity and gallery evidence must be clear. A score isn't a probability. */
    fun choose(group:GroupRules.Capsule,references:List<Reference>,keepGoing:()->Boolean={true}):ContactNames.Contact? {
        if(group.prototypes.isEmpty())return null
        data class Rank(val reference:Reference,val best:Float,val second:Float)
        val ranks=references.distinctBy{it.contact.lookup}.map{r->
            if(!keepGoing())return null
            val scores=group.prototypes.distinctBy{it.member.key.uri}.map{it to FaceVectors.cosine(r.vector,it.vector)}.sortedByDescending{it.second}
            val best=scores.first()
            Rank(r,best.second,scores.firstOrNull{IdentityEvidence.independent(best.first.member,it.first.member)}?.second?:-1f)
        }.sortedByDescending{it.best}
        val top=ranks.firstOrNull()?:return null
        if(top.best<=.85f || top.best-(ranks.getOrNull(1)?.best?:-1f)<.08f)return null
        // A singleton needs an exceptionally close match; larger groups need another photo.
        if(top.best<.93f && top.second<.78f)return null
        return top.reference.contact
    }
    fun match(store:FaceStore,references:List<Reference>,keepGoing:()->Boolean):Int {
        if(references.isEmpty())return 0
        val people=PeopleStore(store)
        val referenceToken=hash((POLICY+references.sortedBy{it.contact.lookup}.joinToString{"${it.contact.lookup}:${it.contact.name}:${hash(FaceVectors.pack(it.vector))}"}).toByteArray())
        var seeded=0
        for(member in people.members().filter{it.person==null && it.ready && !it.manual && it.status!="excluded" && it.face.authority=="Anchor"}){
            if(!keepGoing())return seeded
            val vector=people.vector(member.key)?:continue
            val token=hash((referenceToken+hash(FaceVectors.pack(vector))).toByteArray())
            val done=store.readableDatabase.rawQuery("SELECT token FROM contact_face_matches WHERE uri=? AND ordinal=?",arrayOf(member.key.uri,member.key.ordinal.toString())).use{it.moveToFirst() && it.getString(0)==token}
            if(done)continue
            val candidate=GroupRules.Capsule(0,mutableSetOf(),mutableSetOf(member.key.uri),mutableSetOf(member.key.uri),mutableListOf(GroupRules.Prototype(member,vector)))
            val contact=choose(candidate,references,keepGoing)
            if(!keepGoing())return seeded
            if(contact==null){
                store.writableDatabase.insertWithOnConflict("contact_face_matches",null,ContentValues().apply{put("uri",member.key.uri);put("ordinal",member.key.ordinal);put("token",token)},SQLiteDatabase.CONFLICT_REPLACE)
                continue
            }
            val id=people.record(member,GroupRules.Decision(seed=true,status="known",score=1f,reason="Clear contact portrait match"))?:continue
            if(people.autoContact(id,contact))seeded++
        }
        val rows=people.members();val roots=people.components(rows)
        val named=people.names().keys;val blocked=people.contactAutomationBlocked();val contacts=people.contacts(roots)
        val groups=people.capsules(rows,roots)
        val manual=rows.filter{it.manual}.mapNotNull{it.person?.let{p->roots[p]?:p}}.toSet()
        val eligible=groups.filter{group->contacts[group.id]==null && group.leaves.none{it in named || it in blocked} && group.id !in manual}
        if(eligible.isEmpty())return seeded
        var linked=seeded
        for(group in eligible){
            if(!keepGoing())break
            val token=hash((referenceToken+group.leaves.sorted().joinToString()+group.prototypes.joinToString{"${it.member.key}:${hash(FaceVectors.pack(it.vector))}"}).toByteArray())
            val done=store.readableDatabase.rawQuery("SELECT token FROM contact_matches WHERE person=?",arrayOf(group.id.toString())).use{it.moveToFirst() && it.getString(0)==token}
            if(done)continue
            val contact=choose(group,references,keepGoing)
            if(!keepGoing())break
            if(contact!=null && people.autoContact(group.id,contact,rows,roots,groups))linked++
            store.writableDatabase.insertWithOnConflict("contact_matches",null,ContentValues().apply{put("person",group.id);put("token",token)},SQLiteDatabase.CONFLICT_REPLACE)
        }
        return linked
    }
    /** A temporarily unavailable address book must not prevent gallery recognition. */
    fun runSafe(c:Context,store:FaceStore,keepGoing:()->Boolean,signal:CancellationSignal=CancellationSignal(),limit:Int=12,manual:Boolean=false):Result = try{run(c,store,keepGoing,signal,limit,manual)}catch(e:Exception){
        signal.throwIfCanceled()
        if(!keepGoing())Result(true,null)else{val delay=15*60_000L;if(!manual)prefs(c).edit().putLong("provider-retry-at",System.currentTimeMillis()+delay).apply();Result(false,delay)}
    }
    /** Called under FaceWork's writer lock by both foreground and scheduled processing. */
    fun run(c:Context,store:FaceStore,keepGoing:()->Boolean,signal:CancellationSignal=CancellationSignal(),limit:Int=12,manual:Boolean=false):Result {
        fun permittedRun()=if(manual)permitted(c)else available(c)
        if(!permittedRun()){if(!manual && !permitted(c))clear(store);return Result(false,null)}
        if(!keepGoing())return Result(true,null)
        if(!manual && !needsWork(c)){
            val now=System.currentTimeMillis();val p=prefs(c)
            val wake=listOf(p.getLong("provider-retry-at",0),p.getLong("retry-at",0)).filter{it>now}.minOrNull()
            return Result(false,wake?.let{it-now})
        }
        val photos=photos(c,signal,manual)
        // Only a successful complete provider query may discard removed contact references.
        val live=photos.map{it.contact.lookup}.toSet()
        val stale=buildList{store.readableDatabase.rawQuery("SELECT lookup FROM contact_signatures",null).use{while(it.moveToNext())if(it.getString(0) !in live)add(it.getString(0))}}
        stale.forEach{store.writableDatabase.delete("contact_signatures","lookup=?",arrayOf(it))}
        if(stale.isNotEmpty())PeopleData.changed()
        var detector:FaceEngine?=null;var encoder:FaceEncoder?=null
        try{
            fun active()=keepGoing() && permittedRun() && !signal.isCanceled
            val complete=scan(store,photos,::active,limit=limit,read={readPhoto(c,it,signal)},infer={data->
                val d=detector?:FaceEngine(c).also{detector=it};val e=encoder?:FaceEncoder(c,1).also{encoder=it};encode(data,d,e)
            })
            val references=photos.mapNotNull{p->cached(store,p.contact.lookup)?.takeIf{it.stamp==p.stamp && it.status=="done"}?.vector?.let{Reference(p.contact,it)}}
            val retry=store.readableDatabase.rawQuery("SELECT MIN(next_time) FROM contact_signatures WHERE status='error' AND attempts<3 AND model=?",arrayOf(PORTRAIT_MODEL)).use{if(it.moveToFirst() && !it.isNull(0))maxOf(60_000L,it.getLong(0)-System.currentTimeMillis())else null}
            // Never name someone against a partially scanned address book: an unseen portrait
            // could be the competing contact. Let bounded transient retries finish first too.
            if(complete && retry==null && active() && match(store,references,::active)>0)AutoPeople.dirty(c)
            if(!permittedRun()){if(!manual && !permitted(c))clear(store);return Result(false,null)}
            if(complete && active())prefs(c).edit().remove("provider-retry-at").putLong("checked",System.currentTimeMillis()).putLong("retry-at",retry?.let{System.currentTimeMillis()+it}?:0).putLong("faces",AutoPeople.revision(c)).putString("model",PORTRAIT_MODEL).apply()
            return Result(!complete || !active(),retry)
        }finally{detector?.close();encoder?.close()}
    }
}
