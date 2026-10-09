package com.mosaic.gallery

import android.app.Activity
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.SeekBar
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CameraAndVideoControlsTest {
    private val photo=PhotoRecord(1,Uri.parse("content://media/external/images/media/1"),"photo.jpg",0,400,300)
    @Test fun cameraFilterIncludesCameraPhotosAndVideosButExcludesScreenshotsAndDownloads(){
        assertTrue(CameraMedia.contains(photo.copy(path="/storage/emulated/0/DCIM/Camera/photo.jpg")))
        assertTrue(CameraMedia.contains(photo.copy(path="/storage/1234-5678/DCIM/camera/movie.mp4",mimeType="video/mp4")))
        assertFalse(CameraMedia.contains(photo.copy(path="/storage/emulated/0/Pictures/Screenshots/a.jpg",album="Camera")))
        assertFalse(CameraMedia.contains(photo.copy(path="/storage/emulated/0/Download/a.jpg")))
        assertTrue(CameraMedia.contains(photo.copy(album="Camera")))
        assertFalse(CameraMedia.contains(photo.copy(album="Screenshots")))
    }
    @Test fun mainFilterDoesNotDiscardFullLibraryAndPersistsUserChoice(){
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences("gallery-layout",0).edit().clear().commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).create()
        val activity=c.get()
        fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
        val camera=photo.copy(album="Camera")
        val download=photo.copy(id=2,uri=Uri.parse("content://media/external/images/media/2"),album="Download")
        field("allPhotos").set(activity,listOf(camera,download))
        MainActivity::class.java.getDeclaredMethod("render").apply{isAccessible=true}.invoke(activity)
        assertEquals(listOf(camera),field("visible").get(activity))
        MainActivity::class.java.getDeclaredMethod("setCameraOnly",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(activity,false)
        assertEquals(listOf(camera,download),field("visible").get(activity))
        assertEquals(listOf(camera,download),field("allPhotos").get(activity))
        c.destroy()
        val reopened=Robolectric.buildActivity(MainActivity::class.java).create()
        assertEquals(false,field("cameraOnly").get(reopened.get()))
        reopened.destroy();app.getSharedPreferences("gallery-layout",0).edit().clear().commit()
    }
    private fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{descendants(view.getChildAt(it))}else emptyList()
    @Test fun muteControlAndSavedStateSurvivePlayerReleaseAndRebind(){
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val video=photo.copy(uri=Uri.parse("content://media/external/video/media/1"),mimeType="video/mp4")
        val view=GalleryVideoView(controller.get())
        view.bind(video,GalleryVideoView.State(video.uri.toString(),45000,false,true))
        assertTrue(view.state().muted);assertFalse(view.state().playing)
        view.stopPlayback();assertTrue(view.state().muted);assertEquals(45000L,view.state().position)
        val restored=GalleryVideoView(controller.get());restored.bind(video,view.state())
        val mute=descendants(restored).filterIsInstance<ImageButton>().last()
        mute.performClick();assertFalse(restored.state().muted)
        mute.performClick();assertTrue(restored.state().muted)
        assertEquals(10000,descendants(restored).filterIsInstance<SeekBar>().single().max)
        controller.pause().stop().destroy()
    }
    @Test
    @Config(qualifiers="w320dp-h640dp-xxhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun videoBarFitsNarrowScreensInBothThemes(){
        for(theme in listOf("notnight","night")){
            org.robolectric.RuntimeEnvironment.setQualifiers("w320dp-h640dp-$theme-xxhdpi")
            val c=Robolectric.buildActivity(Activity::class.java).setup()
            val video=photo.copy(uri=Uri.parse("content://media/external/video/media/1"),mimeType="video/mp4",durationMillis=3661000)
            val view=GalleryVideoView(c.get());view.bind(video,GalleryVideoView.State(video.uri.toString(),3600000,false,true));view.visibility=View.VISIBLE
            val mute=descendants(view).filterIsInstance<ImageButton>().last();mute.performClick();mute.performClick()
            val width=GalleryStyle.dp(c.get(),320);val height=GalleryStyle.dp(c.get(),200)
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)
            val seek=descendants(view).filterIsInstance<SeekBar>().single()
            assertTrue(seek.width>=GalleryStyle.dp(c.get(),140))
            assertEquals(GalleryStyle.dp(c.get(),48),mute.width)
            val controls=GalleryVideoView::class.java.getDeclaredField("controls").apply{isAccessible=true}.get(view) as View
            val bitmap=android.graphics.Bitmap.createBitmap(controls.width,controls.height,android.graphics.Bitmap.Config.ARGB_8888)
            controls.draw(android.graphics.Canvas(bitmap))
            val folder=java.io.File("build/ui-review").apply{mkdirs()}
            java.io.File(folder,"video-controls-$theme.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle();c.pause().stop().destroy()
        }
    }

}
