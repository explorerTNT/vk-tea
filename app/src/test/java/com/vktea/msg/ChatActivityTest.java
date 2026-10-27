package com.vktea.msg;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.feed.ViewerPhoto;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Every transport seam is replaced; these tests never open a real conversation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class ChatActivityTest {
    private ActivityController<OfflineChat> controller;
    private OfflineChat activity;

    @After public void close() { if (controller != null) controller.close(); }

    private void open(long peerId) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), OfflineChat.class)
                .putExtra(ChatActivity.EXTRA_PEER_ID, peerId)
                .putExtra(ChatActivity.EXTRA_TITLE, "Synthetic peer");
        controller = Robolectric.buildActivity(OfflineChat.class, intent).setup();
        activity = controller.get();
    }

    private void deliver(String json) throws Exception {
        activity.pending.success(new JSONObject(json));
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void historyBindsPeerAndEveryAttachmentThroughProductionAdapter() throws Exception {
        open(42);
        assertEquals(View.VISIBLE, activity.findViewById(R.id.chat_loading).getVisibility());
        deliver("{\"response\":{\"profiles\":[{\"id\":42,\"first_name\":\"Test\","
                + "\"last_name\":\"Peer\",\"online\":1}],\"items\":[{\"id\":5,\"from_id\":42,"
                + "\"date\":1234,\"text\":\"Synthetic message\",\"attachments\":["
                + "{\"type\":\"video\",\"video\":{\"id\":9,\"owner_id\":42,\"title\":\"Video\"}},"
                + "{\"type\":\"doc\",\"doc\":{\"title\":\"Synthetic document\"}}]}]}}");
        assertEquals("Test Peer", ((TextView) activity.findViewById(R.id.chat_peer_name)).getText().toString());
        assertEquals(activity.getString(R.string.online_now),
                ((TextView) activity.findViewById(R.id.chat_peer_status)).getText().toString());
        RecyclerView list = activity.findViewById(R.id.chat_list);
        assertTrue(list.getAdapter() instanceof ChatMessagesAdapter);
        assertEquals(1, list.getAdapter().getItemCount());
        assertEquals(View.GONE, activity.findViewById(R.id.chat_loading).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.chat_state).getVisibility());
        assertEquals(1, activity.readCalls);
    }

    @Test public void chatPhotoViewerRetainsResponsePhotoIdentityAccessKeyAndCounts() throws Exception {
        open(42);
        deliver("{\"response\":{\"items\":[{\"id\":999,\"from_id\":42,\"date\":1234,\"attachments\":["
                + "{\"type\":\"photo\",\"photo\":{\"id\":73,\"owner_id\":-91,\"access_key\":\"synthetic-chat-key\","
                + "\"likes\":{\"count\":4,\"user_likes\":1},\"photo_604\":\"file:///synthetic-chat-photo.png\"}}]}]}}");
        RecyclerView list = activity.findViewById(R.id.chat_list);
        ChatMessagesAdapter adapter = (ChatMessagesAdapter) list.getAdapter();
        ChatMessagesAdapter.Holder holder = adapter.onCreateViewHolder(list, adapter.getItemViewType(0));
        adapter.onBindViewHolder(holder, 0);
        assertTrue(holder.attachments.getChildAt(0).performClick());
        Intent opened = shadowOf(activity).getNextStartedActivity();
        java.util.ArrayList<ViewerPhoto> metadata = opened.getParcelableArrayListExtra(ViewerPhoto.EXTRA_PHOTOS);
        assertEquals(73, metadata.get(0).id);
        assertEquals(-91, metadata.get(0).ownerId);
        assertEquals("synthetic-chat-key", metadata.get(0).accessKey);
        assertEquals(4, metadata.get(0).likes);
        assertTrue(metadata.get(0).liked);
        assertEquals(metadata.get(0).url, opened.getStringArrayListExtra("photos").get(0));
    }

    @Test public void failedAndMalformedHistoryOfferSingleExplicitRetryAndEmptyIsDifferent() throws Exception {
        open(42);
        activity.pending.error(new Exception("Synthetic private transport detail"));
        assertEquals(activity.getString(R.string.chat_load_failed),
                ((TextView) activity.findViewById(R.id.chat_state_text)).getText().toString());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.chat_retry).getVisibility());
        activity.findViewById(R.id.chat_retry).performClick();
        activity.findViewById(R.id.chat_retry).performClick();
        assertEquals(2, activity.requests);
        deliver("{\"response\":{}}");
        assertEquals(View.VISIBLE, activity.findViewById(R.id.chat_retry).getVisibility());
        activity.findViewById(R.id.chat_retry).performClick();
        deliver("{\"response\":{\"items\":[]}}");
        assertEquals(activity.getString(R.string.chat_empty),
                ((TextView) activity.findViewById(R.id.chat_state_text)).getText().toString());
        assertEquals(View.GONE, activity.findViewById(R.id.chat_retry).getVisibility());
        assertEquals(3, activity.requests);
    }

    @Test public void composerOnlySubmitsNonblankTextThroughExplicitClick() {
        open(42);
        EditText input = activity.findViewById(R.id.chat_input);
        View send = activity.findViewById(R.id.chat_send);
        assertFalse(send.isEnabled());
        input.setText("  \n  ");
        assertFalse(send.isEnabled());
        input.setText("  Local synthetic draft  ");
        assertTrue(send.isEnabled());
        assertEquals(0, activity.sendCalls);
        send.performClick();
        assertEquals(1, activity.sendCalls);
        assertEquals("Local synthetic draft", activity.sent);
        assertEquals("", input.getText().toString());
        assertFalse(send.isEnabled());
        assertNull(input.getBackground());
    }

    @Test public void longPressServerMessageOffersForwardActionWithoutSending() throws Exception {
        open(42);
        deliver("{\"response\":{\"items\":[{\"id\":15,\"from_id\":42,\"text\":\"Synthetic\"}]}}");
        RecyclerView list = activity.findViewById(R.id.chat_list);
        ChatMessagesAdapter adapter = (ChatMessagesAdapter) list.getAdapter();
        ChatMessagesAdapter.Holder holder = adapter.onCreateViewHolder(list, adapter.getItemViewType(0));
        adapter.onBindViewHolder(holder, 0);
        assertTrue(holder.bubble.performLongClick());
        androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog)
                org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertEquals(activity.getString(R.string.chat_forward_action), dialog.getListView().getItemAtPosition(0));
        assertEquals(0, activity.sendCalls);
    }

    @Test public void invalidPeerNeverLoadsMarksReadOrSends() {
        open(0);
        assertEquals(0, activity.requests);
        assertEquals(0, activity.readCalls);
        assertFalse(activity.findViewById(R.id.chat_input).isEnabled());
        assertEquals(activity.getString(R.string.chat_unavailable),
                ((TextView) activity.findViewById(R.id.chat_state_text)).getText().toString());
    }

    @Test public void openingDialogPassesItsAvatarToChatHeader() {
        open(42);
        DialogsAdapter dialogs = new DialogsAdapter();
        dialogs.add(new Dialog(77, "Synthetic person", "file:///synthetic-avatar.png", "", 1234, false));
        RecyclerView parent = new RecyclerView(activity);
        parent.setLayoutManager(new LinearLayoutManager(activity));
        DialogsAdapter.DialogViewHolder holder = dialogs.onCreateViewHolder(parent, 0);
        dialogs.onBindViewHolder(holder, 0);
        holder.itemView.performClick();
        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertEquals(77, opened.getLongExtra(ChatActivity.EXTRA_PEER_ID, 0));
        assertEquals("Synthetic person", opened.getStringExtra(ChatActivity.EXTRA_TITLE));
        assertEquals("file:///synthetic-avatar.png", opened.getStringExtra(ChatActivity.EXTRA_PHOTO));
    }

    @Test public void lateHistoryDoesNotChangeDestroyedActivity() throws Exception {
        open(42);
        VKApi.Callback old = activity.pending;
        controller.pause().stop().destroy();
        controller = null;
        old.success(new JSONObject("{\"response\":{\"items\":[{\"id\":1,\"text\":\"Late\"}]}}"));
        old.error(new Exception("Late synthetic error"));
        RecyclerView list = activity.findViewById(R.id.chat_list);
        assertEquals(0, list.getAdapter().getItemCount());
    }

    @Test public void historyArrivingAfterSendKeepsLocalMessage() throws Exception {
        open(42);
        activity.useRealSend = true;
        ((EditText) activity.findViewById(R.id.chat_input)).setText("Synthetic local message");
        activity.findViewById(R.id.chat_send).performClick();
        deliver("{\"response\":{\"items\":[]}}");
        RecyclerView list = activity.findViewById(R.id.chat_list);
        assertEquals(1, list.getAdapter().getItemCount());
        assertEquals(View.GONE, activity.findViewById(R.id.chat_state).getVisibility());
        activity.sending.success(new JSONObject("{\"response\":17}"));
        assertEquals(1, list.getAdapter().getItemCount());
    }

    @Test public void acceptedServerIdDeduplicatesLocalRowIfAlreadyLoadedInHistory() throws Exception {
        open(42);
        activity.useRealSend = true;
        ((EditText) activity.findViewById(R.id.chat_input)).setText("Synthetic local message");
        activity.findViewById(R.id.chat_send).performClick();
        deliver("{\"response\":{\"items\":[{\"id\":17,\"out\":1,\"text\":\"Synthetic local message\"}]}}");
        activity.sending.success(new JSONObject("{\"response\":17}"));
        RecyclerView list = activity.findViewById(R.id.chat_list);
        assertEquals(1, list.getAdapter().getItemCount());
    }

    @Test public void uncertainSendRemainsVisibleWithoutAutomaticRetry() throws Exception {
        open(42);
        deliver("{\"response\":{\"items\":[]}}");
        activity.useRealSend = true;
        ((EditText) activity.findViewById(R.id.chat_input)).setText("Synthetic message");
        activity.findViewById(R.id.chat_send).performClick();
        assertTrue(localStatus().contains("Отправка…"));
        activity.sending.error(new Exception("Synthetic transport failure"));
        assertTrue(localStatus().contains("Отправка не подтверждена"));
        assertEquals(1, activity.requestSendCalls);
        activity.onLiveMessagesChanged();
        deliver("{\"response\":{\"items\":[]}}");
        assertTrue(localStatus().contains("Отправка не подтверждена"));
        assertEquals(1, activity.requestSendCalls);
    }

    @Test public void missingServerIdIsUnconfirmedAndAcceptedIdIsNotMarkedRead() throws Exception {
        open(42);
        deliver("{\"response\":{\"items\":[]}}");
        activity.useRealSend = true;
        ((EditText) activity.findViewById(R.id.chat_input)).setText("Synthetic message");
        activity.findViewById(R.id.chat_send).performClick();
        activity.sending.success(new JSONObject("{\"response\":0}"));
        assertTrue(localStatus().contains("Отправка не подтверждена"));
        ((EditText) activity.findViewById(R.id.chat_input)).setText("Another synthetic message");
        activity.findViewById(R.id.chat_send).performClick();
        activity.sending.success(new JSONObject("{\"response\":17}"));
        RecyclerView list = activity.findViewById(R.id.chat_list);
        ChatMessagesAdapter adapter = (ChatMessagesAdapter) list.getAdapter();
        ChatMessagesAdapter.Holder holder = adapter.onCreateViewHolder(list, adapter.getItemViewType(1));
        adapter.onBindViewHolder(holder, 1);
        assertFalse(holder.time.getText().toString().contains("✓"));
        assertFalse(holder.time.getText().toString().contains("Отправка"));
        assertEquals(2, activity.requestSendCalls);
    }

    private String localStatus() {
        RecyclerView list = activity.findViewById(R.id.chat_list);
        ChatMessagesAdapter adapter = (ChatMessagesAdapter) list.getAdapter();
        ChatMessagesAdapter.Holder holder = adapter.onCreateViewHolder(list, adapter.getItemViewType(0));
        adapter.onBindViewHolder(holder, 0);
        return holder.time.getText().toString();
    }

    @Test public void liveEventDuringHistoryLoadTriggersOneFollowUpAndMarksNewIncomingRead() throws Exception {
        open(42);
        activity.onLiveMessagesChanged();
        activity.onLiveMessagesChanged();
        assertEquals(1, activity.requests);
        deliver("{\"response\":{\"items\":[{\"id\":1,\"from_id\":42,\"text\":\"first\"}]}}");
        assertEquals(2, activity.requests);
        deliver("{\"response\":{\"items\":[{\"id\":2,\"from_id\":42,\"text\":\"new\"},{\"id\":1,\"from_id\":42,\"text\":\"first\"}]}}");
        assertEquals(2, ((RecyclerView) activity.findViewById(R.id.chat_list)).getAdapter().getItemCount());
        assertEquals(2, activity.readCalls);
    }

    @Test @Config(sdk = 35)
    public void imeInsetReplacesNavigationInsetAndPreservesSideAndStatusInsets() {
        open(42);
        View root = activity.findViewById(R.id.chat_root);
        WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(12, 24, 16, 32))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 300)).build();
        ViewCompat.dispatchApplyWindowInsets(root, insets);
        assertEquals(12, root.getPaddingLeft());
        assertEquals(24, root.getPaddingTop());
        assertEquals(16, root.getPaddingRight());
        assertEquals(300, root.getPaddingBottom());
    }

    public static class OfflineChat extends ChatActivity {
        @Override protected void subscribeLiveUpdates() { }
        @Override protected void unsubscribeLiveUpdates() { }
        VKApi.Callback pending;
        VKApi.Callback sending;
        boolean useRealSend;
        int requests;
        int readCalls;
        int sendCalls;
        int requestSendCalls;
        String sent;
        @Override protected void requestHistory(VKApi.Callback callback) { requests++; pending = callback; }
        @Override protected void markRead() { readCalls++; }
        @Override protected void sendMessage(String text) {
            if (useRealSend) super.sendMessage(text);
            else { sendCalls++; sent = text; }
        }
        @Override protected void requestSend(String text, VKApi.Callback callback) { requestSendCalls++; sending = callback; }
    }
}
