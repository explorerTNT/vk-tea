package com.vktea.msg;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.view.Gravity;
import android.view.ViewGroup;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import androidx.media3.datasource.ByteArrayDataSource;

/** Offline synthetic-only launcher. This source set is excluded from release and debug. */
public final class SyntheticChatActivity extends ChatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicInteger sentIds = new AtomicInteger(100);
    private final java.util.List<JSONObject> sentMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    private android.widget.TextView trace;
    private MessageLongPoll poll;
    private MessageLongPoll.PollCallback pending;
    private final MessageLongPoll.Listener listener = this::onLiveMessagesChanged;
    private final OkHttpClient fakeClient = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        String path = request.url().encodedPath();
        handler.post(() -> trace("HTTP " + path));
        String result;
        if (path.endsWith("messages.getConversations")) result = "{\"response\":{\"items\":[{\"conversation\":{\"peer\":{\"id\":77}},\"last_message\":{\"peer_id\":77,\"text\":\"synthetic\"}}],\"profiles\":[{\"id\":77,\"first_name\":\"Тестовый\",\"last_name\":\"получатель\"}]}}";
        else if (path.endsWith("getMessagesUploadServer")) result = "{\"response\":{\"upload_url\":\"https://upload.vk.com/synthetic\"}}";
        else if (path.equals("/synthetic")) result = "{\"file\":\"synthetic\",\"photo\":\"synthetic\",\"hash\":\"synthetic\",\"server\":1}";
        else if (path.endsWith("photos.saveMessagesPhoto")) result = "{\"response\":[{\"owner_id\":42,\"id\":19}]}";
        else if (path.endsWith("docs.save")) result = "{\"response\":{\"doc\":{\"owner_id\":42,\"id\":19},\"audio_message\":{\"owner_id\":42,\"id\":20}}}";
        else if (path.endsWith("messages.send")) {
            FormBody form = (FormBody) request.body();
            String peer = "", attachment = "", message = "", forward = "";
            for (int i = 0; i < form.size(); i++) {
                if ("peer_id".equals(form.name(i))) peer = form.value(i);
                if ("attachment".equals(form.name(i))) attachment = form.value(i);
                if ("message".equals(form.name(i))) message = form.value(i);
                if ("forward_messages".equals(form.name(i))) forward = form.value(i);
            }
            int id = sentIds.incrementAndGet();
            if ("42".equals(peer) && forward.isEmpty()) {
                try {
                    JSONObject row = new JSONObject().put("id", id).put("out", 1)
                            .put("date", System.currentTimeMillis() / 1000L).put("text", message);
                    if (!attachment.isEmpty()) {
                        String text = attachment.contains("_20") ? "Голосовое отправлено (QA)" : "Вложение отправлено (QA)";
                        row.put("text", text);
                    }
                    sentMessages.add(row);
                } catch (Exception e) { throw new java.io.IOException(e); }
            }
            result = "{\"response\":" + id + "}";
        }
        else throw new java.io.IOException("Unexpected synthetic request");
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(result, MediaType.parse("application/json"))).build();
    }).build();

    @Override protected void onCreate(Bundle state) {
        getIntent().putExtra(EXTRA_PEER_ID, 42L).putExtra(EXTRA_TITLE, "Синтетический собеседник");
        Session.save(this, "synthetic-only", "1");
        poll = MessageLongPoll.forTesting(this, new MessageLongPoll.Transport() {
            @Override public void acquire(VKApi.Callback callback) {
                trace("LongPoll acquire");
                try { callback.success(new JSONObject("{\"response\":{\"server\":\"https://lp.vk.com\",\"key\":\"synthetic\",\"ts\":\"1\"}}")); }
                catch (Exception e) { throw new AssertionError(e); }
            }
            @Override public MessageLongPoll.Cancel poll(String server, String key, String ts, MessageLongPoll.PollCallback callback) {
                trace("LongPoll poll ts=" + ts);
                pending = callback;
                return () -> { if (pending == callback) pending = null; };
            }
        });
        super.onCreate(state);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button event = new Button(this); event.setText("QA событие");
        event.setOnClickListener(v -> emit("{\"ts\":\"2\",\"updates\":[[4,123,0,42]]}", true));
        Button cursor = new Button(this); cursor.setText("QA курсор");
        cursor.setOnClickListener(v -> emit("{\"failed\":1,\"ts\":\"3\"}", false));
        Button failure = new Button(this); failure.setText("QA сбой");
        failure.setOnClickListener(v -> { MessageLongPoll.PollCallback cb = pending; pending = null; if (cb != null) cb.failure(); });
        controls.addView(event); controls.addView(cursor); controls.addView(failure);
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(controls); trace = new android.widget.TextView(this);
        trace.setBackgroundColor(0xff222222); trace.setTextColor(0xffffffff);
        trace.setText("QA: ожидание событий"); panel.addView(trace);
        android.widget.FrameLayout.LayoutParams layout = new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        layout.topMargin = Math.round(70 * getResources().getDisplayMetrics().density);
        addContentView(panel, layout);
    }
    private void trace(String text) { android.util.Log.i("VkteaSyntheticQA", text); if (trace != null) trace.setText("QA: " + text); }
    private void emit(String value, boolean next) {
        MessageLongPoll.PollCallback cb = pending; pending = null;
        if (cb == null) return;
        if (next) sequence.incrementAndGet();
        try { cb.success(new JSONObject(value)); } catch (Exception e) { throw new AssertionError(e); }
    }
    @Override protected void subscribeLiveUpdates() { poll.addListener(listener); }
    @Override protected void unsubscribeLiveUpdates() { poll.removeListener(listener); }
    @Override protected void markRead() { }
    @Override protected void requestHistory(VKApi.Callback callback) {
        try {
            JSONObject root = new JSONObject("{\"response\":{\"profiles\":[{\"id\":42,\"first_name\":\"Тестовый\",\"last_name\":\"собеседник\",\"online\":1}],\"items\":[{\"id\":12,\"from_id\":42,\"date\":1235,\"text\":\"Тестовое голосовое\",\"attachments\":[{\"type\":\"audio_message\",\"audio_message\":{\"duration\":2,\"link_mp3\":\"https://audio.vk.com/synthetic.wav\"}}]},{\"id\":11,\"from_id\":42,\"date\":1234,\"text\":\"Тестовое сообщение для пересылки\",\"attachments\":[{\"type\":\"doc\",\"doc\":{\"title\":\"Тестовый документ\",\"size\":123}}]}]}}");
            if (sequence.get() > 0) root.getJSONObject("response").getJSONArray("items").put(new JSONObject().put("id", 13).put("from_id", 42).put("date", 1236).put("text", "Новое синтетическое сообщение"));
            for (JSONObject row : sentMessages) root.getJSONObject("response").getJSONArray("items").put(row);
            handler.post(() -> callback.success(root));
        } catch (Exception e) { callback.error(e); }
    }
    @Override MessageForwarder createMessageForwarder() {
        return new MessageForwarder(fakeClient, HttpUrl.get("https://api.vk.com/"), () -> new MessageForwarder.Credentials("synthetic-only", "", "1"));
    }
    @Override protected ChatFileSender createFileSender(long peer, Runnable sent) { return new ChatFileSender(this, peer, sent, fakeClient); }
    @Override protected VoiceMessageRecorder createVoiceRecorder(long peer, Runnable sent) { return new VoiceMessageRecorder(this, peer, sent, fakeClient); }
    @Override protected VoiceMessagePlayer createVoicePlayer() { return new VoiceMessagePlayer(this, () -> new ByteArrayDataSource(wav())); }
    private byte[] wav() {
        int rate=8000, samples=rate*2, size=samples*2;
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        try {
            b.write(new byte[]{'R','I','F','F'}); le(b,36+size,4); b.write(new byte[]{'W','A','V','E','f','m','t',' '}); le(b,16,4); le(b,1,2); le(b,1,2); le(b,rate,4); le(b,rate*2,4); le(b,2,2); le(b,16,2); b.write(new byte[]{'d','a','t','a'}); le(b,size,4);
            for(int i=0;i<samples;i++) le(b,(int)(Math.sin(i*2*Math.PI*440/rate)*4000),2);
        } catch (Exception e) { throw new AssertionError(e); }
        return b.toByteArray();
    }
    private static void le(ByteArrayOutputStream b,int n,int count) { for(int i=0;i<count;i++) b.write((n >>> (i*8)) & 255); }
}
