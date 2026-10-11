package com.mosaic.gallery

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*

/** One shared, focused editor. Bulk changes are deltas, preserving each photo's other tags. */
object TagEditor {
    fun content(activity:Activity,uris:Set<String>,done:()->Unit):View {
        val data=MediaTags.read(activity)
        val common=uris.map{data.media[it].orEmpty()}.reduceOrNull{a,b->a.intersect(b)}.orEmpty()
        val chosen=common.toMutableSet();val names=data.names.toMutableMap()
        val root=GalleryStyle.panelRoot(activity)
        val header=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        header.addView(GalleryStyle.text(activity,"Tags",20f),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(GalleryStyle.action(activity,"close","Close tags",compact=true,action=done));root.addView(header)
        val entry=EditText(activity).apply{
            hint="Add a tag";setSingleLine();textSize=16f;setTextColor(GalleryStyle.textColor(context));setHintTextColor(GalleryStyle.muted(context))
            setPadding(GalleryStyle.dp(context,16),0,GalleryStyle.dp(context,16),0)
            background=android.graphics.drawable.GradientDrawable().apply{setColor(GalleryStyle.surface(context));cornerRadius=GalleryStyle.dp(context,16).toFloat()}
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val chips=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val scroll=HorizontalScrollView(activity).apply{isHorizontalScrollBarEnabled=false;addView(chips)}
        fun render(){
            chips.removeAllViews()
            val query=MediaTags.key(entry.text.toString())
            for((key,name) in names.entries.sortedWith(compareByDescending<Map.Entry<String,String>>{it.key in chosen}.thenBy{it.value.lowercase()}).filter{query.isEmpty() || it.key.contains(query)}.take(24)){
                val chip=GalleryStyle.button(activity,(if(key in chosen)"✓ "else"")+name,key in chosen){if(!chosen.add(key))chosen.remove(key);entry.setText("");render()}.apply{minimumHeight=0;minHeight=0;textSize=13f;setPadding(GalleryStyle.dp(context,14),0,GalleryStyle.dp(context,14),0)}
                chips.addView(chip,LinearLayout.LayoutParams(-2,GalleryStyle.dp(activity,36)).apply{rightMargin=GalleryStyle.dp(activity,8)})
            }
            scroll.visibility=if(chips.childCount==0)View.GONE else View.VISIBLE
        }
        fun save(){
            val pending=entry.text.toString().trim()
            if(pending.length>40){entry.error="Use 40 characters or fewer";return}
            if(pending.isNotEmpty()){val key=MediaTags.key(pending);names.putIfAbsent(key,pending);chosen.add(key)}
            MediaTags.update(activity,uris,(chosen-common).mapNotNull{names[it]}.toSet(),common-chosen)
            done()
        }
        val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        row.addView(entry,LinearLayout.LayoutParams(0,GalleryStyle.dp(activity,52),1f))
        row.addView(GalleryStyle.action(activity,"check","Save tags",compact=true){save()},LinearLayout.LayoutParams(GalleryStyle.dp(activity,48),GalleryStyle.dp(activity,52)).apply{leftMargin=GalleryStyle.dp(activity,8)})
        root.addView(row)
        root.addView(scroll,LinearLayout.LayoutParams(-1,-2).apply{topMargin=GalleryStyle.dp(activity,12)})
        entry.addTextChangedListener(object:android.text.TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){render()}
            override fun afterTextChanged(s:android.text.Editable?){}
        })
        entry.setOnEditorActionListener{_,action,_->if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){save();true}else false}
        render()
        root.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:View){entry.post{if(entry.isAttachedToWindow){entry.requestFocus();(activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(entry,InputMethodManager.SHOW_IMPLICIT)}}}
            override fun onViewDetachedFromWindow(v:View){}
        })
        return root
    }
    fun show(activity:Activity,uris:Set<String>,changed:()->Unit={}) {
        if(uris.isEmpty())return
        var dialog:AlertDialog?=null
        val root=content(activity,uris){dialog?.dismiss();changed()}
        dialog=AlertDialog.Builder(activity).setView(root).create()
        dialog.show();dialog.window?.apply{
            setBackgroundDrawableResource(android.R.color.transparent);setGravity(Gravity.BOTTOM)
            setLayout(activity.resources.displayMetrics.widthPixels-GalleryStyle.dp(activity,24),-2)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
}
