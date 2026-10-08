package com.mosaic.gallery

import org.junit.Assert.*
import org.junit.Test

class CropBoundsTest {
    @Test fun fullCropPreservesLandscapeDimensions() {
        val crop = CropBounds().pixelRect(6000, 4000)
        assertEquals(CropBounds.Pixels(0, 0, 6000, 4000), crop)
    }
    @Test fun previewSelectionMapsToOriginalPixels() {
        // Middle half on a small preview must crop the middle half of a 24MP source.
        val crop = CropBounds(.25f, .25f, .75f, .75f).pixelRect(6000, 4000)
        assertEquals(CropBounds.Pixels(1500, 1000, 4500, 3000), crop)
        assertEquals(3000, crop.width())
        assertEquals(2000, crop.height())
    }
    @Test fun rotatedPortraitUsesOrientedDimensions() {
        val crop = CropBounds(0f, 0f, .5f, 1f).pixelRect(4000, 6000)
        assertEquals(CropBounds.Pixels(0, 0, 2000, 6000), crop)
    }
    @Test fun onePixelSourceHasANonEmptyCrop() {
        assertEquals(CropBounds.Pixels(0, 0, 1, 1), CropBounds(.49f, .49f, .51f, .51f).pixelRect(1, 1))
    }
    @Test fun tinyEdgeCropNeverRunsBeyondBitmap() {
        assertEquals(CropBounds.Pixels(99, 59, 100, 60), CropBounds(.999f, .999f, 1f, 1f).pixelRect(100, 60))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidBitmapSizeIsRejected() {
        CropBounds().pixelRect(0, 6000)
    }
}
