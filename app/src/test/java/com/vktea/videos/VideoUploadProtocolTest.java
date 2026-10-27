package com.vktea.videos;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.HttpUrl;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VideoUploadProtocolTest {
    private static final int LENGTH = VideoUploadProtocol.CHUNK_SIZE + 17;
    private static final class FakeTransport implements VideoUploadProtocol.Transport {
        int saves, sends;
        String title, description, firstSession, firstFilename;
        String firstRange = "0-1048575";
        JSONObject finalBody;
        boolean failSend, valid = true;
        final List<String> ranges = new ArrayList<>();
        final List<Integer> lengths = new ArrayList<>();
        FakeTransport() throws Exception { finalBody = new JSONObject().put("video_id", 7).put("size", LENGTH); }
        @Override public JSONObject save(String title, String description) throws Exception {
            saves++; this.title = title; this.description = description;
            return new JSONObject().put("video_id", 7).put("upload_url", "https://upload.vk.com/video");
        }
        @Override public boolean valid() { return valid; }
        @Override public VideoUploadProtocol.Reply send(HttpUrl url, String session, String filename,
                                                        long from, long to, long total, byte[] body) throws Exception {
            sends++; assertEquals("upload.vk.com", url.host());
            if (firstSession == null) { firstSession = session; firstFilename = filename; }
            else { assertEquals(firstSession, session); assertEquals(firstFilename, filename); }
            ranges.add(from + "-" + to + "/" + total); lengths.add(body.length);
            if (failSend) throw new IOException("synthetic failure");
            return sends == 1 ? new VideoUploadProtocol.Reply(201, firstRange, null)
                    : new VideoUploadProtocol.Reply(200, null, finalBody);
        }
    }
    private static void upload(FakeTransport transport) throws Exception {
        byte[] source = new byte[LENGTH]; source[0] = 1; source[LENGTH - 1] = 2;
        VideoUploadProtocol.upload(() -> new ByteArrayInputStream(source), LENGTH, "Название", "Описание", "video.webm", transport);
    }
    @Test public void createsOneVideoAndSendsSequentialChunksWithOneSession() throws Exception {
        FakeTransport t = new FakeTransport(); upload(t);
        assertEquals(1, t.saves); assertEquals(2, t.sends);
        assertEquals("Название", t.title); assertEquals("Описание", t.description); assertEquals("video.webm", t.firstFilename);
        assertEquals("0-1048575/1048593", t.ranges.get(0)); assertEquals("1048576-1048592/1048593", t.ranges.get(1));
        assertEquals(Integer.valueOf(1048576), t.lengths.get(0)); assertEquals(Integer.valueOf(17), t.lengths.get(1));
        assertNotNull(t.firstSession);
    }
    @Test public void incorrectRangeStopsBeforeNextChunkWithoutRetry() throws Exception {
        FakeTransport t = new FakeTransport(); t.firstRange = "0-100";
        try { upload(t); fail("Expected uncertain result"); } catch (VideoWriter.Failure e) { assertEquals("uncertain", e.code); }
        assertEquals(1, t.saves); assertEquals(1, t.sends);
    }
    @Test public void emptyFinalResponseIsUncertainAndNotRetried() throws Exception {
        FakeTransport t = new FakeTransport(); t.finalBody = new JSONObject();
        try { upload(t); fail("Expected uncertain result"); } catch (VideoWriter.Failure e) { assertEquals("uncertain", e.code); }
        assertEquals(1, t.saves); assertEquals(2, t.sends);
    }
    @Test public void networkFailureIsNotRetried() throws Exception {
        FakeTransport t = new FakeTransport(); t.failSend = true;
        try { upload(t); fail("Expected network failure"); } catch (IOException expected) { }
        assertEquals(1, t.saves); assertEquals(1, t.sends);
    }
    @Test public void sessionChangeStopsBeforeUploading() throws Exception {
        FakeTransport t = new FakeTransport(); t.valid = false;
        try { upload(t); fail("Expected uncertain result"); } catch (VideoWriter.Failure e) { assertEquals("uncertain", e.code); }
        assertEquals(1, t.saves); assertEquals(0, t.sends);
    }
}
