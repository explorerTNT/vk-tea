package com.vktea.feed;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import com.vktea.msg.Dialog;
import com.vktea.msg.DialogsParser;
import com.vktea.msg.MessageAvatars;
import com.vktea.share.ShareAttachment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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

/** Nonlogging VK-only transport; no redirects, implicit retries, message reads or read receipts. */
final class VkPhotoShareRepository implements PhotoShareRepository {
    private static final long CHAT_BASE = 2_000_000_000L;
    interface Sessions { Snapshot get(); }
    static final class Snapshot {
        final String token, secret, user;
        Snapshot(String token, String secret, String user) { this.token = clean(token); this.secret = clean(secret); this.user = clean(user); }
        boolean matches(Snapshot other) { return other != null && token.equals(other.token) && secret.equals(other.secret) && user.equals(other.user); }
        private static String clean(String value) { return value == null ? "" : value; }
    }
    private final OkHttpClient client;
    private final HttpUrl endpoint;
    private final Sessions sessions;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Operation<?>> running = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    VkPhotoShareRepository(Context context) {
        this(new OkHttpClient(), HttpUrl.get("https://api.vk.com/"),
                () -> new Snapshot(Session.getToken(context), Session.getSecret(context), Session.getUserId(context)));
    }
    VkPhotoShareRepository(OkHttpClient original, HttpUrl endpoint, Sessions sessions) {
        this.endpoint = endpoint; this.sessions = sessions;
        client = original.newBuilder().protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
                .addNetworkInterceptor(chain -> {
                    Response response = chain.proceed(chain.request());
                    return response.code() == 503 ? response.newBuilder().header("Retry-After", "2147483647").build() : response;
                }).build();
    }
    @Override public Request recipients(String query, Callback<List<Recipient>> callback) {
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        boolean search = query != null && !query.trim().isEmpty();
        if (search) args.put("q", query.trim());
        else { args.put("offset", "0"); args.put("count", "40"); args.put("extended", "1"); }
        args.put("fields", "first_name,last_name,photo_200,photo_100,photo_50,photo_medium_rec,photo_rec");
        return execute(search ? "messages.searchDialogs" : "messages.getConversations", args, false, callback,
                root -> search ? searchRecipients(root.getJSONArray("response")) : recentRecipients(root));
    }
    @Override public Request send(ViewerPhoto photo, Recipient recipient, int randomId, String draft, Callback<Long> callback) {
        return send(ShareAttachment.photo(photo), recipient, randomId, draft, callback);
    }
    @Override public Request send(ShareAttachment attachment, Recipient recipient, int randomId, String draft, Callback<Long> callback) {
        if (attachment == null || !attachment.hasIdentity() || recipient == null || recipient.peerId == 0 || recipient.peerId == CHAT_BASE
                || recipient.peerId == Long.MIN_VALUE || randomId == 0 || draft == null) return invalid(callback);
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        if (recipient.peerId > CHAT_BASE) args.put("chat_id", Long.toString(recipient.peerId - CHAT_BASE));
        else args.put("peer_id", Long.toString(recipient.peerId));
        args.put("random_id", Integer.toString(randomId));
        if (!draft.isEmpty()) args.put("message", draft);
        args.put("attachment", attachment.messageAttachment());
        return execute("messages.send", args, true, callback, root -> {
            long id = root.getLong("response");
            if (id <= 0) throw new PhotoSocialError(PhotoSocialError.Kind.UNAVAILABLE);
            return id;
        });
    }
    @Override public Request repost(ShareAttachment attachment, long groupId, String draft, Callback<Long> callback) {
        if (attachment == null || !attachment.hasIdentity() || !attachment.canRepost || groupId < 0 || draft == null) return invalid(callback);
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("object", attachment.object()); args.put("message", draft);
        if (groupId > 0) args.put("group_id", Long.toString(groupId));
        if (!attachment.accessKey.isEmpty()) args.put("access_key", attachment.accessKey);
        return execute("wall.repost", args, true, callback, root -> {
            long postId = root.getJSONObject("response").getLong("post_id");
            if (postId <= 0) throw new PhotoSocialError(PhotoSocialError.Kind.UNAVAILABLE);
            return postId;
        });
    }
    @Override public Request communities(Callback<List<Community>> callback) {
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("extended", "1"); args.put("filter", "editor"); args.put("fields", "can_post,photo_200,photo_100,photo_50");
        Pager<Community> pager = new Pager<>("groups.get", args, callback, row -> {
            long id = row.optLong("id", 0);
            if (id <= 0 || row.optInt("admin_level", 0) < 2 || !flag(row, "can_post", true)
                    || !text(row, "deactivated").isEmpty() || flag(row, "deleted", false)) return null;
            return new Community(id, text(row, "name"), MessageAvatars.photoUrl(row));
        }, group -> group.id);
        main.post(pager.next); return pager;
    }
    @Override public Request friends(String query, Callback<List<Recipient>> callback) {
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("order", "hints"); args.put("fields", "first_name,last_name,photo_200,photo_100,photo_50");
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Pager<Recipient> pager = new Pager<>("friends.get", args, callback, row -> {
            long id = row.optLong("id", 0); if (id <= 0 || !text(row, "deactivated").isEmpty()) return null;
            String name = (text(row, "first_name") + " " + text(row, "last_name")).trim();
            if (!name.toLowerCase(Locale.ROOT).contains(needle)) return null;
            return new Recipient(id, name.isEmpty() ? "Пользователь" : name, MessageAvatars.photoUrl(row));
        }, friend -> friend.peerId);
        main.post(pager.next); return pager;
    }
    private interface RowParser<T> { T parse(JSONObject row); }
    private interface RowId<T> { long get(T row); }
    /** Accumulates complete lists; raw item count advances offsets even for filtered/malformed rows. */
    private final class Pager<T> implements Request {
        final String method;
        final LinkedHashMap<String, String> args;
        final Callback<List<T>> callback;
        final RowParser<T> parser;
        final RowId<T> id;
        final Snapshot session = sessions.get();
        final LinkedHashMap<Long, T> rows = new LinkedHashMap<>();
        final long started = android.os.SystemClock.elapsedRealtime();
        Request active;
        boolean canceled, finished;
        long offset;
        final Runnable next = this::page;
        Pager(String method, LinkedHashMap<String, String> args, Callback<List<T>> callback, RowParser<T> parser, RowId<T> id) {
            this.method = method; this.args = args; this.callback = callback; this.parser = parser; this.id = id;
        }
        void page() {
            if (canceled || finished || closed) return;
            if (!session.matches(sessions.get())) { fail(new PhotoSocialError(PhotoSocialError.Kind.AUTH)); return; }
            if (android.os.SystemClock.elapsedRealtime() - started > 120_000) { fail(new PhotoSocialError(PhotoSocialError.Kind.UNAVAILABLE)); return; }
            LinkedHashMap<String, String> parameters = new LinkedHashMap<>(args);
            parameters.put("count", "1000"); parameters.put("offset", Long.toString(offset));
            active = execute(method, parameters, false, new Callback<JSONObject>() {
                @Override public void success(JSONObject response) {
                    if (canceled || finished || closed) return;
                    if (!session.matches(sessions.get())) { fail(new PhotoSocialError(PhotoSocialError.Kind.AUTH)); return; }
                    JSONArray items = response.optJSONArray("items");
                    long count = response.optLong("count", -1);
                    if (items == null || count < 0 || items.length() == 0 && offset < count) { fail(new PhotoSocialError(PhotoSocialError.Kind.UNAVAILABLE)); return; }
                    for (int index = 0; index < items.length(); index++) {
                        JSONObject value = items.optJSONObject(index); T parsed = value == null ? null : parser.parse(value);
                        if (parsed != null) rows.putIfAbsent(id.get(parsed), parsed);
                    }
                    offset += items.length();
                    if (offset >= count) { finished = true; callback.success(Collections.unmodifiableList(new ArrayList<>(rows.values()))); }
                    else main.postDelayed(next, 350);
                }
                @Override public void error(PhotoSocialError error) { fail(error); }
            }, root -> root.getJSONObject("response"));
        }
        void fail(PhotoSocialError failure) { if (!canceled && !finished && !closed) { finished = true; callback.error(failure); } }
        @Override public void cancel() { canceled = true; main.removeCallbacks(next); if (active != null) active.cancel(); }
    }
    static List<Recipient> recentRecipients(JSONObject root) throws Exception {
        JSONObject data = root.getJSONObject("response"); data.getJSONArray("items");
        Set<Long> blocked = new HashSet<>();
        JSONArray rows = data.getJSONArray("items");
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index), conversation = row == null ? null : row.optJSONObject("conversation");
            JSONObject permission = conversation == null ? null : conversation.optJSONObject("can_write");
            JSONObject peer = conversation == null ? null : conversation.optJSONObject("peer");
            if (permission != null && !flag(permission, "allowed", true) && peer != null) blocked.add(peer.optLong("id"));
        }
        List<Recipient> result = new ArrayList<>(); Set<Long> seen = new HashSet<>();
        for (Dialog dialog : DialogsParser.parse(root)) if (!blocked.contains(dialog.peerId) && seen.add(dialog.peerId)) {
            String title = dialog.title.equals("...") ? "Пользователь" : dialog.title;
            result.add(new Recipient(dialog.peerId, title, dialog.photo));
        }
        return Collections.unmodifiableList(result);
    }
    static List<Recipient> searchRecipients(JSONArray rows) {
        List<Recipient> result = new ArrayList<>(); Set<Long> seen = new HashSet<>();
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index); if (row == null) continue;
            long id = row.optLong("id", 0); if (id <= 0) continue;
            String type = text(row, "type"); if (type.isEmpty()) type = "profile";
            String title;
            if (type.equals("chat")) { if (id > Long.MAX_VALUE - CHAT_BASE) continue; id += CHAT_BASE; title = text(row, "title"); }
            else if (type.equals("group")) { id = -id; title = text(row, "name"); }
            else if (type.equals("profile")) title = (text(row, "first_name") + " " + text(row, "last_name")).trim();
            else continue;
            if (seen.add(id)) {
                String photo = MessageAvatars.photoUrl(row);
                if (photo.isEmpty()) photo = text(row, "photo_medium_rec");
                if (photo.isEmpty()) photo = text(row, "photo_rec");
                result.add(new Recipient(id, title.isEmpty() ? "Получатель" : title, photo));
            }
        }
        return Collections.unmodifiableList(result);
    }
    private interface Parser<T> { T parse(JSONObject root) throws Exception; }
    private <T> Request invalid(Callback<T> callback) {
        Operation<T> operation = new Operation<>(callback, sessions.get()); running.add(operation);
        operation.deliver(null, new PhotoSocialError(PhotoSocialError.Kind.INVALID)); return operation;
    }
    private <T> Request execute(String method, LinkedHashMap<String, String> args, boolean post, Callback<T> callback, Parser<T> parser) {
        Operation<T> operation = new Operation<>(callback, sessions.get()); running.add(operation);
        if (closed) { operation.cancel(); return operation; }
        if (operation.session.token.isEmpty()) { operation.deliver(null, new PhotoSocialError(PhotoSocialError.Kind.AUTH)); return operation; }
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("v", "5.82"); params.put("lang", Locale.getDefault().getLanguage()); params.put("https", "1");
        params.put("access_token", operation.session.token); params.putAll(args);
        if (!operation.session.secret.isEmpty()) params.put("sig", signature(method, params, operation.session.secret));
        HttpUrl.Builder url = endpoint.newBuilder().addPathSegments("method/" + method);
        FormBody.Builder form = new FormBody.Builder();
        for (Map.Entry<String, String> param : params.entrySet()) {
            if (post) form.add(param.getKey(), param.getValue()); else url.addQueryParameter(param.getKey(), param.getValue());
        }
        okhttp3.Request.Builder request = new okhttp3.Request.Builder().url(url.build()).header("User-Agent", VKApi.USER_AGENT);
        if (post) request.post(form.build()); else request.get();
        operation.call = client.newCall(request.build());
        if (closed || operation.canceled) { operation.cancel(); return operation; }
        operation.call.enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException ignored) { operation.deliver(null, new PhotoSocialError(PhotoSocialError.Kind.NETWORK)); }
            @Override public void onResponse(Call call, Response response) {
                try (Response received = response) {
                    if (!received.isSuccessful()) operation.deliver(null, new PhotoSocialError(received.code() == 429 ? PhotoSocialError.Kind.RATE_LIMITED
                            : received.code() == 401 ? PhotoSocialError.Kind.AUTH : PhotoSocialError.Kind.API, received.code()));
                    else if (received.body() == null || received.body().source().request(2 * 1024 * 1024 + 1L)
                            && received.body().source().buffer().size() > 2 * 1024 * 1024) operation.deliver(null, new PhotoSocialError(PhotoSocialError.Kind.UNAVAILABLE));
                    else {
                        JSONObject root = new JSONObject(received.body().string()); JSONObject error = root.optJSONObject("error");
                        if (error == null) operation.deliver(parser.parse(root), null);
                        else {
                            int code = error.optInt("error_code", 0);
                            operation.deliver(null, new PhotoSocialError(code == 5 || code == 17 || code == 25 ? PhotoSocialError.Kind.AUTH
                                    : code == 6 || code == 9 || code == 29 ? PhotoSocialError.Kind.RATE_LIMITED
                                    : code == 7 || code == 15 || code == 900 || code == 901 || code == 902 ? PhotoSocialError.Kind.ACCESS : PhotoSocialError.Kind.API, code));
                        }
                    }
                } catch (PhotoSocialError failure) { operation.deliver(null, failure); }
                catch (IOException ignored) { operation.deliver(null, new PhotoSocialError(PhotoSocialError.Kind.NETWORK)); }
                catch (Exception ignored) { operation.deliver(null, new PhotoSocialError(PhotoSocialError.Kind.UNAVAILABLE)); }
            }
        });
        return operation;
    }
    private static String signature(String method, LinkedHashMap<String, String> args, String secret) {
        StringBuilder raw = new StringBuilder("/method/").append(method).append('?');
        for (Map.Entry<String, String> field : args.entrySet()) {
            if (raw.charAt(raw.length() - 1) != '?') raw.append('&'); raw.append(field.getKey()).append('=').append(field.getValue());
        }
        try {
            StringBuilder hex = new StringBuilder();
            for (byte value : MessageDigest.getInstance("MD5").digest(raw.append(secret).toString().getBytes(StandardCharsets.UTF_8))) {
                hex.append(Character.forDigit((value >>> 4) & 15, 16)); hex.append(Character.forDigit(value & 15, 16));
            }
            return hex.toString();
        } catch (Exception impossible) { throw new AssertionError("Signature unavailable"); }
    }
    private static String text(JSONObject object, String key) { Object value = object.opt(key); return value instanceof String ? (String) value : ""; }
    private static boolean flag(JSONObject object, String key, boolean fallback) {
        Object value = object.opt(key); return value instanceof Boolean ? (Boolean) value : value instanceof Number ? ((Number) value).intValue() != 0 : fallback;
    }
    @Override public void close() { closed = true; for (Operation<?> operation : running) operation.cancel(); }
    private final class Operation<T> implements Request {
        final Callback<T> callback; final Snapshot session;
        volatile Call call; volatile boolean canceled;
        Operation(Callback<T> callback, Snapshot session) { this.callback = callback; this.session = session; }
        @Override public void cancel() { canceled = true; if (call != null) call.cancel(); running.remove(this); }
        void deliver(T value, PhotoSocialError failure) {
            main.post(() -> {
                running.remove(this); if (closed || canceled) return;
                if (!session.matches(sessions.get())) callback.error(new PhotoSocialError(PhotoSocialError.Kind.AUTH));
                else if (failure != null) callback.error(failure); else callback.success(value);
            });
        }
    }
}
