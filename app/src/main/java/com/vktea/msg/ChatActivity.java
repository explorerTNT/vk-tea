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
import com.vktea.auth.Session;
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
import java.util.Objects;

public class ChatActivity extends AppCompatActivity {
    public static final String EXTRA_PEER_ID = "peer_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_PHOTO = "photo";

    private long peerId;
    private boolean isSelfChat;
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
    private android.widget.LinearLayout contextBar;
    private android.widget.ImageView contextIcon;
    private android.widget.TextView contextText;
    private EditText inputView;
    private long replyMessageId;
    private long editingMessageId;
    private final List<Long> barForwardIds = new ArrayList<>();
    private final Set<Long> selectedIds = new HashSet<>();
    private androidx.appcompat.view.ActionMode selectionMode;
    private VoiceMessagePlayer voicePlayer;
    private VoiceMessageRecorder voiceRecorder;
    private ChatFileSender fileSender;
    private boolean loading;
    private boolean livePending;
    private boolean liveStarted;
    private boolean startedOnce;
    private long sessionGeneration;
    @androidx.annotation.Nullable private String sessionUser;
    private long historyGeneration;
    private boolean sessionEnded;
    private final Runnable sessionUpdates = this::synchronizeSession;
    private final MessageLongPoll.Listener liveUpdates = this::onLiveMessagesChanged;

    protected void onLiveMessagesChanged() {
        if (loading) livePending = true;
        else {
            loadHistory(true);
            // Курсор out_read и закреп меняются при событиях LongPoll (прочтение, закреп).
            loadPinnedMessage();
        }
    }
    private long localId = -100;
    private final List<ChatMessage> historyMessages = new ArrayList<>();
    private final List<ChatMessage> rewoundMessages = new ArrayList<>();
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
        Session.Snapshot initialSession = Session.snapshot(this);
        sessionGeneration = initialSession.generation;
        sessionUser = initialSession.userId;
        Session.addChangeListener(sessionUpdates);
        isSelfChat = peerId != 0 && peerId == DialogsParser.selfId();
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
        if (isSelfChat) {
            peerName.setText(R.string.favorites);
            peerStatus.setVisibility(View.GONE);
            peerAvatar.setImageResource(R.drawable.ic_favorites);
            peerAvatar.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            int pad = (int) android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP, 6, getResources().getDisplayMetrics());
            peerAvatar.setPadding(pad, pad, pad, pad);
        } else {
            peerName.setText(title == null || title.isEmpty() ? getString(R.string.section_messages) : title);
            bindPeerPhoto(getIntent().getStringExtra(EXTRA_PHOTO));
        }

        list = findViewById(R.id.chat_list);
        LinearLayoutManager layout = new LinearLayoutManager(this);
        layout.setStackFromEnd(true);
        list.setLayoutManager(layout);
        voicePlayer = createVoicePlayer();
        voicePlayer.setObserver((attachment, playing, position, duration) -> {
            if (adapter != null) adapter.setVoicePlayback(attachment, playing, position, duration);
        });
        adapter = new ChatMessagesAdapter(chatCallback(), peerId);
        adapter = new ChatMessagesAdapter(chatCallback(), peerId);
        list.setAdapter(adapter);
        loadingView = findViewById(R.id.chat_loading);
        stateView = findViewById(R.id.chat_state);
        stateText = findViewById(R.id.chat_state_text);
        retry = findViewById(R.id.chat_retry);
        retry.setOnClickListener(v -> loadHistory());

        View send = findViewById(R.id.chat_send);
        EditText input = findViewById(R.id.chat_input);
        inputView = input;
        contextBar = findViewById(R.id.chat_context_bar);
        contextIcon = findViewById(R.id.chat_context_icon);
        contextText = findViewById(R.id.chat_context_text);
        findViewById(R.id.chat_context_cancel).setOnClickListener(v -> resetComposerContext());
        voiceRecorder = createVoiceRecorder(peerId, this::onLiveMessagesChanged);
        fileSender = createFileSender(peerId, this::onLiveMessagesChanged);
        View attach = findViewById(R.id.chat_attach);
        attach.setEnabled(peerId != 0);
        attach.setOnClickListener(v -> { if (synchronizeSession()) fileSender.choose(); });
        View voice = findViewById(R.id.chat_voice);
        voice.setEnabled(peerId != 0);
        voice.setOnClickListener(v -> { if (synchronizeSession()) voiceRecorder.start(); });
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
            if (!synchronizeSession()) return;
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
            loadPinnedMessage();
        }
        ViewCompat.requestApplyInsets(root);
    }

    // Factories let the isolated QA build use synthetic transports without altering user sessions.
    protected VoiceMessagePlayer createVoicePlayer() { return new VoiceMessagePlayer(this); }

    private ChatMessagesAdapter.Callback chatCallback() {
        return new ChatMessagesAdapter.Callback() {
            @Override public void onAttachmentClicked(ChatAttachment attachment) { openAttachment(attachment); }
            @Override public void onVoiceSeek(ChatAttachment attachment, float fraction) {
                voicePlayer.seek(attachment, fraction);
            }
            @Override public void onMessageLongClicked(ChatMessage message) { showMessageActions(message); }
            @Override public void onMessageClicked(ChatMessage message) { toggleSelection(message); }
            @Override public void onAuthorClicked(long fromId) { openAuthorProfile(fromId); }
            @Override public void onQuoteClicked(long messageId) { scrollToMessage(messageId); }
            @Override public void onAttachmentLongClicked(ChatAttachment attachment) {
                showAttachmentActions(attachment);
            }
            @Override public void onLinkClicked(String url) {
                try {
                    startActivity(new Intent(ChatActivity.this, com.vktea.auth.AuthWebViewActivity.class)
                            .setData(android.net.Uri.parse(url)));
                } catch (Exception ignored) {
                    Toast.makeText(ChatActivity.this, R.string.chat_attachment_open_failed,
                            Toast.LENGTH_SHORT).show();
                }
            }
        };
    }    protected VoiceMessageRecorder createVoiceRecorder(long peer, Runnable sent) { return new VoiceMessageRecorder(this, peer, sent); }
    protected ChatFileSender createFileSender(long peer, Runnable sent) { return new ChatFileSender(this, peer, sent); }
    MessageForwarder createMessageForwarder() { return new MessageForwarder(this); }

    /** Separate transport boundary lets offline UI tests use synthetic history only. */
    protected void requestHistory(VKApi.Callback callback) {
        // 5.92: reply_message возвращается полноценным объектом с id оригинала
        // (на 5.82 ответ вырождается в урезанный fwd без id - живая проверка 18.11.2026).
        VKApi.get(this, "messages.getHistory", "peer_id=" + peerId
                + "&count=50&rev=0&extended=1&photo_sizes=1"
                + "&fields=first_name,last_name,photo_200,photo_100,photo_50,online,last_seen",
                callback, "5.92");
    }

    protected void markRead() {
        VKApi.get(this, "messages.markAsRead", "peer_id=" + peerId, new VKApi.Callback() {
            @Override public void success(JSONObject response) { }
            @Override public void error(Exception e) { }
        });
    }

    /** Планка закреплённого сообщения под шапкой чата + курсор прочитанного out_read. */
    private void loadPinnedMessage() {
        // ВАЖНО: messages.getConversations игнорирует peer_ids (отдаёт первый чат
        // списка) - закреп берём только через getConversationsById.
        VKApi.get(this, "messages.getConversationsById",
                "peer_ids=" + peerId,
                new VKApi.Callback() {
            @Override public void success(JSONObject response) {
                try {
                    org.json.JSONArray items = response.optJSONObject("response")
                            .optJSONArray("items");
                    if (items == null || items.length() == 0) return;
                    org.json.JSONObject conversation = items.getJSONObject(0);
                    // Coffee: исходящее прочитано, когда его id <= conversation.out_read.
                    long outRead = conversation.optLong("out_read", 0);
                    if (outRead > 0 && adapter != null) {
                        runOnUiThread(() -> adapter.setReadCursor(outRead));
                    }
                    org.json.JSONObject settings = conversation.optJSONObject("chat_settings");
                    org.json.JSONObject pinned = settings == null
                            ? null : settings.optJSONObject("pinned_message");
                    if (pinned == null) return;
                    String text = pinned.optString("text", "").trim();
                    String label = text.isEmpty() ? "Вложение" : text;
                    long fromId = pinned.optLong("from_id", 0);
                    String name = "";
                    if (fromId > 0) {
                        String[] cached = PeerCache.load(ChatActivity.this, fromId);
                        if (cached != null) name = cached[0];
                    }
                    final String sender = name;
                    final String shown = label;
                    final long messageId = pinned.optLong("id", 0);
                    runOnUiThread(() -> {
                        android.view.View bar = findViewById(R.id.chat_pinned_bar);
                        android.widget.TextView view = findViewById(R.id.chat_pinned_text);
                        if (bar == null || view == null) return;
                        view.setText(sender.isEmpty() ? shown : sender + ": " + shown);
                        bar.setVisibility(android.view.View.VISIBLE);
                        bar.setOnClickListener(v -> scrollToMessage(messageId));
                    });
                } catch (Exception ignored) {
                }
            }
            @Override public void error(Exception e) { }
        });
    }

    private void scrollToMessage(long messageId) {
        if (messageId == 0) return;
        if (adapter != null) {
            int position = adapter.findMessagePosition(messageId);
            if (position >= 0 && list != null) {
                list.getLayoutManager().scrollToPosition(position);
                return;
            }
        }
        // Сообщения нет в последних 50 - подгружаем историю вокруг него и мотаем чат
        rewindToMessage(messageId);
    }

    private void rewindToMessage(long messageId) {
        VKApi.get(this, "messages.getHistory", "peer_id=" + peerId
                        + "&count=30&start_message_id=" + messageId + "&rev=0&extended=1&photo_sizes=1"
                        + "&fields=first_name,last_name,photo_200,photo_100,photo_50,online,last_seen",
                new VKApi.Callback() {
            @Override public void success(JSONObject response) {
                try {
                    List<ChatMessage> chunk = ChatHistoryParser.parse(response, peerId);
                    runOnUiThread(() -> {
                        rewoundMessages.clear();
                        rewoundMessages.addAll(chunk);
                        renderMessages();
                        int position = adapter.findMessagePosition(messageId);
                        if (position >= 0 && list != null && list.getLayoutManager() != null) {
                            ((androidx.recyclerview.widget.LinearLayoutManager)
                                    list.getLayoutManager())
                                    .scrollToPositionWithOffset(position, list.getHeight() / 3);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
            @Override public void error(Exception e) { }
        }, "5.92");
    }

    @Override protected void onStart() {
        super.onStart();
        if (!synchronizeSession()) return;
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
        Session.removeChangeListener(sessionUpdates);
        historyGeneration++;
        if (forwarder != null) forwarder.close();
        for (MessageForwarder send : textSends) send.close();
        textSends.clear();
        if (fileSender != null) fileSender.close();
        super.onDestroy();
    }

    private void loadHistory() { loadHistory(false); }

    private void loadHistory(boolean silent) {
        if (!synchronizeSession()) return;
        if (loading || peerId == 0) return;
        loading = true;
        final long requestGeneration = ++historyGeneration;
        final long requestSession = sessionGeneration;
        if (!silent) {
            loadingView.setVisibility(View.VISIBLE);
            stateView.setVisibility(View.GONE);
        }
        requestHistory(new VKApi.Callback() {
            @Override
            public void success(JSONObject response) {
                if (!historyCurrent(requestGeneration, requestSession)) return;
                try {
                    JSONObject data = response == null ? null : response.optJSONObject("response");
                    if (data == null || data.optJSONArray("items") == null) {
                        showHistoryError(silent, requestGeneration, requestSession);
                        return;
                    }
                    List<ChatMessage> messages = ChatHistoryParser.parse(response, peerId);
                    // Временная диагностика цитат: только счётчики, без содержимого.
                    int withReply = 0, replyWithId = 0, fwdTotal = 0, fwdWithId = 0;
                    for (ChatMessage m : messages) {
                        if (m.replyTo != null) { withReply++; if (m.replyTo.id > 0) replyWithId++; }
                        for (ChatMessage f : m.forwarded) { fwdTotal++; if (f.id > 0) fwdWithId++; }
                    }
                    android.util.Log.d("VKTeaHistory", "peer=" + peerId
                            + " msgs=" + messages.size() + " replies=" + withReply
                            + " repliesWithId=" + replyWithId + " fwd=" + fwdTotal
                            + " fwdWithId=" + fwdWithId);
                    runOnUiThread(() -> {
                        if (!historyCurrent(requestGeneration, requestSession)) return;
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
                        resolveUnknownAuthors(historyMessages);
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
                        if (historyCurrent(requestGeneration, requestSession))
                            showHistoryError(silent, requestGeneration, requestSession);
                }
            }
            @Override public void error(Exception e) {
                if (historyCurrent(requestGeneration, requestSession))
                    showHistoryError(silent, requestGeneration, requestSession);
            }
        });
    }

    private boolean historyCurrent(long requestGeneration, long requestSession) {
        return synchronizeSession() && !isFinishing() && !isDestroyed()
                && requestGeneration == historyGeneration && requestSession == sessionGeneration;
    }

    /** A chat belongs to the account that opened it; never retain it after an account switch. */
    private boolean synchronizeSession() {
        if (sessionEnded || isFinishing() || isDestroyed()) return false;
        Session.Snapshot current = Session.snapshot(this);
        if (current.generation == sessionGeneration) return true;
        boolean sameUser = sessionUser != null && Objects.equals(sessionUser, current.userId)
                && current.token != null && !current.token.isEmpty();
        sessionGeneration = current.generation;
        historyGeneration++;
        loading = false;
        livePending = false;
        if (sameUser) {
            if (voiceRecorder != null) voiceRecorder.onSessionRefreshed();
            if (liveStarted && peerId != 0) loadHistory(true);
            return true;
        }
        sessionEnded = true;
        if (voicePlayer != null) voicePlayer.close();
        if (voiceRecorder != null) voiceRecorder.close();
        if (fileSender != null) fileSender.close();
        if (forwarder != null) { forwarder.close(); forwarder = null; }
        for (MessageForwarder send : textSends) send.close();
        textSends.clear();
        historyMessages.clear();
        localMessages.clear();
        if (adapter != null) adapter.replaceAll(Collections.emptyList());
        EditText input = findViewById(R.id.chat_input);
        if (input != null) input.setText("");
        if (peerName != null) peerName.setText("");
        if (peerStatus != null) peerStatus.setVisibility(View.GONE);
        if (peerAvatar != null) MessageAvatars.clear(peerAvatar);
        finish();
        return false;
    }

    private void showHistoryError(boolean silent, long requestGeneration, long requestSession) {
        runOnUiThread(() -> {
            if (!historyCurrent(requestGeneration, requestSession)) return;
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
        if (data == null || isSelfChat) return;
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
        if (!synchronizeSession()) return;
        if (editingMessageId > 0) {
            sendEdit(text);
            return;
        }
        stateView.setVisibility(View.GONE);
        ChatMessage message = new ChatMessage(--localId, true, 0, "", "", text,
                System.currentTimeMillis() / 1000L, Collections.emptyList())
                .withSendStatus(ChatMessage.SendStatus.SENDING);
        LocalMessage local = new LocalMessage(message);
        localMessages.add(local);
        adapter.add(message);
        list.scrollToPosition(adapter.getItemCount() - 1);
        long replyTo = replyMessageId;
        List<Long> forwards = new ArrayList<>(barForwardIds);
        resetComposerContext();
        requestSend(text, replyTo, forwards, new VKApi.Callback() {
                    @Override public void success(JSONObject response) {
                        runOnUiThread(() -> {
                            if (!synchronizeSession()) return;
                            local.serverId = response == null ? 0 : response.optLong("response", 0);
                            local.message = local.message.withSendStatus(local.serverId > 0
                                    ? ChatMessage.SendStatus.NONE : ChatMessage.SendStatus.UNCONFIRMED);
                            renderMessages();
                        });
                    }
                    @Override public void error(Exception e) {
                        runOnUiThread(() -> {
                            if (!synchronizeSession()) return;
                            local.message = local.message.withSendStatus(ChatMessage.SendStatus.UNCONFIRMED);
                            renderMessages();
                            Toast.makeText(ChatActivity.this, R.string.send_failed, Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    private void sendEdit(String text) {
        long target = editingMessageId;
        resetComposerContext();
        MessageForwarder send = createMessageForwarder();
        textSends.add(send);
        send.editMessage(peerId, target, text, new MessageForwarder.Callback() {
            @Override public void complete(JSONObject response) {
                textSends.remove(send);
                send.close();
                runOnUiThread(() -> {
                    if (!synchronizeSession()) return;
                    loadHistory(true);
                });
            }
            @Override public void failed() {
                textSends.remove(send);
                send.close();
                runOnUiThread(() -> {
                    if (!synchronizeSession()) return;
                    Toast.makeText(ChatActivity.this, R.string.send_failed, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    protected void requestSend(String text, long replyTo, List<Long> forwardIds, VKApi.Callback callback) {
        MessageForwarder send = createMessageForwarder();
        textSends.add(send);
        send.sendText(peerId, text, replyTo, forwardIds, new MessageForwarder.Callback() {
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
        for (ChatMessage message : rewoundMessages) {
            if (!loadedIds.contains(message.id)) displayed.add(message);
        }
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

    /** Авторы пересланных сообщений часто не приходят в history - догружаем одним запросом. */
    private void resolveUnknownAuthors(List<ChatMessage> messages) {
        Set<Long> users = new HashSet<>();
        Set<Long> groups = new HashSet<>();
        for (ChatMessage message : messages) collectUnknown(message, users, groups);
        users.remove(DialogsParser.selfId());
        if (users.isEmpty() && groups.isEmpty()) return;
        final long requestSession = sessionGeneration;
        if (!users.isEmpty()) {
            StringBuilder ids = new StringBuilder();
            for (Long id : users) { if (ids.length() > 0) ids.append(','); ids.append(id); }
            VKApi.get(this, "users.get", "user_ids=" + ids + "&fields=photo_100,first_name,last_name",
                    new VKApi.Callback() {
                @Override public void success(JSONObject response) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || requestSession != sessionGeneration) return;
                        applyResolvedIdentities(response.optJSONArray("response"), true);
                    });
                }
                @Override public void error(Exception e) { }
            });
        }
        if (!groups.isEmpty()) {
            StringBuilder ids = new StringBuilder();
            for (Long id : groups) { if (ids.length() > 0) ids.append(','); ids.append(id); }
            VKApi.get(this, "groups.getById", "group_ids=" + ids + "&fields=photo_100",
                    new VKApi.Callback() {
                @Override public void success(JSONObject response) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || requestSession != sessionGeneration) return;
                        JSONObject data = response.optJSONObject("response");
                        applyResolvedIdentities(data == null ? null : data.optJSONArray("groups"), false);
                    });
                }
                @Override public void error(Exception e) { }
            });
        }
    }

    private void collectUnknown(ChatMessage message, Set<Long> users, Set<Long> groups) {
        if (message.fromId != 0 && !message.authorKnown
                && !message.body.equals("Сообщение удалено") && !message.body.equals("Сообщение недоступно")) {
            if (message.fromId > 0) users.add(message.fromId);
            else groups.add(-message.fromId);
        }
        if (message.replyTo != null) collectUnknown(message.replyTo, users, groups);
        for (ChatMessage fwd : message.forwarded) collectUnknown(fwd, users, groups);
    }

    private void applyResolvedIdentities(JSONArray rows, boolean users) {
        if (rows == null) return;
        java.util.Map<Long, String[]> identities = new java.util.HashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            long id = row.optLong("id", 0);
            if (id == 0) continue;
            String name = users
                    ? (row.optString("first_name", "") + " " + row.optString("last_name", "")).trim()
                    : row.optString("name", "");
            if (name.isEmpty()) continue;
            identities.put(users ? id : -id, new String[]{name, MessageAvatars.photoUrl(row)});
        }
        if (identities.isEmpty()) return;
        boolean changed = false;
        List<ChatMessage> updated = new ArrayList<>(historyMessages.size());
        for (ChatMessage message : historyMessages) {
            ChatMessage next = applyIdentity(message, identities);
            changed |= next != message;
            updated.add(next);
        }
        if (!changed) return;
        historyMessages.clear();
        historyMessages.addAll(updated);
        renderMessages();
    }

    private ChatMessage applyIdentity(ChatMessage message, java.util.Map<Long, String[]> identities) {
        boolean changed = false;
        String[] identity = identities.get(message.fromId);
        if (!message.authorKnown && identity != null) {
            message = message.withIdentity(identity[0], identity[1]);
            changed = true;
        }
        if (message.replyTo != null) {
            ChatMessage reply = applyIdentity(message.replyTo, identities);
            if (reply != message.replyTo) changed = true;
            // Immutable tree: rebuild is not supported, so quote updates apply on next parse.
        }
        List<ChatMessage> forwarded = message.forwarded;
        List<ChatMessage> updatedForwarded = null;
        for (int i = 0; i < forwarded.size(); i++) {
            ChatMessage fwd = applyIdentity(forwarded.get(i), identities);
            if (fwd != forwarded.get(i)) {
                if (updatedForwarded == null) updatedForwarded = new ArrayList<>(forwarded);
                updatedForwarded.set(i, fwd);
            }
        }
        if (updatedForwarded != null) {
            ChatMessage withFwd = new ChatMessage(message.id, message.out, message.fromId,
                    message.author, message.avatar, message.body, message.date, message.attachments,
                    message.readState, updatedForwarded, message.replyTo, true);
            message = message.sendStatus == ChatMessage.SendStatus.NONE
                    ? withFwd : withFwd.withSendStatus(message.sendStatus);
            changed = true;
        }
        return changed ? message : message;
    }

    private void openAttachment(ChatAttachment attachment) {
        if (!synchronizeSession()) return;
        if ("audio_message".equals(attachment.type)) {
            voicePlayer.toggle(attachment);
            return;
        }
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
        } else if (ChatAttachmentActions.open(this, attachment)) {
            return;
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
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        items.add(getString(R.string.chat_action_reply));
        actions.add(() -> attachReply(message));
        items.add(getString(R.string.chat_forward_action));
        actions.add(() -> chooseForwardRecipient(message.id));
        if (!message.body.trim().isEmpty()) {
            items.add(getString(R.string.chat_action_copy));
            actions.add(() -> copyMessageText(message));
        }
        if (canEditMessage(message)) {
            items.add(getString(R.string.chat_action_edit));
            actions.add(() -> startEditing(message));
        }
        items.add(getString(R.string.chat_action_delete));
        actions.add(() -> confirmDelete(Collections.singletonList(message)));
        if (peerId >= 2_000_000_000L && message.fromId > 0) {
            items.add(getString(R.string.chat_action_mention));
            actions.add(() -> insertMention(message));
        }
        items.add(getString(R.string.chat_action_select));
        actions.add(() -> {
            startSelectionMode();
            toggleSelection(message);
        });
        new AlertDialog.Builder(this).setTitle(R.string.chat_message_title)
                .setItems(items.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .show();
    }

    /** Coffee canEditMessage: своё, моложе суток, без граффити/стикера/голосового/подарка. */
    private boolean canEditMessage(ChatMessage message) {
        if (!message.out || message.id <= 0 || message.sendStatus != ChatMessage.SendStatus.NONE) return false;
        if (System.currentTimeMillis() / 1000L - message.date > 86400) return false;
        for (ChatAttachment attachment : message.attachments) {
            String type = attachment.type;
            if ("sticker".equals(type) || "graffiti".equals(type)
                    || "audio_message".equals(type) || "gift".equals(type)) return false;
        }
        return true;
    }

    private void attachReply(ChatMessage message) {
        replyMessageId = message.id;
        editingMessageId = 0;
        barForwardIds.clear();
        showContextBar(R.drawable.ic_ab_forward,
                getString(R.string.chat_reply_attached_author, message.author));
        inputView.requestFocus();
    }

    /** Быстрая пересылка внутри чата: сообщение прикрепляется к полю ввода (Coffee WriteBar.addFwdMessages). */
    private void attachForwardToBar(ChatMessage message) {
        if (!barForwardIds.contains(message.id)) barForwardIds.add(message.id);
        editingMessageId = 0;
        replyMessageId = 0;
        String label = barForwardIds.size() == 1 && !message.body.isEmpty()
                ? getString(R.string.chat_reply_attached_author, message.author)
                : getResources().getQuantityString(R.plurals.chat_forward_attached_messages,
                        barForwardIds.size(), barForwardIds.size());
        showContextBar(R.drawable.ic_ab_forward, label);
        inputView.requestFocus();
    }

    private void startEditing(ChatMessage message) {
        editingMessageId = message.id;
        replyMessageId = 0;
        barForwardIds.clear();
        inputView.setText(message.body);
        showContextBar(R.drawable.ic_chat_edit, getString(R.string.chat_editing));
        inputView.requestFocus();
        inputView.setSelection(inputView.getText().length());
    }

    private void showContextBar(int iconRes, String text) {
        contextIcon.setImageResource(iconRes);
        contextText.setText(text);
        contextBar.setVisibility(View.VISIBLE);
    }

    private void resetComposerContext() {
        replyMessageId = 0;
        editingMessageId = 0;
        barForwardIds.clear();
        contextBar.setVisibility(View.GONE);
        inputView.setText("");
    }

    private boolean composerHasContext() {
        return replyMessageId > 0 || editingMessageId > 0 || !barForwardIds.isEmpty();
    }

    private void copyMessageText(ChatMessage message) {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("VK Tea", message.body));
        Toast.makeText(this, R.string.chat_text_copied, Toast.LENGTH_SHORT).show();
    }

    private void insertMention(ChatMessage message) {
        String mention = "[id" + message.fromId + "|" + message.author + "], ";
        inputView.getText().insert(inputView.getSelectionStart(), mention);
        inputView.requestFocus();
    }

    private void openAuthorProfile(long fromId) {
        if (fromId == 0) return;
        startActivity(new Intent(this, com.vktea.MainActivity.class)
                .putExtra(com.vktea.MainActivity.EXTRA_OPEN_PROFILE, fromId));
    }

    private void confirmDelete(List<ChatMessage> targets) {
        List<Long> ids = new ArrayList<>();
        boolean allOwn = true;
        boolean allFresh = true;
        for (ChatMessage message : targets) {
            if (message.id > 0) ids.add(message.id);
            if (!message.out) allOwn = false;
            if (System.currentTimeMillis() / 1000L - message.date > 86400) allFresh = false;
        }
        if (ids.isEmpty()) return;
        android.view.View custom = getLayoutInflater().inflate(R.layout.dialog_delete_messages, null);
        android.widget.TextView text = custom.findViewById(R.id.delete_message_text);
        text.setText(ids.size() == 1 ? getString(R.string.chat_delete_confirm_one)
                : getString(R.string.chat_delete_confirm_many, ids.size()));
        android.widget.CheckBox forAll = custom.findViewById(R.id.delete_for_all);
        boolean canDeleteForAll = allOwn && allFresh && !isSelfChat;
        forAll.setVisibility(canDeleteForAll ? View.VISIBLE : View.GONE);
        new AlertDialog.Builder(this)
                .setTitle(R.string.chat_delete_title)
                .setView(custom)
                .setPositiveButton(R.string.chat_action_delete, (dialog, which) ->
                        deleteMessages(ids, canDeleteForAll && forAll.isChecked()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteMessages(List<Long> ids, boolean deleteForAll) {
        MessageForwarder operation = createMessageForwarder();
        textSends.add(operation);
        MessageForwarder.Callback callback = new MessageForwarder.Callback() {
            @Override public void complete(JSONObject response) {
                runOnUiThread(() -> {
                    textSends.remove(operation);
                    if (!synchronizeSession()) return;
                    Toast.makeText(ChatActivity.this, R.string.chat_delete_done, Toast.LENGTH_SHORT).show();
                    loadHistory(true);
                });
                operation.close();
            }
            @Override public void failed() {
                runOnUiThread(() -> {
                    textSends.remove(operation);
                    if (!synchronizeSession()) return;
                    Toast.makeText(ChatActivity.this, R.string.chat_delete_failed, Toast.LENGTH_LONG).show();
                });
                operation.close();
            }
            @Override public void apiError(int code) {
                operation.close();
                if (code == 924 && deleteForAll) {
                    // VK отказал удалять для всех (например, получатель успел прочитать
                    // или диалог с самим собой) - удаляем хотя бы для себя.
                    runOnUiThread(() -> {
                        textSends.remove(operation);
                        if (synchronizeSession()) deleteMessages(ids, false);
                    });
                    return;
                }
                runOnUiThread(() -> {
                    textSends.remove(operation);
                    if (!synchronizeSession()) return;
                    Toast.makeText(ChatActivity.this, code == 924
                            ? R.string.chat_delete_for_all_denied : R.string.chat_delete_failed,
                            Toast.LENGTH_LONG).show();
                });
            }
        };
        operation.deleteMessages(ids, deleteForAll, callback);
    }

    private void toggleSelection(ChatMessage message) {
        if (selectionMode == null || message.id <= 0) return;
        if (selectedIds.contains(message.id)) selectedIds.remove(message.id);
        else selectedIds.add(message.id);
        if (selectedIds.isEmpty()) {
            if (selectionMode != null) selectionMode.finish();
            return;
        }
        selectionMode.setTitle(getString(R.string.chat_selected_n, selectedIds.size()));
        adapter.setSelectedIds(new HashSet<>(selectedIds));
    }

    private void startSelectionMode() {
        if (selectionMode != null) return;
        selectedIds.clear();
        selectionMode = startSupportActionMode(new androidx.appcompat.view.ActionMode.Callback() {
            @Override public boolean onCreateActionMode(androidx.appcompat.view.ActionMode mode, android.view.Menu menu) {
                mode.getMenuInflater().inflate(R.menu.chat_selection, menu);
                return true;
            }
            @Override public boolean onPrepareActionMode(androidx.appcompat.view.ActionMode mode, android.view.Menu menu) {
                return true;
            }
            @Override public boolean onActionItemClicked(androidx.appcompat.view.ActionMode mode, android.view.MenuItem item) {
                List<ChatMessage> selected = new ArrayList<>();
                for (ChatMessage message : adapter.currentMessages()) {
                    if (selectedIds.contains(message.id)) selected.add(message);
                }
                int id = item.getItemId();
                if (id == R.id.selection_copy && selected.size() == 1) {
                    copyMessageText(selected.get(0));
                    mode.finish();
                    return true;
                }
                if (id == R.id.selection_forward) {
                    List<Long> ids = new ArrayList<>();
                    for (ChatMessage message : selected) ids.add(message.id);
                    mode.finish();
                    chooseForwardRecipients(ids);
                    return true;
                }
                if (id == R.id.selection_delete) {
                    mode.finish();
                    confirmDelete(selected);
                    return true;
                }
                return false;
            }
            @Override public void onDestroyActionMode(androidx.appcompat.view.ActionMode mode) {
                selectionMode = null;
                selectedIds.clear();
                adapter.setSelectedIds(null);
                adapter.setSelectionMode(false);
            }
        });
        adapter.setSelectionMode(true);
        selectionMode.setTitle(getString(R.string.chat_selected_n, 1));
    }

    private void showAttachmentActions(ChatAttachment attachment) {
        if (!"doc".equals(attachment.type) && !"link".equals(attachment.type)
                && !"article".equals(attachment.type)) return;
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!attachment.url.isEmpty()) {
            items.add(getString(R.string.chat_doc_download));
            actions.add(() -> downloadAttachment(attachment));
            items.add(getString(R.string.chat_doc_open));
            actions.add(() -> ChatAttachmentActions.open(this, attachment));
            items.add(getString(R.string.chat_doc_share));
            actions.add(() -> shareAttachmentLink(attachment));
        }
        if (items.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setItems(items.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .show();
    }

    /** Coffee DocAttachView: файлы без превью скачиваются в Downloads через DownloadManager. */
    private void downloadAttachment(ChatAttachment attachment) {
        if (attachment.url.isEmpty()) return;
        try {
            String name = attachment.title == null || attachment.title.trim().isEmpty()
                    ? "vk-tea-file" : attachment.title.replaceAll("[/\\\\:*?\"<>|]", "_");
            android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(
                    android.net.Uri.parse(attachment.url))
                    .setTitle(name)
                    .setDestinationInExternalPublicDir(
                            android.os.Environment.DIRECTORY_DOWNLOADS, name)
                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.allowScanningByMediaScanner();
            android.app.DownloadManager manager = (android.app.DownloadManager)
                    getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException();
            manager.enqueue(request);
            Toast.makeText(this, R.string.chat_doc_downloading, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.chat_doc_download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareAttachmentLink(ChatAttachment attachment) {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, attachment.url);
        try {
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.chat_doc_share)));
        } catch (Exception ignored) {
            Toast.makeText(this, R.string.chat_attachment_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void chooseForwardRecipient(long messageId) {
        chooseForwardRecipients(Collections.singletonList(messageId));
    }

    private void chooseForwardRecipients(List<Long> messageIds) {
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
                        .setItems(titles, (dialog, which) -> {
                            forwardBusy = false;
                            long destination = dialogs.get(which).peerId;
                            if (destination == peerId && messageIds.size() >= 1) {
                                // Coffee: пересылка в текущий диалог прикрепляется к полю ввода.
                                for (Long id : messageIds) {
                                    ChatMessage source = adapter.findMessage(id);
                                    if (source != null) attachForwardToBar(source);
                                }
                                return;
                            }
                            sendForward(operation, destination, messageIds);
                        })
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

    private void sendForward(MessageForwarder operation, long destination, List<Long> messageIds) {
        operation.send(destination, messageIds, new MessageForwarder.Callback() {
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
