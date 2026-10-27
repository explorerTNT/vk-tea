package com.vktea.feed;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/** Holds in-flight mutations across configuration changes. Never retries or logs requests. */
public final class StoryActionsViewModel extends AndroidViewModel {
    // Package-private injection point for offline lifecycle and send-result tests.
    static java.util.function.Function<android.content.Context, StoryDraftStore> draftStores = StoryDraftStore::create;
    enum Outcome { SEEN_FAILED, REPLY_SENT, REPLY_REJECTED, REPLY_UNKNOWN }
    static final class Event {
        final String key, message;
        final Outcome outcome;
        Event(String key, String message, Outcome outcome) { this.key = key; this.message = message; this.outcome = outcome; }
    }
    final MutableLiveData<Event> events = new MutableLiveData<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final Set<String> seenAttempts = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> pending = Collections.synchronizedSet(new HashSet<>());
    private static final Object markerLock = new Object();
    private final Set<Call> calls = Collections.synchronizedSet(new HashSet<>());
    private final OkHttpClient client;
    private final SecureRandom random = new SecureRandom();
    private final String initialToken, initialSecret, initialUser;
    public StoryActionsViewModel(Application application) { this(application, new OkHttpClient()); }
    StoryActionsViewModel(Application application, OkHttpClient transport) {
        super(application);
        client = transport.newBuilder().protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
                .addNetworkInterceptor(chain -> {
                    Response result = chain.proceed(chain.request());
                    return result.code() == 503 ? result.newBuilder().header("Retry-After", "2147483647").build() : result;
                }).build();
        initialToken = token(); initialSecret = secret(); initialUser = user();
    }
    private static String value(String s) { return s == null ? "" : s; }
    private String token() { return value(Session.getToken(getApplication())); }
    private String secret() { return value(Session.getSecret(getApplication())); }
    private String user() { return value(Session.getUserId(getApplication())); }
    boolean sameSession() { return !initialToken.isEmpty() && initialToken.equals(token()) && initialSecret.equals(secret()) && initialUser.equals(user()); }
    static String storyKey(JSONObject story) { return story.optLong("owner_id") + "_" + story.optLong("id"); }
    static boolean identity(JSONObject story) { return story != null && story.optLong("owner_id") != 0 && story.optLong("id") > 0; }
    static boolean canReply(JSONObject story, String user) {
        if (!identity(story) || !story.optBoolean("can_reply", false)) return false;
        try { return Long.parseLong(user) != story.optLong("owner_id"); }
        catch (NumberFormatException e) { return false; }
    }
    void markSeen(JSONObject story) {
        if (!sameSession() || !identity(story) || story.optBoolean("seen", false)) return;
        String key = storyKey(story);
        if (!seenAttempts.add(initialUser + ":" + key)) return;
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("owner_id", Long.toString(story.optLong("owner_id")));
        args.put("story_id", Long.toString(story.optLong("id"))); args.put("source", "list");
        String access = story.optString("access_key", ""); if (!access.isEmpty()) args.put("access_key", access);
        execute("stories.markSeen", args, (success, unknown) -> {
            if (!success) events.setValue(new Event(key, "Не удалось подтвердить отметку просмотра", Outcome.SEEN_FAILED));
        });
    }
    boolean sendReply(JSONObject story, String text) { return sendReply(story, text, false); }
    boolean sendReply(JSONObject story, String text, boolean clearDraftOnSuccess) {
        final String draftText = text;
        text = text == null ? "" : text.trim();
        if (!sameSession() || !canReply(story, user()) || text.isEmpty() || text.length() > 4000) return false;
        String key = storyKey(story), lock = initialUser + ":" + key;
        final long owner = story.optLong("owner_id"), storyId = story.optLong("id");
        if (!pending.add(lock)) return false;
        String action = reserveMarker(key, text);
        if (action == null) { pending.remove(lock); return false; }
        int randomId; do { randomId = random.nextInt(Integer.MAX_VALUE); } while (randomId == 0);
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("peer_id", Long.toString(story.optLong("owner_id"))); args.put("random_id", Integer.toString(randomId));
        args.put("message", "https://vk.com/story" + key + "\n" + text);
        execute("messages.send", args, (success, unknown) -> {
            pending.remove(lock);
            if (!unknown) clearMarker(action);
            if (success && clearDraftOnSuccess && draftText != null) {
                // The callback is independent of the Activity lifecycle. Never delete a newer edit.
                StoryDraftCoordinator.execute(() -> draftStores.apply(getApplication())
                        .clearIfMatches(initialUser, owner, storyId, draftText));
            }
            events.setValue(new Event(key, success ? "Сообщение отправлено" : unknown
                    ? "Результат неизвестен. Проверьте диалог перед повторной отправкой"
                    : "Не удалось отправить сообщение", success ? Outcome.REPLY_SENT
                    : unknown ? Outcome.REPLY_UNKNOWN : Outcome.REPLY_REJECTED));
        });
        return true;
    }
    boolean isPending(JSONObject story) { return identity(story) && pending.contains(initialUser + ":" + storyKey(story)); }
    private interface Completion { void done(boolean success, boolean unknown); }
    private void execute(String method, LinkedHashMap<String, String> args, Completion completion) {
        String token = initialToken, secret = initialSecret, user = initialUser;
        if (!sameSession() || user.isEmpty()) { completion.done(false, false); return; }
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("v", "5.82"); params.put("lang", Locale.getDefault().getLanguage()); params.put("https", "1");
        params.put("access_token", token); params.putAll(args);
        if (!secret.isEmpty()) params.put("sig", signature(method, params, secret));
        FormBody.Builder body = new FormBody.Builder();
        for (Map.Entry<String, String> item : params.entrySet()) body.add(item.getKey(), item.getValue());
        Request request = new Request.Builder().url("https://api.vk.com/method/" + method)
                .header("User-Agent", VKApi.USER_AGENT).post(body.build()).build();
        if (!sameSession()) { completion.done(false, false); return; }
        Call call = client.newCall(request); calls.add(call);
        call.enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call c, java.io.IOException ignored) { finish(c, false, true); }
            @Override public void onResponse(Call c, Response response) {
                boolean success = false, unknown = true;
                try (Response received = response) {
                    if (received.isSuccessful() && received.body() != null && !received.body().source().request(1048577L)) {
                        JSONObject root = new JSONObject(received.body().string());
                        JSONObject error = root.optJSONObject("error");
                        if (error != null && definiteRejection(error.optInt("error_code", 0))) unknown = false;
                        else if (error == null && (root.optLong("response", 0) > 0 || Boolean.TRUE.equals(root.opt("response")))) { success = true; unknown = false; }
                    }
                } catch (Exception ignored) { }
                finish(c, success, unknown);
            }
            private void finish(Call c, boolean success, boolean unknown) {
                calls.remove(c); main.post(() -> {
                    if (!token.equals(token()) || !secret.equals(secret()) || !user.equals(user())) completion.done(false, true);
                    else completion.done(success, unknown);
                });
            }
        });
    }
    // Explicit auth, permission, validation and rate-limit errors indicate that VK rejected the request.
    // Internal and undocumented errors may have occurred after a write and remain uncertain.
    private static boolean definiteRejection(int code) {
        switch (code) {
            case 5: case 6: case 7: case 9: case 15: case 17: case 25: case 29:
            case 100: case 900: case 901: case 902: return true;
            default: return false;
        }
    }
    private String reserveMarker(String key, String text) {
        synchronized (markerLock) {
            SharedPreferences preferences = getApplication().getSharedPreferences("story_send_guard", 0);
            String salt = preferences.getString("salt", "");
            if (salt == null || salt.isEmpty()) {
                byte[] bytes = new byte[32]; random.nextBytes(bytes);
                StringBuilder encoded = new StringBuilder();
                for (byte b : bytes) encoded.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
                salt = encoded.toString();
                if (!preferences.edit().putString("salt", salt).commit()) return null;
            }
            String marker = "pending_" + digest(salt + ":" + initialUser + ":" + key + ":" + text);
            if (preferences.contains(marker) || !preferences.edit().putBoolean(marker, true).commit()) return null;
            return marker;
        }
    }
    private void clearMarker(String marker) {
        synchronized (markerLock) { getApplication().getSharedPreferences("story_send_guard", 0).edit().remove(marker).commit(); }
    }
    private static String signature(String method, LinkedHashMap<String, String> args, String secret) {
        StringBuilder raw = new StringBuilder("/method/").append(method).append('?');
        for (Map.Entry<String, String> field : args.entrySet()) {
            if (raw.charAt(raw.length() - 1) != '?') raw.append('&');
            raw.append(field.getKey()).append('=').append(field.getValue());
        }
        try {
            StringBuilder result = new StringBuilder();
            for (byte b : MessageDigest.getInstance("MD5").digest(raw.append(secret).toString().getBytes(StandardCharsets.UTF_8))) {
                result.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            }
            return result.toString();
        } catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    private static String digest(String text) {
        try {
            StringBuilder result = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))
                result.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return result.toString();
        } catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    @Override protected void onCleared() { synchronized (calls) { for (Call call : calls) call.cancel(); calls.clear(); } }
}
