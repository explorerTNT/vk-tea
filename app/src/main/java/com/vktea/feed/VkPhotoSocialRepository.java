package com.vktea.feed;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import com.vktea.msg.MessageAvatars;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;

/** Separate nonlogging transport. POST mutations are never replayed or redirected. */
final class VkPhotoSocialRepository implements PhotoSocialRepository {
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
    private final Set<Operation<?>> running = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    VkPhotoSocialRepository(Context context) {
        this(new OkHttpClient(), HttpUrl.get("https://api.vk.com/"),
                () -> new Snapshot(Session.getToken(context), Session.getSecret(context), Session.getUserId(context)));
    }
    VkPhotoSocialRepository(OkHttpClient client, HttpUrl endpoint, Sessions sessions) {
        this.endpoint = endpoint; this.sessions = sessions;
        this.client = client.newBuilder().protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
                .addNetworkInterceptor(chain -> {
                    Response response = chain.proceed(chain.request());
                    return response.code() == 503 ? response.newBuilder().header("Retry-After", "2147483647").build() : response;
                }).build();
    }

    @Override public Request load(ViewerPhoto photo, Callback<ViewerPhoto> callback) {
        if (photo == null || !photo.hasIdentity()) return invalid(callback);
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("photos", photo.ownerId + "_" + photo.id + (photo.accessKey.isEmpty() ? "" : "_" + photo.accessKey));
        args.put("extended", "1");
        return execute(photo, "photos.getById", args, false, callback, root -> {
            JSONObject value = root.getJSONArray("response").getJSONObject(0);
            ViewerPhoto parsed = ViewerPhoto.parse(value);
            if (parsed.id != photo.id || parsed.ownerId != photo.ownerId) throw new PhotoSocialError(PhotoSocialError.Kind.UNAVAILABLE);
            boolean available = value.optInt("content_restricted", 0) == 0 && !flag(value, "deleted", false);
            return new ViewerPhoto(parsed.url.isEmpty() ? photo.url : parsed.url, parsed.id, parsed.ownerId,
                    parsed.accessKey.isEmpty() ? photo.accessKey : parsed.accessKey, parsed.likes, parsed.comments, parsed.reposts,
                    parsed.liked, available && flag(value, "can_like", flag(value.optJSONObject("likes"), "can_like", true)),
                    available && flag(value, "can_comment", flag(value.optJSONObject("comments"), "can_post", true)),
                    available && flag(value, "can_repost", true),
                    parsed.ownerName.isEmpty() ? photo.ownerName : parsed.ownerName,
                    parsed.ownerPhoto.isEmpty() ? photo.ownerPhoto : parsed.ownerPhoto,
                    value.has("date") || value.has("created") ? parsed.date : photo.date,
                    value.has("text") ? parsed.text : photo.text);
        });
    }
    @Override public Request owner(ViewerPhoto photo, Callback<Owner> callback) {
        if (photo == null || !photo.hasIdentity() || photo.ownerId == Long.MIN_VALUE) return invalid(callback);
        boolean group = photo.ownerId < 0;
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put(group ? "group_ids" : "user_ids", Long.toString(Math.abs(photo.ownerId)));
        args.put("fields", "photo_200");
        return execute(photo, group ? "groups.getById" : "users.get", args, false, callback, root -> {
            JSONObject owner = root.getJSONArray("response").getJSONObject(0);
            if (owner.optLong("id", 0) != Math.abs(photo.ownerId)) throw new PhotoSocialError(PhotoSocialError.Kind.UNAVAILABLE);
            String name = group ? string(owner, "name")
                    : (string(owner, "first_name") + " " + string(owner, "last_name")).trim();
            return new Owner(name, MessageAvatars.photoUrl(owner));
        });
    }
    @Override public Request like(ViewerPhoto photo, boolean liked, Callback<Integer> callback) {
        if (photo == null || !photo.hasIdentity()) return invalid(callback);
        LinkedHashMap<String, String> args = identity(photo, "item_id");
        args.put("type", "photo");
        return execute(photo, liked ? "likes.add" : "likes.delete", args, true, callback,
                root -> Math.max(0, root.getJSONObject("response").getInt("likes")));
    }
    @Override public Request comments(ViewerPhoto photo, Callback<Comments> callback) {
        if (photo == null || !photo.hasIdentity()) return invalid(callback);
        LinkedHashMap<String, String> args = identity(photo, "photo_id");
        args.put("count", "50"); args.put("sort", "desc"); args.put("extended", "1");
        args.put("fields", "first_name,last_name"); args.put("need_likes", "0");
        return execute(photo, "photos.getComments", args, false, callback, root -> parseComments(root.getJSONObject("response")));
    }
    @Override public Request comment(ViewerPhoto photo, String text, Callback<Long> callback) {
        if (photo == null || !photo.hasIdentity() || text == null || text.trim().isEmpty()) return invalid(callback);
        LinkedHashMap<String, String> args = identity(photo, "photo_id");
        args.put("message", text);
        return execute(photo, "photos.createComment", args, true, callback, root -> {
            long id = root.getLong("response");
            if (id <= 0) throw new PhotoSocialError(PhotoSocialError.Kind.UNAVAILABLE);
            return id;
        });
    }
    @Override public Request repost(ViewerPhoto photo, String text, Callback<Integer> callback) {
        if (photo == null || !photo.hasIdentity() || text == null) return invalid(callback);
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("object", "photo" + photo.ownerId + "_" + photo.id); args.put("message", text);
        if (!photo.accessKey.isEmpty()) args.put("access_key", photo.accessKey);
        return execute(photo, "wall.repost", args, true, callback, root -> {
            JSONObject value = root.getJSONObject("response");
            if (value.optLong("post_id", 0) <= 0) throw new PhotoSocialError(PhotoSocialError.Kind.UNAVAILABLE);
            return Math.max(0, value.optInt("reposts_count", photo.reposts + 1));
        });
    }
    private static LinkedHashMap<String, String> identity(ViewerPhoto photo, String item) {
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("owner_id", Long.toString(photo.ownerId)); args.put(item, Long.toString(photo.id));
        if (!photo.accessKey.isEmpty()) args.put("access_key", photo.accessKey);
        return args;
    }
    private interface Parser<T> { T parse(JSONObject response) throws Exception; }
    private <T> Request invalid(Callback<T> callback) {
        Operation<T> operation = new Operation<>(callback, sessions.get());
        running.add(operation);
        operation.deliver(null, new PhotoSocialError(PhotoSocialError.Kind.INVALID));
        return operation;
    }
    private <T> Request execute(ViewerPhoto photo, String method, LinkedHashMap<String, String> args,
                                boolean mutation, Callback<T> callback, Parser<T> parser) {
        Operation<T> operation = new Operation<>(callback, sessions.get());
        running.add(operation);
        if (closed) { operation.cancel(); return operation; }
        if (photo == null || !photo.hasIdentity() || operation.session.token.isEmpty()) {
            operation.deliver(null, new PhotoSocialError(operation.session.token.isEmpty()
                    ? PhotoSocialError.Kind.AUTH : PhotoSocialError.Kind.INVALID));
            return operation;
        }
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("v", "5.82"); params.put("lang", Locale.getDefault().getLanguage()); params.put("https", "1");
        params.put("access_token", operation.session.token); params.putAll(args);
        if (!operation.session.secret.isEmpty()) params.put("sig", signature(method, params, operation.session.secret));
        HttpUrl.Builder url = endpoint.newBuilder().addPathSegments("method/" + method);
        FormBody.Builder body = new FormBody.Builder();
        for (Map.Entry<String, String> param : params.entrySet()) {
            if (mutation) body.add(param.getKey(), param.getValue()); else url.addQueryParameter(param.getKey(), param.getValue());
        }
        okhttp3.Request.Builder request = new okhttp3.Request.Builder().url(url.build()).header("User-Agent", VKApi.USER_AGENT);
        if (mutation) request.post(body.build()); else request.get();
        operation.call = client.newCall(request.build());
        if (closed || operation.canceled) { operation.cancel(); return operation; }
        operation.call.enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException ignored) {
                operation.deliver(null, new PhotoSocialError(PhotoSocialError.Kind.NETWORK));
            }
            @Override public void onResponse(Call call, Response response) {
                try (Response received = response) {
                    if (!received.isSuccessful()) {
                        operation.deliver(null, new PhotoSocialError(received.code() == 429 ? PhotoSocialError.Kind.RATE_LIMITED
                                : received.code() == 401 ? PhotoSocialError.Kind.AUTH : PhotoSocialError.Kind.API, received.code()));
                    } else if (received.body() == null || received.body().source().request(2 * 1024 * 1024 + 1L)
                            && received.body().source().buffer().size() > 2 * 1024 * 1024) {
                        operation.deliver(null, new PhotoSocialError(PhotoSocialError.Kind.UNAVAILABLE));
                    } else {
                        JSONObject root = new JSONObject(received.body().string());
                        JSONObject error = root.optJSONObject("error");
                        if (error != null) operation.deliver(null, error(error.optInt("error_code", 0)));
                        else operation.deliver(parser.parse(root), null);
                    }
                } catch (PhotoSocialError error) { operation.deliver(null, error); }
                catch (IOException ignored) { operation.deliver(null, new PhotoSocialError(PhotoSocialError.Kind.NETWORK)); }
                catch (Exception ignored) { operation.deliver(null, new PhotoSocialError(PhotoSocialError.Kind.UNAVAILABLE)); }
            }
        });
        return operation;
    }
    private static PhotoSocialError error(int code) {
        return new PhotoSocialError(code == 5 || code == 17 || code == 25 ? PhotoSocialError.Kind.AUTH
                : code == 6 || code == 9 || code == 29 ? PhotoSocialError.Kind.RATE_LIMITED
                : code == 7 || code == 15 || code == 200 || code == 201 ? PhotoSocialError.Kind.ACCESS : PhotoSocialError.Kind.API, code);
    }
    static String signature(String method, LinkedHashMap<String, String> args, String secret) {
        StringBuilder raw = new StringBuilder("/method/").append(method).append('?');
        for (Map.Entry<String, String> entry : args.entrySet()) {
            if (raw.charAt(raw.length() - 1) != '?') raw.append('&');
            raw.append(entry.getKey()).append('=').append(entry.getValue());
        }
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(raw.append(secret).toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : hash) { result.append(Character.forDigit((value >>> 4) & 15, 16)); result.append(Character.forDigit(value & 15, 16)); }
            return result.toString();
        } catch (Exception impossible) { throw new AssertionError("Signature unavailable"); }
    }
    static Comments parseComments(JSONObject response) {
        Map<Long, String> names = new HashMap<>();
        JSONArray users = response.optJSONArray("profiles"), groups = response.optJSONArray("groups");
        if (users != null) for (int i = 0; i < users.length(); i++) {
            JSONObject user = users.optJSONObject(i);
            if (user != null) names.put(user.optLong("id"), (string(user, "first_name") + " " + string(user, "last_name")).trim());
        }
        if (groups != null) for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group != null) names.put(-group.optLong("id"), string(group, "name"));
        }
        List<Comment> items = new ArrayList<>();
        JSONArray rows = response.optJSONArray("items");
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            long from = row.optLong("from_id");
            String text = string(row, "text");
            if (text.isEmpty() && row.optJSONArray("attachments") != null) text = "Вложение";
            String name = names.get(from);
            if (name == null || name.trim().isEmpty()) name = from < 0 ? "Сообщество" : "Пользователь";
            items.add(new Comment(row.optLong("id"), from, name,
                    text.isEmpty() ? "Комментарий недоступен" : text, row.optLong("date")));
        }
        Collections.reverse(items);
        return new Comments(response.optInt("count", items.size()), items);
    }
    private static boolean flag(JSONObject value, String key, boolean fallback) {
        Object field = value == null ? null : value.opt(key);
        return field instanceof Boolean ? (Boolean) field : field instanceof Number ? ((Number) field).intValue() == 1 : fallback;
    }
    private static String string(JSONObject value, String key) {
        Object field = value.opt(key);
        return field instanceof String ? (String) field : "";
    }
    @Override public void close() { closed = true; for (Operation<?> operation : running) operation.cancel(); }
    private final class Operation<T> implements Request {
        final Callback<T> callback; final Snapshot session;
        volatile Call call; volatile boolean canceled;
        Operation(Callback<T> callback, Snapshot session) { this.callback = callback; this.session = session; }
        @Override public void cancel() { canceled = true; if (call != null) call.cancel(); running.remove(this); }
        void deliver(T result, PhotoSocialError error) {
            main.post(() -> {
                running.remove(this);
                if (closed || canceled) return;
                if (!session.matches(sessions.get())) callback.error(new PhotoSocialError(PhotoSocialError.Kind.AUTH));
                else if (error != null) callback.error(error); else callback.success(result);
            });
        }
    }
}
