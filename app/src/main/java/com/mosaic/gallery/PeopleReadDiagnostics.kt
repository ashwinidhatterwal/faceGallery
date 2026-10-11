package com.mosaic.gallery

import android.content.Context
import android.os.CancellationSignal
import org.json.JSONObject

/** Bounded, local-only failure evidence. Never records media URIs, contact names or exception messages. */
object PeopleReadDiagnostics {
    private val metricKeys=setOf("snapshot_builds","snapshot_reuses","snapshot_restores","snapshot_saves","reference_builds","reference_reuses")
    @Synchronized fun metric(context:Context,name:String,amount:Int=1){
        require(name in metricKeys && amount>0)
        val prefs=context.getSharedPreferences("people-read-diagnostics",0);prefs.edit().putInt(name,prefs.getInt(name,0)+amount).apply()
    }
    fun record(context:Context,error:Throwable){
        val prefs=context.getSharedPreferences("people-read-diagnostics",0)
        prefs.edit().putInt("count",prefs.getInt("count",0)+1).putLong("time",System.currentTimeMillis())
            .putString("type",error.javaClass.name).putString("frames",error.stackTrace.take(8).joinToString("\n"){"${it.className}.${it.methodName}:${it.lineNumber}"}).apply()
    }
    fun read(context:Context):JSONObject=context.getSharedPreferences("people-read-diagnostics",0).let{
        JSONObject().put("failure_count",it.getInt("count",0)).put("last_time",it.getLong("time",0)).put("last_type",it.getString("type","")).put("last_frames",it.getString("frames","")).put("snapshot_builds",it.getInt("snapshot_builds",0)).put("snapshot_reuses",it.getInt("snapshot_reuses",0)).put("snapshot_restores",it.getInt("snapshot_restores",0)).put("snapshot_saves",it.getInt("snapshot_saves",0)).put("reference_builds",it.getInt("reference_builds",0)).put("reference_reuses",it.getInt("reference_reuses",0))
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
