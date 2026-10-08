package com.mosaic.gallery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.util.concurrent.Executors

class PhotoEditorActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var preview: CropPhotoView
    private lateinit var status: TextView
    private lateinit var panel: LinearLayout
    private lateinit var tabs: LinearLayout
    private var base: Bitmap? = null
    private var degrees = 0f
    private var busy = false
    private var loaded = false
    private var mode = "Crop"
    private var presetName = "Custom"
    private var exportPercent=100
    private var exportQuality=95
    private var exportDialog:ExportOptionsDialog?=null
    private data class Edit(val degrees:Float,val crop:CropBounds,val ratio:Float?,val preset:String,val adjustments:PhotoAdjustments)
    private val history=mutableListOf<Edit>()
    private var cursor=0
    private fun snapshot()=Edit(degrees,preview.crop,preview.ratio,presetName,preview.adjustments)
    private fun commit(){if(!loaded||busy)return;val edit=snapshot();if(history.getOrNull(cursor)==edit)return;while(history.size>cursor+1)history.removeAt(history.lastIndex);history.add(edit);if(history.size>30)history.removeAt(0);cursor=history.lastIndex}
    private fun restore(delta:Int){if(busy||!loaded)return;val target=cursor+delta;if(target !in history.indices)return;cursor=target;val edit=history[target];degrees=edit.degrees;rotatePreview();preview.crop=edit.crop;preview.ratio=edit.ratio;presetName=edit.preset;preview.adjustments=edit.adjustments;renderPanel()}
    private fun rotatePreview(){preview.orientationDegrees=degrees;val old=preview.bitmap;preview.bitmap=PhotoImages.rotateDegrees(base!!,degrees);if(old!==base&&old!==preview.bitmap)old?.recycle();dimensions()}
    private fun dimensions(){status.visibility=android.view.View.GONE}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);val uri=intent.data?:run{finish();return}
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(GalleryStyle.canvas(this@PhotoEditorActivity))}
        val top=GalleryStyle.bar(this)
        GalleryStyle.add(top,GalleryStyle.action(this,"close","Cancel",compact=true){if(!busy)finish()})
        top.addView(LinearLayout(this).apply{gravity=Gravity.CENTER;addView(GalleryStyle.text(context,"Resolution",16f));addView(ImageView(context).apply{setImageDrawable(GalleryStyle.icon(context,"down"))},LinearLayout.LayoutParams(GalleryStyle.dp(context,18),GalleryStyle.dp(context,18)).apply{leftMargin=GalleryStyle.dp(context,4)});setOnClickListener{showExportOptions()}},LinearLayout.LayoutParams(0,GalleryStyle.dp(this,48),2f))
        GalleryStyle.add(top,GalleryStyle.action(this,"check","Save copy",selected=true,compact=true){requestSave()})
        top.addView(GalleryStyle.action(this,"info","Photo details",compact=true){PhotoDetails.show(this,PhotoRecord(0,uri,intent.getStringExtra("name").orEmpty(),0,0,0))})
        root.addView(top);status=GalleryStyle.text(this,"Loading…",13f).apply{gravity=Gravity.CENTER;setPadding(0,12,0,12)};root.addView(status)
        preview=CropPhotoView(this).apply{
            onFinished={commit()}
            onRotationFinished={delta->if(loaded&&!busy){degrees=(degrees+delta)%360f;rotatePreview();presetName="Custom";preview.preset(null);commit();renderPanel()}}
        }
        root.addView(preview,LinearLayout.LayoutParams(-1,0,1f))
        val tools=GalleryStyle.bar(this)
        GalleryStyle.add(tools,GalleryStyle.action(this,"undo","Undo"){restore(-1)})
        GalleryStyle.add(tools,GalleryStyle.action(this,"redo","Redo"){restore(1)})
        GalleryStyle.add(tools,GalleryStyle.action(this,"rotate","Rotate 90°"){if(loaded&&!busy){degrees=(degrees+90f)%360f;rotatePreview();preview.preset(if(presetName=="Original")preview.bitmap!!.width.toFloat()/preview.bitmap!!.height else preview.ratio);commit()}})
        root.addView(GalleryStyle.divider(this));root.addView(tools)
        panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(GalleryStyle.surface(this@PhotoEditorActivity));setPadding(6,2,6,2)};root.addView(panel)
        tabs=GalleryStyle.bar(this);root.addView(GalleryStyle.divider(this));root.addView(tabs)
        Ui.insets(this,root);setContentView(root);Ui.back(this){if(!busy)finish()};renderPanel()
        worker.execute{val result=runCatching{PhotoImages.decode(this,uri,1600)};runOnUiThread{if(isDestroyed){result.getOrNull()?.recycle();return@runOnUiThread};result.onSuccess{base=it;preview.bitmap=it;loaded=true;history.add(snapshot());dimensions()}.onFailure{status.text="Could not open this photo for editing."}}}
    }
    private fun card(label:String,selected:Boolean,action:()->Unit)=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
        val color=if(selected)GalleryStyle.accent(this@PhotoEditorActivity) else GalleryStyle.iconColor(this@PhotoEditorActivity)
        background=GradientDrawable().apply{setColor(GalleryStyle.panel(this@PhotoEditorActivity));cornerRadius=GalleryStyle.dp(this@PhotoEditorActivity,14).toFloat();if(selected)setStroke(GalleryStyle.dp(this@PhotoEditorActivity,2),GalleryStyle.accent(this@PhotoEditorActivity))}
        val icon=if(mode=="Filters")"filters"else when(label){"Custom"->"custom";"Original"->"original";"1:1"->"ratioSquare";"4:3"->"ratioLandscape";else->"ratioPortrait"}
        addView(ImageView(this@PhotoEditorActivity).apply{setImageDrawable(GalleryStyle.icon(context,icon,color))},LinearLayout.LayoutParams(GalleryStyle.dp(context,24),GalleryStyle.dp(context,24)))
        addView(GalleryStyle.text(context,label,13f,color).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,GalleryStyle.dp(context,20)).apply{topMargin=GalleryStyle.dp(context,5)})
        contentDescription=label;setOnClickListener{if(loaded&&!busy)action()}
    }
    private fun renderPanel(){
        panel.removeAllViews();tabs.removeAllViews()
        listOf("Crop" to "crop","Adjust" to "adjust","Filters" to "filters").forEach{(name,icon)->GalleryStyle.add(tabs,GalleryStyle.action(this,icon,name,mode==name){if(!busy){mode=name;renderPanel()}})}
        if(mode=="Crop"||mode=="Filters"){
            val row=LinearLayout(this);val scroll=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(row)}
            val names=if(mode=="Crop")listOf("Custom","Original","1:1","3:4","4:3","9:16")else listOf("Original","B&W","Warm","Cool")
            names.forEach{name->row.addView(card(name,if(mode=="Crop")presetName==name else preview.adjustments.filter==name){
                if(mode=="Crop"){presetName=name;preview.preset(when(name){"Original"->preview.bitmap?.let{it.width.toFloat()/it.height};"1:1"->1f;"3:4"->.75f;"4:3"->4f/3;"9:16"->9f/16;else->null})}
                else preview.adjustments=preview.adjustments.copy(filter=name)
                commit();renderPanel()
            },LinearLayout.LayoutParams(GalleryStyle.dp(this,78),GalleryStyle.dp(this,64)).apply{setMargins(4,3,4,3)})};panel.addView(scroll)
        }else{
            fun slider(name:String,value:Float,max:Float,changed:(Float)->Unit){
                val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(16,0,16,0)}
                row.addView(GalleryStyle.text(this,name,12f),LinearLayout.LayoutParams(GalleryStyle.dp(this,76),-2))
                val bar=SeekBar(this).apply{this.max=200;progress=(value/max*200).toInt();setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(b:SeekBar,p:Int,user:Boolean){if(user&&loaded&&!busy)changed(p*max/200)}
                    override fun onStartTrackingTouch(b:SeekBar){}
                    override fun onStopTrackingTouch(b:SeekBar){commit()}
                })};row.addView(bar,LinearLayout.LayoutParams(0,GalleryStyle.dp(this,36),1f));panel.addView(row)
            }
            slider("Brightness",preview.adjustments.brightness+.5f,1f){preview.adjustments=preview.adjustments.copy(brightness=it-.5f)}
            slider("Contrast",preview.adjustments.contrast,2f){preview.adjustments=preview.adjustments.copy(contrast=it)}
            slider("Saturation",preview.adjustments.saturation,2f){preview.adjustments=preview.adjustments.copy(saturation=it)}
        }
    }
    private fun showExportOptions(){
        if(!loaded||busy)return
        val edit=snapshot()
        exportDialog=ExportOptionsDialog(this,exportPercent,exportQuality,
            {PhotoImages.renderCopy(this,intent.data!!,edit.degrees,edit.crop,edit.adjustments)}){percent,quality->exportPercent=percent;exportQuality=quality}.also{it.show()}
    }
    private fun requestSave(){
        if(!loaded||busy)return
        if(Build.VERSION.SDK_INT<=28&&checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),2002);return}
        busy=true;preview.isEnabled=false;requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED;status.visibility=android.view.View.VISIBLE;status.text="Saving edited copy…"
        val edit=snapshot();val percent=exportPercent;val quality=exportQuality
        worker.execute{val result=runCatching{PhotoImages.saveCopy(this,intent.data!!,intent.getStringExtra("name").orEmpty(),edit.degrees,edit.crop,edit.adjustments,percent,quality)};runOnUiThread{
            if(isDestroyed)return@runOnUiThread;busy=false;requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            result.onSuccess{Toast.makeText(this,"Saved copy · ${it.second}",Toast.LENGTH_LONG).show();setResult(RESULT_OK);finish()}.onFailure{status.text="Could not save the copy. Check storage and photo access.";preview.isEnabled=true}
        }}
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);if(code==2002&&results.firstOrNull()==PackageManager.PERMISSION_GRANTED)requestSave()}
    @android.annotation.SuppressLint("GestureBackNavigation") @Deprecated("Legacy Android back navigation") override fun onBackPressed(){if(!busy)finish()}
    override fun onDestroy(){exportDialog?.dismiss();if(preview.bitmap!==base)preview.bitmap?.recycle();preview.bitmap=null;base?.recycle();base=null;worker.shutdownNow();super.onDestroy()}
}
