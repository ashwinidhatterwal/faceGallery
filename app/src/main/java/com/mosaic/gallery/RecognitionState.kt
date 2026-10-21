package com.mosaic.gallery

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase

/** Work evidence is committed by the same SQLite transaction as face/identity edits. */
internal object RecognitionState {
    fun create(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE IF NOT EXISTS recognition_work(id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER NOT NULL,grouped INTEGER NOT NULL,policy INTEGER NOT NULL)")
        db.execSQL("INSERT OR IGNORE INTO recognition_work VALUES(1,0,-1,0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS recognition_repairs(stage TEXT PRIMARY KEY,generation INTEGER NOT NULL,cursor INTEGER NOT NULL,complete INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS recognition_refinement_plan(id INTEGER PRIMARY KEY CHECK(id=1),generation INTEGER NOT NULL,payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS recognition_contacts(token TEXT PRIMARY KEY,cursor INTEGER NOT NULL)")
        for(table in listOf("faces","embeddings","people","membership","relations","contact_hint_rejections","correction_samples")){
            val columns=buildList{db.rawQuery("PRAGMA table_info($table)",null).use{c->while(c.moveToNext())add(c.getString(1))}}
            if(columns.isEmpty())continue
            for(event in listOf("INSERT","UPDATE","DELETE")){
                val condition=if(event=="UPDATE")" WHEN "+columns.joinToString(" OR "){"OLD.$it IS NOT NEW.$it"}else""
                db.execSQL("CREATE TRIGGER IF NOT EXISTS recognition_${table}_${event.lowercase()} AFTER $event ON $table$condition BEGIN UPDATE recognition_work SET revision=revision+1 WHERE id=1; END")
            }
        }
    }
    fun revision(db:SQLiteDatabase)=DatabaseUtils.longForQuery(db,"SELECT revision FROM recognition_work WHERE id=1",null)
    fun dirty(db:SQLiteDatabase){db.execSQL("UPDATE recognition_work SET revision=revision+1 WHERE id=1")}
    fun needed(db:SQLiteDatabase)=DatabaseUtils.longForQuery(db,"SELECT revision!=grouped OR policy!=3 FROM recognition_work WHERE id=1",null)!=0L
    fun grouped(db:SQLiteDatabase,value:Long){db.execSQL("UPDATE recognition_work SET grouped=?,policy=3 WHERE id=1 AND revision=?",arrayOf(value,value))}
    data class Repair(val cursor:Long=0,val complete:Boolean=false)
    fun repair(db:SQLiteDatabase,stage:String):Repair=db.rawQuery("SELECT cursor,complete FROM recognition_repairs WHERE stage=? AND generation=?",arrayOf(stage,revision(db).toString())).use{if(it.moveToFirst())Repair(it.getLong(0),it.getInt(1)!=0)else Repair()}
    fun checkpoint(db:SQLiteDatabase,stage:String,cursor:Long,complete:Boolean){db.execSQL("INSERT OR REPLACE INTO recognition_repairs VALUES(?,?,?,?)",arrayOf<Any>(stage,revision(db),cursor,if(complete)1 else 0))}
}

/** Shared native availability is a worker condition, never a bad-image attempt. */
internal class DetectorUnavailable(message:String,cause:Throwable?=null):Exception(message,cause)
