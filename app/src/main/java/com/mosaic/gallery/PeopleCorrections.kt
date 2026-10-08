package com.mosaic.gallery

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** Transactional face corrections. Source vectors and photos remain unchanged. */
class PeopleCorrections(private val faces:FaceStore) {
    private val db get()=faces.writableDatabase
    private val people get()=PeopleStore(faces)
    companion object {
        fun create(db:SQLiteDatabase){
            db.execSQL("CREATE TABLE IF NOT EXISTS face_edits(id INTEGER PRIMARY KEY,batch INTEGER NOT NULL,uri TEXT NOT NULL,ordinal INTEGER NOT NULL,person INTEGER,suggested INTEGER,status TEXT NOT NULL,score REAL NOT NULL,reason TEXT NOT NULL,model TEXT NOT NULL,manual INTEGER NOT NULL,newperson INTEGER,active INTEGER NOT NULL,FOREIGN KEY(uri,ordinal) REFERENCES faces(uri,ordinal) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS edit_batch ON face_edits(batch,active)")
            db.execSQL("CREATE TABLE IF NOT EXISTS correction_samples(id INTEGER PRIMARY KEY,uri1 TEXT NOT NULL,ordinal1 INTEGER NOT NULL,uri2 TEXT NOT NULL,ordinal2 INTEGER NOT NULL,label INTEGER NOT NULL,similarity REAL NOT NULL,model TEXT NOT NULL,batch INTEGER,FOREIGN KEY(uri1,ordinal1) REFERENCES faces(uri,ordinal) ON DELETE CASCADE,FOREIGN KEY(uri2,ordinal2) REFERENCES faces(uri,ordinal) ON DELETE CASCADE,UNIQUE(uri1,ordinal1,uri2,ordinal2,label,model))")
        }
    }
    private fun transaction(action:()->Unit){db.beginTransaction();try{action();db.setTransactionSuccessful()}finally{db.endTransaction()}}
    fun policy():PeopleCalibration.Policy {
        val samples=buildList{db.rawQuery("SELECT uri1,uri2,label,similarity FROM correction_samples WHERE model=?",arrayOf(FaceVectors.MODEL)).use{while(it.moveToNext())add(PeopleCalibration.Sample(it.getString(0),it.getString(1),it.getInt(2),it.getFloat(3)))}}
        return PeopleCalibration.policy(samples)
    }
    fun feedback(a:GroupRules.Capsule,b:GroupRules.Capsule,label:Int){
        val pairs=a.prototypes.flatMap{x->b.prototypes.map{y->x to y}}
        val pair=pairs.filter{it.first.member.key.uri!=it.second.member.key.uri}.maxByOrNull{FaceVectors.cosine(it.first.vector,it.second.vector)}?:return
        sample(pair.first.member,pair.second,label,null)
    }
    private fun sample(member:GroupRules.Member,reference:GroupRules.Prototype,label:Int,batch:Long?){
        if(member.face.authority!="Anchor" || member.key.uri==reference.member.key.uri)return
        val vector=people.vector(member.key)?:return
        db.insertWithOnConflict("correction_samples",null,ContentValues().apply{put("uri1",member.key.uri);put("ordinal1",member.key.ordinal);put("uri2",reference.member.key.uri);put("ordinal2",reference.member.key.ordinal);put("label",label);put("similarity",FaceVectors.cosine(vector,reference.vector));put("model",FaceVectors.MODEL);put("batch",batch)},SQLiteDatabase.CONFLICT_IGNORE)
        db.execSQL("DELETE FROM correction_samples WHERE id NOT IN (SELECT id FROM correction_samples ORDER BY id DESC LIMIT 200)")
    }
    fun correct(keys:Set<GroupRules.Key>,target:Long?=null,create:Boolean=false,exclude:Boolean=false){
        require(keys.isNotEmpty() && listOf(target!=null,create,exclude).count{it}==1)
        transaction{
            val rows=people.members();val selected=rows.filter{it.key in keys};check(selected.size==keys.size){"Some selected faces are no longer available."}
            val roots=people.components(rows);val groups=people.capsules()
            val destination=target?.let{id->groups.firstOrNull{id in it.leaves}?:error("Destination group is no longer available.")}
            if(!exclude){
                check(selected.map{it.key.uri}.distinct().size==selected.size){"Select one face per photo for a person."}
                val occupied=rows.filter{it.key !in keys && it.status=="known" && it.person!=null && roots[it.person]==destination?.id}.map{it.key.uri}.toSet()
                check(selected.none{destination!=null && it.key.uri in occupied}){"That person already has a different face in one of these photos."}
            }
            val batch=db.rawQuery("SELECT MAX(COALESCE((SELECT MAX(batch) FROM face_edits),0),COALESCE((SELECT MAX(batch) FROM relations),0),COALESCE((SELECT MAX(batch) FROM correction_samples),0))+1",null).use{it.moveToFirst();it.getLong(0)}
            var newRoot=destination?.id
            for(member in selected){
                if(destination!=null && member.status=="known" && roots[member.person]==destination.id)continue
                val oldGroup=groups.firstOrNull{member.person in it.leaves}
                val newPerson=if(exclude)null else db.insertOrThrow("people",null,ContentValues().apply{put("label","")})
                db.insertOrThrow("face_edits",null,ContentValues().apply{put("batch",batch);put("uri",member.key.uri);put("ordinal",member.key.ordinal);put("person",member.person);put("suggested",member.suggested);put("status",member.status);put("score",member.score);put("reason",member.reason);put("model",GroupRules.VERSION);put("manual",if(member.manual)1 else 0);put("newperson",newPerson);put("active",1)})
                if(newPerson!=null){
                    if(newRoot==null)newRoot=newPerson else relation(newPerson,newRoot!!,"attach",batch)
                    oldGroup?.leaves?.forEach{relation(newPerson,it,"cannot",batch)}
                }
                val vector=people.vector(member.key)
                val oldReferences=oldGroup?.prototypes.orEmpty().filter{it.member.key !in keys && it.member.key.uri!=member.key.uri}
                oldReferences.maxByOrNull{reference->vector?.let{FaceVectors.cosine(it,reference.vector)}?:-1f}?.let{sample(member,it,0,batch)}
                destination?.prototypes?.filter{it.member.key.uri!=member.key.uri}?.maxByOrNull{reference->vector?.let{FaceVectors.cosine(it,reference.vector)}?:-1f}?.let{sample(member,it,1,batch)}
                db.insertWithOnConflict("membership",null,ContentValues().apply{put("uri",member.key.uri);put("ordinal",member.key.ordinal);put("person",newPerson);put("suggested",null as Long?);put("status",if(exclude)"excluded"else"known");put("score",1f);put("reason",if(exclude)"Not this person · user correction"else"Person confirmed by user");put("model",GroupRules.VERSION);put("manual",1)},SQLiteDatabase.CONFLICT_REPLACE).also{check(it!=-1L)}
            }
            db.execSQL("DELETE FROM face_edits WHERE batch NOT IN (SELECT DISTINCT batch FROM face_edits ORDER BY batch DESC LIMIT 10)")
        }
    }
    private fun relation(a:Long,b:Long,type:String,batch:Long){
        if(a==b)return
        db.insertOrThrow("relations",null,ContentValues().apply{put("a",minOf(a,b));put("b",maxOf(a,b));put("type",type);put("source","user");put("active",1);put("reason",if(type=="cannot")"Face correction: different person"else"Faces assigned by user");put("batch",batch)})
    }
    fun canUndo()=db.rawQuery("SELECT 1 FROM face_edits WHERE active=1 LIMIT 1",null).use{it.moveToFirst()}
    fun undo(){transaction{
        val batch=db.rawQuery("SELECT MAX(batch) FROM face_edits WHERE active=1",null).use{it.moveToFirst();if(it.isNull(0))return@transaction;it.getLong(0)}
        val currentMembers=people.members().associateBy{it.key}
        db.rawQuery("SELECT uri,ordinal,person,suggested,status,score,reason,model,manual,newperson FROM face_edits WHERE batch=? AND active=1",arrayOf(batch.toString())).use{c->while(c.moveToNext()){
            val key=GroupRules.Key(c.getString(0),c.getInt(1));val current=currentMembers[key]?:error("A corrected face is no longer available.")
            val expected=if(c.isNull(9))null else c.getLong(9);check(current.manual && current.person==expected){"This correction was changed later. Review it before undoing."}
            db.update("membership",ContentValues().apply{put("person",if(c.isNull(2))null else c.getLong(2));put("suggested",if(c.isNull(3))null else c.getLong(3));put("status",c.getString(4));put("score",c.getFloat(5));put("reason",c.getString(6));put("model",c.getString(7));put("manual",c.getInt(8))},"uri=? AND ordinal=?",arrayOf(key.uri,key.ordinal.toString()))
        }}
        db.execSQL("UPDATE relations SET active=0 WHERE batch=?",arrayOf(batch));db.execSQL("UPDATE face_edits SET active=0 WHERE batch=?",arrayOf(batch));db.delete("correction_samples","batch=?",arrayOf(batch.toString()))
    }}
}
