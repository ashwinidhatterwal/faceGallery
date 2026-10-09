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
    private var updating=false
    private lateinit var automatic:CheckBox
    private lateinit var contactMatching:CheckBox
    private lateinit var contactPermission:android.view.View
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
                if(updating)return@setOnCheckedChangeListener
                if(enabled){AutoPeople.resume(this@PrivacyActivity);AutoPeople.ensure(this@PrivacyActivity)}
                else {
                    AutoPeople.pause(this@PrivacyActivity)
                    if(FaceJobs.state.busy)startService(Intent(this@PrivacyActivity,FaceScanService::class.java).setAction(FaceScanService.PAUSE))
                }
            }
        }
        body.addView(automatic)
        body.addView(GalleryStyle.text(this,"Pause automatic recognition without deleting saved people. Turn it on again to resume when the phone is ready.",14f,GalleryStyle.muted(this)))
        contactMatching=CheckBox(this).apply{
            text="Match contact photos automatically";setTextColor(GalleryStyle.textColor(context));isChecked=ContactRecognition.enabled(this@PrivacyActivity)
            setOnCheckedChangeListener{_,enabled->ContactRecognition.setEnabled(this@PrivacyActivity,enabled)}
        }
        body.addView(contactMatching)
        body.addView(GalleryStyle.text(this,"With contacts access, clear portrait matches name people automatically, entirely on your phone. Saved names and corrections take priority. Missing or unclear contact photos leave grouping unchanged. Turn this off to remove cached contact face signatures; saved names and links remain editable.",14f,GalleryStyle.muted(this)))
        contactPermission=GalleryStyle.button(this,"Allow phone contacts"){requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS),PeopleNames.PERMISSION)}
        body.addView(contactPermission)
        body.addView(GalleryStyle.text(this,assets.open("PRIVACY.txt").bufferedReader().use{it.readText()},15f))
        body.addView(GalleryStyle.action(this,"settings","Manage permissions and app storage"){
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
        })
        root.addView(ScrollView(this).apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        Ui.insets(this,root);setContentView(root);Ui.back(this){finish()}
    }
    override fun onResume(){super.onResume();updating=true;automatic.isChecked=AutoPeople.enabled(this);contactMatching.isChecked=ContactRecognition.enabled(this);updating=false;contactPermission.visibility=if(ContactRecognition.allowed(this))android.view.View.GONE else android.view.View.VISIBLE}
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);if(code==PeopleNames.PERMISSION){ContactRecognition.invalidate(this);AutoPeople.ensure(this,1_000);contactPermission.visibility=if(ContactRecognition.allowed(this))android.view.View.GONE else android.view.View.VISIBLE}}
}
