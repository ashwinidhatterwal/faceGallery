package com.mosaic.gallery

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.*

/** Shared editor for one photo or a selected set, including people folders and search results. */
object TagEditor {
    fun content(activity:Activity,uris:Set<String>,done:()->Unit):View {
        val data=MediaTags.read(activity)
        val common=uris.map{data.media[it].orEmpty()}.reduceOrNull{a,b->a.intersect(b)}.orEmpty()
        val chosen=common.toMutableSet();val names=data.names.toMutableMap()
        val root=GalleryStyle.panelRoot(activity)
        val header=GalleryStyle.bar(activity)
        header.addView(GalleryStyle.text(activity,if(uris.size==1)"Tags"else"Tag ${uris.size} items",22f),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(GalleryStyle.action(activity,"close","Close tags",compact=true,action=done));root.addView(header)
        root.addView(GalleryStyle.text(activity,"Add a tag or choose one below. Tags appear in Albums.",13f,GalleryStyle.muted(activity)))
        val entry=EditText(activity).apply{hint="New tag";setSingleLine();setTextColor(GalleryStyle.textColor(context));setHintTextColor(GalleryStyle.muted(context));maxLines=1}
        val chips=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        fun render(){
            chips.removeAllViews()
            for((key,name) in names.entries.sortedBy{it.value.lowercase()}){
                val chip=GalleryStyle.button(activity,(if(key in chosen)"✓ "else"＋ ")+name,key in chosen){if(!chosen.add(key))chosen.remove(key);render()}
                chips.addView(chip,LinearLayout.LayoutParams(-1,GalleryStyle.dp(activity,44)).apply{topMargin=GalleryStyle.dp(activity,6)})
            }
        }
        val row=GalleryStyle.bar(activity);row.addView(entry,LinearLayout.LayoutParams(0,GalleryStyle.dp(activity,48),1f))
        row.addView(GalleryStyle.button(activity,"Add"){
            val name=entry.text.toString().trim();if(name.isBlank())return@button
            if(name.length>40){entry.error="Use 40 characters or fewer";return@button}
            val key=MediaTags.key(name);names.putIfAbsent(key,name);chosen.add(key);entry.setText("");render()
        });root.addView(row)
        root.addView(ScrollView(activity).apply{addView(chips)},LinearLayout.LayoutParams(-1,0,1f));render()
        val place=EditText(activity).apply{hint="Place (optional)";setSingleLine();setTextColor(GalleryStyle.textColor(context));setHintTextColor(GalleryStyle.muted(context));if(uris.size==1)setText(data.places[uris.first()].orEmpty())}
        root.addView(place)
        root.addView(GalleryStyle.button(activity,"Save tags",true){
            val pending=entry.text.toString().trim()
            if(pending.length>40){entry.error="Use 40 characters or fewer";return@button}
            if(pending.isNotEmpty()){val key=MediaTags.key(pending);names.putIfAbsent(key,pending);chosen.add(key)}
            MediaTags.update(activity,uris,(chosen-common).mapNotNull{names[it]}.toSet(),common-chosen,
                if(uris.size==1 || place.text.isNotBlank())place.text.toString()else null)
            done()
        },LinearLayout.LayoutParams(-1,GalleryStyle.dp(activity,48)))
        return root
    }
    fun show(activity:Activity,uris:Set<String>,changed:()->Unit={}) {
        if(uris.isEmpty())return
        var dialog:AlertDialog?=null
        val root=content(activity,uris){dialog?.dismiss();changed()}
        dialog=AlertDialog.Builder(activity).setView(root).create()
        dialog.show();dialog.window?.apply{setBackgroundDrawableResource(android.R.color.transparent);setGravity(Gravity.BOTTOM);setLayout(-1,(activity.resources.displayMetrics.heightPixels*.62f).toInt());setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)}
    }
}
