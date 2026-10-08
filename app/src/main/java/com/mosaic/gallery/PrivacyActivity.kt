package com.mosaic.gallery

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.CheckBox

/** Bundled policy is readable offline and never opens an embedded web session. */
class PrivacyActivity:Activity() {
    private lateinit var automatic:CheckBox
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(GalleryStyle.canvas(context))}
        val header=GalleryStyle.bar(this)
        header.addView(GalleryStyle.action(this,"back","Back",compact=true){finish()})
        header.addView(GalleryStyle.text(this,"Privacy",30f),LinearLayout.LayoutParams(0,-2,1f))
        root.addView(header)
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;val pad=(20*resources.displayMetrics.density).toInt();setPadding(pad,pad,pad,pad)}
        automatic=CheckBox(this).apply{
            text="Background recognition";setTextColor(GalleryStyle.textColor(context))
            isChecked=AutoPeople.enabled(this@PrivacyActivity)
            setOnCheckedChangeListener{_,enabled->
                if(enabled){AutoPeople.resume(this@PrivacyActivity);AutoPeople.ensure(this@PrivacyActivity)}
                else {
                    AutoPeople.pause(this@PrivacyActivity)
                    if(FaceJobs.state.busy)startService(Intent(this@PrivacyActivity,FaceScanService::class.java).setAction(FaceScanService.PAUSE))
                }
            }
        }
        body.addView(automatic)
        body.addView(GalleryStyle.text(this,"Pause automatic recognition without deleting saved people. Turn it on again to resume when the phone is ready.",14f,GalleryStyle.muted(this)))
        body.addView(GalleryStyle.text(this,assets.open("PRIVACY.txt").bufferedReader().use{it.readText()},15f))
        body.addView(GalleryStyle.action(this,"settings","Manage permissions and app storage"){
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
        })
        root.addView(ScrollView(this).apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        Ui.insets(this,root);setContentView(root);Ui.back(this){finish()}
    }
    override fun onResume(){super.onResume();automatic.isChecked=AutoPeople.enabled(this)}
}
