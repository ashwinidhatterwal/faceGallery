package com.mosaic.gallery

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportEncodingTest{
    private fun photo():Bitmap{
        val bitmap=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888);bitmap.setHasAlpha(false)
        val random=java.util.Random(71);val pixels=IntArray(400*300){0xff000000.toInt() or random.nextInt(0x1000000)}
        bitmap.setPixels(pixels,0,400,0,0,400,300);return bitmap
    }
    @Test fun loweringQualityOrResolutionReducesEncodedFileSize(){
        val original=photo();val smaller=PhotoImages.resized(original,50)
        assertEquals(200,smaller.width);assertEquals(150,smaller.height)
        val full=PhotoImages.encodedSize(original,95)
        assertTrue(PhotoImages.encodedSize(original,30)<full)
        assertTrue(PhotoImages.encodedSize(smaller,95)<full)
        smaller.recycle();original.recycle()
    }
    @Test fun transparencyUsesLosslessPng(){
        val bitmap=photo();bitmap.setHasAlpha(true)
        assertEquals(PhotoImages.encodedSize(bitmap,30),PhotoImages.encodedSize(bitmap,95));bitmap.recycle()
    }
    @Test fun arbitraryRotationPreservesOpaqueExportAndQuarterTurnDimensions(){
        val bitmap=photo();val quarter=PhotoImages.rotateDegrees(bitmap,90f)
        assertEquals(300,quarter.width);assertEquals(400,quarter.height)
        val tilted=PhotoImages.rotateDegrees(bitmap,35f)
        assertTrue(tilted.width>400);assertTrue(tilted.height>300);assertFalse(tilted.hasAlpha())
        listOf(bitmap,quarter,tilted).forEach{it.recycle()}
    }
}
