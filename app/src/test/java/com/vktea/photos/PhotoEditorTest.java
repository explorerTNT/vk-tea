package com.vktea.photos;

import android.graphics.Rect;
import android.graphics.RectF;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
public class PhotoEditorTest {
    @Test public void previewFitsCompactAndWideScreens() {
        assertEquals(144, PhotoEditor.previewHeight(360, 1));
        assertEquals(320, PhotoEditor.previewHeight(2400, 1));
        assertEquals(240, PhotoEditor.previewHeight(100, 2));
    }
    @Test public void cropUsesPreviewFractionOnFullSizeImage() {
        assertEquals(new Rect(100, 100, 700, 350), PhotoEditor.cropPixels(1000, 500, new RectF(.1f, .2f, .7f, .7f)));
        assertEquals(new Rect(50, 200, 350, 700), PhotoEditor.cropPixels(500, 1000, new RectF(.1f, .2f, .7f, .7f)));
    }
    @Test public void cropIsClampedAndNeverEmpty() {
        assertEquals(new Rect(0, 0, 8, 9), PhotoEditor.cropPixels(8, 9, new RectF(-1, -1, 2, 2)));
        assertEquals(new Rect(7, 8, 8, 9), PhotoEditor.cropPixels(8, 9, new RectF(1, 1, 1, 1)));
    }
}
