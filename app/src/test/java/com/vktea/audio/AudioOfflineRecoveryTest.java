package com.vktea.audio;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import com.vktea.auth.Session;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@UnstableApi
public class AudioOfflineRecoveryTest {
    private static final String ACCOUNT = "42", ID = "vk-audio:42_73";
    private Context context;
    private File dir;
    private String name;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Session.save(context, "synthetic-token", ACCOUNT);
        dir = new File(new File(context.getNoBackupFilesDir(), "offline_audio"), hash(ACCOUNT));
        assertTrue(dir.mkdirs() || dir.isDirectory());
        name = hash(ID);
    }

    @After public void cleanup() {
        AudioOfflineCache.clearAll(context);
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void findAndListRestoreOldHlsWhenNewDirectFileWasOnlyPartiallyPublished() throws Exception {
        File metadataBackup = new File(dir, name + ".json.backup.part");
        File hlsBackup = new File(dir, name + ".hls.backup.part");
        assertTrue(hlsBackup.mkdir());
        String manifest = "#EXTM3U\n#EXTINF:1,\nobject-0001.bin\n#EXT-X-ENDLIST\n";
        write(new File(hlsBackup, "index.m3u8"), manifest);
        write(new File(hlsBackup, "object-0001.bin"), "old audio");
        write(new File(hlsBackup, "complete"), "object-0001.bin 9\nindex.m3u8 " + manifest.getBytes(StandardCharsets.UTF_8).length);
        write(metadataBackup, "{\"id\":\"" + ID + "\",\"title\":\"Old title\",\"artist\":\"Artist\",\"duration\":1000}");
        File unfinishedDirect = new File(dir, name + ".audio");
        write(unfinishedDirect, "new but incomplete");

        File recovered = AudioOfflineCache.findFile(context, ID);
        assertNotNull(recovered);
        assertEquals(new File(new File(dir, name + ".hls"), "index.m3u8"), recovered);
        assertEquals(manifest, new String(Files.readAllBytes(recovered.toPath()), StandardCharsets.UTF_8));
        assertFalse(unfinishedDirect.exists());
        assertFalse(metadataBackup.exists());
        assertFalse(hlsBackup.exists());
        List<MediaItem> library = AudioOfflineCache.list(context);
        assertEquals(1, library.size());
        assertEquals(ID, library.get(0).mediaId);
        assertEquals("Old title", library.get(0).mediaMetadata.title.toString());
    }

    private static void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    private static String hash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
