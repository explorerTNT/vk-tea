package com.vktea.videos;

import static org.junit.Assert.*;
import android.os.Bundle;
import android.widget.EditText;
import org.robolectric.RuntimeEnvironment;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VideoEditingSafetyTest {
    @Test public void openingEditorKeepsExistingTextLongerThanNewInputLimit() {
        String longDescription = new String(new char[2100]).replace('\0', 'x');
        EditText field = new EditText(RuntimeEnvironment.getApplication());
        VideosFragment.setExistingTextAndLimit(field, longDescription, 2048);
        assertEquals(longDescription, field.getText().toString());
    }
    @Test public void interruptedWriteIsReportedOnceEvenWhenViewIsRecreatedWithoutSavedBundle() throws Exception {
        VideosFragment fragment = new VideosFragment();
        Field pending = VideosFragment.class.getDeclaredField("writePending"); pending.setAccessible(true); pending.setBoolean(fragment, true);
        assertTrue(fragment.consumeInterruptedWrite(null));
        assertFalse(fragment.consumeInterruptedWrite(null));
        Bundle saved = new Bundle(); saved.putBoolean("video_write_pending", true);
        assertTrue(new VideosFragment().consumeInterruptedWrite(saved));
    }
    @Test public void previewMonitorRemainsActiveDuringBufferingAndStopsAtEnd() {
        VideoPreviewBoundary boundary = new VideoPreviewBoundary(); boundary.start(2000);
        assertFalse(boundary.shouldPause(0)); assertTrue(boundary.isActive());
        assertFalse(boundary.shouldPause(1500)); assertTrue(boundary.isActive());
        assertTrue(boundary.shouldPause(2000)); assertFalse(boundary.isActive());
        assertFalse(boundary.shouldPause(2100));
        boundary.start(1000); boundary.stop(); assertFalse(boundary.shouldPause(1500));
    }
}
