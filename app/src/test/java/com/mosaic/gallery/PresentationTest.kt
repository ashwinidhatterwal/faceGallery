package com.mosaic.gallery

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="xhdpi")
class PresentationTest {
    private fun image()=ZoomPhotoView(RuntimeEnvironment.getApplication()).apply{
        measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.EXACTLY))
        layout(0,0,1080,1200)
    }
    @Test fun sharedElementTransformEndsAtFitSize(){
        val view=image();view.show(Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888))
        view.animateTransform(Matrix().apply{postScale(8f,8f);postTranslate(-1000f,-1000f)})
        view.resetToFit()
        val actual=FloatArray(9);view.imageMatrix.getValues(actual)
        assertEquals(1.35f,actual[Matrix.MSCALE_X],0.001f)
        assertEquals(0f,actual[Matrix.MTRANS_X],0.001f)
        assertEquals(195f,actual[Matrix.MTRANS_Y],0.001f)
    }
    @Test fun bitmapDensityCannotApplyASecondZoom(){
        val view=image()
        val bitmap=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888).apply{density=160}
        view.show(bitmap)
        assertEquals(bitmap.width,view.drawable.intrinsicWidth)
        assertEquals(bitmap.height,view.drawable.intrinsicHeight)
        val actual=FloatArray(9);view.imageMatrix.getValues(actual)
        assertEquals(1080f,view.drawable.intrinsicWidth*actual[Matrix.MSCALE_X],0.001f)
        assertEquals(810f,view.drawable.intrinsicHeight*actual[Matrix.MSCALE_Y],0.001f)
    }
    @Test fun newPhotoCannotInheritAPreviousMatrix(){
        val view=image();view.show(Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888))
        view.imageMatrix=Matrix().apply{postScale(12f,12f)}
        view.show(Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888))
        val actual=FloatArray(9);view.imageMatrix.getValues(actual)
        assertEquals(1200f/900,actual[Matrix.MSCALE_X],0.001f)
        assertEquals(140f,actual[Matrix.MTRANS_X],0.001f)
        assertEquals(0f,actual[Matrix.MTRANS_Y],0.001f)
    }
    @Test fun photoViewportEndsBeforeControls(){
        val controller=Robolectric.buildActivity(PhotoActivity::class.java,Intent().setData(Uri.parse("content://presentation/photo"))).create()
        val activity=controller.get();val content=activity.findViewById<ViewGroup>(android.R.id.content)
        val root=content.getChildAt(0) as ViewGroup
        fun layout(){root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY));root.layout(0,0,1080,1920);root.viewTreeObserver.dispatchOnGlobalLayout()}
        layout();layout()
        val pager=root.getChildAt(0) as PhotoPager
        assertTrue("Photo must clear the header",pager.top>0)
        assertTrue("Photo must clear the filmstrip/actions",pager.bottom<root.height)
        val before=pager.height;root.getChildAt(1).visibility=View.INVISIBLE;layout()
        assertEquals("Hiding controls must not resize the photo",before,pager.height)
        controller.destroy()
    }
    @Test fun paletteFollowsLightAndDarkConfiguration(){
        fun themed(night:Int):ContextThemeWrapper{
            val base=RuntimeEnvironment.getApplication()
            val config=Configuration(base.resources.configuration).apply{uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night}
            return ContextThemeWrapper(base.createConfigurationContext(config),R.style.GalleryTheme)
        }
        val light=themed(Configuration.UI_MODE_NIGHT_NO);val dark=themed(Configuration.UI_MODE_NIGHT_YES)
        assertTrue(light.resources.getBoolean(R.bool.gallery_light_bars));assertFalse(dark.resources.getBoolean(R.bool.gallery_light_bars))
        assertNotEquals(GalleryStyle.canvas(light),GalleryStyle.canvas(dark))
        assertEquals(light.getColor(android.R.color.system_accent1_700),GalleryStyle.accent(light))
        assertEquals(dark.getColor(android.R.color.system_accent1_200),GalleryStyle.accent(dark))
        assertEquals(GalleryStyle.textColor(light),GalleryStyle.text(light,"Photo").currentTextColor)
        assertEquals(GalleryStyle.textColor(dark),GalleryStyle.text(dark,"Photo").currentTextColor)
    }
}
