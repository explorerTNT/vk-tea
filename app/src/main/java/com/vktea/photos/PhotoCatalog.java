package com.vktea.photos;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import com.vktea.feed.PostPhoto;
import com.vktea.feed.ViewerPhoto;

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
import java.util.Set;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Bounded, read-only VK album and photo requests. Never logs credentials or API payloads. */
final class PhotoCatalog {
    static final int PAGE_SIZE = 100;
    private static final long MAX_BYTES = 4 * 1024 * 1024;
    private final Context context;
    private final OkHttpClient client;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HttpUrl endpoint;

    PhotoCatalog(Context context) {
        this.context = context.getApplicationContext();
        endpoint = HttpUrl.get("https://api.vk.com/");
        client = new OkHttpClient.Builder().protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .addNetworkInterceptor(chain -> {
                    Response response = chain.proceed(chain.request());
                    return response.code() == 503 ? response.newBuilder().header("Retry-After", "2147483647").build() : response;
                }).build();
    }

    interface Callback { void success(Page page); void error(String message); }
    enum Kind { ALBUM, ALL, USER }
    static final class Album {
        final long id; final Kind kind; final String title, cover, description; final int size; final boolean titleKnown;
        Album(long id, Kind kind, String title, String cover, int size) { this(id, kind, title, cover, size, ""); }
        Album(long id, Kind kind, String title, String cover, int size, String description) { this(id, kind, title, cover, size, description, true); }
        Album(long id, Kind kind, String title, String cover, int size, String description, boolean titleKnown) { this.id = id; this.kind = kind; this.title = title; this.cover = cover; this.size = size; this.description = description; this.titleKnown = titleKnown; }
        String identity() { return kind == Kind.ALBUM ? "album:" + id : kind.name(); }
    }
    static final class Page {
        final int total, consumed; final List<Album> albums; final List<ViewerPhoto> photos; final Set<String> knownPhotoDescriptions;
        Page(int total, int consumed, List<Album> albums, List<ViewerPhoto> photos, Set<String> knownPhotoDescriptions) {
            this.total = total; this.consumed = consumed; this.albums = albums; this.photos = photos; this.knownPhotoDescriptions = knownPhotoDescriptions;
        }
    }

    Call load(long owner, Long album, Kind kind, int offset, Callback callback) {
        String token = Session.getToken(context), secret = Session.getSecret(context), user = Session.getUserId(context);
        if (owner == 0 || token == null || token.isEmpty()) { main.post(() -> callback.error("Войдите в аккаунт, чтобы открыть фотографии")); return null; }
        String method = method(album, kind);
        LinkedHashMap<String, String> args = parameters(owner, album, kind, offset, token);
        HttpUrl.Builder url = endpoint.newBuilder().addPathSegments("method/" + method);
        for (Map.Entry<String, String> entry : args.entrySet()) url.addQueryParameter(entry.getKey(), entry.getValue());
        if (secret != null && !secret.isEmpty()) url.addQueryParameter("sig", signature(method, args, secret));
        Call call = client.newCall(new okhttp3.Request.Builder().url(url.build()).header("User-Agent", VKApi.USER_AGENT).build());
        call.enqueue(new okhttp3.Callback() {
            private void deliver(Page page, String failure) {
                main.post(() -> {
                    if (call.isCanceled()) return;
                    if (!same(token, Session.getToken(context)) || !same(secret, Session.getSecret(context)) || !same(user, Session.getUserId(context))) {
                        callback.error("Аккаунт изменился. Откройте раздел заново"); return;
                    }
                    if (failure == null) callback.success(page); else callback.error(failure);
                });
            }
            @Override public void onFailure(Call ignored, IOException error) { deliver(null, "Не удалось загрузить фотографии. Проверьте соединение"); }
            @Override public void onResponse(Call ignored, Response response) {
                try (Response received = response) {
                    if (!received.isSuccessful()) { deliver(null, received.code() == 429 ? "Слишком много запросов. Попробуйте позже" : "Не удалось загрузить фотографии"); return; }
                    ResponseBody body = received.body();
                    if (body == null || body.source().request(MAX_BYTES + 1) && body.source().buffer().size() > MAX_BYTES) throw new JSONException("Invalid body");
                    JSONObject root = new JSONObject(body.string());
                    JSONObject error = root.optJSONObject("error");
                    if (error != null) { deliver(null, errorMessage(error.optInt("error_code"))); return; }
                    Page page = parse(root, album == null);
                    if (page.consumed == 0 && offset < page.total) throw new JSONException("Incomplete page");
                    deliver(page, null);
                } catch (IOException error) { deliver(null, "Не удалось загрузить фотографии. Проверьте соединение"); }
                catch (JSONException | RuntimeException error) { deliver(null, "Не удалось обработать ответ VK"); }
            }
        });
        return call;
    }

    static LinkedHashMap<String, String> parameters(long owner, Long album, Kind kind, int offset, String token) {
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("v", "5.82"); args.put("lang", Locale.getDefault().getLanguage()); args.put("https", "1");
        args.put("access_token", token); args.put(kind == Kind.USER && album != null ? "user_id" : "owner_id", Long.toString(owner));
        args.put("count", Integer.toString(PAGE_SIZE)); args.put("offset", Integer.toString(offset));
        if (album == null) { args.put("need_system", "1"); args.put("need_covers", "1"); args.put("photo_sizes", "1"); }
        else {
            if (kind == Kind.ALBUM) args.put("album_id", Long.toString(album));
            if (kind == Kind.ALL) args.put("need_hidden", "1");
            if (kind == Kind.USER) args.put("sort", "desc");
            args.put("extended", "1"); args.put("photo_sizes", "1");
        }
        return args;
    }

    static String method(Long album, Kind kind) {
        if (album == null) return "photos.getAlbums";
        if (kind == Kind.ALL) return "photos.getAll";
        if (kind == Kind.USER) return "photos.getUserPhotos";
        return "photos.get";
    }

    static Page parse(JSONObject root, boolean albumsPage) throws JSONException {
        JSONObject response = root.getJSONObject("response");
        long count = response.optLong("count", -1);
        JSONArray rows = response.getJSONArray("items");
        if (count < 0 || count > Integer.MAX_VALUE || rows.length() > PAGE_SIZE) throw new JSONException("Invalid page");
        List<Album> albums = new ArrayList<>(); List<ViewerPhoto> photos = new ArrayList<>(); Set<String> known = new HashSet<>();
        if (albumsPage) {
            addSystemAlbum(albums, response.optJSONObject("all_photos"), Kind.ALL, "Все фотографии");
            addSystemAlbum(albums, response.optJSONObject("user_photos"), Kind.USER, "Фотографии с отметками");
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            if (albumsPage) {
                long id = row.optLong("id", 0); if (id == 0) continue;
                String title = row.optString("title", "").trim();
                String cover = PostPhoto.bestUrl(row);
                if (cover.isEmpty()) cover = row.optString("thumb_src", "");
                if (cover.isEmpty()) cover = PostPhoto.bestUrl(row.optJSONObject("thumb"));
                albums.add(new Album(id, Kind.ALBUM, title.isEmpty() ? "Альбом" : title, cover, Math.max(0, row.optInt("size", 0)), row.has("description") && !row.isNull("description") ? row.optString("description", "") : null, !title.isEmpty()));
            } else {
                ViewerPhoto photo = ViewerPhoto.parse(row);
                if (!photo.url.isEmpty()) {
                    photos.add(photo);
                    if (photo.hasIdentity() && row.opt("text") instanceof String) known.add(photo.ownerId + ":" + photo.id);
                }
            }
        }
        return new Page((int) count, rows.length(), albums, photos, known);
    }
    private static void addSystemAlbum(List<Album> albums, JSONObject row, Kind kind, String title) {
        if (row == null) return;
        String cover = PostPhoto.bestUrl(row);
        if (cover.isEmpty()) cover = row.optString("thumb_src", "");
        JSONArray last = row.optJSONArray("last_photos");
        if (cover.isEmpty() && last != null && last.length() > 0) cover = PostPhoto.bestUrl(last.optJSONObject(0));
        albums.add(new Album(row.optLong("id", 0), kind, title, cover, Math.max(0, row.optInt("size", 0))));
    }
    private static boolean same(String a, String b) { return a == null ? b == null : a.equals(b); }
    static String errorMessage(int code) {
        switch (code) {
            case 5: case 17: case 25: return "Сессия недействительна. Войдите снова";
            case 7: case 15: case 30: case 200: case 201: return "Нет доступа к фотографиям";
            case 6: case 9: case 29: return "Слишком много запросов. Попробуйте позже";
            default: return "VK не удалось загрузить фотографии";
        }
    }
    static String signature(String method, LinkedHashMap<String, String> params, String secret) {
        StringBuilder value = new StringBuilder("/method/").append(method).append('?');
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (value.charAt(value.length() - 1) != '?') value.append('&');
            value.append(entry.getKey()).append('=').append(entry.getValue());
        }
        value.append(secret);
        try {
            StringBuilder result = new StringBuilder();
            for (byte b : MessageDigest.getInstance("MD5").digest(value.toString().getBytes(StandardCharsets.UTF_8))) result.append(String.format(Locale.US, "%02x", b & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
