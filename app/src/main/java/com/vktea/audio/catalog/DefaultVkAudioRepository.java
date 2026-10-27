package com.vktea.audio.catalog;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

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

/** Separate transport: no API logging, redirects, automatic retries or authentication attempts. */
final class DefaultVkAudioRepository implements VkAudioRepository {
    private static final long MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final HttpUrl API = HttpUrl.get("https://api.vk.com/");
    private final OkHttpClient client;
    private final HttpUrl endpoint;
    private final CredentialProvider credentials;
    private final Handler main;
    @androidx.annotation.Nullable private final Long boundGeneration;
    private final Set<Operation<?>> operations = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    interface CredentialProvider {
        Credentials get();
    }

    static final class Credentials {
        final String token;
        final String secret;
        final String userId;
        final long generation;

        Credentials(String token, String secret, String userId) {
            this(token, secret, userId, -1);
        }

        Credentials(String token, String secret, String userId, long generation) {
            this.token = token == null ? "" : token;
            this.secret = secret == null ? "" : secret;
            this.userId = userId == null ? "" : userId;
            this.generation = generation;
        }

        static Credentials fromSnapshot(Session.Snapshot snapshot) {
            return new Credentials(snapshot.token, snapshot.secret, snapshot.userId, snapshot.generation);
        }

        boolean sameSession(Credentials other) {
            return other != null && token.equals(other.token) && secret.equals(other.secret)
                    && userId.equals(other.userId) && generation == other.generation;
        }
    }

    DefaultVkAudioRepository(Context context) {
        this(context, Session.generation());
    }

    DefaultVkAudioRepository(Context context, long generation) {
        this(new OkHttpClient(), API, context, generation, new Handler(Looper.getMainLooper()));
    }

    /** Offline transport injection using the same session safeguards as production. */
    DefaultVkAudioRepository(OkHttpClient client, HttpUrl endpoint, Context context,
                             long generation, Handler main) {
        this(client, endpoint, () -> Credentials.fromSnapshot(Session.snapshot(context)), main, generation);
    }

    /** Package-local injection keeps production's destination fixed while tests stay entirely offline. */
    DefaultVkAudioRepository(OkHttpClient client, HttpUrl endpoint,
                             CredentialProvider credentials, Handler main) {
        this(client, endpoint, credentials, main, null);
    }

    DefaultVkAudioRepository(OkHttpClient client, HttpUrl endpoint,
                             CredentialProvider credentials, Handler main,
                             @androidx.annotation.Nullable Long boundGeneration) {
        this.client = client.newBuilder().protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .addNetworkInterceptor(chain -> {
                    Response response = chain.proceed(chain.request());
                    // OkHttp otherwise retries 503 + Retry-After: 0 independently
                    // of connection recovery. Every retry here must be explicit.
                    return response.code() == 503 ? response.newBuilder()
                            .header("Retry-After", "2147483647").build() : response;
                }).build();
        this.endpoint = endpoint;
        this.credentials = credentials;
        this.main = main;
        this.boundGeneration = boundGeneration;
    }

    @Override
    public VkAudioRepository.Request getMyAudio(int offset, int count, Callback<VkAudioPage> callback) {
        Credentials current = credentials.get();
        if (offset < 0 || count < 1 || count > 1000) {
            return fail(callback, current, new VkAudioError(VkAudioError.Kind.INVALID_REQUEST));
        }
        long owner;
        try {
            owner = Long.parseLong(current.userId);
            if (owner <= 0 || current.token.isEmpty()) throw new NumberFormatException();
        } catch (NumberFormatException ignored) {
            return fail(callback, current, new VkAudioError(VkAudioError.Kind.AUTH_REQUIRED));
        }
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("owner_id", Long.toString(owner));
        params.put("count", Integer.toString(count));
        params.put("offset", Integer.toString(offset));
        return execute("audio.get", params, current, callback, response -> {
            JSONObject page = response.getJSONObject("response");
            JSONArray items = page.getJSONArray("items");
            return new VkAudioPage(page.getInt("count"), parseTracks(items), items.length());
        });
    }

    @Override
    public VkAudioRepository.Request getById(List<String> audios, Callback<List<VkAudioTrack>> callback) {
        Credentials current = credentials.get();
        if (audios == null || audios.isEmpty() || audios.size() > 1000) {
            return fail(callback, current, new VkAudioError(VkAudioError.Kind.INVALID_REQUEST));
        }
        List<String> ids = new ArrayList<>(audios);
        for (String identity : ids) {
            if (!validApiId(identity)) {
                return fail(callback, current, new VkAudioError(VkAudioError.Kind.INVALID_REQUEST));
            }
        }
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("audios", TextUtils.join(",", ids));
        return execute("audio.getById", params, current, callback,
                response -> Collections.unmodifiableList(parseTracks(response.getJSONArray("response"))));
    }

    private static boolean validApiId(String identity) {
        if (identity == null || !identity.matches("-?[0-9]+_[0-9]+(?:_[A-Za-z0-9_-]{1,256})?")) return false;
        String[] parts = identity.split("_", 3);
        try {
            return Long.parseLong(parts[0]) != 0 && Long.parseLong(parts[1]) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private interface Parser<T> {
        T parse(JSONObject response) throws JSONException;
    }

    private <T> VkAudioRepository.Request execute(String method, LinkedHashMap<String, String> params,
                    Credentials current, Callback<T> callback, Parser<T> parser) {
        if (current.token.isEmpty()
                || (boundGeneration != null && current.generation != boundGeneration)) {
            return fail(callback, current, new VkAudioError(VkAudioError.Kind.AUTH_REQUIRED));
        }
        Operation<T> operation = new Operation<>(callback, current);
        operations.add(operation);
        if (closed) {
            operation.cancel();
            return operation;
        }
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("v", "5.82");
        query.put("lang", Locale.getDefault().getLanguage());
        query.put("https", "1");
        query.put("access_token", current.token);
        query.putAll(params);
        HttpUrl.Builder url = endpoint.newBuilder().addPathSegments("method/" + method);
        for (Map.Entry<String, String> entry : query.entrySet()) {
            url.addQueryParameter(entry.getKey(), entry.getValue());
        }
        if (!current.secret.isEmpty()) url.addQueryParameter("sig", signature(method, query, current.secret));
        // Reuse only the existing client identity, never VKApi's request or logging path.
        okhttp3.Request request = new okhttp3.Request.Builder().url(url.build())
                .header("User-Agent", VKApi.USER_AGENT).get().build();
        operation.call = client.newCall(request);
        if (operation.isCanceled() || closed) {
            operation.cancel();
            return operation;
        }
        if (boundGeneration != null && !VKApi.registerCall(boundGeneration, operation.call)) {
            operation.deliver(null, new VkAudioError(VkAudioError.Kind.AUTH_REQUIRED));
            return operation;
        }
        // Close/cancel may race with call registration; never leave a registered call behind.
        if (operation.isCanceled() || closed) {
            operation.cancel();
            return operation;
        }
        operation.call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException ignored) {
                operation.deliver(null, new VkAudioError(VkAudioError.Kind.NETWORK));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response received = response) {
                    if (!received.isSuccessful()) {
                        operation.deliver(null, new VkAudioError(received.code() == 429
                                ? VkAudioError.Kind.RATE_LIMITED : VkAudioError.Kind.HTTP, 0, received.code()));
                        return;
                    }
                    ResponseBody body = received.body();
                    if (body == null || body.source().request(MAX_RESPONSE_BYTES + 1)
                            && body.source().buffer().size() > MAX_RESPONSE_BYTES) {
                        operation.deliver(null, new VkAudioError(VkAudioError.Kind.MALFORMED_RESPONSE));
                        return;
                    }
                    JSONObject parsed = new JSONObject(body.string());
                    JSONObject error = parsed.optJSONObject("error");
                    if (error != null) {
                        operation.deliver(null, apiError(error.optInt("error_code", 0)));
                    } else {
                        operation.deliver(parser.parse(parsed), null);
                    }
                } catch (IOException ignored) {
                    operation.deliver(null, new VkAudioError(VkAudioError.Kind.NETWORK));
                } catch (JSONException | RuntimeException ignored) {
                    operation.deliver(null, new VkAudioError(VkAudioError.Kind.MALFORMED_RESPONSE));
                }
            }
        });
        return operation;
    }

    private <T> VkAudioRepository.Request fail(Callback<T> callback, Credentials current, VkAudioError error) {
        Operation<T> operation = new Operation<>(callback, current);
        operations.add(operation);
        operation.deliver(null, error);
        return operation;
    }

    private static VkAudioError apiError(int code) {
        VkAudioError.Kind kind;
        switch (code) {
            case 5: case 17: case 25: kind = VkAudioError.Kind.AUTH_REQUIRED; break;
            case 7: case 15: case 20: case 23: case 200: case 201:
                kind = VkAudioError.Kind.ACCESS_DENIED; break;
            case 6: case 9: case 29: kind = VkAudioError.Kind.RATE_LIMITED; break;
            default: kind = VkAudioError.Kind.API;
        }
        return new VkAudioError(kind, code, 0);
    }

    static String signature(String method, LinkedHashMap<String, String> query, String secret) {
        // Coffee VKAPIRequest.getSig uses the same insertion-ordered, unescaped values.
        StringBuilder input = new StringBuilder("/method/").append(method).append('?');
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (input.charAt(input.length() - 1) != '?') input.append('&');
            input.append(entry.getKey()).append('=').append(entry.getValue());
        }
        input.append(secret);
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(input.toString().getBytes(StandardCharsets.UTF_8));
            char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder output = new StringBuilder(32);
            for (byte value : digest) output.append(hex[(value >>> 4) & 15]).append(hex[value & 15]);
            return output.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("MD5 unavailable");
        }
    }

    static List<VkAudioTrack> parseTracks(JSONArray items) {
        List<VkAudioTrack> tracks = new ArrayList<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject row = items.optJSONObject(index);
            if (row == null) continue;
            long id = row.optLong("id", row.optLong("aid", 0));
            long owner = row.optLong("owner_id", 0);
            if (id <= 0 || owner == 0) continue;
            boolean available = row.optInt("content_restricted", 0) == 0
                    && !row.optBoolean("is_restricted", false)
                    && row.optBoolean("is_available", true);
            tracks.add(new VkAudioTrack(id, owner, row.optString("artist", ""), row.optString("title", ""),
                    row.optLong("duration", 0), row.optString("url", ""), row.optString("access_key", ""), available));
        }
        return tracks;
    }

    @Override
    public void close() {
        closed = true;
        for (Operation<?> operation : operations) operation.cancel();
    }

    private final class Operation<T> implements VkAudioRepository.Request {
        private final Callback<T> callback;
        private final Credentials requestedSession;
        private volatile boolean canceled;
        private volatile Call call;

        Operation(Callback<T> callback, Credentials requestedSession) {
            this.callback = callback;
            this.requestedSession = requestedSession;
        }

        @Override
        public void cancel() {
            canceled = true;
            Call running = call;
            if (running != null) {
                running.cancel();
                if (boundGeneration != null) VKApi.unregisterCall(running);
            }
            operations.remove(this);
        }

        @Override
        public boolean isCanceled() {
            return canceled;
        }

        void deliver(T value, VkAudioError error) {
            Call completed = call;
            if (boundGeneration != null && completed != null) VKApi.unregisterCall(completed);
            main.post(() -> {
                operations.remove(this);
                if (canceled || closed) return;
                if (!requestedSession.sameSession(credentials.get())) {
                    callback.onError(new VkAudioError(VkAudioError.Kind.AUTH_REQUIRED));
                } else if (error != null) {
                    callback.onError(error);
                } else {
                    callback.onSuccess(value);
                }
            });
        }
    }
}
