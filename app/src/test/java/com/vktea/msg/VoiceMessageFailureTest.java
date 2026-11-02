package com.vktea.msg;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
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
import org.robolectric.shadows.ShadowToast;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import okhttp3.HttpUrl;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VoiceMessageFailureTest {
    private ActivityController<AppCompatActivity> controller;
    private VoiceMessageRecorder recorder;
    private File recording;

    @After public void cleanup() {
        if (recorder != null) recorder.close();
        if (controller != null) controller.close();
        if (recording != null) recording.delete();
    }

    private void prepareRecording() throws Exception {
        controller = Robolectric.buildActivity(AppCompatActivity.class).create();
        AppCompatActivity activity = controller.get();
        activity.getSharedPreferences("session", Context.MODE_PRIVATE).edit()
                .putString("access_token", "synthetic-token")
                .putString("user_id", "42").putString("secret", "").commit();
        recorder = new VoiceMessageRecorder(activity, 43, null);
        recording = File.createTempFile("synthetic-voice", ".ogg", activity.getCacheDir());
        set("file", recording);
        set("token", "synthetic-token");
        set("user", "42");
        set("secret", "");
        set("sessionGeneration", Session.generation());
        set("generation", 7);
    }

    private void set(String name, Object value) throws Exception {
        Field field = VoiceMessageRecorder.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(recorder, value);
    }

    private Object get(String name) throws Exception {
        Field field = VoiceMessageRecorder.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(recorder);
    }

    private void fail(VoiceMessageRecorder.SendStage stage) throws Exception {
        Method method = VoiceMessageRecorder.class.getDeclaredMethod("fail", int.class, VoiceMessageRecorder.SendStage.class);
        method.setAccessible(true);
        method.invoke(recorder, 7, stage);
    }

    @Test public void preSendFailureRetainsRecordingUntilExplicitDeleteOrScreenClose() throws Exception {
        prepareRecording();
        fail(VoiceMessageRecorder.SendStage.UPLOAD);
        assertTrue(recording.exists());
        AlertDialog dialog = (AlertDialog) get("dialog");
        assertTrue(dialog.isShowing());
        assertEquals("Повторить", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertEquals("Удалить", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        assertTrue(((TextView) dialog.findViewById(android.R.id.message)).getText().toString().contains("загрузить"));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(recording.exists());
    }

    @Test public void uncertainMessageSendNeverOffersRetryAndDiscardsLocalRecording() throws Exception {
        prepareRecording();
        fail(VoiceMessageRecorder.SendStage.SEND);
        assertFalse(recording.exists());
        AlertDialog dialog = (AlertDialog) get("dialog");
        assertTrue(dialog.isShowing());
        assertTrue(((TextView) dialog.findViewById(android.R.id.message)).getText().toString().contains("Проверьте чат"));
        assertEquals("Понятно", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE) == null
                || dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getVisibility() != android.view.View.VISIBLE);
    }

    @Test public void screenCloseDeletesRetainedRecording() throws Exception {
        prepareRecording();
        fail(VoiceMessageRecorder.SendStage.SERVER);
        assertTrue(recording.exists());
        recorder.close();
        assertFalse(recording.exists());
        assertNull(get("dialog"));
    }

    @Test public void retryDoesNotSendRecordingWithDifferentAccount() throws Exception {
        prepareRecording();
        fail(VoiceMessageRecorder.SendStage.SERVER);
        AlertDialog dialog = (AlertDialog) get("dialog");
        controller.get().getSharedPreferences("session", Context.MODE_PRIVATE).edit()
                .putString("access_token", "other-synthetic-token").putString("user_id", "99").commit();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(recording.exists());
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Аккаунт изменился"));
    }

    @Test public void tokenRefreshCancelsUploadAndRetainsRecordingForExplicitRetry() throws Exception {
        prepareRecording();
        Call pending = new OkHttpClient().newCall(new Request.Builder().url("https://vk.com/").build());
        set("upload", pending);
        set("sending", true);
        set("sendingStage", VoiceMessageRecorder.SendStage.UPLOAD);
        controller.get().getSharedPreferences("session", Context.MODE_PRIVATE).edit()
                .putString("access_token", "new-synthetic-token").commit();
        recorder.onSessionRefreshed();
        assertTrue(pending.isCanceled());
        assertTrue(recording.exists());
        assertFalse((Boolean) get("sending"));
        AlertDialog dialog = (AlertDialog) get("dialog");
        assertEquals("Повторить", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(recording.exists());
    }

    @Test public void tokenRefreshDuringUnconfirmedSendNeverOffersRetry() throws Exception {
        prepareRecording();
        Call pending = new OkHttpClient().newCall(new Request.Builder().url("https://vk.com/").build());
        set("upload", pending);
        set("sending", true);
        set("sendingStage", VoiceMessageRecorder.SendStage.SEND);
        controller.get().getSharedPreferences("session", Context.MODE_PRIVATE).edit()
                .putString("access_token", "new-synthetic-token").commit();
        recorder.onSessionRefreshed();
        assertTrue(pending.isCanceled());
        assertFalse(recording.exists());
        AlertDialog dialog = (AlertDialog) get("dialog");
        assertTrue(((TextView) dialog.findViewById(android.R.id.message)).getText().toString().contains("Проверьте чат"));
        assertEquals("Понятно", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE) == null
                || dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getVisibility() != android.view.View.VISIBLE);
    }

    @Test public void tokenRefreshPreservesUnsentRecordingWithoutStartingTransfer() throws Exception {
        prepareRecording();
        controller.get().getSharedPreferences("session", Context.MODE_PRIVATE).edit()
                .putString("access_token", "new-synthetic-token").commit();
        recorder.onSessionRefreshed();
        assertTrue(recording.exists());
        assertNull(get("dialog"));
        assertNull(get("upload"));
    }

    @Test public void switchedAwayAndBackWithSameCredentialsInvalidatesOldTransfer() throws Exception {
        prepareRecording();
        Call pending = new OkHttpClient().newCall(new Request.Builder().url("https://vk.com/").build());
        set("upload", pending);
        set("sending", true);
        set("sendingStage", VoiceMessageRecorder.SendStage.UPLOAD);
        Session.save(controller.get(), "other-token", "99");
        Session.save(controller.get(), "synthetic-token", "42", "");
        Method active = VoiceMessageRecorder.class.getDeclaredMethod("active", int.class);
        active.setAccessible(true);
        assertFalse((Boolean) active.invoke(recorder, 7));
        recorder.onSessionRefreshed();
        assertTrue(pending.isCanceled());
        assertTrue(recording.exists());
        assertFalse((Boolean) get("sending"));
        AlertDialog dialog = (AlertDialog) get("dialog");
        assertEquals("Повторить", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
    }

    @Test public void errorDetailsExposeOnlyBoundedNumericStatus() {
        assertEquals(" (код VK 15)", VoiceMessageRecorder.safeStatus(new VoiceMessageRecorder.FailureStatus(true, 15)));
        assertEquals(" (код HTTP 403)", VoiceMessageRecorder.safeStatus(new VoiceMessageRecorder.FailureStatus(false, 403)));
        assertEquals("", VoiceMessageRecorder.safeStatus(new VoiceMessageRecorder.FailureStatus(false, 999)));
        assertEquals("", VoiceMessageRecorder.safeStatus(new VoiceMessageRecorder.FailureStatus(true, -1)));
        assertEquals("", VoiceMessageRecorder.safeStatus(new Exception("private URL or credentials")));
    }

    @Test public void serverFailureShowsStageAndSafeStatusWhileKeepingRecording() throws Exception {
        prepareRecording();
        Method method = VoiceMessageRecorder.class.getDeclaredMethod("fail", int.class,
                VoiceMessageRecorder.SendStage.class, Exception.class);
        method.setAccessible(true);
        method.invoke(recorder, 7, VoiceMessageRecorder.SendStage.SERVER,
                new VoiceMessageRecorder.FailureStatus(true, 15));
        AlertDialog dialog = (AlertDialog) get("dialog");
        String message = ((TextView) dialog.findViewById(android.R.id.message)).getText().toString();
        assertTrue(message.contains("сервер загрузки"));
        assertTrue(message.contains("код VK 15"));
        assertTrue(recording.exists());
    }
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
}
