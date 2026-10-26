package com.vktea.groups;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** VK-only read transport: no payload logging, redirects, automatic retries or account mutations. */
final class VkGroupsRepository implements GroupsRepository {
    static final int PAGE_SIZE = 1000;
    private static final long MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final long MAX_LOAD_MILLIS = 120_000;
    private static final int MAX_RAW_ITEMS = 100_000;
    private static final String FIELDS = "members_count,activity,screen_name,verified";
    interface Sessions { Snapshot get(); }
    static final class Snapshot {
        final String token, secret, user;
        Snapshot(String token, String secret, String user) {
            this.token = token == null ? "" : token; this.secret = secret == null ? "" : secret; this.user = user == null ? "" : user;
        }
        boolean matches(Snapshot other) { return other != null && token.equals(other.token) && secret.equals(other.secret) && user.equals(other.user); }
    }
    private final OkHttpClient client;
    private final HttpUrl endpoint;
    private final Sessions sessions;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Load> loads = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    VkGroupsRepository(Context context) {
        this(new OkHttpClient(), HttpUrl.get("https://api.vk.com/"),
                () -> new Snapshot(Session.getToken(context), Session.getSecret(context), Session.getUserId(context)));
    }
    /** Production fixes the destination above; the package-local transport seam keeps tests offline. */
    VkGroupsRepository(OkHttpClient original, HttpUrl endpoint, Sessions sessions) {
        this.endpoint = endpoint; this.sessions = sessions;
        client = original.newBuilder().protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
                .addNetworkInterceptor(chain -> {
                    Response response = chain.proceed(chain.request());
                    return response.code() == 503 ? response.newBuilder().header("Retry-After", "2147483647").build() : response;
                }).build();
    }
    @Override public Request load(Callback<List<Group>> callback) {
        Snapshot current = sessions.get();
        Load load = new Load(callback, current == null ? new Snapshot("", "", "") : current);
        loads.add(load);
        if (closed) load.cancel(); else main.post(load.next);
        return load;
    }
    @Override public void close() { closed = true; for (Load load : loads) load.cancel(); }

    private final class Load implements Request {
        final Callback<List<Group>> callback;
        final Snapshot snapshot;
        final LinkedHashMap<Long, Group> items = new LinkedHashMap<>();
        final long started = SystemClock.elapsedRealtime();
        final Runnable next = this::page;
        volatile boolean canceled, finished;
        volatile Call call;
        int offset;
        Load(Callback<List<Group>> callback, Snapshot snapshot) { this.callback = callback; this.snapshot = snapshot; }
        private boolean active() { return !canceled && !finished && !closed; }
        void page() {
            if (!active()) return;
            long userId;
            try { userId = Long.parseLong(snapshot.user); }
            catch (NumberFormatException ignored) { finish(null, new GroupsError(GroupsError.Kind.AUTH)); return; }
            if (userId <= 0 || snapshot.token.isEmpty() || !snapshot.matches(sessions.get())) {
                finish(null, new GroupsError(GroupsError.Kind.AUTH)); return;
            }
            if (SystemClock.elapsedRealtime() - started > MAX_LOAD_MILLIS) { finish(null, new GroupsError(GroupsError.Kind.NETWORK)); return; }
            LinkedHashMap<String, String> params = new LinkedHashMap<>();
            params.put("v", "5.82"); params.put("lang", Locale.getDefault().getLanguage()); params.put("https", "1");
            params.put("access_token", snapshot.token); params.put("user_id", Long.toString(userId));
            params.put("extended", "1"); params.put("fields", FIELDS);
            params.put("count", Integer.toString(PAGE_SIZE)); params.put("offset", Integer.toString(offset));
            HttpUrl.Builder url = endpoint.newBuilder().addPathSegments("method/groups.get");
            for (Map.Entry<String, String> field : params.entrySet()) url.addQueryParameter(field.getKey(), field.getValue());
            if (!snapshot.secret.isEmpty()) url.addQueryParameter("sig", signature(params, snapshot.secret));
            call = client.newCall(new okhttp3.Request.Builder().url(url.build()).header("User-Agent", VKApi.USER_AGENT).get().build());
            if (!active()) { call.cancel(); return; }
            call.enqueue(new okhttp3.Callback() {
                @Override public void onFailure(Call failed, IOException ignored) { deliver(null, new GroupsError(GroupsError.Kind.NETWORK)); }
                @Override public void onResponse(Call completed, Response response) {
                    try (Response received = response) {
                        if (!received.isSuccessful()) {
                            GroupsError.Kind kind = received.code() == 429 ? GroupsError.Kind.RATE_LIMITED
                                    : received.code() == 401 ? GroupsError.Kind.AUTH : received.code() == 403 ? GroupsError.Kind.ACCESS : GroupsError.Kind.HTTP;
                            deliver(null, new GroupsError(kind, 0, received.code())); return;
                        }
                        ResponseBody body = received.body();
                        if (body == null || body.source().request(MAX_RESPONSE_BYTES + 1) && body.source().buffer().size() > MAX_RESPONSE_BYTES) {
                            deliver(null, new GroupsError(GroupsError.Kind.MALFORMED)); return;
                        }
                        JSONObject root = new JSONObject(body.string());
                        JSONObject error = root.optJSONObject("error");
                        if (error != null) deliver(null, apiError(error.optInt("error_code", 0)));
                        else deliver(parse(root), null);
                    } catch (IOException ignored) { deliver(null, new GroupsError(GroupsError.Kind.NETWORK)); }
                    catch (JSONException | RuntimeException ignored) { deliver(null, new GroupsError(GroupsError.Kind.MALFORMED)); }
                }
            });
        }
        void deliver(Page page, GroupsError failure) {
            main.post(() -> {
                if (!active()) return;
                call = null;
                if (!snapshot.matches(sessions.get())) { finish(null, new GroupsError(GroupsError.Kind.AUTH)); return; }
                if (failure != null) { finish(null, failure); return; }
                if (SystemClock.elapsedRealtime() - started > MAX_LOAD_MILLIS) { finish(null, new GroupsError(GroupsError.Kind.NETWORK)); return; }
                if (page.consumed == 0 && offset < page.total || (long) offset + page.consumed > MAX_RAW_ITEMS) {
                    finish(null, new GroupsError(GroupsError.Kind.MALFORMED)); return;
                }
                for (Group group : page.items) items.putIfAbsent(group.id, group);
                offset += page.consumed;
                if (offset >= page.total) finish(Collections.unmodifiableList(new ArrayList<>(items.values())), null);
                else main.postDelayed(next, 350);
            });
        }
        void finish(List<Group> value, GroupsError failure) {
            if (!active()) return;
            finished = true; loads.remove(this); main.removeCallbacks(next);
            if (failure != null) callback.error(failure); else callback.success(value);
        }
        @Override public void cancel() {
            canceled = true; main.removeCallbacks(next);
            Call active = call; if (active != null) active.cancel();
            loads.remove(this);
        }
    }
    private static final class Page {
        final int total, consumed;
        final List<Group> items;
        Page(int total, int consumed, List<Group> items) { this.total = total; this.consumed = consumed; this.items = items; }
    }
    private static Page parse(JSONObject root) throws JSONException {
        JSONObject response = root.getJSONObject("response");
        long count = response.optLong("count", -1);
        JSONArray rows = response.getJSONArray("items");
        if (count < 0 || count > MAX_RAW_ITEMS || rows.length() > PAGE_SIZE) throw new JSONException("Invalid page");
        List<Group> items = new ArrayList<>();
        for (int index = 0; index < rows.length(); index++) { Group row = Group.parse(rows.optJSONObject(index)); if (row != null) items.add(row); }
        return new Page((int) count, rows.length(), items);
    }
    private static GroupsError apiError(int code) {
        GroupsError.Kind kind;
        switch (code) {
            case 5: case 17: case 25: kind = GroupsError.Kind.AUTH; break;
            case 7: case 15: case 20: case 23: case 200: case 201: case 260: kind = GroupsError.Kind.ACCESS; break;
            case 6: case 9: case 29: case 1105: kind = GroupsError.Kind.RATE_LIMITED; break;
            default: kind = GroupsError.Kind.API;
        }
        return new GroupsError(kind, code, 0);
    }
    private static String signature(LinkedHashMap<String, String> params, String secret) {
        StringBuilder raw = new StringBuilder("/method/groups.get?");
        for (Map.Entry<String, String> field : params.entrySet()) {
            if (raw.charAt(raw.length() - 1) != '?') raw.append('&');
            raw.append(field.getKey()).append('=').append(field.getValue());
        }
        raw.append(secret);
        try {
            StringBuilder result = new StringBuilder(32);
            char[] hex = "0123456789abcdef".toCharArray();
            for (byte value : MessageDigest.getInstance("MD5").digest(raw.toString().getBytes(StandardCharsets.UTF_8)))
                result.append(hex[(value >>> 4) & 15]).append(hex[value & 15]);
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError("MD5 unavailable"); }
    }
}
