package com.vktea.videos;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.JSONObject;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VideoWriterTest {
    @Test public void rejectsUnsafeUploadDestinations() {
        assertNotNull(VideoWriter.safeUploadUrl("https://upload.vk.com/path"));
        assertNotNull(VideoWriter.safeUploadUrl("https://upload.userapi.com/path"));
        assertNull(VideoWriter.safeUploadUrl("http://upload.vk.com/path"));
        assertNull(VideoWriter.safeUploadUrl("https://upload.vk.com.evil.test/path"));
        assertNull(VideoWriter.safeUploadUrl("https://vk.com@evil.test/path"));
        assertNull(VideoWriter.safeUploadUrl("https://127.0.0.1/path"));
        assertNull(VideoWriter.safeUploadUrl("https://192.168.1.1/path"));
        assertNull(VideoWriter.safeUploadUrl("https://[::1]/path"));
        assertNull(VideoWriter.safeUploadUrl("https://vk.com:8443/path"));
    }
    @Test public void refusesPrivateDnsResults() throws Exception {
        assertFalse(VideoWriter.isPublicAddress(java.net.InetAddress.getByName("127.0.0.1")));
        assertFalse(VideoWriter.isPublicAddress(java.net.InetAddress.getByName("10.2.3.4")));
        assertFalse(VideoWriter.isPublicAddress(java.net.InetAddress.getByName("100.64.0.1")));
        assertFalse(VideoWriter.isPublicAddress(java.net.InetAddress.getByName("::1")));
        assertFalse(VideoWriter.isPublicAddress(java.net.InetAddress.getByName("fc00::1")));
        assertTrue(VideoWriter.isPublicAddress(java.net.InetAddress.getByName("8.8.8.8")));
    }
    @Test public void onlyContinuesAfterServerConfirmsEveryByte() {
        assertTrue(VideoWriter.confirmedRange("0-1048575", 1048575));
        assertTrue(VideoWriter.confirmedRange("0-1048575/2000000", 1048575));
        assertTrue(VideoWriter.confirmedRange("bytes=0-1048575", 1048575));
        assertFalse(VideoWriter.confirmedRange("0-1048574", 1048575));
        assertFalse(VideoWriter.confirmedRange("2-1048575", 1048575));
        assertFalse(VideoWriter.confirmedRange(null, 1048575));
    }
    @Test public void finalUploadNeedsPositiveEvidence() throws Exception {
        assertFalse(VideoWriter.confirmedUpload(new JSONObject("{}"), 7, 100));
        assertFalse(VideoWriter.confirmedUpload(new JSONObject("{\"error\":1,\"video_id\":7,\"size\":100}"), 7, 100));
        assertFalse(VideoWriter.confirmedUpload(new JSONObject("{\"video_id\":8,\"size\":100}"), 7, 100));
        assertFalse(VideoWriter.confirmedUpload(new JSONObject("{\"video_id\":7,\"size\":99}"), 7, 100));
        assertTrue(VideoWriter.confirmedUpload(new JSONObject("{\"video_id\":7,\"size\":100}"), 7, 100));
        assertTrue(VideoWriter.confirmedUpload(new JSONObject("{\"direct_link\":\"https://video.example/clip\"}"), 7, 100));
        assertFalse(VideoWriter.confirmedUpload(new JSONObject("{\"video_id\":8,\"direct_link\":\"https://video.example/clip\"}"), 7, 100));
        assertFalse(VideoWriter.confirmedUpload(new JSONObject("{\"size\":99,\"direct_link\":\"https://video.example/clip\"}"), 7, 100));
    }
    @Test public void categorizesVkWriteErrorsWithoutRetry() {
        assertEquals("auth", VideoRepository.mapError(5));
        assertEquals("rate", VideoRepository.mapError(6));
        assertEquals("access", VideoRepository.mapError(7));
    }
}
