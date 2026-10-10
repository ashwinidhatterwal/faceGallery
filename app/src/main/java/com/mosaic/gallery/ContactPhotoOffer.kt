package com.mosaic.gallery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.provider.ContactsContract
import android.view.Gravity
import android.widget.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs

/** Explicit user action only. Never replaces an existing contact portrait. */
internal class ContactPhotoOffer(private val activity:Activity) {
    companion object {
        const val PERMISSION=4203
        fun eligible(face:FaceObservation)=face.score>=.55f && face.sharpness>=8f && abs(face.yaw)<=40f && abs(face.pitch)<=30f
        fun crop(image:Bitmap,face:FaceObservation):Bitmap {
            val side=(maxOf((face.right-face.left)*image.width,(face.bottom-face.top)*image.height)*1.5f).toInt().coerceIn(1,minOf(image.width,image.height))
            val x=(((face.left+face.right)*image.width-side)/2).toInt().coerceIn(0,image.width-side)
            val y=(((face.top+face.bottom)*image.height-side)/2).toInt().coerceIn(0,image.height-side)
            val output=Bitmap.createBitmap(minOf(side,512),minOf(side,512),Bitmap.Config.ARGB_8888)
            Canvas(output).drawBitmap(image,Rect(x,y,x+side,y+side),Rect(0,0,output.width,output.height),Paint(Paint.FILTER_BITMAP_FLAG))
            return output
        }
    }
    private val worker=Executors.newSingleThreadExecutor()
    private var dialog:AlertDialog?=null
    private var bitmap:Bitmap?=null
    private var contact:ContactNames.Contact?=null
    @Volatile private var token=0
    @Volatile private var closed=false
    var requestingPermission=false;private set
    private var writing=false
    private var signal:android.os.CancellationSignal?=null

    private fun emptyId(contact:ContactNames.Contact):Long? {
        if(!ContactNames.valid(contact.lookup))return null
        return activity.contentResolver.query(Uri.parse(contact.lookup),arrayOf(ContactsContract.Contacts._ID,ContactsContract.Contacts.PHOTO_ID),null,null,null)?.use {
            if(it.moveToFirst() && it.isNull(1))it.getLong(0)else null
        }
    }
    fun offer(contact:ContactNames.Contact,key:GroupRules.Key?=null,group:Long?=null,singleFace:Boolean=false) {
        dismiss();val version=token;val cancel=android.os.CancellationSignal();signal=cancel
        worker.execute {
            val result=runCatching {
                if(emptyId(contact)==null)return@runCatching null
                val media=GalleryData.load(activity,cancel).photos
                val photos=media.map{it.uri.toString()}.toSet()
                val candidates=FaceStore(activity).use { f->PeopleStore(f).let { store->store.snapshot {
                    val stale=f.pending(media).map{it.uri.toString()}.toSet()
                    val rows=store.members();val roots=store.components(rows)
                    val source=group?:rows.firstOrNull{it.key==key}?.person
                    val root=source?.let{roots[it]?:it}
                    rows.filter{it.key.uri in photos && it.key.uri !in stale && (if(root==null || singleFace)it.key==key else it.status=="known" && it.person?.let{p->roots[p]?:p}==root) && eligible(it.face)}
                        .sortedWith(compareByDescending<GroupRules.Member>{it.face.score}.thenByDescending{it.face.sharpness}).take(8)
                }}}
                for(row in candidates) {
                    if(closed || version!=token)return@runCatching null
                    val image=runCatching{PhotoImages.decode(activity,Uri.parse(row.key.uri),4096,8_000_000)}.getOrNull()?:continue
                    try{val photo=crop(image,row.face);if(photo.width>=96)return@runCatching photo;photo.recycle()}finally{image.recycle()}
                }
                null
            }
            activity.runOnUiThread {
                val photo=result.getOrNull()
                if(closed || version!=token || activity.isFinishing || activity.isDestroyed){photo?.recycle();return@runOnUiThread}
                if(photo==null)return@runOnUiThread
                this.contact=contact;bitmap=photo
                val root=GalleryStyle.panelRoot(activity)
                root.addView(GalleryStyle.text(activity,"Add contact photo?",22f))
                root.addView(GalleryStyle.text(activity,"${contact.name} has no photo. Use this clear face from their gallery group? Your contacts account may sync the photo.",14f,GalleryStyle.muted(activity)))
                root.addView(ImageView(activity).apply{GalleryStyle.roundFace(this);setImageBitmap(photo);scaleType=ImageView.ScaleType.CENTER_CROP},LinearLayout.LayoutParams(GalleryStyle.dp(activity,96),GalleryStyle.dp(activity,96)).apply{gravity=Gravity.CENTER_HORIZONTAL;setMargins(0,16,0,16)})
                val actions=LinearLayout(activity)
                actions.addView(GalleryStyle.button(activity,"Not now"){dismiss()},LinearLayout.LayoutParams(0,GalleryStyle.dp(activity,48),1f))
                actions.addView(GalleryStyle.button(activity,"Use photo",true){
                    if(writing || requestingPermission)return@button
                    if(activity.checkSelfPermission(Manifest.permission.WRITE_CONTACTS)==PackageManager.PERMISSION_GRANTED)write()
                    else {requestingPermission=true;activity.requestPermissions(arrayOf(Manifest.permission.WRITE_CONTACTS),PERMISSION)}
                },LinearLayout.LayoutParams(0,GalleryStyle.dp(activity,48),1f))
                root.addView(actions)
                dialog=GalleryStyle.dialog(activity,root).apply{setOnDismissListener{if(dialog===this)dismiss()}}
            }
        }
    }
    fun permissionResult(code:Int):Boolean {
        if(code!=PERMISSION)return false
        requestingPermission=false
        if(activity.checkSelfPermission(Manifest.permission.WRITE_CONTACTS)==PackageManager.PERMISSION_GRANTED && dialog?.isShowing==true)write()
        else {Toast.makeText(activity,"Contact photo unchanged",Toast.LENGTH_SHORT).show();dismiss()}
        return true
    }
    private fun write() {
        val selected=contact?:return;val photo=bitmap?:return
        val bytes=ByteArrayOutputStream().use{check(photo.compress(Bitmap.CompressFormat.JPEG,95,it));it.toByteArray()}
        val version=token;writing=true;dialog?.setCancelable(false)
        worker.execute {
            val result=runCatching {
                check(!closed && version==token)
                val id=emptyId(selected)?:error("Contact already has a photo or is unavailable")
                val raw=activity.contentResolver.query(ContactsContract.RawContacts.CONTENT_URI,arrayOf(ContactsContract.RawContacts._ID),"contact_id=? AND deleted=0 AND raw_contact_is_read_only=0",arrayOf(id.toString()),"_id ASC")?.use{if(it.moveToFirst())it.getLong(0)else null}?:error("Contact cannot be edited")
                val operations=arrayListOf(
                    ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI,id)).withSelection("photo_id IS NULL",null).withExpectedCount(1).build(),
                    ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI,raw)).withSelection("contact_id=? AND deleted=0 AND raw_contact_is_read_only=0",arrayOf(id.toString())).withExpectedCount(1).build(),
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValue(ContactsContract.Data.RAW_CONTACT_ID,raw).withValue(ContactsContract.Data.MIMETYPE,ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE).withValue(ContactsContract.CommonDataKinds.Photo.PHOTO,bytes).withValue(ContactsContract.Data.IS_SUPER_PRIMARY,1).build())
                check(!closed && version==token)
                activity.contentResolver.applyBatch(ContactsContract.AUTHORITY,operations)
                ContactRecognition.invalidate(activity);AutoPeople.ensure(activity,1_000)
            }
            activity.runOnUiThread {
                if(closed || version!=token)return@runOnUiThread
                Toast.makeText(activity,if(result.isSuccess)"Contact photo saved"else"Could not add photo. Contact unchanged.",Toast.LENGTH_LONG).show();dismiss()
            }
        }
    }
    fun dismiss(){token++;signal?.cancel();signal=null;requestingPermission=false;writing=false;val old=dialog;dialog=null;old?.dismiss();bitmap?.recycle();bitmap=null;contact=null}
    fun close(){closed=true;dismiss();worker.shutdownNow()}
}
