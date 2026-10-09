package com.mosaic.gallery

import android.app.Activity
import android.app.AlertDialog
import android.content.Context

/** Separate from Android permissions: an upgrade must not silently authorize background use. */
object RecognitionConsent {
    private fun prefs(c:Context)=c.getSharedPreferences("recognition-consent",0)
    fun decided(c:Context)=prefs(c).getBoolean("decided",false)
    fun allowed(c:Context)=prefs(c).getBoolean("accepted",false)
    fun accept(c:Context){prefs(c).edit().putBoolean("decided",true).putBoolean("accepted",true).commit()}
    fun decline(c:Context){prefs(c).edit().putBoolean("decided",true).putBoolean("accepted",false).commit();AutoPeople.pause(c)}
    fun request(activity:Activity,accepted:()->Unit,declined:()->Unit={}) {
        if(allowed(activity)){accepted();return}
        AlertDialog.Builder(activity)
            .setTitle("Organise people on your phone?")
            .setMessage("Face Gallery uses permitted photos to detect faces and saves face signatures, people groups and names on this phone. Recognition can continue when the app is closed, while battery and temperature allow it.\n\nIf you allow optional contacts access, it also reads contact names and portrait photos to name matching groups locally. Photos, contacts and face signatures are not uploaded to the developer. Google's face detector may handle technical SDK diagnostics as described in Privacy.\n\nYou can correct matches, pause background recognition and clear face results in Settings. Choose Browse only to use the gallery without automatic recognition or contact matching.")
            .setPositiveButton("Enable people grouping"){_,_->accept(activity);accepted()}
            .setNegativeButton("Browse only"){_,_->decline(activity);declined()}
            .setOnCancelListener{declined()}
            .show().setCanceledOnTouchOutside(false)
    }
}
