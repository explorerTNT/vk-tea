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
import java.util.List;
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
    interface Callback {
        void complete(JSONObject result);
        void failed();
        /** VK API отказал с конкретным кодом (например 924 при удалении для всех). */
        default void apiError(int code) { failed(); }
    }
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
        send(peerId, Collections.singletonList(messageId), callback);
    }

    /** Пересылка одного или нескольких сообщений (Coffee forward_messages). */
    void send(long peerId, List<Long> messageIds, Callback callback) {
        if (peerId == 0 || peerId == Long.MIN_VALUE || peerId == 2_000_000_000L
                || messageIds == null || messageIds.isEmpty()) {
            callback.failed(); return;
        }
        StringBuilder ids = new StringBuilder();
        for (Long id : messageIds) {
            if (id == null || id <= 0) continue;
            if (ids.length() > 0) ids.append(',');
            ids.append(id);
        }
        if (ids.length() == 0) { callback.failed(); return; }
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("peer_id", Long.toString(peerId));
        int randomId;
        do { randomId = new SecureRandom().nextInt(); } while (randomId == 0);
        args.put("random_id", Integer.toString(randomId));
        args.put("forward_messages", ids.toString());
        execute("messages.send", args, true, callback);
    }

    void sendText(long peerId, String text, Callback callback) {
        sendText(peerId, text, 0, null, callback);
    }

    /** Текст с необязательными ответом (reply_to) и прикреплёнными пересылками (Coffee WriteBar). */
    void sendText(long peerId, String text, long replyTo, List<Long> forwardIds, Callback callback) {
        if (peerId == 0 || peerId == Long.MIN_VALUE || peerId == 2_000_000_000L || text == null || text.trim().isEmpty()) {
            callback.failed(); return;
        }
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("peer_id", Long.toString(peerId));
        int randomId;
        do { randomId = new SecureRandom().nextInt(); } while (randomId == 0);
        args.put("random_id", Integer.toString(randomId));
        args.put("message", text);
        if (replyTo > 0) args.put("reply_to", Long.toString(replyTo));
        if (forwardIds != null && !forwardIds.isEmpty()) {
            StringBuilder ids = new StringBuilder();
            for (Long id : forwardIds) {
                if (id == null || id <= 0) continue;
                if (ids.length() > 0) ids.append(',');
                ids.append(id);
            }
            if (ids.length() > 0) args.put("forward_messages", ids.toString());
        }
        execute("messages.send", args, true, callback);
    }

    /** messages.edit с сохранением пересылок, как в Coffee MessagesEdit. */
    void editMessage(long peerId, long messageId, String text, Callback callback) {
        if (peerId == 0 || messageId <= 0 || text == null || text.trim().isEmpty()) {
            callback.failed(); return;
        }
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("peer_id", Long.toString(peerId));
        args.put("message_id", Long.toString(messageId));
        args.put("message", text);
        args.put("keep_forward_messages", "1");
        execute("messages.edit", args, true, callback);
    }

    /** messages.delete; ошибка 924 означает отказ удаления для всех. */
    void deleteMessages(List<Long> messageIds, boolean deleteForAll, Callback callback) {
        if (messageIds == null || messageIds.isEmpty()) { callback.failed(); return; }
        StringBuilder ids = new StringBuilder();
        for (Long id : messageIds) {
            if (id == null || id <= 0) continue;
            if (ids.length() > 0) ids.append(',');
            ids.append(id);
        }
        if (ids.length() == 0) { callback.failed(); return; }
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("message_ids", ids.toString());
        args.put("delete_for_all", deleteForAll ? "1" : "0");
        execute("messages.delete", args, true, callback);
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
            @Override public void onFailure(Call ignored, java.io.IOException error) { deliver(null, null); }
            @Override public void onResponse(Call ignored, Response response) {
                JSONObject result = null;
                Integer apiErrorCode = null;
                try (Response received = response) {
                    if (received.isSuccessful() && received.body() != null
                            && !(received.body().source().request(2 * 1024 * 1024 + 1L)
                            && received.body().source().buffer().size() > 2 * 1024 * 1024)) {
                        JSONObject root = new JSONObject(received.body().string());
                        if (root.has("response")) {
                            // messages.delete возвращает 1 или массив [1, ...] на каждого - оба успешны.
                            result = root;
                        } else if (root.has("error")) {
                            apiErrorCode = root.optJSONObject("error") == null
                                    ? null : root.optJSONObject("error").optInt("error_code", 0);
                        }
                    }
                } catch (Exception ignoredError) { }
                deliver(result, apiErrorCode);
            }
            private void deliver(JSONObject result, Integer apiErrorCode) {
                main.post(() -> {
                    if (closed || call.isCanceled()) return;
                    if (!snapshot.matches(sessions.get())) callback.failed();
                    else if (result != null) callback.complete(result);
                    else if (apiErrorCode != null) callback.apiError(apiErrorCode);
                    else callback.failed();
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
