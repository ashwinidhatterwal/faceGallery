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
    private const val POLICY="contacts-v1"
    private const val CHECK_INTERVAL=15*60_000L
    data class Photo(val contact:ContactNames.Contact,val stamp:String)
    data class Reference(val contact:ContactNames.Contact,val vector:FloatArray)
    data class Cached(val stamp:String,val digest:String,val vector:FloatArray?,val status:String,val attempts:Int,val next:Long)
    data class Result(val more:Boolean,val retryDelay:Long?)
    fun create(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE IF NOT EXISTS contact_signatures(lookup TEXT PRIMARY KEY,name TEXT NOT NULL,stamp TEXT NOT NULL,digest TEXT NOT NULL,model TEXT NOT NULL,status TEXT NOT NULL,vector BLOB,attempts INTEGER NOT NULL,next_time INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS contact_matches(person INTEGER PRIMARY KEY REFERENCES people(id) ON DELETE CASCADE,token TEXT NOT NULL)")
    }
    private fun prefs(c:Context)=c.getSharedPreferences("contact-recognition",0)
    fun enabled(c:Context)=prefs(c).getBoolean("enabled",true)
    fun allowed(c:Context)=c.checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED
    fun available(c:Context)=enabled(c) && allowed(c)
    fun setEnabled(c:Context,value:Boolean){
        prefs(c).edit().putBoolean("enabled",value).remove("checked").apply()
        if(value)AutoPeople.ensure(c,1_000)
        else {val app=c.applicationContext;Thread({FaceWork.write{if(!enabled(app))FaceStore(app).use(::clear)}},"contact-cache-clear").start()}
    }
    fun invalidate(c:Context){prefs(c).edit().remove("checked").apply()}
    fun needsWork(c:Context):Boolean = available(c) && (prefs(c).getString("model","")!=FaceVectors.MODEL || System.currentTimeMillis()-prefs(c).getLong("checked",0)>=CHECK_INTERVAL || prefs(c).getLong("faces",-1)!=AutoPeople.revision(c))
    fun clear(store:FaceStore){store.writableDatabase.delete("contact_signatures",null,null);store.writableDatabase.delete("contact_matches",null,null)}
    /** One local query, no phone numbers, remote directories, or contact-count limit. */
    fun photos(c:Context,signal:CancellationSignal):List<Photo> {
        if(!available(c))return emptyList()
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
                add(Photo(ContactNames.Contact(ContactsContract.Contacts.getLookupUri(cursor.getLong(0),key).toString(),name),"$photo|$uri|$time"))
            }
        }} ?: error("Contacts temporarily unavailable")
    }
    fun cached(store:FaceStore,lookup:String):Cached?=store.readableDatabase.rawQuery("SELECT stamp,digest,vector,status,attempts,next_time,model FROM contact_signatures WHERE lookup=?",arrayOf(lookup)).use{c->
        if(!c.moveToFirst() || c.getString(6)!=FaceVectors.MODEL)null else Cached(c.getString(0),c.getString(1),if(c.isNull(2))null else FaceVectors.unpack(c.getBlob(2)),c.getString(3),c.getInt(4),c.getLong(5))
    }
    fun due(old:Cached?,photo:Photo,now:Long)=old==null || old.stamp!=photo.stamp || (old.status=="error" && old.attempts<3 && now>=old.next)
    private fun save(store:FaceStore,photo:Photo,value:Cached){
        store.writableDatabase.insertWithOnConflict("contact_signatures",null,ContentValues().apply{
            put("lookup",photo.contact.lookup);put("name",photo.contact.name);put("stamp",value.stamp);put("digest",value.digest);put("model",FaceVectors.MODEL);put("status",value.status);put("vector",value.vector?.let(FaceVectors::pack));put("attempts",value.attempts);put("next_time",value.next)
        },SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun bytes(c:Context,photo:Photo):ByteArray=ContactsContract.Contacts.openContactPhotoInputStream(c.contentResolver,Uri.parse(photo.contact.lookup),true)?.use{stream->
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val count=stream.read(buffer);if(count<0)break;require(output.size()+count<=8*1024*1024){"Contact photo too large"};output.write(buffer,0,count)}
        output.toByteArray().also{require(it.isNotEmpty())}
    }?:error("Contact photo temporarily unavailable")
    private fun encode(data:ByteArray,detector:FaceEngine,encoder:FaceEncoder):FloatArray? {
        val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))){decoder,info,_->
            val ratio=minOf(1.0,1200.0/maxOf(info.size.width,info.size.height),kotlin.math.sqrt(1_500_000.0/(info.size.width.toLong()*info.size.height)))
            decoder.setTargetSize(maxOf(1,(info.size.width*ratio).toInt()),maxOf(1,(info.size.height*ratio).toInt()));decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try{
            val face=detector.detectBitmap(bitmap,requireSingle=true).singleOrNull()?:return null
            if(face.authority=="Shadow" || face.landmarks.size!=10 || face.score<.65f || kotlin.math.abs(face.yaw)>35 || kotlin.math.abs(face.pitch)>30)return null
            val aligned=FaceAlignment.crop(bitmap,face)?:return null
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
                store.writableDatabase.execSQL("UPDATE contact_signatures SET name=? WHERE lookup=? AND name!=?",arrayOf(photo.contact.name,photo.contact.lookup,photo.contact.name));continue
            }
            if(processed++>=limit)return false
            val result=runCatching{
                val data=read(photo);val digest=hash(data)
                // A name/phone change updates the provider timestamp too. Reuse identical pixels.
                if(old!=null && old.digest==digest && old.status!="error")old.copy(stamp=photo.stamp)
                else {val vector=infer(data);Cached(photo.stamp,digest,vector,if(vector==null)"skipped"else"done",0,0)}
            }
            if(!keepGoing())return false
            result.onSuccess{save(store,photo,it)}.onFailure{
                val attempts=if(old?.stamp==photo.stamp)old.attempts+1 else 1
                save(store,photo,Cached(photo.stamp,"",null,"error",attempts,now+if(attempts==1)60_000L else 5*60_000L))
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
        val people=PeopleStore(store);val rows=people.members();val roots=people.components(rows)
        val named=people.names().keys;val blocked=people.contactAutomationBlocked();val contacts=people.contacts(roots)
        val groups=people.capsules(rows,roots)
        val manual=rows.filter{it.manual}.mapNotNull{it.person?.let{p->roots[p]?:p}}.toSet()
        val eligible=groups.filter{group->contacts[group.id]==null && group.leaves.none{it in named || it in blocked} && group.id !in manual}
        if(eligible.isEmpty())return 0
        val referenceToken=hash((POLICY+references.sortedBy{it.contact.lookup}.joinToString{"${it.contact.lookup}:${it.contact.name}:${hash(FaceVectors.pack(it.vector))}"}).toByteArray())
        var linked=0
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
    /** Called under FaceWork's writer lock by both foreground and scheduled processing. */
    fun run(c:Context,store:FaceStore,keepGoing:()->Boolean,signal:CancellationSignal=CancellationSignal()):Result {
        if(!available(c)){clear(store);return Result(false,null)}
        if(!keepGoing())return Result(true,null)
        val photos=photos(c,signal)
        // Only a successful complete provider query may discard removed contact references.
        val live=photos.map{it.contact.lookup}.toSet()
        val stale=buildList{store.readableDatabase.rawQuery("SELECT lookup FROM contact_signatures",null).use{while(it.moveToNext())if(it.getString(0) !in live)add(it.getString(0))}}
        stale.forEach{store.writableDatabase.delete("contact_signatures","lookup=?",arrayOf(it))}
        var detector:FaceEngine?=null;var encoder:FaceEncoder?=null
        try{
            fun active()=keepGoing() && available(c) && !signal.isCanceled
            val complete=scan(store,photos,::active,limit=12,read={bytes(c,it)},infer={data->
                val d=detector?:FaceEngine(c).also{detector=it};val e=encoder?:FaceEncoder(c,1).also{encoder=it};encode(data,d,e)
            })
            val references=photos.mapNotNull{p->cached(store,p.contact.lookup)?.takeIf{it.stamp==p.stamp && it.status=="done"}?.vector?.let{Reference(p.contact,it)}}
            val retry=store.readableDatabase.rawQuery("SELECT MIN(next_time) FROM contact_signatures WHERE status='error' AND attempts<3 AND model=?",arrayOf(FaceVectors.MODEL)).use{if(it.moveToFirst() && !it.isNull(0))maxOf(60_000L,it.getLong(0)-System.currentTimeMillis())else null}
            // Never name someone against a partially scanned address book: an unseen portrait
            // could be the competing contact. Let bounded transient retries finish first too.
            if(complete && retry==null && active())match(store,references,::active)
            if(!available(c)){clear(store);return Result(false,null)}
            if(complete && active())prefs(c).edit().putLong("checked",System.currentTimeMillis()).putLong("faces",AutoPeople.revision(c)).putString("model",FaceVectors.MODEL).apply()
            return Result(!complete || !active(),retry)
        }finally{detector?.close();encoder?.close()}
    }
}
