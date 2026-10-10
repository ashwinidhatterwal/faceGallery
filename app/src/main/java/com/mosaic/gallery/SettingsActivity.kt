package com.mosaic.gallery

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*

/** Stable settings destination; specialist tools stay inside Recognition. */
class SettingsActivity:Activity() {
    private val contactSync by lazy{ContactPhotoSync(this)}
    override fun onStop(){contactSync.dismiss();super.onStop()}
    override fun onDestroy(){contactSync.close();super.onDestroy()}
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(GalleryStyle.canvas(context))}
        val header=GalleryStyle.bar(this);header.addView(GalleryStyle.action(this,"back","Back",compact=true){finish()});header.addView(GalleryStyle.text(this,"Settings",30f),LinearLayout.LayoutParams(0,-2,1f));root.addView(header)
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(GalleryStyle.dp(context,16),GalleryStyle.dp(context,12),GalleryStyle.dp(context,16),GalleryStyle.dp(context,24))}
        fun section(title:String,items:List<GalleryMenu.Action>){
            body.addView(GalleryStyle.text(this,title,13f,GalleryStyle.muted(this)).apply{setPadding(GalleryStyle.dp(context,12),GalleryStyle.dp(context,20),0,GalleryStyle.dp(context,8))})
            val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=android.graphics.drawable.GradientDrawable().apply{setColor(GalleryStyle.surface(context));cornerRadius=GalleryStyle.dp(context,20).toFloat()}}
            items.forEach{panel.addView(GalleryMenu.row(this,it))};body.addView(panel)
        }
        section("People",listOf(GalleryMenu.Action("info","Contact diagnostics"){startActivity(Intent(this,ContactDiagnosticsActivity::class.java))},GalleryMenu.Action("refresh","Sync contact photos"){contactSync.show()},GalleryMenu.Action("personAdd","Recognition and contacts"){startActivity(Intent(this,PrivacyActivity::class.java))},GalleryMenu.Action("personAdd","Manage people"){startActivity(Intent(this,PeopleActivity::class.java).putExtra("recognitionOptions",true))},GalleryMenu.Action("adjust","Recognition tools"){startActivity(Intent(this,FaceScanActivity::class.java))}))
        section("Library",listOf(GalleryMenu.Action("photo","Photo and video access"){startActivity(Intent(this,MainActivity::class.java).putExtra("requestMedia",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))},GalleryMenu.Action("settings","Permissions"){startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))},GalleryMenu.Action("info","About Face Gallery"){GalleryStyle.dialog(this,GalleryStyle.panelRoot(this).apply{addView(GalleryStyle.text(this@SettingsActivity,"Face Gallery",24f));addView(GalleryStyle.text(this@SettingsActivity,"Photos, videos and people. Private, on your phone.\nVersion ${packageManager.getPackageInfo(packageName,0).versionName}",15f));addView(GalleryStyle.text(this@SettingsActivity,"Cloud-only and private app files are not included. Video formats depend on your device.",14f,GalleryStyle.muted(this@SettingsActivity)))})}))
        root.addView(ScrollView(this).apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f));Ui.insets(this,root);setContentView(root);Ui.back(this){finish()}
    }
}
