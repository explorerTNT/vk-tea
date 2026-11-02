package com.vktea.msg;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.os.Looper;
import android.widget.EditText;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class ChatSessionTest {
    private ActivityController<ChatActivityTest.OfflineChat> controller;
    private ChatActivityTest.OfflineChat activity;
    private File recording;
    @Before public void open() {
        Session.save(RuntimeEnvironment.getApplication(), "token-a", "42");
        idle();
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), ChatActivityTest.OfflineChat.class)
                .putExtra(ChatActivity.EXTRA_PEER_ID, 99L).putExtra(ChatActivity.EXTRA_TITLE, "Private peer");
        controller = Robolectric.buildActivity(ChatActivityTest.OfflineChat.class, intent).setup();
        activity = controller.get();
    }
    @After public void close() { if (controller != null) controller.close(); if (recording != null) recording.delete(); }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private void deliver(VKApi.Callback callback) throws Exception {
        callback.success(new JSONObject("{\"response\":{\"items\":[{\"id\":5,\"from_id\":99,\"text\":\"Private A\"}]}}"));
        idle();
    }
    private void stageRecording() throws Exception {
        VoiceMessageRecorder recorder = ReflectionHelpers.getField(activity, "voiceRecorder");
        recording = File.createTempFile("synthetic-voice", ".ogg", activity.getCacheDir());
        ReflectionHelpers.setField(recorder, "file", recording);
    }
    @Test public void switchingAccountClearsVisibleConversationDraftAndRecording() throws Exception {
        VKApi.Callback old = activity.pending;
        deliver(old);
        ((EditText) activity.findViewById(R.id.chat_input)).setText("Private draft");
        stageRecording();
        Session.save(activity, "token-b", "43");
        idle();
        assertTrue(activity.isFinishing());
        assertEquals("", ((EditText) activity.findViewById(R.id.chat_input)).getText().toString());
        assertEquals("", ((TextView) activity.findViewById(R.id.chat_peer_name)).getText().toString());
        assertEquals(0, ((RecyclerView) activity.findViewById(R.id.chat_list)).getAdapter().getItemCount());
        assertFalse(recording.exists());
        deliver(old);
        assertEquals(0, ((RecyclerView) activity.findViewById(R.id.chat_list)).getAdapter().getItemCount());
    }
    @Test public void logoutClosesAndClearsConversation() throws Exception {
        deliver(activity.pending);
        Session.clear(activity);
        idle();
        assertTrue(activity.isFinishing());
        assertEquals(0, ((RecyclerView) activity.findViewById(R.id.chat_list)).getAdapter().getItemCount());
    }
    @Test public void sameUserRefreshPreservesDraftAndRecordingAndRejectsOldHistory() throws Exception {
        VKApi.Callback old = activity.pending;
        deliver(old);
        ((EditText) activity.findViewById(R.id.chat_input)).setText("My unsent draft");
        stageRecording();
        Session.save(activity, "refreshed-token", "42");
        idle();
        assertFalse(activity.isFinishing());
        assertEquals("My unsent draft", ((EditText) activity.findViewById(R.id.chat_input)).getText().toString());
        assertTrue(recording.exists());
        assertEquals(2, activity.requests);
        old.success(new JSONObject("{\"response\":{\"items\":[]}}"));
        idle();
        assertEquals(1, ((RecyclerView) activity.findViewById(R.id.chat_list)).getAdapter().getItemCount());
        assertEquals(0, activity.requestSendCalls);
    }
}
