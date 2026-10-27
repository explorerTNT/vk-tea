package com.vktea.feed;
import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VideoPlayerUrlTest {
    @Test public void fallbackKeepsAndEncodesAccessKey() {
        android.net.Uri uri = VideoPlayerActivity.webUri(-123, 456, "private+key/1");
        assertEquals("/video-123_456", uri.getPath());
        assertEquals("private+key/1", uri.getQueryParameter("access_key"));
        assertNull(VideoPlayerActivity.webUri(1, 2, "").getQueryParameter("access_key"));
    }
}
