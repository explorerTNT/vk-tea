package com.vktea.msg;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import com.vktea.MainActivity;
import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import org.json.JSONObject;

/** Drives the production screen and counters from one offline synthetic poll. */
public final class SyntheticDialogsActivity extends MainActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MessageLongPoll poll;
    private MessageLongPoll.PollCallback pending;
    private final MessageLongPoll.Listener counters = this::onCounterEvent;
    private int unread;

    @Override protected void onCreate(Bundle state) {
        Session.save(this, "synthetic-only", "1");
        poll = MessageLongPoll.forTesting(this, new MessageLongPoll.Transport() {
            @Override public void acquire(VKApi.Callback callback) {
                try { callback.success(new JSONObject("{\"response\":{\"server\":\"https://lp.vk.com\",\"key\":\"synthetic\",\"ts\":\"1\"}}")); }
                catch (Exception e) { throw new AssertionError(e); }
            }
            @Override public MessageLongPoll.Cancel poll(String server, String key, String ts, MessageLongPoll.PollCallback callback) {
                android.util.Log.i("VkteaSyntheticQA", "Dialogs poll ts=" + ts);
                pending = callback;
                return () -> { if (pending == callback) pending = null; };
            }
        });
        super.onCreate(state);
        if (state == null) {
            getSupportFragmentManager().executePendingTransactions();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.content, new SyntheticDialogsFragment()).commitNow();
        }
        Button event = new Button(this);
        event.setText("QA новое непрочитанное");
        event.setOnClickListener(v -> {
            MessageLongPoll.PollCallback cb = pending; pending = null;
            if (cb == null) return;
            unread++;
            try { cb.success(new JSONObject("{\"ts\":\"" + (unread + 1) + "\",\"updates\":[[4,123,0,42]]}")); }
            catch (Exception e) { throw new AssertionError(e); }
        });
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        layout.bottomMargin = Math.round(24 * getResources().getDisplayMetrics().density);
        addContentView(event, layout);
    }
    @Override protected void subscribeCounterUpdates() { poll.addListener(counters); }
    @Override protected void unsubscribeCounterUpdates() { poll.removeListener(counters); }
    @Override protected void requestCounters(VKApi.Callback callback) {
        int count = unread;
        try { JSONObject response = new JSONObject().put("response", new JSONObject().put("messages", count).put("notifications", 0));
            handler.post(() -> callback.success(response));
        } catch (Exception e) { callback.error(e); }
    }
    public static final class SyntheticDialogsFragment extends DialogsFragment {
        private final MessageLongPoll.Listener listener = this::onLiveMessagesChanged;
        private SyntheticDialogsActivity host() { return (SyntheticDialogsActivity) requireActivity(); }
        @Override protected void subscribeLiveUpdates() { host().poll.addListener(listener); }
        @Override protected void unsubscribeLiveUpdates() { host().poll.removeListener(listener); }
        @Override protected void requestDialogs(VKApi.Callback callback) {
            int unread = host().unread;
            String text = unread == 0 ? "Исходное синтетическое сообщение" : "Новое синтетическое сообщение " + unread;
            try {
                JSONObject peer = new JSONObject().put("id", 42);
                JSONObject conversation = new JSONObject().put("peer", peer).put("unread_count", unread);
                JSONObject message = new JSONObject().put("peer_id", 42).put("text", text).put("date", System.currentTimeMillis()/1000L);
                JSONObject item = new JSONObject().put("conversation", conversation).put("last_message", message);
                JSONObject profile = new JSONObject().put("id",42).put("first_name","Тестовый").put("last_name","собеседник");
                JSONObject response = new JSONObject().put("response", new JSONObject()
                        .put("items", new org.json.JSONArray().put(item)).put("profiles", new org.json.JSONArray().put(profile)));
                host().handler.post(() -> callback.success(response));
            } catch (Exception e) { callback.error(e); }
        }
        @Override protected void requestUsers(String ids, VKApi.Callback callback) {
            try { callback.success(new JSONObject("{\"response\":[{\"id\":42,\"first_name\":\"Тестовый\",\"last_name\":\"собеседник\"}]}")); }
            catch (Exception e) { callback.error(e); }
        }
        @Override protected void requestGroups(String ids, VKApi.Callback callback) { callback.error(new Exception("No synthetic groups")); }
    }
}
