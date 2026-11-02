package com.vktea.msg;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.vktea.api.VKApi;
import com.vktea.auth.Session;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;

/** Sends one explicitly selected message, without logging credentials or retrying a POST. */
final class MessageForwarder implements AutoCloseable {
    interface Callback { void complete(JSONObject result); void failed(); }
    interface Sessions { Credentials get(); }
    static final class Credentials {
        final String token, secret, user;
        Credentials(String token, String secret, String user) {
            this.token = clean(token); this.secret = clean(secret); this.user = clean(user);
        }
        boolean matches(Credentials other) {
            return other != null && token.equals(other.token) && secret.equals(other.secret) && user.equals(other.user);
        }
        private static String clean(String value) { return value == null ? "" : value; }
    }
    private final OkHttpClient client;
    private final HttpUrl endpoint;
    private final Sessions sessions;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private volatile Call active;

    MessageForwarder(Context context) {
        this(new OkHttpClient(), HttpUrl.get("https://api.vk.com/"),
                () -> new Credentials(Session.getToken(context), Session.getSecret(context), Session.getUserId(context)));
    }

    MessageForwarder(OkHttpClient original, HttpUrl endpoint, Sessions sessions) {
        this.endpoint = endpoint;
        this.sessions = sessions;
        client = original.newBuilder().protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS).addNetworkInterceptor(chain -> {
                    Response response = chain.proceed(chain.request());
                    return response.code() == 503 ? response.newBuilder().header("Retry-After", "2147483647").build() : response;
                }).build();
    }

    void recipients(Callback callback) {
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("count", "40"); args.put("extended", "1");
        args.put("fields", "first_name,last_name,photo_100");
        execute("messages.getConversations", args, false, callback);
    }

    void send(long peerId, long messageId, Callback callback) {
        if (peerId == 0 || peerId == Long.MIN_VALUE || peerId == 2_000_000_000L || messageId <= 0) {
            callback.failed(); return;
        }
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("peer_id", Long.toString(peerId));
        int randomId;
        do { randomId = new SecureRandom().nextInt(); } while (randomId == 0);
        args.put("random_id", Integer.toString(randomId));
        args.put("forward_messages", Long.toString(messageId));
        execute("messages.send", args, true, callback);
    }

    void sendText(long peerId, String text, Callback callback) {
        if (peerId == 0 || peerId == Long.MIN_VALUE || peerId == 2_000_000_000L || text == null || text.trim().isEmpty()) {
            callback.failed(); return;
        }
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("peer_id", Long.toString(peerId));
        int randomId;
        do { randomId = new SecureRandom().nextInt(); } while (randomId == 0);
        args.put("random_id", Integer.toString(randomId));
        args.put("message", text);
        execute("messages.send", args, true, callback);
    }

    private void execute(String method, LinkedHashMap<String, String> args, boolean post, Callback callback) {
        Credentials snapshot = sessions.get();
        if (closed || snapshot == null || snapshot.token.isEmpty()) { callback.failed(); return; }
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("v", "5.82"); params.put("lang", Locale.getDefault().getLanguage());
        params.put("https", "1"); params.put("access_token", snapshot.token); params.putAll(args);
        if (!snapshot.secret.isEmpty()) params.put("sig", signature(method, params, snapshot.secret));
        HttpUrl.Builder url = endpoint.newBuilder().addPathSegments("method/" + method);
        FormBody.Builder form = new FormBody.Builder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (post) form.add(entry.getKey(), entry.getValue());
            else url.addQueryParameter(entry.getKey(), entry.getValue());
        }
        okhttp3.Request.Builder request = new okhttp3.Request.Builder().url(url.build()).header("User-Agent", VKApi.USER_AGENT);
        if (post) request.post(form.build());
        Call call = client.newCall(request.build());
        active = call;
        if (closed) { call.cancel(); return; }
        call.enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call ignored, java.io.IOException error) { deliver(null); }
            @Override public void onResponse(Call ignored, Response response) {
                JSONObject result = null;
                try (Response received = response) {
                    if (received.isSuccessful() && received.body() != null
                            && !(received.body().source().request(2 * 1024 * 1024 + 1L)
                            && received.body().source().buffer().size() > 2 * 1024 * 1024)) {
                        JSONObject root = new JSONObject(received.body().string());
                        if (root.has("response") && (!post || root.optLong("response", 0) > 0)) result = root;
                    }
                } catch (Exception ignoredError) { }
                deliver(result);
            }
            private void deliver(JSONObject result) {
                main.post(() -> {
                    if (closed || call.isCanceled()) return;
                    if (!snapshot.matches(sessions.get()) || result == null) callback.failed();
                    else callback.complete(result);
                });
            }
        });
    }

    private static String signature(String method, LinkedHashMap<String, String> params, String secret) {
        StringBuilder source = new StringBuilder("/method/").append(method).append('?');
        for (Map.Entry<String, String> param : params.entrySet()) {
            if (source.charAt(source.length() - 1) != '?') source.append('&');
            source.append(param.getKey()).append('=').append(param.getValue());
        }
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(source.append(secret).toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) { result.append(Character.forDigit((b >>> 4) & 15, 16)); result.append(Character.forDigit(b & 15, 16)); }
            return result.toString();
        } catch (Exception impossible) { throw new IllegalStateException("Signature unavailable"); }
    }

    @Override public void close() { closed = true; Call call = active; if (call != null) call.cancel(); }
}
