package com.mosaic.gallery

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.widget.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="xhdpi")
class FluidPeopleUiTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val face=FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,120f,.95f,"Anchor")
    private fun field(target:Any,name:String)=target.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(target)
    private fun find(view:View,label:String):View? {if(view.contentDescription==label)return view;if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i),label)?.let{return it};return null}
    private fun layout(view:View,w:Int=1080,h:Int=1920){view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)}
    private fun await(condition:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition() && System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150));Thread.yield()};assertTrue(condition())}
    @Before fun reset(){app.deleteDatabase("faces.db");FaceJobs.publish(FaceJobs.State());GalleryData.invalidate();Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS,Manifest.permission.READ_MEDIA_IMAGES)}
    @After fun cleanup(){app.deleteDatabase("faces.db");Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS,Manifest.permission.READ_MEDIA_IMAGES)}
    @Test fun customNameSavesWithoutContactAccess(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val names=PeopleNames(c.get());var saved:ContactNames.Choice?=null
        names.show(""){saved=it};val dialog=field(names,"dialog") as android.app.AlertDialog
        (field(names,"input") as EditText).setText("My custom name");find(dialog.window!!.decorView,"Save")!!.performClick()
        assertEquals(ContactNames.Choice("My custom name",null),saved);assertFalse(names.requestingPermission);names.close();c.destroy()
    }
    @Test fun blankNameShowsContactsAndSelectionCanBeSaved(){
        val provider=PeopleProfilesTest.Contacts();ShadowContentResolver.registerProviderInternal("com.android.contacts",provider)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        assertEquals(2,ContactNames.read(app,"").size);assertEquals(android.provider.ContactsContract.Contacts.CONTENT_URI,provider.uri)
        val c=Robolectric.buildActivity(Activity::class.java).setup();val names=PeopleNames(c.get());var saved:ContactNames.Choice?=null
        names.show(""){saved=it};await{(field(names,"list") as? ListView)?.adapter?.count==2}
        val list=field(names,"list") as ListView;list.performItemClick(list.adapter.getView(0,null,list),0,0)
        val dialog=field(names,"dialog") as android.app.AlertDialog;find(dialog.window!!.decorView,"Save")!!.performClick()
        assertEquals("Ashwini",saved?.name);assertTrue(ContactNames.valid(saved!!.contact!!.lookup));names.close();c.destroy()
    }
    @Test fun embeddedFacesOpenUnnamedGroupAndNeverRecycleViewerBitmap(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val host=FrameLayout(c.get());c.get().setContentView(host);layout(host)
        val names=PeopleNames(c.get());var dismissed=false;val sheet=PhotoPeopleSheet(c.get(),names,host){dismissed=true}
        val bitmap=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888);val photo=PhotoRecord(1,Uri.parse("content://fluid/1"),"1.jpg",120000,400,300)
        sheet.show(photo,bitmap);assertNull(field(sheet,"dialog"));assertTrue(sheet.isShowing)
        val member=GroupRules.Member(GroupRules.Key(photo.uri.toString(),0),face,120000,7,null,"known",1f,"",true)
        val data=listOf(PhotoPeople.Face(member,null,"",null,null,identity=7))
        PhotoPeopleSheet::class.java.getDeclaredMethod("render",List::class.java).apply{isAccessible=true}.invoke(sheet,data)
        (field(sheet,"row") as LinearLayout).getChildAt(0).performClick()
        assertEquals(7L,Shadows.shadowOf(c.get()).nextStartedActivity.getLongExtra("person",-1));assertTrue(dismissed);assertFalse(bitmap.isRecycled)
        sheet.close();names.close();bitmap.recycle();c.destroy()
    }
    @Test fun cachedPreviewSurvivesGraphRefreshButNeverPermissionOrMediaChanges(){
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES);val cache=PeopleCache<String>();cache.put(app,PeopleData.version,"profiles")
        PeopleData.changed();assertNull(cache.get(app));assertEquals("profiles",cache.preview(app))
        GalleryData.invalidate();assertNull(cache.preview(app));cache.put(app,PeopleData.version,"new")
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES);assertNull(cache.preview(app))
    }
    @Test fun splitPanelAndPhotoShareBoundsAndBackClosesPanelFirst(){
        val c=Robolectric.buildActivity(PhotoActivity::class.java,Intent(app,PhotoActivity::class.java).setData(Uri.parse("content://fluid/2"))).create().start().visible()
        val activity=c.get();val root=field(activity,"viewerRoot") as View;layout(root)
        PhotoActivity::class.java.getDeclaredMethod("showPeople").apply{isAccessible=true}.invoke(activity)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));layout(root);root.viewTreeObserver.dispatchOnGlobalLayout();layout(root)
        val pager=field(activity,"pager") as View;val panel=field(activity,"peopleHost") as View
        assertEquals(View.VISIBLE,panel.visibility);assertEquals(panel.top.toFloat(),pager.bottom.toFloat(),2f);assertTrue(panel.height>0)
        PhotoActivity::class.java.getDeclaredMethod("closePhoto").apply{isAccessible=true}.invoke(activity)
        assertFalse(activity.isFinishing);assertFalse((field(activity,"peopleSheet") as PhotoPeopleSheet).isShowing)
        c.stop().destroy()
    }
    @Test fun pinchWithAngularDriftKeepsRotationAndFocusStable(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val view=ZoomPhotoView(c.get());c.get().setContentView(view);layout(view,800,600)
        view.show(Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888));val before=FloatArray(9);view.imageMatrix.getValues(before)
        val inverse=Matrix();view.imageMatrix.invert(inverse);val start=floatArrayOf(300f,220f);inverse.mapPoints(start)
        val time=SystemClock.uptimeMillis();var tick=0L
        fun send(action:Int,points:List<Pair<Float,Float>>){tick+=32
            val props=points.indices.map{MotionEvent.PointerProperties().apply{id=it;toolType=MotionEvent.TOOL_TYPE_FINGER}}.toTypedArray()
            val coords=points.map{MotionEvent.PointerCoords().apply{x=it.first;y=it.second;pressure=1f;size=1f}}.toTypedArray()
            val event=MotionEvent.obtain(time,time+tick,action,points.size,props,coords,0,0,1f,1f,0,0,0,0);view.dispatchTouchEvent(event);event.recycle()
        }
        send(MotionEvent.ACTION_DOWN,listOf(200f to 220f));send(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(200f to 220f,400f to 220f))
        for(span in listOf(120f,160f,200f,240f))send(MotionEvent.ACTION_MOVE,listOf(300f-span to 220f-span*.1f,300f+span to 220f+span*.1f))
        val after=FloatArray(9);view.imageMatrix.getValues(after);assertTrue(after[0]>before[0]);assertEquals(0f,view.rotationDegrees,0f)
        view.imageMatrix.invert(inverse);val end=floatArrayOf(300f,220f);inverse.mapPoints(end);assertArrayEquals(start,end,1f)
        c.destroy()
    }
    @Test fun warmedPeopleSearchShowsProfilesWithoutLoadingFrame(){
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        val photos=(1..2).map{PhotoRecord(it.toLong(),Uri.parse("content://fluid/warm/$it"),"$it.jpg",it*120000L,400,300)}
        FaceStore(app).use{f->photos.forEach{f.save(it,listOf(face));f.saveSignature(it.uri.toString(),0,FloatArray(128){i->if(i==0)1f else 0f})};val p=PeopleStore(f);p.correct(photos.map{GroupRules.Key(it.uri.toString(),0)}.toSet(),create=true);p.rename(p.capsules().single().id,"Saved person")}
        GalleryData.remember(app,GalleryRepository.Result(photos,0));PeopleActivity.warm(app,photos)
        val cache=PeopleActivity::class.java.getDeclaredField("cache").apply{isAccessible=true}.get(null) as PeopleCache<*>
        await{cache.get(app)!=null}
        val c=Robolectric.buildActivity(PeopleActivity::class.java,Intent(app,PeopleActivity::class.java).putExtra("search",true)).create().start().resume()
        assertFalse((field(c.get(),"caption") as TextView).text.toString().contains("Loading"));assertTrue((field(c.get(),"grid") as androidx.recyclerview.widget.RecyclerView).adapter!!.itemCount>0)
        c.pause().stop().destroy()
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun namePickerNativePreviewKeepsSaveVisible(){
        ShadowContentResolver.registerProviderInternal("com.android.contacts",PeopleProfilesTest.Contacts());Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        val c=Robolectric.buildActivity(Activity::class.java).setup();val names=PeopleNames(c.get());names.show(""){}
        await{(field(names,"list") as? ListView)?.adapter?.count==2}
        val dialog=field(names,"dialog") as android.app.AlertDialog;val decor=dialog.window!!.decorView;layout(decor,960,1450)
        val save=find(decor,"Save")!!;assertEquals(View.VISIBLE,save.visibility);assertTrue(save.isShown)
        val image=Bitmap.createBitmap(960,1450,Bitmap.Config.ARGB_8888);decor.draw(android.graphics.Canvas(image))
        val file=java.io.File("build/reports/ui/name-picker.png");file.parentFile?.mkdirs();file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle();names.close();c.destroy()
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun identityPickerNativePreviewUsesClearHorizontalActions(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val names=PeopleNames(c.get());val chooser=IdentityChooser(c.get(),names,{_,_->},{})
        val bitmap=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888).apply{eraseColor(0xffdbc8ad.toInt())}
        val key=GroupRules.Key("content://fluid/portrait",0);val member=GroupRules.Member(key,face,120000,null,null,"tentative",.7f,"",true)
        val reference=GroupRules.Prototype(member.copy(person=2),FloatArray(128){if(it==0)1f else 0f})
        val choice=IdentitySuggestions.Choice(2,"Papa Ji",reference,.8f,emptySet());val data=IdentitySuggestions.Result(member,null,"",listOf(choice),listOf(choice))
        IdentityChooser::class.java.getDeclaredMethod("render",GroupRules.Key::class.java,IdentitySuggestions.Result::class.java,Bitmap::class.java,List::class.java,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(chooser,key,data,bitmap,listOf(bitmap),false)
        val dialog=field(chooser,"dialog") as android.app.AlertDialog;val decor=dialog.window!!.decorView;layout(decor,960,1200)
        val add=find(decor,"Name / contact")!!;val choose=find(decor,"Choose person")!!;assertEquals(add.top,choose.top);assertTrue(choose.left>add.left)
        val image=Bitmap.createBitmap(960,1200,Bitmap.Config.ARGB_8888);decor.draw(android.graphics.Canvas(image))
        val file=java.io.File("build/reports/ui/identity-picker.png");file.parentFile?.mkdirs();file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        val source=(field(chooser,"crops") as List<*>).first() as View;val bounds=android.graphics.Rect(0,0,source.width,source.height)
        (decor as ViewGroup).offsetDescendantRectToMyCoords(source,bounds)
        assertEquals(0xffdbc8ad.toInt(),image.getPixel(bounds.centerX(),bounds.centerY()));assertNotEquals(0xffdbc8ad.toInt(),image.getPixel(bounds.left,bounds.top))
        image.recycle();chooser.close();names.close();bitmap.recycle();c.destroy()
    }

    @Test fun sharedFaceThumbnailsAvoidDecodeAndRejectChangedPhotosOrAccess(){
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES);FaceThumbnails.clear()
        val photo=PhotoRecord(1,Uri.parse("content://fluid/thumb"),"face.jpg",120000,400,300);var decodes=0
        fun decode():Bitmap{decodes++;return Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888)}
        val first=FaceThumbnails.load(app,photo,face,::decode)!!;assertEquals(192,first.width);assertSame(first,FaceThumbnails.load(app,photo,face,::decode));assertEquals(1,decodes)
        val changed=photo.copy(modifiedMillis=99);assertNull(FaceThumbnails.get(app,changed,face));assertNotSame(first,FaceThumbnails.load(app,changed,face,::decode));assertEquals(2,decodes)
        GalleryData.invalidate();assertNull(FaceThumbnails.get(app,photo,face));Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES);assertNull(FaceThumbnails.get(app,photo,face));assertNull(FaceThumbnails.load(app,photo,face,::decode));assertEquals(2,decodes)
        assertFalse(first.isRecycled);FaceThumbnails.clear();assertFalse(first.isRecycled)
    }

}
