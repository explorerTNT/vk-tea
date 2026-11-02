package com.vktea.documents;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Response;

/** Read-only, cancellable VK transport; never logs responses, URLs or credentials. */
final class VkDocumentsRepository implements DocumentsRepository {
    static final int PAGE_SIZE = 50;
    private static final int MAX_ITEMS = 100_000;
    interface Sessions { Snapshot get(); }
    static final class Snapshot {
        final String token, secret, user;
        Snapshot(String token, String secret, String user) {
            this.token = token == null ? "" : token;
            this.secret = secret == null ? "" : secret;
            this.user = user == null ? "" : user;
        }
        boolean matches(Snapshot other) {
            return other != null && token.equals(other.token) && secret.equals(other.secret) && user.equals(other.user);
        }
    }
    private final OkHttpClient client;
    private final HttpUrl endpoint;
    private final Sessions sessions;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Load> loads = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    VkDocumentsRepository(Context context) {
        this(new OkHttpClient(), HttpUrl.get("https://api.vk.com/"),
                () -> new Snapshot(Session.getToken(context), Session.getSecret(context), Session.getUserId(context)));
    }
    VkDocumentsRepository(OkHttpClient source, HttpUrl endpoint, Sessions sessions) {
        this.endpoint = endpoint; this.sessions = sessions;
        this.client = source.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(chain -> {
                    try { return chain.proceed(chain.request()); }
                    catch (RuntimeException unavailable) { throw new IOException("Network unavailable"); }
                }).addNetworkInterceptor(chain -> {
                    Response response = chain.proceed(chain.request());
                    return response.code() == 503 ? response.newBuilder().header("Retry-After", "2147483647").build() : response;
                }).build();
    }
    @Override public Request load(String query, int offset, Callback callback) {
        Snapshot snapshot = sessions.get();
        Load load = new Load(callback, snapshot == null ? new Snapshot("", "", "") : snapshot);
        loads.add(load);
        if (closed) { load.cancel(); return load; }
        if (snapshot == null || snapshot.token.isEmpty() || !snapshot.matches(sessions.get())) {
            load.deliver(null, new Error(Error.Kind.AUTH)); return load;
        }
        String cleaned = query == null ? "" : query.trim();
        if (cleaned.length() > 200) cleaned = cleaned.substring(0, 200);
        String method = cleaned.isEmpty() ? "docs.get" : "docs.search";
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("v", "5.82"); params.put("lang", Locale.getDefault().getLanguage()); params.put("https", "1");
        params.put("access_token", snapshot.token);
        if (cleaned.isEmpty()) {
            if (!snapshot.user.matches("[1-9][0-9]{0,18}")) { load.deliver(null, new Error(Error.Kind.AUTH)); return load; }
            params.put("owner_id", snapshot.user);
        } else { params.put("q", cleaned); params.put("search_own", "1"); }
        params.put("count", Integer.toString(PAGE_SIZE));
        params.put("offset", Integer.toString(Math.max(0, Math.min(MAX_ITEMS, offset))));
        if (!snapshot.secret.isEmpty()) params.put("sig", signature(method, params, snapshot.secret));
        FormBody.Builder form = new FormBody.Builder();
        for (Map.Entry<String, String> item : params.entrySet()) form.add(item.getKey(), item.getValue());
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(endpoint.newBuilder().addPathSegments("method/" + method).build())
                .header("User-Agent", VKApi.USER_AGENT).post(form.build()).build();
        Call call = client.newCall(request); load.call = call;
        if (!load.active()) { call.cancel(); return load; }
        call.enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call ignored, IOException error) { load.deliver(null, new Error(Error.Kind.NETWORK)); }
            @Override public void onResponse(Call ignored, Response response) {
                try (Response received = response) {
                    if (!received.isSuccessful()) {
                        Error.Kind kind = received.code() == 429 ? Error.Kind.RATE_LIMIT : received.code() == 401
                                ? Error.Kind.AUTH : received.code() == 403 ? Error.Kind.ACCESS : Error.Kind.NETWORK;
                        load.deliver(null, new Error(kind)); return;
                    }
                    if (received.body() == null || received.body().source().request(2_097_153)
                            && received.body().source().buffer().size() > 2_097_152) throw new JSONException("Invalid response");
                    JSONObject root = new JSONObject(received.body().string());
                    JSONObject error = root.optJSONObject("error");
                    if (error != null) { load.deliver(null, new Error(errorKind(error.optInt("error_code")))); return; }
                    load.deliver(parse(root), null);
                } catch (Exception failure) { load.deliver(null, new Error(Error.Kind.API)); }
            }
        });
        return load;
    }
    static Page parse(JSONObject root) throws JSONException {
        JSONObject response = root.getJSONObject("response");
        JSONArray rows = response.getJSONArray("items");
        long count = response.optLong("count", -1);
        if (count < 0 || count > MAX_ITEMS || rows.length() > PAGE_SIZE) throw new JSONException("Invalid page");
        ArrayList<DocumentItem> items = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            DocumentItem item = DocumentItem.parse(rows.optJSONObject(i));
            if (item != null) items.add(item);
        }
        return new Page(Collections.unmodifiableList(items), (int) count, rows.length());
    }
    private static Error.Kind errorKind(int code) {
        if (code == 5 || code == 17 || code == 25) return Error.Kind.AUTH;
        if (code == 6 || code == 9 || code == 29) return Error.Kind.RATE_LIMIT;
        if (code == 7 || code == 15 || code == 200 || code == 201) return Error.Kind.ACCESS;
        return Error.Kind.API;
    }
    private static String signature(String method, LinkedHashMap<String, String> params, String secret) {
        StringBuilder source = new StringBuilder("/method/").append(method).append('?');
        for (Map.Entry<String, String> item : params.entrySet()) {
            if (source.charAt(source.length() - 1) != '?') source.append('&');
            source.append(item.getKey()).append('=').append(item.getValue());
        }
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(source.append(secret).toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) { hex.append(Character.forDigit((value >>> 4) & 15, 16)); hex.append(Character.forDigit(value & 15, 16)); }
            return hex.toString();
        } catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    @Override public void close() { closed = true; for (Load load : loads) load.cancel(); }
    private final class Load implements Request {
        final Callback callback; final Snapshot snapshot;
        volatile boolean canceled, finished; volatile Call call;
        Load(Callback callback, Snapshot snapshot) { this.callback = callback; this.snapshot = snapshot; }
        boolean active() { return !closed && !canceled && !finished; }
        void deliver(Page page, Error error) {
            main.post(() -> {
                if (!active()) return;
                finished = true; loads.remove(this);
                if (!snapshot.matches(sessions.get())) callback.error(new Error(Error.Kind.AUTH));
                else if (error != null) callback.error(error);
                else callback.success(page);
            });
        }
        @Override public void cancel() { canceled = true; Call old = call; if (old != null) old.cancel(); loads.remove(this); }
    }
}
