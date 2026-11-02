package com.vktea.audio;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class HlsOfflineDownloaderTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final Map<String, byte[]> responses = new HashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(true);

    private void put(String url, String value) { responses.put(url, value.getBytes(StandardCharsets.UTF_8)); }
    private File snapshot(String manifest, long limit) throws Exception {
        File dir = temp.newFolder();
        File initial = new File(dir, "initial.part");
        Files.write(initial.toPath(), manifest.getBytes(StandardCharsets.UTF_8));
        HlsOfflineDownloader downloader = new HlsOfflineDownloader((url, destination, remaining) -> {
            byte[] body = responses.get(url);
            if (body == null || body.length > remaining) throw new IOException();
            Files.write(destination.toPath(), body);
            return body.length;
        }, active::get, dir, limit);
        downloader.download("https://cdn.example/path/master.m3u8?signature=secret", initial);
        return dir;
    }
    private String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    @Test public void masterEncryptedRangesAndInitBecomeClosedLocalSnapshot() throws Exception {
        put("https://cdn.example/path/media.m3u8", "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:17\n"
                + "#EXT-X-KEY:URI=\"keys/key?token=secret\", METHOD=AES-128,IV=0x00000000000000000000000000000011\n"
                + "#EXT-X-MAP:URI=\"parts/blob\",BYTERANGE=\"4@0\"\n"
                + "#EXTINF:2,https://private.example/title\n#EXT-X-BYTERANGE:4@4\nparts/blob\n"
                + "#EXTINF:2,\n#EXT-X-BYTERANGE:4\nparts/blob\n#EXT-X-ENDLIST\n");
        put("https://cdn.example/path/keys/key?token=secret", "0123456789abcdef");
        put("https://cdn.example/path/parts/blob", "abcdefghijkl");
        File dir = snapshot("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000\nmedia.m3u8\n", 4096);
        assertTrue(HlsOfflineDownloader.valid(dir));
        String local = read(new File(dir, "index.m3u8"));
        assertTrue(local.contains("#EXT-X-MEDIA-SEQUENCE:17"));
        assertTrue(local.contains("#EXT-X-BYTERANGE:4@4"));
        assertFalse(local.contains("https:"));
        assertFalse(local.contains("secret"));
        assertEquals(4, dir.listFiles().length); // manifest, key, shared media object, marker
        assertEquals("0123456789abcdef", read(new File(dir, "object-0001.bin")));
        new File(dir, "object-0002.bin").delete();
        assertFalse(HlsOfflineDownloader.valid(dir));
    }
    @Test public void liveDrmUnknownUriAndMissingSegmentsNeverCommit() throws Exception {
        String[] invalid = {
                "#EXTM3U\n#EXTINF:2,\nsegment\n",
                "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n#EXTINF:2,\nsegment\n#EXT-X-ENDLIST\n",
                "#EXTM3U\n#EXT-X-FOO:URI=\"https://private.example/x\"\n#EXTINF:2,\nsegment\n#EXT-X-ENDLIST\n",
                "#EXTM3U\n#EXTINF:2,\nsegment\n#EXT-X-ENDLIST\n",
                "#EXTM3U\n#EXTINF:2,\n#EXT-X-BYTERANGE:10@0\nsegment\n#EXT-X-ENDLIST\n"
        };
        for (int i = 0; i < invalid.length; i++) {
            if (i == 0 || i == 4) put("https://cdn.example/path/segment", "abc");
            else responses.remove("https://cdn.example/path/segment");
            try { snapshot(invalid[i], 1024); fail("expected rejection " + i); } catch (IOException expected) { }
        }
    }
    @Test public void cancellationAndQuotaDoNotCommit() throws Exception {
        put("https://cdn.example/path/segment", "abcd");
        active.set(false);
        try { snapshot("#EXTM3U\n#EXTINF:1,\nsegment\n#EXT-X-ENDLIST\n", 1024); fail(); } catch (IOException expected) { }
        active.set(true);
        try { snapshot("#EXTM3U\n#EXTINF:1,\nsegment\n#EXT-X-ENDLIST\n", 3); fail(); } catch (IOException expected) { }
    }
    @Test public void cancellationDuringFetchAndShortWriteNeverPublish() throws Exception {
        for (boolean cancel : new boolean[] {true, false}) {
            File dir = temp.newFolder();
            File initial = new File(dir, "initial.part");
            Files.write(initial.toPath(), "#EXTM3U\n#EXTINF:1,\nsegment\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8));
            AtomicBoolean running = new AtomicBoolean(true);
            HlsOfflineDownloader downloader = new HlsOfflineDownloader((url, destination, remaining) -> {
                Files.write(destination.toPath(), new byte[] {1, 2, 3});
                if (cancel) running.set(false);
                return cancel ? 3 : 4;
            }, running::get, dir, 1024);
            try { downloader.download("https://cdn.example/master.m3u8", initial); fail(); }
            catch (IOException expected) { }
            assertFalse(new File(dir, "complete").exists());
            assertFalse(HlsOfflineDownloader.valid(dir));
        }
    }
    @Test public void rejectsNetworkProtocolsAndSeparateRenditions() throws Exception {
        try { snapshot("#EXTM3U\n#EXTINF:1,\nfile:///private\n#EXT-X-ENDLIST\n", 1024); fail(); } catch (IOException expected) { }
        try { snapshot("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO=\"a\"\nvideo.m3u8\n", 1024); fail(); } catch (IOException expected) { }
    }
}
