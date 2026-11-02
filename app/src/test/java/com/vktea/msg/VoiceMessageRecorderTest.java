package com.vktea.msg;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.os.Bundle;
import android.os.Looper;
import android.widget.Button;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.vktea.R;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.LooperMode;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.JSONObject;

import okhttp3.HttpUrl;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class VoiceMessageRecorderTest {
    @Test public void uploadEndpointRequiresVkHttpsAndNoCredentials() {
        assertTrue(VoiceMessageRecorder.allowedUpload(HttpUrl.parse("https://upload.vk.com/upload")));
        assertTrue(VoiceMessageRecorder.allowedUpload(HttpUrl.parse("https://ps1.userapi.com/upload")));
        assertFalse(VoiceMessageRecorder.allowedUpload(HttpUrl.parse("http://upload.vk.com/upload")));
        assertFalse(VoiceMessageRecorder.allowedUpload(HttpUrl.parse("https://vk.com.example.org/upload")));
        assertFalse(VoiceMessageRecorder.allowedUpload(HttpUrl.parse("https://vk.com@evil.example/upload")));
        assertFalse(VoiceMessageRecorder.allowedUpload(HttpUrl.parse("https://vk.com:8443/upload")));
    }

    @Test public void savedVoiceSupportsModernAndLegacyVoiceButRejectsOrdinaryDocs() throws Exception {
        assertEquals(42, VoiceMessageRecorder.savedVoice(new JSONObject("{\"response\":{\"audio_message\":{\"id\":42}}}")).getInt("id"));
        assertEquals(43, VoiceMessageRecorder.savedVoice(new JSONObject("{\"response\":[{\"id\":43,\"preview\":{\"audio_msg\":{}}}]}")).getInt("id"));
        assertNull(VoiceMessageRecorder.savedVoice(new JSONObject("{\"response\":[{\"id\":44}]}")));
    }

    @Test @Config(sdk = 29)
    public void timeoutStopsTheActiveRecordingAndOffersReview() throws Exception {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        try (ActivityController<RecordingHost> controller = Robolectric.buildActivity(RecordingHost.class).setup()) {
            VoiceMessageRecorder recorder = controller.get().recorder;
            recorder.start();
            assertNotNull(field(recorder, "recorder"));
            File file = (File) field(recorder, "file");
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(new byte[]{1, 2, 3}); }
            shadowOf(Looper.getMainLooper()).idleFor(120, TimeUnit.SECONDS);
            assertNull(field(recorder, "recorder"));
            assertNull(field(recorder, "recordingTimeout"));
            assertNotNull(field(recorder, "dialog"));
            assertTrue(file.exists());
            recorder.close();
            assertFalse(file.exists());
        }
    }

    @Test @Config(sdk = 29)
    public void staleTimeoutCannotStopTheNextRecording() throws Exception {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        try (ActivityController<RecordingHost> controller = Robolectric.buildActivity(RecordingHost.class).setup()) {
            VoiceMessageRecorder recorder = controller.get().recorder;
            recorder.start();
            Runnable oldTimeout = (Runnable) field(recorder, "recordingTimeout");
            assertNotNull(oldTimeout);
            recorder.close();
            recorder.start();
            Object current = field(recorder, "recorder");
            assertNotNull(current);
            oldTimeout.run();
            assertTrue(current == field(recorder, "recorder"));
            recorder.close();
        }
    }

    @Test @Config(sdk = 29)
    public void previewCanBeReplayedAfterCompletionFailureOrStop() throws Exception {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        List<FakePreview> previews = new ArrayList<>();
        RecordingHost.factory = () -> { FakePreview player = new FakePreview(); previews.add(player); return player; };
        try (ActivityController<RecordingHost> controller = Robolectric.buildActivity(RecordingHost.class).setup()) {
            VoiceMessageRecorder recorder = controller.get().recorder;
            recorder.start();
            File file = (File) field(recorder, "file");
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(new byte[]{1, 2, 3}); }
            shadowOf(Looper.getMainLooper()).idleFor(120, TimeUnit.SECONDS);
            AlertDialog review = (AlertDialog) field(recorder, "dialog");
            Button button = review.getButton(AlertDialog.BUTTON_NEUTRAL);
            assertEquals("Прослушать", button.getText().toString());
            button.performClick();
            assertEquals("Остановить", button.getText().toString());
            previews.get(0).completed.run();
            assertTrue(previews.get(0).released);
            assertEquals("Прослушать", button.getText().toString());
            button.performClick();
            assertEquals(2, previews.size());
            previews.get(0).failed.run(); // A late callback must not stop the next player.
            assertFalse(previews.get(1).released);
            previews.get(1).failed.run();
            assertTrue(previews.get(1).released);
            assertEquals("Прослушать", button.getText().toString());
            button.performClick();
            assertEquals(3, previews.size());
            button.performClick();
            assertTrue(previews.get(2).released);
            assertEquals("Прослушать", button.getText().toString());
            button.performClick();
            assertEquals(4, previews.size());
            recorder.close();
            assertTrue(previews.get(3).released);
        } finally { RecordingHost.factory = null; }
    }

    private static class FakePreview implements VoiceMessageRecorder.Preview {
        Runnable completed, failed;
        boolean released;
        @Override public void start(File file, Runnable completed, Runnable failed) {
            this.completed = completed; this.failed = failed;
        }
        @Override public void release() { released = true; }
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    public static class RecordingHost extends AppCompatActivity {
        static VoiceMessageRecorder.PreviewFactory factory;
        VoiceMessageRecorder recorder;
        @Override protected void onCreate(Bundle state) {
            setTheme(R.style.Theme_VKTea);
            super.onCreate(state);
            recorder = factory == null ? new VoiceMessageRecorder(this, 42, null)
                    : new VoiceMessageRecorder(this, 42, null, new OkHttpClient(), factory);
        }
    }
}
