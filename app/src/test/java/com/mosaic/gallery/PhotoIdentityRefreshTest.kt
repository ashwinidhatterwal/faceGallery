package com.mosaic.gallery

import android.Manifest
import android.app.*
import android.content.*
import android.database.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import android.widget.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.*
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PhotoIdentityRefreshTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun await(condition:()->Boolean){val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition() && System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.yield()};assertTrue(condition())}
    @Test fun acceptingAGroupRefreshesTheSheetAndOpensItWithoutRegrouping(){
        app.deleteDatabase("faces.db");GalleryData.invalidate();FaceJobs.publish(FaceJobs.State());Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        val file=File(app.cacheDir,"identity.jpg");val bmp=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);file.outputStream().use{bmp.compress(Bitmap.CompressFormat.JPEG,80,it)};bmp.recycle()
        val photos=(1..2).map{PhotoRecord(it.toLong(),Uri.parse("content://media/external/images/media/$it"),"$it.jpg",it*120000L,100,100,"Camera",0,"",1000)}
        ShadowContentResolver.registerProviderInternal("media",object:ContentProvider(){
            override fun onCreate()=true
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor=MatrixCursor(p!!).apply{photos.forEach{addRow(arrayOf<Any>(it.id,it.displayName,it.dateTakenMillis,1,100,100,"Camera",0,"",1))}}
            override fun openFile(uri:Uri,mode:String)=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
            override fun getType(uri:Uri)="image/jpeg"
            override fun insert(uri:Uri,v:ContentValues?):Uri?=null
            override fun delete(uri:Uri,s:String?,a:Array<out String>?)=0
            override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
        })
        var target=0L
        FaceStore(app).use{f->photos.forEach{f.save(it,listOf(FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,120f,.95f,"Anchor")));f.saveSignature(it.uri.toString(),0,FloatArray(128){if(it==0)1f else 0f})};val p=PeopleStore(f);photos.forEach{p.correct(setOf(GroupRules.Key(it.uri.toString(),0)),create=true)};target=p.members().first{it.key.uri==photos[0].uri.toString()}.person!!;p.rename(target,"Ankita")}
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val names=PeopleNames(controller.get());val sheet=PhotoPeopleSheet(controller.get(),names)
        fun field(name:String)=PhotoPeopleSheet::class.java.getDeclaredField(name).apply{isAccessible=true}.get(sheet)
        try{
            sheet.show(photos[1]);await{(field("caption") as TextView).text=="Tap a face. Hold to correct."}
            val action:(PeopleStore)->Unit={it.confirmIdentity(GroupRules.Key(photos[1].uri.toString(),0),target)}
            PhotoPeopleSheet::class.java.getDeclaredMethod("change",kotlin.jvm.functions.Function1::class.java,String::class.java).apply{isAccessible=true}.invoke(sheet,action,"Merged")
            await{ShadowToast.getTextOfLatestToast()=="Merged"}
            assertEquals("Tap a face. Hold to correct.",(field("caption") as TextView).text.toString())
            val row=field("row") as LinearLayout;val cell=row.getChildAt(0) as LinearLayout
            assertEquals("Ankita",(cell.getChildAt(1) as TextView).text.toString())
            cell.performClick();val next=Shadows.shadowOf(controller.get()).nextStartedActivity
            assertEquals(PeopleActivity::class.java.name,next.component!!.className);assertEquals(target,next.getLongExtra("person",-1))
            FaceStore(app).use{assertEquals(2,PeopleStore(it).capsules().single().photos.size)}
        }finally{sheet.close();names.close();controller.pause().stop().destroy();Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE);app.deleteDatabase("faces.db")}
    }
}
