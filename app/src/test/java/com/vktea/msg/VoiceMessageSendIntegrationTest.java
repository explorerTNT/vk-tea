package com.vktea.msg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.vktea.auth.Session;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** The transport responds in-process and never proceeds to a network connection. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class VoiceMessageSendIntegrationTest {
    private ActivityController<AppCompatActivity> controller;
    private VoiceMessageRecorder recorder;
    private File recording;
    private final AtomicInteger completed = new AtomicInteger();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> uploadBodies = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger uploadCount = new AtomicInteger();
    private enum Failure { NONE, SEND, SERVER_VK, UPLOAD_HTTP, UPLOAD_HTTP_ONCE, SAVE_VK }

    @After public void close() {
        if (recorder != null) recorder.close();
        if (controller != null) controller.close();
        if (recording != null) recording.delete();
    }

    private void prepare(Failure failure) throws Exception {
        controller = Robolectric.buildActivity(AppCompatActivity.class).create();
        AppCompatActivity activity = controller.get();
        Session.save(activity, "synthetic-token", "101");
        OkHttpClient fake = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            String path = request.url().encodedPath();
            requests.add(path);
            String payload;
            int status = 200;
            if (path.endsWith("/docs.getMessagesUploadServer") || path.endsWith("/docs.getUploadServer")) {
                payload = failure == Failure.SERVER_VK ? "{\"error\":{\"error_code\":5}}"
                        : "{\"response\":{\"upload_url\":\"https://upload.vk.com/upload\"}}";
            } else if (path.equals("/upload")) {
                Buffer multipart = new Buffer();
                request.body().writeTo(multipart);
                uploadBodies.add(multipart.readUtf8());
                int uploadNumber = uploadCount.incrementAndGet();
                payload = "{\"file\":\"synthetic-upload\"}";
                if (failure == Failure.UPLOAD_HTTP || (failure == Failure.UPLOAD_HTTP_ONCE && uploadNumber == 1)) status = 503;
            } else if (path.endsWith("/docs.save")) {
                payload = failure == Failure.SAVE_VK ? "{\"error\":{\"error_code\":5}}"
                        : "{\"response\":{\"audio_message\":{\"owner_id\":101,\"id\":303}}}";
            } else if (path.endsWith("/messages.send")) {
                payload = failure == Failure.SEND ? "{\"error\":{\"error_code\":5}}" : "{\"response\":404}";
            } else {
                throw new AssertionError("Unexpected synthetic request path: " + path);
            }
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(status).message("Synthetic")
                    .body(ResponseBody.create(payload, MediaType.parse("application/json"))).build();
        }).build();
        recorder = new VoiceMessageRecorder(activity, 202, completed::incrementAndGet, fake);
        controller.start().resume().visible();
        recording = File.createTempFile("synthetic-voice-", ".ogg", activity.getCacheDir());
        try (FileOutputStream out = new FileOutputStream(recording)) {
            out.write("synthetic-audio".getBytes(StandardCharsets.UTF_8));
        }
        ReflectionHelpers.setField(recorder, "file", recording);
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Timed out waiting for synthetic send", condition.getAsBoolean());
    }

    @Test public void successfulUploadAndSendCompletesOnceAndDeletesRecording() throws Exception {
        prepare(Failure.NONE);
        ReflectionHelpers.callInstanceMethod(recorder, "send");
        await(() -> completed.get() == 1);
        assertEquals(4, requests.size());
        assertTrue(requests.get(0).contains("docs.get"));
        assertEquals("/upload", requests.get(1));
        assertTrue(requests.get(2).endsWith("/docs.save"));
        assertTrue(requests.get(3).endsWith("/messages.send"));
        assertFalse(recording.exists());
    }

    @Test public void successfulSendUploadsWaveformAndVoiceFile() throws Exception {
        prepare(Failure.NONE);
        ReflectionHelpers.setField(recorder, "recordedWaveform", "[0,16,31]");
        ReflectionHelpers.callInstanceMethod(recorder, "send");
        await(() -> completed.get() == 1);
        assertEquals(4, requests.size());
        assertEquals(1, uploadBodies.size());
        assertVoiceMultipart(uploadBodies.get(0));
        assertFalse(recording.exists());
    }

    @Test public void explicitRetryPreservesWaveformAfterUploadFailure() throws Exception {
        prepare(Failure.UPLOAD_HTTP_ONCE);
        ReflectionHelpers.setField(recorder, "recordedWaveform", "[0,16,31]");
        ReflectionHelpers.callInstanceMethod(recorder, "send");
        await(() -> ReflectionHelpers.getField(recorder, "dialog") != null);
        assertTrue(recording.exists());
        assertEquals(2, requests.size());
        assertEquals(1, uploadBodies.size());
        assertVoiceMultipart(uploadBodies.get(0));
        AlertDialog dialog = ReflectionHelpers.getField(recorder, "dialog");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        await(() -> completed.get() == 1);
        assertEquals(6, requests.size());
        assertEquals(2, uploadBodies.size());
        assertVoiceMultipart(uploadBodies.get(1));
        assertFalse(recording.exists());
    }

    private void assertVoiceMultipart(String body) {
        assertTrue(body.contains("name=\"waveform\""));
        assertTrue(body.contains("[0,16,31]"));
        assertTrue(body.contains("name=\"file\"; filename=\"Audio Message\""));
        assertTrue(body.contains("Content-Type: audio/ogg"));
        assertTrue(body.contains("synthetic-audio"));
    }

    @Test public void rejectedSendDoesNotRetryOrReportSuccess() throws Exception {
        prepare(Failure.SEND);
        ReflectionHelpers.callInstanceMethod(recorder, "send");
        await(() -> !recording.exists());
        assertEquals(0, completed.get());
        assertEquals(4, requests.size());
        assertTrue(requests.get(3).endsWith("/messages.send"));
    }

    @Test public void vkServerRejectionRetainsRecordingAndShowsStageWithoutRetry() throws Exception {
        assertEarlyFailure(Failure.SERVER_VK, 1, "сервер загрузки");
    }

    @Test public void uploadHttpFailureRetainsRecordingAndShowsStageWithoutRetry() throws Exception {
        assertEarlyFailure(Failure.UPLOAD_HTTP, 2, "загрузить запись");
    }

    @Test public void vkSaveRejectionRetainsRecordingAndShowsStageWithoutRetry() throws Exception {
        assertEarlyFailure(Failure.SAVE_VK, 3, "сохранить запись");
    }

    private void assertEarlyFailure(Failure failure, int expectedRequests, String stage) throws Exception {
        prepare(failure);
        ReflectionHelpers.callInstanceMethod(recorder, "send");
        await(() -> ReflectionHelpers.getField(recorder, "dialog") != null);
        AlertDialog dialog = ReflectionHelpers.getField(recorder, "dialog");
        assertTrue(dialog.isShowing());
        TextView message = dialog.findViewById(android.R.id.message);
        assertTrue(message.getText().toString().contains(stage));
        assertTrue(recording.exists());
        assertEquals(0, completed.get());
        assertEquals(expectedRequests, requests.size());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(recording.exists());
        assertEquals(expectedRequests, requests.size());
    }
}
