package com.mosaic.gallery

import android.database.sqlite.SQLiteDatabase

/** Transactional UI revision. Checkpoint writes never invalidate the display snapshot. */
internal object PeopleRevision {
    fun create(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE IF NOT EXISTS people_ui_revision(id INTEGER PRIMARY KEY CHECK(id=1),value INTEGER NOT NULL)")
        db.execSQL("INSERT OR IGNORE INTO people_ui_revision(id,value) VALUES(1,0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS people_ui_sources(uri TEXT PRIMARY KEY,value INTEGER NOT NULL)")
        val tables=buildSet{db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'",null).use{while(it.moveToNext())add(it.getString(0))}}
        for(table in listOf("photos","faces","embeddings","people","membership","relations","contact_signatures","contact_hint_rejections","face_edits","correction_samples").filter{it in tables}){
            val columns=buildList{db.rawQuery("PRAGMA table_info($table)",null).use{while(it.moveToNext())add(it.getString(1))}}
            for(event in listOf("INSERT","UPDATE","DELETE")){
                val whenChanged=if(event=="UPDATE")" WHEN "+columns.joinToString(" OR "){"OLD.$it IS NOT NEW.$it"}else ""
                val source=if(table=="photos" && event=="DELETE")" DELETE FROM people_ui_sources WHERE uri=OLD.uri;"else if(table in setOf("photos","faces","embeddings") && "uri" in columns){val row=if(event=="DELETE")"OLD"else"NEW";" INSERT OR REPLACE INTO people_ui_sources(uri,value) VALUES($row.uri,(SELECT value FROM people_ui_revision WHERE id=1));"}else ""
                db.execSQL("CREATE TRIGGER IF NOT EXISTS people_ui_${table}_${event.lowercase()} AFTER $event ON $table$whenChanged BEGIN UPDATE people_ui_revision SET value=value+1 WHERE id=1;$source END")
            }
        }
    }
    fun sources(db:SQLiteDatabase):Map<String,Long> = buildMap{db.rawQuery("SELECT uri,value FROM people_ui_sources",null).use{while(it.moveToNext())put(it.getString(0),it.getLong(1))}}
    fun value(db:SQLiteDatabase)=android.database.DatabaseUtils.longForQuery(db,"SELECT value FROM people_ui_revision WHERE id=1",null).toString()
}
