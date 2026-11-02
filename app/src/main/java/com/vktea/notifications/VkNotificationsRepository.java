package com.vktea.notifications;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** VK-only single-page reader. Credentials and response bodies are never logged. */
final class VkNotificationsRepository implements NotificationsRepository {
    private static final long MAX_BYTES = 4 * 1024 * 1024;
    interface Sessions { Snapshot get(); }
    static final class Snapshot {
        final String token, secret, user;
        Snapshot(String token, String secret, String user) {
            this.token = token == null ? "" : token; this.secret = secret == null ? "" : secret; this.user = user == null ? "" : user;
        }
        boolean matches(Snapshot other) { return other != null && token.equals(other.token) && secret.equals(other.secret) && user.equals(other.user); }
    }
    private final HttpUrl endpoint;
    private final Sessions sessions;
    private final OkHttpClient client;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Load> loads = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    VkNotificationsRepository(Context context) {
        this(new OkHttpClient(), HttpUrl.get("https://api.vk.com/"),
                () -> new Snapshot(Session.getToken(context), Session.getSecret(context), Session.getUserId(context)));
    }
    VkNotificationsRepository(OkHttpClient original, HttpUrl endpoint, Sessions sessions) {
        this.endpoint = endpoint; this.sessions = sessions;
        client = original.newBuilder().protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
                .addNetworkInterceptor(chain -> {
                    Response response = chain.proceed(chain.request());
                    return response.code() == 503 ? response.newBuilder().header("Retry-After", "2147483647").build() : response;
                }).build();
    }
    @Override public Request load(String startFrom, Callback<EventPage> callback) {
        Snapshot current = sessions.get();
        Load load = new Load(current == null ? new Snapshot("", "", "") : current, startFrom == null ? "" : startFrom, callback);
        loads.add(load);
        if (closed) load.cancel(); else main.post(load::start);
        return load;
    }
    @Override public void close() { closed = true; for (Load load : loads) load.cancel(); }
    private final class Load implements Request {
        final Snapshot snapshot;
        final String cursor;
        final Callback<EventPage> callback;
        volatile boolean canceled, finished;
        volatile Call call;
        Load(Snapshot snapshot, String cursor, Callback<EventPage> callback) { this.snapshot = snapshot; this.cursor = cursor; this.callback = callback; }
        boolean active() { return !canceled && !finished && !closed; }
        void start() {
            if (!active()) return;
            try { if (Long.parseLong(snapshot.user) <= 0) throw new NumberFormatException(); }
            catch (NumberFormatException ignored) { finish(null, new NotificationsError(NotificationsError.Kind.AUTH)); return; }
            if (snapshot.token.isEmpty() || !snapshot.matches(sessions.get())) { finish(null, new NotificationsError(NotificationsError.Kind.AUTH)); return; }
            if (cursor.length() > 4096) { finish(null, new NotificationsError(NotificationsError.Kind.MALFORMED)); return; }
            HttpUrl.Builder builder = endpoint.newBuilder().addPathSegments("method/notifications.get")
                    .addQueryParameter("v", "5.82").addQueryParameter("lang", Locale.getDefault().getLanguage())
                    .addQueryParameter("https", "1").addQueryParameter("access_token", snapshot.token)
                    .addQueryParameter("count", "100").addQueryParameter("start_time", "1");
            if (!cursor.isEmpty()) builder.addQueryParameter("start_from", cursor);
            HttpUrl unsigned = builder.build();
            if (!snapshot.secret.isEmpty()) builder.addQueryParameter("sig", signature(unsigned.encodedPath() + "?" + unsigned.encodedQuery() + snapshot.secret));
            call = client.newCall(new okhttp3.Request.Builder().url(builder.build()).header("User-Agent", VKApi.USER_AGENT).get().build());
            if (!active()) { call.cancel(); return; }
            call.enqueue(new okhttp3.Callback() {
                @Override public void onFailure(Call failed, IOException ignored) { deliver(null, new NotificationsError(NotificationsError.Kind.NETWORK)); }
                @Override public void onResponse(Call completed, Response response) {
                    try (Response received = response) {
                        if (!received.isSuccessful()) {
                            int status = received.code();
                            NotificationsError.Kind kind = status == 429 ? NotificationsError.Kind.RATE_LIMITED
                                    : status == 401 ? NotificationsError.Kind.AUTH : status == 403 ? NotificationsError.Kind.ACCESS : NotificationsError.Kind.HTTP;
                            deliver(null, new NotificationsError(kind, 0, status)); return;
                        }
                        ResponseBody body = received.body();
                        if (body == null || body.source().request(MAX_BYTES + 1) && body.source().buffer().size() > MAX_BYTES) {
                            deliver(null, new NotificationsError(NotificationsError.Kind.MALFORMED)); return;
                        }
                        JSONObject root = new JSONObject(body.string());
                        JSONObject error = root.optJSONObject("error");
                        if (error != null) deliver(null, apiError(error.optInt("error_code", 0)));
                        else deliver(NotificationsParser.parse(root), null);
                    } catch (IOException ignored) { deliver(null, new NotificationsError(NotificationsError.Kind.NETWORK)); }
                    catch (JSONException | RuntimeException ignored) { deliver(null, new NotificationsError(NotificationsError.Kind.MALFORMED)); }
                }
            });
        }
        void deliver(EventPage page, NotificationsError error) { main.post(() -> finish(page, error)); }
        void finish(EventPage page, NotificationsError error) {
            if (!active()) return;
            if (!snapshot.matches(sessions.get())) { page = null; error = new NotificationsError(NotificationsError.Kind.AUTH); }
            finished = true; loads.remove(this);
            if (error != null) callback.error(error); else callback.success(page);
        }
        @Override public void cancel() { canceled = true; Call active = call; if (active != null) active.cancel(); loads.remove(this); }
    }
    private static NotificationsError apiError(int code) {
        NotificationsError.Kind kind;
        switch (code) {
            case 5: case 17: case 25: kind = NotificationsError.Kind.AUTH; break;
            case 7: case 15: case 20: case 23: case 200: case 201: case 260: kind = NotificationsError.Kind.ACCESS; break;
            case 6: case 9: case 29: case 1105: kind = NotificationsError.Kind.RATE_LIMITED; break;
            default: kind = NotificationsError.Kind.API;
        }
        return new NotificationsError(kind, code, 0);
    }
    private static String signature(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] digits = "0123456789abcdef".toCharArray(); StringBuilder result = new StringBuilder(32);
            for (byte b : hash) result.append(digits[(b >>> 4) & 15]).append(digits[b & 15]);
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError("MD5 unavailable"); }
    }
}
