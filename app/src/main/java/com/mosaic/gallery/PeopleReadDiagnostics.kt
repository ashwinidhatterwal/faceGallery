package com.mosaic.gallery

import android.content.Context
import android.os.CancellationSignal
import org.json.JSONObject

/** Bounded, local-only failure evidence. Never records media URIs, contact names or exception messages. */
object PeopleReadDiagnostics {
    fun record(context:Context,error:Throwable){
        val prefs=context.getSharedPreferences("people-read-diagnostics",0)
        prefs.edit().putInt("count",prefs.getInt("count",0)+1).putLong("time",System.currentTimeMillis())
            .putString("type",error.javaClass.name).putString("frames",error.stackTrace.take(8).joinToString("\n"){"${it.className}.${it.methodName}:${it.lineNumber}"}).apply()
    }
    fun read(context:Context):JSONObject=context.getSharedPreferences("people-read-diagnostics",0).let{
        JSONObject().put("failure_count",it.getInt("count",0)).put("last_time",it.getLong("time",0)).put("last_type",it.getString("type","")).put("last_frames",it.getString("frames",""))
    }
}
/** Provider resolution runs once per contact change and does not invalidate the recognition model. */
object ContactLinkSync {
    private var revision=0L;private var checked=-1L;private var access=""
    @Synchronized fun invalidate(){revision++}
    @Synchronized fun refresh(context:Context,signal:CancellationSignal){
        val scope=PeopleData.access(context)
        if(checked==revision && access==scope)return
        FaceStore(context).use{PeopleStore(it).syncContacts(context,signal)}
        checked=revision;access=scope
    }
}
