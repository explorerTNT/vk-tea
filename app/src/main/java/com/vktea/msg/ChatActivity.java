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

import java.net.URLEncoder;
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
    private boolean loading;
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
        adapter = new ChatMessagesAdapter(this::openAttachment);
        list.setAdapter(adapter);
        loadingView = findViewById(R.id.chat_loading);
        stateView = findViewById(R.id.chat_state);
        stateText = findViewById(R.id.chat_state_text);
        retry = findViewById(R.id.chat_retry);
        retry.setOnClickListener(v -> loadHistory());

        View send = findViewById(R.id.chat_send);
        EditText input = findViewById(R.id.chat_input);
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

    private void loadHistory() {
        if (loading || peerId == 0) return;
        loading = true;
        loadingView.setVisibility(View.VISIBLE);
        stateView.setVisibility(View.GONE);
        requestHistory(new VKApi.Callback() {
            @Override
            public void success(JSONObject response) {
                try {
                    JSONObject data = response == null ? null : response.optJSONObject("response");
                    if (data == null || data.optJSONArray("items") == null) {
                        showHistoryError();
                        return;
                    }
                    List<ChatMessage> messages = ChatHistoryParser.parse(response, peerId);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        loading = false;
                        loadingView.setVisibility(View.GONE);
                        historyMessages.clear();
                        historyMessages.addAll(messages);
                        renderMessages();
                        updatePeer(data);
                        if (adapter.getItemCount() == 0) showState(R.string.chat_empty, false);
                        else list.scrollToPosition(adapter.getItemCount() - 1);
                    });
                } catch (Exception ignored) {
                    showHistoryError();
                }
            }
            @Override public void error(Exception e) { showHistoryError(); }
        });
    }

    private void showHistoryError() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            loading = false;
            showState(R.string.chat_load_failed, true);
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
                System.currentTimeMillis() / 1000L, Collections.emptyList());
        LocalMessage local = new LocalMessage(message);
        localMessages.add(local);
        adapter.add(message);
        list.scrollToPosition(adapter.getItemCount() - 1);
        requestSend(text, new VKApi.Callback() {
                    @Override public void success(JSONObject response) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            local.serverId = response.optLong("response", 0);
                            if (local.serverId > 0) {
                                for (ChatMessage loaded : historyMessages) {
                                    if (loaded.id == local.serverId) {
                                        renderMessages();
                                        break;
                                    }
                                }
                            }
                        });
                    }
                    @Override public void error(Exception e) {
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()) Toast.makeText(ChatActivity.this,
                                    R.string.send_failed, Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    protected void requestSend(String text, VKApi.Callback callback) {
        VKApi.get(this, "messages.send", "peer_id=" + peerId + "&message=" + urlEncode(text), callback);
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
        final ChatMessage message;
        long serverId;
        LocalMessage(ChatMessage message) { this.message = message; }
    }

    private void openAttachment(ChatAttachment attachment) {
        if (("video".equals(attachment.type) || "clip".equals(attachment.type)) && attachment.id != 0) {
            startActivity(new Intent(this, VideoPlayerActivity.class)
                    .putExtra("owner_id", attachment.ownerId).putExtra("video_id", attachment.id)
                    .putExtra("access_key", attachment.accessKey));
        } else if (("wall".equals(attachment.type) || "wall_reply".equals(attachment.type))
                && attachment.ownerId != 0 && attachment.id != 0) {
            com.vktea.feed.PostActivity.startById(this, attachment.ownerId, attachment.id);
        } else if (!attachment.url.isEmpty()
                && ("link".equals(attachment.type) || "story".equals(attachment.type))) {
            startActivity(new Intent(this, com.vktea.auth.AuthWebViewActivity.class)
                    .setData(android.net.Uri.parse(attachment.url)));
        } else if (!attachment.previewUrl.isEmpty()) {
            ArrayList<String> photos = new ArrayList<>();
            ViewerPhoto metadata = attachment.viewerPhoto;
            photos.add(metadata != null && !metadata.url.isEmpty() ? metadata.url
                    : ("photo".equals(attachment.type) || "posted_photo".equals(attachment.type)) && !attachment.url.isEmpty()
                    ? attachment.url : attachment.previewUrl);
            PostPhotosRenderer.openViewer(this, photos, Collections.singletonList(metadata), 0);
        }
    }

    private static String urlEncode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (Exception ignored) { return value; }
    }
}
