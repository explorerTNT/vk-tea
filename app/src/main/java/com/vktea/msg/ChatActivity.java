package com.vktea.msg;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.feed.PostPhotosRenderer;
import com.vktea.feed.ViewerPhoto;
import com.vktea.feed.VideoPlayerActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChatActivity extends AppCompatActivity {
    public static final String EXTRA_PEER_ID = "peer_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_PHOTO = "photo";

    private long peerId;
    private ChatMessagesAdapter adapter;
    private RecyclerView list;
    private View loadingView;
    private View stateView;
    private TextView stateText;
    private View retry;
    private ImageView peerAvatar;
    private TextView peerName;
    private TextView peerStatus;
    private MessageForwarder forwarder;
    private final Set<MessageForwarder> textSends = new HashSet<>();
    private boolean forwardBusy;
    private VoiceMessagePlayer voicePlayer;
    private VoiceMessageRecorder voiceRecorder;
    private ChatFileSender fileSender;
    private boolean loading;
    private boolean livePending;
    private boolean liveStarted;
    private boolean startedOnce;
    private final MessageLongPoll.Listener liveUpdates = this::onLiveMessagesChanged;

    protected void onLiveMessagesChanged() {
        if (loading) livePending = true;
        else loadHistory(true);
    }
    private long localId = -100;
    private final List<ChatMessage> historyMessages = new ArrayList<>();
    private final List<LocalMessage> localMessages = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_chat);
        View root = findViewById(R.id.chat_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightNavigationBars(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        peerId = getIntent().getLongExtra(EXTRA_PEER_ID, 0);
        Toolbar toolbar = findViewById(R.id.chat_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        peerAvatar = findViewById(R.id.chat_peer_avatar);
        peerName = findViewById(R.id.chat_peer_name);
        peerStatus = findViewById(R.id.chat_peer_status);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        peerName.setText(title == null || title.isEmpty() ? getString(R.string.section_messages) : title);
        bindPeerPhoto(getIntent().getStringExtra(EXTRA_PHOTO));

        list = findViewById(R.id.chat_list);
        LinearLayoutManager layout = new LinearLayoutManager(this);
        layout.setStackFromEnd(true);
        list.setLayoutManager(layout);
        voicePlayer = createVoicePlayer();
        adapter = new ChatMessagesAdapter(new ChatMessagesAdapter.Callback() {
            @Override public void onAttachmentClicked(ChatAttachment attachment) { openAttachment(attachment); }
            @Override public void onMessageLongClicked(ChatMessage message) { showMessageActions(message); }
        });
        list.setAdapter(adapter);
        loadingView = findViewById(R.id.chat_loading);
        stateView = findViewById(R.id.chat_state);
        stateText = findViewById(R.id.chat_state_text);
        retry = findViewById(R.id.chat_retry);
        retry.setOnClickListener(v -> loadHistory());

        View send = findViewById(R.id.chat_send);
        EditText input = findViewById(R.id.chat_input);
        voiceRecorder = createVoiceRecorder(peerId, this::onLiveMessagesChanged);
        fileSender = createFileSender(peerId, this::onLiveMessagesChanged);
        View attach = findViewById(R.id.chat_attach);
        attach.setEnabled(peerId != 0);
        attach.setOnClickListener(v -> fileSender.choose());
        View voice = findViewById(R.id.chat_voice);
        voice.setEnabled(peerId != 0);
        voice.setOnClickListener(v -> voiceRecorder.start());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                send.setEnabled(s.toString().trim().length() > 0);
                send.setAlpha(send.isEnabled() ? 1f : .4f);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        send.setEnabled(!input.getText().toString().trim().isEmpty());
        send.setAlpha(send.isEnabled() ? 1f : .4f);
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty() || peerId == 0) return;
            input.setText("");
            sendMessage(text);
        });

        if (peerId == 0) {
            showState(R.string.chat_unavailable, false);
            input.setEnabled(false);
        } else {
            loadHistory();
            markRead();
        }
        ViewCompat.requestApplyInsets(root);
    }

    // Factories let the isolated QA build use synthetic transports without altering user sessions.
    protected VoiceMessagePlayer createVoicePlayer() { return new VoiceMessagePlayer(this); }
    protected VoiceMessageRecorder createVoiceRecorder(long peer, Runnable sent) { return new VoiceMessageRecorder(this, peer, sent); }
    protected ChatFileSender createFileSender(long peer, Runnable sent) { return new ChatFileSender(this, peer, sent); }
    MessageForwarder createMessageForwarder() { return new MessageForwarder(this); }

    /** Separate transport boundary lets offline UI tests use synthetic history only. */
    protected void requestHistory(VKApi.Callback callback) {
        VKApi.get(this, "messages.getHistory", "peer_id=" + peerId
                + "&count=50&rev=0&extended=1&photo_sizes=1"
                + "&fields=first_name,last_name,photo_200,photo_100,photo_50,online,last_seen", callback);
    }

    protected void markRead() {
        VKApi.get(this, "messages.markAsRead", "peer_id=" + peerId, new VKApi.Callback() {
            @Override public void success(JSONObject response) { }
            @Override public void error(Exception e) { }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        if (peerId != 0) {
            liveStarted = true;
            subscribeLiveUpdates();
            if (startedOnce) liveUpdates.onMessagesChanged();
            startedOnce = true;
        }
    }

    @Override protected void onStop() {
        liveStarted = false;
        unsubscribeLiveUpdates();
        if (voicePlayer != null) voicePlayer.close();
        if (voiceRecorder != null) voiceRecorder.close();
        super.onStop();
    }

    protected void subscribeLiveUpdates() { MessageLongPoll.get(this).addListener(liveUpdates); }
    protected void unsubscribeLiveUpdates() { MessageLongPoll.get(this).removeListener(liveUpdates); }

    @Override protected void onDestroy() {
        if (forwarder != null) forwarder.close();
        for (MessageForwarder send : textSends) send.close();
        textSends.clear();
        if (fileSender != null) fileSender.close();
        super.onDestroy();
    }

    private void loadHistory() { loadHistory(false); }

    private void loadHistory(boolean silent) {
        if (loading || peerId == 0) return;
        loading = true;
        if (!silent) {
            loadingView.setVisibility(View.VISIBLE);
            stateView.setVisibility(View.GONE);
        }
        requestHistory(new VKApi.Callback() {
            @Override
            public void success(JSONObject response) {
                try {
                    JSONObject data = response == null ? null : response.optJSONObject("response");
                    if (data == null || data.optJSONArray("items") == null) {
                        showHistoryError(silent);
                        return;
                    }
                    List<ChatMessage> messages = ChatHistoryParser.parse(response, peerId);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        loading = false;
                        loadingView.setVisibility(View.GONE);
                        boolean atEnd = adapter.getItemCount() == 0 ||
                                ((LinearLayoutManager) list.getLayoutManager()).findLastVisibleItemPosition()
                                        >= adapter.getItemCount() - 2;
                        Set<Long> previousIds = new HashSet<>();
                        for (ChatMessage old : historyMessages) previousIds.add(old.id);
                        boolean newIncoming = false;
                        if (silent) for (ChatMessage message : messages) {
                            if (!message.out && !previousIds.contains(message.id)) { newIncoming = true; break; }
                        }
                        historyMessages.clear();
                        historyMessages.addAll(messages);
                        renderMessages();
                        updatePeer(data);
                        if (adapter.getItemCount() == 0) showState(R.string.chat_empty, false);
                        else {
                            stateView.setVisibility(View.GONE);
                            if (!silent || atEnd) list.scrollToPosition(adapter.getItemCount() - 1);
                        }
                        if (newIncoming && liveStarted) markRead();
                        if (livePending && liveStarted) { livePending = false; loadHistory(true); }
                    });
                } catch (Exception ignored) {
                        showHistoryError(silent);
                }
            }
            @Override public void error(Exception e) { showHistoryError(silent); }
        });
    }

    private void showHistoryError() { showHistoryError(false); }

    private void showHistoryError(boolean silent) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            loading = false;
            if (!silent || adapter.getItemCount() == 0) showState(R.string.chat_load_failed, true);
            if (livePending && liveStarted) { livePending = false; loadHistory(true); }
        });
    }

    private void showState(int text, boolean canRetry) {
        loadingView.setVisibility(View.GONE);
        stateText.setText(text);
        retry.setVisibility(canRetry ? View.VISIBLE : View.GONE);
        stateView.setVisibility(View.VISIBLE);
    }

    private void updatePeer(JSONObject data) {
        if (data == null) return;
        JSONArray peers = data.optJSONArray(peerId < 0 ? "groups" : "profiles");
        if (peers == null) return;
        for (int i = 0; i < peers.length(); i++) {
            JSONObject peer = peers.optJSONObject(i);
            if (peer == null || peer.optLong("id") != Math.abs(peerId)) continue;
            String name = peerId < 0 ? peer.optString("name")
                    : (peer.optString("first_name") + " " + peer.optString("last_name")).trim();
            if (!name.isEmpty()) peerName.setText(name);
            bindPeerPhoto(MessageAvatars.photoUrl(peer));
            if (peer.optInt("online") == 1) {
                peerStatus.setText(R.string.online_now);
                peerStatus.setVisibility(View.VISIBLE);
            } else {
                JSONObject seen = peer.optJSONObject("last_seen");
                long time = seen == null ? 0 : seen.optLong("time");
                peerStatus.setVisibility(time > 0 ? View.VISIBLE : View.GONE);
                if (time > 0) peerStatus.setText(getString(R.string.last_seen_fmt,
                        DateUtils.getRelativeTimeSpanString(time * 1000L,
                                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)));
            }
            break;
        }
    }

    private void bindPeerPhoto(String photo) {
        MessageAvatars.bind(peerAvatar, photo, peerId < 0 || peerId >= 2_000_000_000L);
    }

    protected void sendMessage(String text) {
        stateView.setVisibility(View.GONE);
        ChatMessage message = new ChatMessage(--localId, true, 0, "", "", text,
                System.currentTimeMillis() / 1000L, Collections.emptyList())
                .withSendStatus(ChatMessage.SendStatus.SENDING);
        LocalMessage local = new LocalMessage(message);
        localMessages.add(local);
        adapter.add(message);
        list.scrollToPosition(adapter.getItemCount() - 1);
        requestSend(text, new VKApi.Callback() {
                    @Override public void success(JSONObject response) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            local.serverId = response == null ? 0 : response.optLong("response", 0);
                            local.message = local.message.withSendStatus(local.serverId > 0
                                    ? ChatMessage.SendStatus.NONE : ChatMessage.SendStatus.UNCONFIRMED);
                            renderMessages();
                        });
                    }
                    @Override public void error(Exception e) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            local.message = local.message.withSendStatus(ChatMessage.SendStatus.UNCONFIRMED);
                            renderMessages();
                            Toast.makeText(ChatActivity.this, R.string.send_failed, Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    protected void requestSend(String text, VKApi.Callback callback) {
        MessageForwarder send = createMessageForwarder();
        textSends.add(send);
        send.sendText(peerId, text, new MessageForwarder.Callback() {
            @Override public void complete(JSONObject response) {
                textSends.remove(send);
                callback.success(response);
                send.close();
            }
            @Override public void failed() {
                textSends.remove(send);
                callback.error(new Exception("Send could not be confirmed"));
                send.close();
            }
        });
    }

    /** History may arrive after an explicit send; keep local rows until their server IDs appear. */
    private void renderMessages() {
        List<ChatMessage> displayed = new ArrayList<>(historyMessages);
        Set<Long> loadedIds = new HashSet<>();
        for (ChatMessage message : historyMessages) loadedIds.add(message.id);
        for (LocalMessage local : localMessages) {
            if (local.serverId <= 0 || !loadedIds.contains(local.serverId)) displayed.add(local.message);
        }
        displayed.sort(Comparator.comparingLong(message -> message.date));
        adapter.replaceAll(displayed);
    }

    private static final class LocalMessage {
        ChatMessage message;
        long serverId;
        LocalMessage(ChatMessage message) { this.message = message; }
    }

    private void openAttachment(ChatAttachment attachment) {
        if ("audio_message".equals(attachment.type)) {
            voicePlayer.open(attachment.url);
            return;
        }
        if (ChatAttachmentActions.open(this, attachment)) return;
        if (("video".equals(attachment.type) || "clip".equals(attachment.type)) && attachment.id != 0) {
            startActivity(new Intent(this, VideoPlayerActivity.class)
                    .putExtra("owner_id", attachment.ownerId).putExtra("video_id", attachment.id)
                    .putExtra("access_key", attachment.accessKey));
        } else if (!attachment.previewUrl.isEmpty()) {
            ArrayList<String> photos = new ArrayList<>();
            ViewerPhoto metadata = attachment.viewerPhoto;
            photos.add(metadata != null && !metadata.url.isEmpty() ? metadata.url
                    : ("photo".equals(attachment.type) || "posted_photo".equals(attachment.type)) && !attachment.url.isEmpty()
                    ? attachment.url : attachment.previewUrl);
            PostPhotosRenderer.openViewer(this, photos, Collections.singletonList(metadata), 0);
        }
    }

    private void showMessageActions(ChatMessage message) {
        if (message.id <= 0) return;
        new AlertDialog.Builder(this).setItems(new String[]{getString(R.string.chat_forward_action)},
                (dialog, which) -> chooseForwardRecipient(message.id)).show();
    }

    private void chooseForwardRecipient(long messageId) {
        if (forwardBusy) return;
        forwardBusy = true;
        if (forwarder != null) forwarder.close();
        MessageForwarder operation = createMessageForwarder();
        forwarder = operation;
        operation.recipients(new MessageForwarder.Callback() {
            @Override public void complete(JSONObject response) {
                if (isFinishing() || isDestroyed() || forwarder != operation) return;
                JSONObject data = response.optJSONObject("response");
                if (data == null || data.optJSONArray("items") == null) { failed(); return; }
                List<Dialog> dialogs = DialogsParser.parse(response);
                if (dialogs.isEmpty()) {
                    forwardBusy = false;
                    Toast.makeText(ChatActivity.this, R.string.chat_forward_no_recipients, Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] titles = new String[dialogs.size()];
                for (int i = 0; i < dialogs.size(); i++) titles[i] = dialogs.get(i).title;
                new AlertDialog.Builder(ChatActivity.this).setTitle(R.string.chat_forward_choose)
                        .setItems(titles, (dialog, which) -> sendForward(operation, dialogs.get(which).peerId, messageId))
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> forwardBusy = false)
                        .setOnCancelListener(dialog -> forwardBusy = false).show();
            }
            @Override public void failed() {
                forwardBusy = false;
                if (!isFinishing() && !isDestroyed() && forwarder == operation)
                    Toast.makeText(ChatActivity.this, R.string.chat_forward_load_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void sendForward(MessageForwarder operation, long destination, long messageId) {
        operation.send(destination, messageId, new MessageForwarder.Callback() {
            @Override public void complete(JSONObject response) {
                forwardBusy = false;
                if (!isFinishing() && !isDestroyed()) Toast.makeText(ChatActivity.this,
                        R.string.chat_forward_sent, Toast.LENGTH_SHORT).show();
            }
            @Override public void failed() {
                forwardBusy = false;
                if (!isFinishing() && !isDestroyed()) Toast.makeText(ChatActivity.this,
                        R.string.chat_forward_send_failed, Toast.LENGTH_LONG).show();
            }
        });
    }

}
