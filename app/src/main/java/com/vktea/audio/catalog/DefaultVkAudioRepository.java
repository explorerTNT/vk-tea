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
    private final Set<Operation<?>> operations = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    interface CredentialProvider {
        Credentials get();
    }

    static final class Credentials {
        final String token;
        final String secret;
        final String userId;

        Credentials(String token, String secret, String userId) {
            this.token = token == null ? "" : token;
            this.secret = secret == null ? "" : secret;
            this.userId = userId == null ? "" : userId;
        }

        boolean sameSession(Credentials other) {
            return other != null && token.equals(other.token) && secret.equals(other.secret)
                    && userId.equals(other.userId);
        }
    }

    DefaultVkAudioRepository(Context context) {
        this(new OkHttpClient(), API,
                () -> new Credentials(Session.getToken(context), Session.getSecret(context),
                        Session.getUserId(context)), new Handler(Looper.getMainLooper()));
    }

    /** Package-local injection keeps production's destination fixed while tests stay entirely offline. */
    DefaultVkAudioRepository(OkHttpClient client, HttpUrl endpoint,
                             CredentialProvider credentials, Handler main) {
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

    @Override
    public Request search(String query, int offset, int count, Callback<VkAudioPage> callback) {
        Credentials current = credentials.get();
        if (query == null || query.trim().isEmpty() || query.length() > 256 || !validPage(offset, count))
            return fail(callback, current, new VkAudioError(VkAudioError.Kind.INVALID_REQUEST));
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("q", query.trim());
        params.put("offset", Integer.toString(offset));
        params.put("count", Integer.toString(count));
        return execute("audio.search", params, current, callback, DefaultVkAudioRepository::parsePage);
    }

    @Override
    public Request getPlaylists(int offset, int count, Callback<List<VkAudioPlaylist>> callback) {
        Credentials current = credentials.get();
        if (!validPage(offset, count) || !validOwner(current.userId))
            return fail(callback, current, new VkAudioError(validPage(offset, count) ? VkAudioError.Kind.AUTH_REQUIRED : VkAudioError.Kind.INVALID_REQUEST));
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("owner_id", current.userId);
        params.put("offset", Integer.toString(offset));
        params.put("count", Integer.toString(count));
        return execute("audio.getPlaylists", params, current, callback, response -> {
            JSONArray items = response.getJSONObject("response").getJSONArray("items");
            List<VkAudioPlaylist> playlists = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject row = items.optJSONObject(i);
                if (row == null) continue;
                long owner = row.optLong("owner_id", 0), id = row.optLong("id", 0);
                if (owner != 0 && id > 0) playlists.add(new VkAudioPlaylist(owner, id,
                        row.optString("title", ""), row.optString("access_key", "")));
            }
            return Collections.unmodifiableList(playlists);
        });
    }

    @Override
    public Request getPlaylistAudio(VkAudioPlaylist playlist, int offset, int count, Callback<VkAudioPage> callback) {
        Credentials current = credentials.get();
        if (playlist == null || !validPage(offset, count))
            return fail(callback, current, new VkAudioError(VkAudioError.Kind.INVALID_REQUEST));
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("owner_id", Long.toString(playlist.ownerId));
        params.put("playlist_id", Long.toString(playlist.id));
        if (!playlist.accessKey.isEmpty()) params.put("access_key", playlist.accessKey);
        params.put("offset", Integer.toString(offset));
        params.put("count", Integer.toString(count));
        return execute("audio.get", params, current, callback, DefaultVkAudioRepository::parsePage);
    }

    @Override
    public Request createPlaylist(String title, Callback<Boolean> callback) {
        Credentials current = credentials.get();
        if (title == null || title.trim().isEmpty() || title.length() > 1024 || !validOwner(current.userId))
            return fail(callback, current, new VkAudioError(VkAudioError.Kind.INVALID_REQUEST));
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("owner_id", current.userId);
        params.put("title", title.trim());
        return execute("audio.createPlaylist", params, current, callback, response -> {
            Object value = response.get("response");
            if (value instanceof JSONObject && ((JSONObject) value).optLong("id", 0) > 0) return true;
            throw new JSONException("Unexpected response");
        });
    }

    @Override
    public Request changeLibrary(VkAudioTrack track, boolean add, Callback<Boolean> callback) {
        Credentials current = credentials.get();
        if (track == null) return fail(callback, current, new VkAudioError(VkAudioError.Kind.INVALID_REQUEST));
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("owner_id", Long.toString(track.ownerId));
        params.put("audio_id", Long.toString(track.id));
        if (add && !track.accessKey.isEmpty()) params.put("access_key", track.accessKey);
        return execute(add ? "audio.add" : "audio.delete", params, current, callback, DefaultVkAudioRepository::parseMutation);
    }

    @Override
    public Request changePlaylist(VkAudioPlaylist playlist, VkAudioTrack track, boolean add, Callback<Boolean> callback) {
        Credentials current = credentials.get();
        if (playlist == null || track == null || !validOwner(current.userId)
                || !current.userId.equals(Long.toString(playlist.ownerId)))
            return fail(callback, current, new VkAudioError(VkAudioError.Kind.INVALID_REQUEST));
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("owner_id", Long.toString(playlist.ownerId));
        params.put("playlist_id", Long.toString(playlist.id));
        params.put("audio_ids", track.ownerId + "_" + track.id);
        return execute(add ? "audio.addToPlaylist" : "audio.removeFromPlaylist", params, current, callback,
                response -> {
                    if (!add) {
                        if (response.getInt("response") == 1) return true;
                    } else {
                        JSONArray added = response.getJSONArray("response");
                        if (added.length() > 0 && added.getJSONObject(0).optLong("audio_id", 0) > 0) return true;
                    }
                    throw new JSONException("Unsuccessful mutation");
                });
    }

    private static boolean validPage(int offset, int count) { return offset >= 0 && count > 0 && count <= 1000; }
    private static boolean validOwner(String id) {
        try { return Long.parseLong(id) > 0; } catch (NumberFormatException ignored) { return false; }
    }
    private static VkAudioPage parsePage(JSONObject response) throws JSONException {
        JSONObject page = response.getJSONObject("response");
        JSONArray items = page.getJSONArray("items");
        return new VkAudioPage(page.getInt("count"), parseTracks(items), items.length());
    }
    private static Boolean parseMutation(JSONObject response) throws JSONException {
        Object value = response.get("response");
        if (value instanceof Number && ((Number) value).longValue() > 0) return true;
        throw new JSONException("Unsuccessful mutation");
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
        if (current.token.isEmpty()) {
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
        okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder().url(url.build())
                .header("User-Agent", VKApi.USER_AGENT);
        boolean mutation = method.equals("audio.add") || method.equals("audio.delete")
                || method.equals("audio.createPlaylist") || method.equals("audio.addToPlaylist")
                || method.equals("audio.removeFromPlaylist");
        if (mutation) requestBuilder.post(okhttp3.RequestBody.create(new byte[0], null));
        else requestBuilder.get();
        okhttp3.Request request = requestBuilder.build();
        operation.call = client.newCall(request);
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
            if (running != null) running.cancel();
            operations.remove(this);
        }

        @Override
        public boolean isCanceled() {
            return canceled;
        }

        void deliver(T value, VkAudioError error) {
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
