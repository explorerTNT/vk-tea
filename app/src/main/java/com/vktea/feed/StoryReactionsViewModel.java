package com.vktea.feed;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import org.json.JSONArray;
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

/** Single-flight story likes. Requests and credentials are never logged or retried. */
public final class StoryReactionsViewModel extends AndroidViewModel {
    enum Result { REACTION_CONFIRMED, APPLIED, REMOVED, REJECTED, UNKNOWN }
    static final class Event {
        final String key;
        final Result result;
        final Integer reactionId;
        Event(String key, Result result, Integer reactionId) { this.key = key; this.result = result; this.reactionId = reactionId; }
    }
    final MutableLiveData<Event> events = new MutableLiveData<>();
    private static final Set<String> pending = Collections.synchronizedSet(new HashSet<>());
    private static final Object markerLock = new Object();
    private final SecureRandom random = new SecureRandom();
    private final Set<Call> calls = Collections.synchronizedSet(new HashSet<>());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient client;
    private final String initialToken, initialSecret, initialUser;

    public StoryReactionsViewModel(Application app) { this(app, new OkHttpClient()); }
    StoryReactionsViewModel(Application app, OkHttpClient transport) {
        super(app);
        initialToken = token(); initialSecret = secret(); initialUser = user();
        client = transport.newBuilder().protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
                .addNetworkInterceptor(chain -> {
                    Response r = chain.proceed(chain.request());
                    return r.code() == 503 ? r.newBuilder().header("Retry-After", "2147483647").build() : r;
                }).build();
    }
    private static String value(String s) { return s == null ? "" : s; }
    private String token() { return value(Session.getToken(getApplication())); }
    private String secret() { return value(Session.getSecret(getApplication())); }
    private String user() { return value(Session.getUserId(getApplication())); }
    boolean sameSession() { return !initialToken.isEmpty() && !initialUser.isEmpty()
            && initialToken.equals(token()) && initialSecret.equals(secret()) && initialUser.equals(user()); }
    static boolean canLike(JSONObject story) {
        if (!StoryActionsViewModel.identity(story)) return false;
        Object v = story.opt("can_like");
        return Boolean.TRUE.equals(v) || (v instanceof Number && ((Number) v).intValue() == 1);
    }
    static boolean validReaction(int id) { return id == 0 || id == 1 || id == 2 || id == 3 || id == 5 || id == 6; }
    private String lock(JSONObject story) { return initialUser + ":" + StoryActionsViewModel.storyKey(story); }
    boolean isPending(JSONObject story) { return StoryActionsViewModel.identity(story) && pending.contains(lock(story)); }
    boolean isUncertain(JSONObject story) {
        if (!StoryActionsViewModel.identity(story)) return false;
        synchronized (pending) { return !pending.contains(lock(story)) && hasMarker(StoryActionsViewModel.storyKey(story)); }
    }

    /** Explicit acknowledgement after the user checks VK; never sends a network request. */
    boolean acknowledgeUnknown(JSONObject story) {
        if (!StoryActionsViewModel.identity(story)) return false;
        synchronized (pending) {
            if (!sameSession() || pending.contains(lock(story))) return false;
            String key = StoryActionsViewModel.storyKey(story);
            return hasMarker(key) && clearMarker(key);
        }
    }

    /** null removes a reaction. APPLIED confirms a like but not its particular reaction ID. */
    boolean send(JSONObject story, Integer reactionId) {
        if (!sameSession() || !canLike(story) || (reactionId != null && !validReaction(reactionId))) return false;
        String key = StoryActionsViewModel.storyKey(story), lock = lock(story);
        long ownerId = story.optLong("owner_id"), storyId = story.optLong("id");
        synchronized (pending) {
            if (pending.contains(lock) || !reserveMarker(key)) return false;
            pending.add(lock);
        }
        String method = reactionId == null ? "likes.delete" : "likes.add";
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("v", "5.82"); params.put("lang", Locale.getDefault().getLanguage()); params.put("https", "1");
        params.put("access_token", initialToken); params.put("type", "story");
        params.put("owner_id", Long.toString(ownerId));
        params.put("item_id", Long.toString(storyId));
        if (reactionId != null) params.put("reaction_id", Integer.toString(reactionId));
        String access = story.optString("access_key", ""); if (!access.isEmpty()) params.put("access_key", access);
        if (!initialSecret.isEmpty()) params.put("sig", signature(method, params, initialSecret));
        FormBody.Builder body = new FormBody.Builder();
        for (Map.Entry<String, String> param : params.entrySet()) body.add(param.getKey(), param.getValue());
        Request request = new Request.Builder().url("https://api.vk.com/method/" + method)
                .header("User-Agent", VKApi.USER_AGENT).post(body.build()).build();
        if (!sameSession()) { synchronized (pending) { clearMarker(key); pending.remove(lock); } return false; }
        Call call = client.newCall(request); calls.add(call);
        call.enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call c, java.io.IOException ignored) { finish(c, Result.UNKNOWN); }
            @Override public void onResponse(Call c, Response response) {
                Result result = Result.UNKNOWN;
                try (Response received = response) {
                    if (received.isSuccessful() && received.body() != null && !received.body().source().request(1048577L)) {
                        JSONObject root = new JSONObject(received.body().string());
                        JSONObject error = root.optJSONObject("error");
                        if (error != null && definiteRejection(error.optInt("error_code"))) result = Result.REJECTED;
                        else if (error == null && validResponse(root.optJSONObject("response"), reactionId != null))
                            result = reactionId == null ? Result.REMOVED : Result.APPLIED;
                    }
                } catch (Exception ignored) { }
                if (result == Result.APPLIED && sameSession()) {
                    calls.remove(c);
                    verifyReaction(ownerId, storyId, access, reactionId, verified -> finish(c, verified));
                } else finish(c, result);
            }
            private void finish(Call c, Result result) {
                calls.remove(c);
                main.post(() -> {
                    Result checked = sameSession() ? result : Result.UNKNOWN;
                    synchronized (pending) {
                        if (checked != Result.UNKNOWN) clearMarker(key);
                        pending.remove(lock);
                    }
                    events.setValue(new Event(key, checked, checked == Result.REACTION_CONFIRMED ? reactionId : null));
                });
            }
        });
        return true;
    }
    private interface Verification { void done(Result result); }
    /** One read only; failure cannot undo the already confirmed mutation. */
    private void verifyReaction(long owner, long id, String access, int requested, Verification completion) {
        if (!sameSession()) { completion.done(Result.UNKNOWN); return; }
        String method = "stories.getById";
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("v", "5.82"); params.put("lang", Locale.getDefault().getLanguage()); params.put("https", "1");
        params.put("access_token", initialToken);
        params.put("stories", owner + "_" + id + (access.isEmpty() ? "" : "_" + access));
        params.put("extended", "1");
        if (!initialSecret.isEmpty()) params.put("sig", signature(method, params, initialSecret));
        FormBody.Builder body = new FormBody.Builder();
        for (Map.Entry<String, String> param : params.entrySet()) body.add(param.getKey(), param.getValue());
        Request request = new Request.Builder().url("https://api.vk.com/method/" + method)
                .header("User-Agent", VKApi.USER_AGENT).post(body.build()).build();
        if (!sameSession()) { completion.done(Result.UNKNOWN); return; }
        Call call = client.newCall(request); calls.add(call);
        call.enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call c, java.io.IOException ignored) { finish(c, Result.APPLIED); }
            @Override public void onResponse(Call c, Response response) {
                Result result = Result.APPLIED;
                try (Response received = response) {
                    if (received.isSuccessful() && received.body() != null && !received.body().source().request(1048577L)) {
                        JSONObject root = new JSONObject(received.body().string());
                        JSONObject data = root.optJSONObject("response");
                        JSONArray items = data == null ? null : data.optJSONArray("items");
                        if (root.optJSONObject("error") == null && items != null) {
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.optJSONObject(i);
                                if (item == null || item.optLong("owner_id") != owner || item.optLong("id") != id) continue;
                                Object actual = item.opt("user_reaction_id");
                                if ((actual instanceof Integer || actual instanceof Long)
                                        && ((Number) actual).longValue() == requested) result = Result.REACTION_CONFIRMED;
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) { }
                finish(c, result);
            }
            private void finish(Call c, Result result) { calls.remove(c); completion.done(result); }
        });
    }
    private static boolean validResponse(JSONObject response, boolean adding) {
        if (response == null) return false;
        Object likes = response.opt("likes");
        if (!(likes instanceof Integer || likes instanceof Long)) return false;
        long count = ((Number) likes).longValue();
        return adding ? count > 0 : count >= 0;
    }
    private static boolean definiteRejection(int code) {
        switch (code) {
            case 5: case 6: case 7: case 9: case 15: case 17: case 25: case 29:
            case 100: case 900: case 901: case 902: return true;
            default: return false;
        }
    }
    /* Persist only a salted digest. A process death while in flight remains an unknown outcome. */
    private String marker(String key, boolean createSalt) {
        SharedPreferences prefs = getApplication().getSharedPreferences("story_reaction_guard", 0);
        String salt = prefs.getString("salt", "");
        if (salt == null || salt.isEmpty()) {
            if (!createSalt) return null;
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            StringBuilder encoded = new StringBuilder();
            for (byte b : bytes) encoded.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            salt = encoded.toString();
            if (!prefs.edit().putString("salt", salt).commit()) return null;
        }
        return "pending_" + digest(salt + ":" + initialUser + ":" + key);
    }
    private boolean reserveMarker(String key) {
        synchronized (markerLock) {
            String marker = marker(key, true);
            if (marker == null) return false;
            SharedPreferences prefs = getApplication().getSharedPreferences("story_reaction_guard", 0);
            return !prefs.contains(marker) && prefs.edit().putBoolean(marker, true).commit();
        }
    }
    private boolean hasMarker(String key) {
        synchronized (markerLock) {
            String marker = marker(key, false);
            return marker != null && getApplication().getSharedPreferences("story_reaction_guard", 0).contains(marker);
        }
    }
    private boolean clearMarker(String key) {
        synchronized (markerLock) {
            String marker = marker(key, false);
            return marker != null && getApplication().getSharedPreferences("story_reaction_guard", 0).edit().remove(marker).commit();
        }
    }
    private static String digest(String text) {
        try {
            StringBuilder result = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))
                result.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return result.toString();
        } catch (Exception impossible) { throw new AssertionError(impossible); }
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
    @Override protected void onCleared() { synchronized (calls) { for (Call call : calls) call.cancel(); calls.clear(); } }
}
