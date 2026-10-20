package com.mosaic.gallery

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PortraitViewerTest {
    private fun layout(v:View,w:Int=1080,h:Int=2400){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)}
    private fun checkViewer(){
        val app=RuntimeEnvironment.getApplication()
        val c=Robolectric.buildActivity(PhotoActivity::class.java,Intent(app,PhotoActivity::class.java).setData(Uri.parse("content://portrait/1"))).create().start().visible();val a=c.get()
        fun field(name:String)=PhotoActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(a)
        val root=field("viewerRoot") as View;val pager=field("pager") as PhotoPager
        layout(root);PhotoActivity::class.java.getDeclaredMethod("layoutPeople").apply{isAccessible=true}.invoke(a);layout(root)
        val margins=pager.layoutParams as FrameLayout.LayoutParams
        assertEquals(0,margins.topMargin);assertEquals(0,margins.bottomMargin);assertEquals(2400,pager.height)
        val image=pager.currentImage()!!;val bitmap=Bitmap.createBitmap(900,1950,Bitmap.Config.ARGB_8888)
        image.show(bitmap);val bounds=RectF(0f,0f,900f,1950f);image.imageMatrix.mapRect(bounds)
        assertEquals(0f,bounds.left,.1f);assertEquals(1080f,bounds.right,.1f)
        PhotoActivity::class.java.getDeclaredMethod("toggleControls").apply{isAccessible=true}.invoke(a)
        assertEquals(View.INVISIBLE,(field("tagCapsule") as View).visibility)
        layout(root);assertEquals(2400,pager.height);assertEquals(0,(pager.layoutParams as FrameLayout.LayoutParams).topMargin)
        c.destroy()
    }
    @Test fun portraitUsesFullWidthAndFullViewerWithAndWithoutControls(){checkViewer()}
    @Test @Config(sdk=[28]) fun portraitAlsoUsesFullViewerOnAndroidNine(){checkViewer()}
}
