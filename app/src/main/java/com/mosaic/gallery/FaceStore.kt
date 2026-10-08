package com.mosaic.gallery

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class FaceStore(private val context:Context,private val now:()->Long=System::currentTimeMillis):SQLiteOpenHelper(context,"faces.db",null,8),java.io.Closeable{
    companion object{
        const val MODEL="mlkit-16.1.7-accurate-1600-quality1"
        fun fingerprint(photo:PhotoRecord)="${photo.modifiedMillis}:${photo.sizeBytes}:${photo.width}:${photo.height}:${photo.dateTakenMillis}"
    }
    private fun changed(uri:String?=null){if(uri!=null)PeopleData.facesChanged(uri)else PeopleData.changed();AutoPeople.dirty(context)}
    override fun onConfigure(db:SQLiteDatabase){db.setForeignKeyConstraintsEnabled(true)}
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE photos(uri TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,model TEXT NOT NULL,status TEXT NOT NULL,error TEXT)")
        db.execSQL("CREATE TABLE faces(uri TEXT NOT NULL REFERENCES photos(uri) ON DELETE CASCADE,ordinal INTEGER NOT NULL,l REAL,t REAL,r REAL,b REAL,yaw REAL,pitch REAL,roll REAL,sharpness REAL,score REAL,authority TEXT,landmarks BLOB,PRIMARY KEY(uri,ordinal))")
        createEmbeddings(db);PeopleStore.create(db);createRetries(db);createDurable(db);ContactRecognition.create(db);PeopleData.changed()
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){
        if(oldVersion<2){db.execSQL("ALTER TABLE faces ADD COLUMN landmarks BLOB");createEmbeddings(db)}
        if(oldVersion<3)PeopleStore.create(db)
        else if(oldVersion<4){db.execSQL("ALTER TABLE membership ADD COLUMN manual INTEGER NOT NULL DEFAULT 0");db.execSQL("ALTER TABLE relations ADD COLUMN batch INTEGER");PeopleCorrections.create(db)}
        if(oldVersion<6)createRetries(db)
        if(oldVersion<7)createDurable(db)
        if(oldVersion in 3..4){db.execSQL("ALTER TABLE people ADD COLUMN contact_lookup TEXT");db.execSQL("ALTER TABLE people ADD COLUMN contact_name TEXT");db.execSQL("ALTER TABLE people ADD COLUMN name_rank INTEGER NOT NULL DEFAULT 0")}
        if(oldVersion<8){
            val hasVeto=db.rawQuery("PRAGMA table_info(people)",null).use{c->var found=false;while(c.moveToNext())if(c.getString(1)=="contact_auto_blocked")found=true;found}
            if(!hasVeto)db.execSQL("ALTER TABLE people ADD COLUMN contact_auto_blocked INTEGER NOT NULL DEFAULT 0")
            ContactRecognition.create(db)
        }
    }
    private fun createDurable(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE IF NOT EXISTS operation_retries(uri TEXT NOT NULL,ordinal INTEGER NOT NULL,phase TEXT NOT NULL,fingerprint TEXT NOT NULL,model TEXT NOT NULL,attempts INTEGER NOT NULL,next_time INTEGER NOT NULL,PRIMARY KEY(uri,ordinal,phase))")
        db.execSQL("CREATE TABLE IF NOT EXISTS identity_assertions(uri TEXT NOT NULL,ordinal INTEGER NOT NULL,person INTEGER REFERENCES people(id) ON DELETE CASCADE,status TEXT NOT NULL,vector BLOB,model TEXT NOT NULL,l REAL,t REAL,r REAL,b REAL,fingerprint TEXT NOT NULL,PRIMARY KEY(uri,ordinal))")
    }
    private fun retryAllowed(uri:String,ordinal:Int,phase:String,fp:String?=null):Boolean = readableDatabase.rawQuery("SELECT attempts,next_time,fingerprint,model FROM operation_retries WHERE uri=? AND ordinal=? AND phase=?",arrayOf(uri,ordinal.toString(),phase)).use{c->!c.moveToFirst() || c.getString(3)!=FaceVectors.MODEL || (fp!=null && c.getString(2)!=fp) || (c.getInt(0)<3 && now()>=c.getLong(1))}
    private fun failed(uri:String,ordinal:Int,phase:String,fp:String=""){
        val db=writableDatabase
        val old=db.rawQuery("SELECT attempts,fingerprint,model FROM operation_retries WHERE uri=? AND ordinal=? AND phase=?",arrayOf(uri,ordinal.toString(),phase)).use{if(it.moveToFirst() && it.getString(1)==fp && it.getString(2)==FaceVectors.MODEL)it.getInt(0)else 0}
        val count=old+1
        db.insertWithOnConflict("operation_retries",null,ContentValues().apply{put("uri",uri);put("ordinal",ordinal);put("phase",phase);put("fingerprint",fp);put("model",FaceVectors.MODEL);put("attempts",count);put("next_time",now()+when(count){1->60_000L;2->300_000L;else->1_800_000L})},SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun nextRetryDelay():Long?=readableDatabase.rawQuery("SELECT MIN(next_time) FROM operation_retries WHERE attempts<3 AND model=?",arrayOf(FaceVectors.MODEL)).use{if(it.moveToFirst() && !it.isNull(0))maxOf(60_000L,it.getLong(0)-now())else null}
    fun refinementFailed(key:GroupRules.Key){failed(key.uri,key.ordinal,"refine")}
    private fun snapshotAssertions(uri:String){
        writableDatabase.execSQL("INSERT OR REPLACE INTO identity_assertions(uri,ordinal,person,status,vector,model,l,t,r,b,fingerprint) SELECT f.uri,f.ordinal,m.person,m.status,e.vector,COALESCE(e.model,?),f.l,f.t,f.r,f.b,p.fingerprint FROM faces f JOIN photos p ON p.uri=f.uri JOIN membership m ON m.uri=f.uri AND m.ordinal=f.ordinal LEFT JOIN embeddings e ON e.uri=f.uri AND e.ordinal=f.ordinal WHERE f.uri=? AND m.manual=1",arrayOf(FaceVectors.MODEL,uri))
    }
    /** Durable assertions survive detection replacement. Reattach only unambiguous mutual matches. */
    fun restoreAssertions(uri:String){
        data class Assertion(val ordinal:Int,val person:Long?,val status:String,val vector:FloatArray?,val box:List<Float>,val fp:String)
        val db=writableDatabase;val saved=buildList{db.rawQuery("SELECT ordinal,person,status,vector,model,l,t,r,b,fingerprint FROM identity_assertions WHERE uri=?",arrayOf(uri)).use{c->while(c.moveToNext())add(Assertion(c.getInt(0),if(c.isNull(1))null else c.getLong(1),c.getString(2),if(c.getString(4)==FaceVectors.MODEL && !c.isNull(3))runCatching{FaceVectors.unpack(c.getBlob(3))}.getOrNull()else null,(5..8).map{c.getFloat(it)},c.getString(9)))}}
        if(saved.isEmpty())return
        val current=observations(uri);val fp=db.rawQuery("SELECT fingerprint FROM photos WHERE uri=?",arrayOf(uri)).use{if(it.moveToFirst())it.getString(0)else ""}
        val vectors=current.indices.associateWith{signature(uri,it)}
        fun score(a:Assertion,i:Int):Float {
            val v=vectors[i];if(a.vector!=null && v!=null)return FaceVectors.cosine(a.vector,v)
            // Only metadata-only edits can reuse geometry; never copy a detector ordinal blindly.
            val compatible=a.fp.split(':').take(4)==fp.split(':').take(4)
            val f=current[i];return if(compatible && a.box.zip(listOf(f.left,f.top,f.right,f.bottom)).all{kotlin.math.abs(it.first-it.second)<.005f})1f else -1f
        }
        val rankings=saved.associateWith{a->current.indices.map{it to score(a,it)}.sortedByDescending{it.second}}
        db.beginTransaction();try{for(a in saved){
            val rank=rankings[a].orEmpty();val best=rank.firstOrNull()?:continue
            if(best.second<.92f || best.second-(rank.getOrNull(1)?.second?:-1f)<.08f)continue
            val rivals=saved.filter{it!==a}.map{score(it,best.first)};if(best.second-(rivals.maxOrNull()?:-1f)<.08f)continue
            val ordinal=best.first
            val already=db.rawQuery("SELECT manual FROM membership WHERE uri=? AND ordinal=?",arrayOf(uri,ordinal.toString())).use{it.moveToFirst() && it.getInt(0)!=0};if(already)continue
            db.insertWithOnConflict("membership",null,ContentValues().apply{put("uri",uri);put("ordinal",ordinal);put("person",a.person);put("status",a.status);put("score",1f);put("reason","Saved user identity after photo edit");put("model",GroupRules.VERSION);put("manual",1)},SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("identity_assertions","uri=? AND ordinal=?",arrayOf(uri,a.ordinal.toString()));PeopleData.changed()
        };db.setTransactionSuccessful()}finally{db.endTransaction()}
    }
    private fun createRetries(db:SQLiteDatabase){db.execSQL("CREATE TABLE IF NOT EXISTS signature_retries(uri TEXT NOT NULL,ordinal INTEGER NOT NULL,model TEXT NOT NULL,PRIMARY KEY(uri,ordinal),FOREIGN KEY(uri,ordinal) REFERENCES faces(uri,ordinal) ON DELETE CASCADE)")}
    fun needsRefinement(key:GroupRules.Key)=retryAllowed(key.uri,key.ordinal,"refine") && readableDatabase.rawQuery("SELECT 1 FROM signature_retries WHERE uri=? AND ordinal=? AND model=?",arrayOf(key.uri,key.ordinal.toString(),"hq1-${FaceVectors.MODEL}")).use{!it.moveToFirst()}
    fun refined(key:GroupRules.Key){writableDatabase.delete("operation_retries","uri=? AND ordinal=? AND phase='refine'",arrayOf(key.uri,key.ordinal.toString()));writableDatabase.insertWithOnConflict("signature_retries",null,ContentValues().apply{put("uri",key.uri);put("ordinal",key.ordinal);put("model","hq1-${FaceVectors.MODEL}")},SQLiteDatabase.CONFLICT_REPLACE)}
    private fun createEmbeddings(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE embeddings(uri TEXT NOT NULL,ordinal INTEGER NOT NULL,model TEXT NOT NULL,status TEXT NOT NULL,vector BLOB,error TEXT,PRIMARY KEY(uri,ordinal),FOREIGN KEY(uri,ordinal) REFERENCES faces(uri,ordinal) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX embedding_state ON embeddings(model,status)")
    }
    fun pending(photos:List<PhotoRecord>,retryErrors:Boolean=true):List<PhotoRecord>{
        val completed=mutableMapOf<String,String>()
        readableDatabase.rawQuery("SELECT uri,fingerprint FROM photos WHERE status='done' AND model=?",arrayOf(MODEL)).use{while(it.moveToNext())completed[it.getString(0)]=it.getString(1)}
        return photos.filter{completed[it.uri.toString()]!=fingerprint(it) && (retryErrors || retryAllowed(it.uri.toString(),-1,"detect",fingerprint(it)))}
    }
    fun retain(photos:List<PhotoRecord>,removeMissing:Boolean=true){
        val db=writableDatabase;db.beginTransaction()
        try{
            db.execSQL("CREATE TEMP TABLE IF NOT EXISTS accessible(uri TEXT PRIMARY KEY,fingerprint TEXT NOT NULL)");db.execSQL("DELETE FROM accessible")
            val statement=db.compileStatement("INSERT OR IGNORE INTO accessible(uri,fingerprint) VALUES(?,?)")
            statement.use{photos.forEach{photo->it.bindString(1,photo.uri.toString());it.bindString(2,fingerprint(photo));it.executeInsert()}}
            db.execSQL("UPDATE photos SET status='stale' WHERE status!='stale' AND EXISTS(SELECT 1 FROM accessible WHERE accessible.uri=photos.uri AND (accessible.fingerprint!=photos.fingerprint OR photos.model!=?))",arrayOf(MODEL))
            val stale=android.database.DatabaseUtils.longForQuery(db,"SELECT changes()",null)>0
            val removed=if(removeMissing){
                db.execSQL("DELETE FROM identity_assertions WHERE NOT EXISTS (SELECT 1 FROM accessible WHERE accessible.uri=identity_assertions.uri)")
                db.execSQL("DELETE FROM operation_retries WHERE NOT EXISTS (SELECT 1 FROM accessible WHERE accessible.uri=operation_retries.uri)")
                db.execSQL("DELETE FROM photos WHERE NOT EXISTS (SELECT 1 FROM accessible WHERE accessible.uri=photos.uri)")
                android.database.DatabaseUtils.longForQuery(db,"SELECT changes()",null)>0
            }else false
            db.setTransactionSuccessful();if(removed || stale)changed()
        }finally{db.endTransaction()}
    }
    fun save(photo:PhotoRecord,observations:List<FaceObservation>,error:String?=null){
        val uri=photo.uri.toString();val db=writableDatabase
        if(error!=null){
            failed(uri,-1,"detect",fingerprint(photo))
            // A temporary failure never replaces an existing successful detection or its assertions.
            if(observations(uri).isNotEmpty())return
        }else db.delete("operation_retries","uri=?",arrayOf(uri))
        db.beginTransaction()
        try{
            snapshotAssertions(uri)
            db.delete("photos","uri=?",arrayOf(uri))
            db.insertOrThrow("photos",null,ContentValues().apply{put("uri",uri);put("fingerprint",fingerprint(photo));put("model",MODEL);put("status",if(error==null)"done"else"error");put("error",error?.take(160))})
            observations.forEachIndexed{ordinal,face->db.insertOrThrow("faces",null,ContentValues().apply{put("uri",uri);put("ordinal",ordinal);put("l",face.left);put("t",face.top);put("r",face.right);put("b",face.bottom);put("yaw",face.yaw);put("pitch",face.pitch);put("roll",face.roll);put("sharpness",face.sharpness);put("score",face.score);put("authority",face.authority);put("landmarks",packLandmarks(face.landmarks))})}
            db.setTransactionSuccessful()
        }finally{db.endTransaction()};restoreAssertions(uri);changed(uri)
    }
    fun observations(uri:String):List<FaceObservation>{
        val result=mutableListOf<FaceObservation>()
        readableDatabase.rawQuery("SELECT l,t,r,b,yaw,pitch,roll,sharpness,score,authority,landmarks FROM faces WHERE uri=? AND EXISTS(SELECT 1 FROM photos WHERE photos.uri=faces.uri AND photos.status='done') ORDER BY ordinal",arrayOf(uri)).use{while(it.moveToNext())result+=FaceObservation(it.getFloat(0),it.getFloat(1),it.getFloat(2),it.getFloat(3),it.getFloat(4),it.getFloat(5),it.getFloat(6),it.getFloat(7),it.getFloat(8),it.getString(9),readLandmarks(it.getBlob(10)))}
        return result
    }
    data class Summary(val done:Int,val errors:Int,val facePhotos:Set<String>,val counts:Map<String,Int>){val faces get()=counts.values.sum()}
    fun summary():Summary{
        var done=0;var errors=0;val uris=mutableSetOf<String>();val counts=mutableMapOf<String,Int>()
        readableDatabase.rawQuery("SELECT status,COUNT(*) FROM photos GROUP BY status",null).use{while(it.moveToNext())if(it.getString(0)=="done")done=it.getInt(1)else errors+=it.getInt(1)}
        readableDatabase.rawQuery("SELECT DISTINCT uri FROM faces",null).use{while(it.moveToNext())uris+=it.getString(0)}
        readableDatabase.rawQuery("SELECT authority,COUNT(*) FROM faces GROUP BY authority",null).use{while(it.moveToNext())counts[it.getString(0)]=it.getInt(1)}
        return Summary(done,errors,uris,counts)
    }
    private fun packLandmarks(points:List<Float>):ByteArray? = if(points.size==10)java.nio.ByteBuffer.allocate(40).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply{points.forEach{putFloat(it)}}.array()else null
    private fun readLandmarks(bytes:ByteArray?):List<Float> {
        if(bytes?.size!=40)return emptyList()
        val buffer=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);return List(10){buffer.float}
    }
    fun updateLandmarks(uri:String,faces:List<FaceObservation>){
        val db=writableDatabase;db.beginTransaction()
        try{faces.forEachIndexed{i,face->db.update("faces",ContentValues().apply{put("landmarks",packLandmarks(face.landmarks))},"uri=? AND ordinal=?",arrayOf(uri,i.toString()))};db.setTransactionSuccessful()}finally{db.endTransaction()}
    }
    fun refinedObservation(key:GroupRules.Key,face:FaceObservation){
        writableDatabase.update("faces",ContentValues().apply{put("landmarks",packLandmarks(face.landmarks));put("score",face.score);put("authority",face.authority);put("sharpness",face.sharpness);put("yaw",face.yaw);put("pitch",face.pitch);put("roll",face.roll)},"uri=? AND ordinal=?",arrayOf(key.uri,key.ordinal.toString()))
    }
    fun pendingSignatures(photos:List<PhotoRecord>,retryErrors:Boolean=true):List<PhotoRecord>{
        val uris=mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT DISTINCT f.uri FROM faces f LEFT JOIN embeddings e ON f.uri=e.uri AND f.ordinal=e.ordinal WHERE f.authority!='Shadow' AND (e.uri IS NULL OR e.model!=? OR e.status='error')",arrayOf(FaceVectors.MODEL)).use{while(it.moveToNext())uris+=it.getString(0)}
        return photos.filter{it.uri.toString() in uris && it !in pending(photos) && (retryErrors || observations(it.uri.toString()).indices.any{i->needsSignature(it.uri.toString(),i) && retryAllowed(it.uri.toString(),i,"encode")})}
    }
    fun encodingDue(uri:String,ordinal:Int)=retryAllowed(uri,ordinal,"encode")
    fun needsSignature(uri:String,ordinal:Int):Boolean = readableDatabase.rawQuery("SELECT status,model FROM embeddings WHERE uri=? AND ordinal=?",arrayOf(uri,ordinal.toString())).use{!it.moveToFirst() || it.getString(1)!=FaceVectors.MODEL || it.getString(0)=="error"}
    fun saveSignature(uri:String,ordinal:Int,vector:FloatArray?,status:String="done",error:String?=null){
        require(status in setOf("done","skipped","error") && (status!="done" || vector!=null))
        val packed=vector?.let{FaceVectors.pack(it)}
        if(status=="error")failed(uri,ordinal,"encode")else writableDatabase.delete("operation_retries","uri=? AND ordinal=? AND phase='encode'",arrayOf(uri,ordinal.toString()))
        writableDatabase.insertWithOnConflict("embeddings",null,ContentValues().apply{put("uri",uri);put("ordinal",ordinal);put("model",FaceVectors.MODEL);put("status",status);put("vector",packed);put("error",error?.take(160))},SQLiteDatabase.CONFLICT_REPLACE).also{check(it!=-1L){"Signature could not be saved"};changed(uri)}
    }
    data class Signatures(val ready:Int,val skipped:Int,val errors:Int)
    fun signatureSummary():Signatures {
        val counts=mutableMapOf<String,Int>();readableDatabase.rawQuery("SELECT status,COUNT(*) FROM embeddings WHERE model=? AND EXISTS(SELECT 1 FROM photos p WHERE p.uri=embeddings.uri AND p.status='done') GROUP BY status",arrayOf(FaceVectors.MODEL)).use{while(it.moveToNext())counts[it.getString(0)]=it.getInt(1)}
        return Signatures(counts["done"]?:0,counts["skipped"]?:0,counts["error"]?:0)
    }
    fun signature(uri:String,ordinal:Int):FloatArray? = readableDatabase.rawQuery("SELECT vector FROM embeddings WHERE uri=? AND ordinal=? AND model=? AND status='done' AND EXISTS(SELECT 1 FROM photos p WHERE p.uri=embeddings.uri AND p.status='done')",arrayOf(uri,ordinal.toString(),FaceVectors.MODEL)).use{if(it.moveToFirst())runCatching{FaceVectors.unpack(it.getBlob(0))}.getOrNull()else null}
    fun similar(uri:String,ordinal:Int,limit:Int=20,eligible:Set<String>?=null,keepGoing:()->Boolean={true}):List<FaceVectors.Match>{
        val vector=signature(uri,ordinal)?:return emptyList();val search=FaceVectors.Search(vector,uri,limit)
        readableDatabase.rawQuery("SELECT f.uri,f.ordinal,f.l,f.t,f.r,f.b,f.yaw,f.pitch,f.roll,f.sharpness,f.score,f.authority,e.vector FROM embeddings e JOIN faces f ON e.uri=f.uri AND e.ordinal=f.ordinal WHERE e.model=? AND e.status='done' AND e.uri!=?",arrayOf(FaceVectors.MODEL,uri)).use{cursor->
            while(cursor.moveToNext()){
                if(!keepGoing())break
                if(eligible!=null && cursor.getString(0) !in eligible)continue
                val face=FaceObservation(cursor.getFloat(2),cursor.getFloat(3),cursor.getFloat(4),cursor.getFloat(5),cursor.getFloat(6),cursor.getFloat(7),cursor.getFloat(8),cursor.getFloat(9),cursor.getFloat(10),cursor.getString(11))
                runCatching{search.offer(cursor.getString(0),cursor.getInt(1),face,FaceVectors.unpack(cursor.getBlob(12)))}
            }
        }
        return search.results()
    }
    fun <T> snapshot(read:()->T):T{val db=readableDatabase;db.beginTransactionNonExclusive();try{return read()}finally{db.endTransaction()}}
    fun comparisonCheckpoint()=android.util.AtomicFile(java.io.File(context.filesDir,"people-comparisons.json"))
    fun clear(){val db=writableDatabase;db.beginTransaction();try{PeopleStore(this).reset();db.delete("identity_assertions",null,null);db.delete("operation_retries",null,null);db.delete("photos",null,null);db.setTransactionSuccessful()}finally{db.endTransaction()};changed()}
}
