package com.vktea.msg;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.vktea.api.VKApi;
import com.vktea.auth.Session;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** One foreground long-poll connection shared by visible screens. No credentials are logged. */
public final class MessageLongPoll {
    public interface Listener { void onMessagesChanged(); }
    private static MessageLongPoll instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners = new LinkedHashSet<>();
    private final OkHttpClient client = new OkHttpClient.Builder().readTimeout(35, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    private Call call;
    private int generation;
    private int failures;
    private String server, key, ts;
    private boolean pendingNotification;

    private MessageLongPoll(Context context) { this.context = context.getApplicationContext(); }
    public static synchronized MessageLongPoll get(Context context) {
        if (instance == null) instance = new MessageLongPoll(context);
        return instance;
    }
    public static synchronized void sessionChanged() {
        if (instance == null) return;
        MessageLongPoll poll = instance;
        if (Looper.myLooper() == Looper.getMainLooper()) poll.resetSession();
        else poll.main.post(poll::resetSession);
    }
    private void resetSession() {
        ++generation;
        if (call != null) call.cancel();
        call = null;
        server = key = ts = null;
        failures = 0;
        if (!listeners.isEmpty() && Session.isAuthorized(context)) acquire(generation, false);
    }
    public void addListener(Listener listener) {
        if (!listeners.add(listener)) return;
        if (listeners.size() == 1 && Session.isAuthorized(context)) { failures = 0; acquire(++generation, false); }
    }
    public void removeListener(Listener listener) {
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            ++generation;
            if (call != null) call.cancel();
            call = null;
            server = key = ts = null;
        }
    }
    private boolean active(int current) { return generation == current && !listeners.isEmpty(); }
    private void acquire(int current, boolean preserveTs) {
        if (!active(current)) return;
        VKApi.get(context, "messages.getLongPollServer", "lp_version=3&use_ssl=1", new VKApi.Callback() {
            @Override public void success(JSONObject root) {
                if (!active(current)) return;
                JSONObject data = root == null ? null : root.optJSONObject("response");
                String raw = data == null ? "" : data.optString("server", "");
                HttpUrl endpoint = endpoint(raw);
                String newKey = data == null ? "" : data.optString("key", "");
                String newTs = data == null ? "" : data.optString("ts", "");
                if (endpoint == null || newKey.isEmpty() || newTs.isEmpty()) { retry(current, false); return; }
                server = endpoint.toString(); key = newKey;
                if (!preserveTs || ts == null) ts = newTs;
                failures = 0;
                // A fresh cursor can skip events while disconnected; reload the authoritative state.
                if (!preserveTs) notifyChanged();
                poll(current);
            }
            @Override public void error(Exception error) {
                retry(current, false, error instanceof IOException || error instanceof JSONException);
            }
        });
    }
    static HttpUrl endpoint(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        HttpUrl url = HttpUrl.parse(raw.contains("://") ? raw : "https://" + raw);
        if (url == null || !"https".equals(url.scheme()) || url.port() != 443
                || !(url.host().equals("vk.com") || url.host().endsWith(".vk.com"))
                || !url.username().isEmpty() || !url.password().isEmpty()) return null;
        return url;
    }
    private void poll(int current) {
        if (!active(current)) return;
        HttpUrl base = HttpUrl.parse(server);
        if (base == null) { retry(current, false); return; }
        HttpUrl url = base.newBuilder().addQueryParameter("act", "a_check")
                .addQueryParameter("key", key).addQueryParameter("ts", ts)
                .addQueryParameter("wait", "25").addQueryParameter("mode", "2")
                .addQueryParameter("version", "3").build();
        call = client.newCall(new Request.Builder().url(url).header("User-Agent", VKApi.USER_AGENT).build());
        call.enqueue(new Callback() {
            @Override public void onFailure(Call request, IOException ignored) { main.post(() -> retry(current, true)); }
            @Override public void onResponse(Call request, Response response) {
                try (Response result = response) {
                    if (!result.isSuccessful() || result.body() == null) { main.post(() -> retry(current, true)); return; }
                    JSONObject data = new JSONObject(result.body().string());
                    main.post(() -> handle(current, data));
                } catch (Exception ignored) { main.post(() -> retry(current, true)); }
            }
        });
    }
    private void handle(int current, JSONObject data) {
        if (!active(current)) return;
        int failed = data.optInt("failed", 0);
        if (failed == 1) {
            String next = data.optString("ts", "");
            if (next.isEmpty()) { retry(current, false); return; }
            ts = next; notifyChanged(); poll(current); return;
        }
        if (failed == 2) { acquire(current, true); return; }
        if (failed != 0) { acquire(current, false); return; }
        String next = data.optString("ts", "");
        JSONArray updates = data.optJSONArray("updates");
        if (next.isEmpty() || updates == null) { retry(current, false); return; }
        ts = next; failures = 0;
        if (hasRelevantUpdates(updates)) notifyChanged();
        poll(current);
    }
    static boolean hasRelevantUpdates(JSONArray updates) {
        for (int i = 0; i < updates.length(); i++) {
            JSONArray event = updates.optJSONArray(i);
            if (event == null || event.length() == 0) continue;
            int code = event.optInt(0, 0);
            // Typing and ephemeral activity do not change the stored state shown by these screens.
            if (code > 0 && code != 61 && code != 62) return true;
        }
        return false;
    }
    private void notifyChanged() {
        if (pendingNotification) return;
        pendingNotification = true;
        main.postDelayed(() -> {
            pendingNotification = false;
            for (Listener listener : new ArrayList<>(listeners)) listener.onMessagesChanged();
        }, 500);
    }
    private void retry(int current, boolean sameServer) {
        retry(current, sameServer, sameServer);
    }
    private void retry(int current, boolean sameServer, boolean recoverable) {
        if (!active(current)) return;
        // Avoid repeatedly requesting an unavailable or unauthorized API method. A dropped
        // long-poll connection itself remains retryable, so connectivity can recover.
        failures = Math.min(6, failures + 1);
        if (!recoverable && failures > 5) return;
        long delay = Math.min(60_000L, 2_000L << (failures - 1));
        main.postDelayed(() -> {
            if (!active(current)) return;
            if (sameServer && server != null && key != null && ts != null) poll(current);
            else acquire(current, false);
        }, delay);
    }
}
