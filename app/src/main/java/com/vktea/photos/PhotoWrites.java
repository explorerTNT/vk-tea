package com.vktea.photos;

import android.content.Context;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.os.Handler;
import android.os.Looper;

import com.vktea.api.VKApi;
import com.vktea.auth.Session;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

/** Single-shot write operations; never retries or logs session material or upload responses. */
final class PhotoWrites {
    static final long MAX_PHOTO_BYTES = 25L * 1024 * 1024;
    private static final long MAX_RESPONSE_BYTES = 1024 * 1024;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder().protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .dns(publicDns(Dns.SYSTEM)).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
            .addNetworkInterceptor(chain -> {
                Response r = chain.proceed(chain.request());
                return r.code() == 503 ? r.newBuilder().header("Retry-After", "2147483647").build() : r;
            }).build();
    private final Transport transport;
    private volatile Call call;
    private volatile boolean cancelled;
    private String token, secret, user;
    interface Callback { void done(String message, boolean success); }
    interface Work { void run() throws Exception; }
    interface Transport { JSONObject execute(Request request) throws Exception; }
    PhotoWrites(Context context) { this(context, null); }
    PhotoWrites(Context context, Transport transport) {
        this.context = context.getApplicationContext(); this.transport = transport; captureSession();
    }
    private void captureSession() {
        token = Session.getToken(context); secret = Session.getSecret(context); user = Session.getUserId(context);
    }
    String runOnce(Work work) {
        try { checkSession(); work.run(); checkSession(); return null; }
        catch (Failure e) { return e.getMessage(); }
        catch (Exception e) { return "Не удалось подтвердить результат. Проверьте изменения в VK перед повтором"; }
    }
    void cancel() { cancelled = true; Call active = call; if (active != null) active.cancel(); }
    void start(Work work, Callback callback) {
        captureSession();
        new Thread(() -> {
            final String result = runOnce(work);
            main.post(() -> { if (!cancelled) callback.done(result == null ? "Изменения сохранены" : result, result == null); });
        }, "photo-write").start();
    }
    void createAlbum(long owner, String title, String description) throws Exception {
        LinkedHashMap<String, String> args = new LinkedHashMap<>(); args.put("title", title); args.put("description", description);
        if (owner < 0) args.put("group_id", Long.toString(-owner));
        JSONObject created = api("photos.createAlbum", args).optJSONObject("response");
        if (created == null || created.optLong("id", 0) <= 0) throw new JSONException("Invalid response");
    }
    void editAlbum(long owner, long album, String title, String description) throws Exception {
        success(api("photos.editAlbum", editAlbumParameters(owner, album, title, description)));
    }
    static LinkedHashMap<String, String> editAlbumParameters(long owner, long album, String title, String description) {
        LinkedHashMap<String, String> args = new LinkedHashMap<>(); args.put("owner_id", Long.toString(owner)); args.put("album_id", Long.toString(album));
        args.put("title", title); if (description != null) args.put("description", description); return args;
    }
    void editPhoto(long owner, long photo, String caption) throws Exception {
        LinkedHashMap<String, String> args = new LinkedHashMap<>(); args.put("owner_id", Long.toString(owner)); args.put("photo_id", Long.toString(photo));
        args.put("caption", caption); success(api("photos.edit", args));
    }
    void upload(long owner, long album, Uri uri) throws Exception {
        if (!"content".equals(uri.getScheme())) throw new Failure("Выберите фотографию на устройстве");
        String mime = context.getContentResolver().getType(uri);
        if (mime == null || !(mime.equalsIgnoreCase("image/jpeg") || mime.equalsIgnoreCase("image/png") || mime.equalsIgnoreCase("image/gif")))
            throw new Failure("Выберите фотографию JPEG, PNG или GIF");
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0) && cursor.getLong(0) > MAX_PHOTO_BYTES)
                throw new Failure("Файл слишком большой: максимум 25 МБ");
        }
        LinkedHashMap<String, String> args = new LinkedHashMap<>(); args.put("album_id", Long.toString(album));
        if (owner < 0) args.put("group_id", Long.toString(-owner));
        JSONObject response = api("photos.getUploadServer", args).getJSONObject("response");
        HttpUrl url = HttpUrl.parse(response.optString("upload_url", ""));
        if (!validUploadUrl(url)) throw new Failure("VK вернул недопустимый адрес загрузки");
        checkSession();
        RequestBody photo = new RequestBody() {
            @Override public MediaType contentType() { return MediaType.parse(mime); }
            @Override public void writeTo(BufferedSink sink) throws IOException {
                try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("Cannot open image");
                    byte[] buffer = new byte[32768]; long count = 0; int n;
                    while ((n = in.read(buffer)) != -1) { count += n; if (count > MAX_PHOTO_BYTES) throw new IOException("Image too large"); sink.write(buffer, 0, n); }
                    if (count == 0) throw new IOException("Empty image");
                }
            }
        };
        RequestBody multipart = new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file1", "photo", photo).build();
        JSONObject uploaded = execute(new Request.Builder().url(url).header("User-Agent", VKApi.USER_AGENT).post(multipart).build());
        checkSession();
        String server = String.valueOf(uploaded.opt("server")), list = uploaded.optString("photos_list", ""), hash = uploaded.optString("hash", "");
        if (server.equals("null") || list.isEmpty() || hash.isEmpty()) throw new JSONException("Invalid upload response");
        JSONArray photos = new JSONArray(list); if (photos.length() == 0) throw new JSONException("Empty upload");
        LinkedHashMap<String, String> save = new LinkedHashMap<>(); save.put("album_id", Long.toString(album));
        if (owner < 0) save.put("group_id", Long.toString(-owner));
        save.put("server", server); save.put("photos_list", list); save.put("hash", hash); save.put("photo_sizes", "1");
        JSONArray saved = api("photos.save", save).optJSONArray("response");
        if (!validSavedPhoto(saved, owner, album)) throw new JSONException("Invalid save response");
    }
    static boolean validSavedPhoto(JSONArray saved, long owner, long album) {
        if (saved == null || saved.length() != 1) return false;
        JSONObject photo = saved.optJSONObject(0);
        return photo != null && photo.optLong("id", 0) > 0 && photo.optLong("owner_id", 0) == owner
                && photo.optLong("album_id", 0) == album;
    }
    static Dns publicDns(Dns delegate) {
        return hostname -> {
            List<InetAddress> addresses = delegate.lookup(hostname);
            for (InetAddress address : addresses) if (!isPublicAddress(address)) throw new UnknownHostException("Unsafe address");
            return addresses;
        };
    }
    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] ip = address.getAddress();
        if (ip.length == 16) return (ip[0] & 0xfe) != 0xfc;
        int first = ip[0] & 255, second = ip[1] & 255;
        return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127);
    }
    static boolean validUploadUrl(HttpUrl url) {
        if (url == null || !url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty() || url.port() != 443) return false;
        String host = url.host();
        return domain(host, "vk.com") || domain(host, "vk.ru") || domain(host, "userapi.com");
    }
    private static boolean domain(String host, String suffix) { return host.equals(suffix) || host.endsWith("." + suffix); }
    private JSONObject api(String method, LinkedHashMap<String, String> args) throws Exception {
        checkSession();
        LinkedHashMap<String, String> params = new LinkedHashMap<>(); params.put("v", "5.82"); params.put("lang", Locale.getDefault().getLanguage());
        params.put("https", "1"); params.put("access_token", token); params.putAll(args);
        FormBody.Builder form = new FormBody.Builder(); for (Map.Entry<String, String> e : params.entrySet()) form.add(e.getKey(), e.getValue());
        if (secret != null && !secret.isEmpty()) form.add("sig", PhotoCatalog.signature(method, params, secret));
        JSONObject result = execute(new Request.Builder().url("https://api.vk.com/method/" + method).header("User-Agent", VKApi.USER_AGENT).post(form.build()).build());
        JSONObject error = result.optJSONObject("error"); if (error != null) {
            int code = error.optInt("error_code");
            if (code == 5 || code == 17 || code == 25) throw new Failure("Сессия недействительна. Войдите снова");
            if (code == 6 || code == 9 || code == 29) throw new Failure("Слишком много запросов. Попробуйте позже");
            if (code == 7 || code == 15 || code == 30 || code == 200 || code == 201) throw new Failure("VK не разрешил изменить фотографии");
            throw new Failure("VK отклонил изменение фотографий");
        }
        return result;
    }
    private JSONObject execute(Request request) throws Exception {
        checkSession();
        if (transport != null) return transport.execute(request);
        Call next = client.newCall(request); call = next;
        if (cancelled) { next.cancel(); throw new IOException("Cancelled"); }
        try (Response response = next.execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP failure");
            ResponseBody body = response.body();
            if (body == null || body.source().request(MAX_RESPONSE_BYTES + 1) && body.source().buffer().size() > MAX_RESPONSE_BYTES) throw new IOException("Invalid response");
            return new JSONObject(body.string());
        } finally { call = null; }
    }
    private void checkSession() throws Failure {
        if (cancelled || token == null || token.isEmpty() || !same(token, Session.getToken(context)) || !same(secret, Session.getSecret(context)) || !same(user, Session.getUserId(context)))
            throw new Failure("Аккаунт изменился. Откройте раздел заново и проверьте результат в VK");
    }
    private static boolean same(String a, String b) { return a == null ? b == null : a.equals(b); }
    private static void success(JSONObject result) throws Exception { if (result.optInt("response", 0) != 1) throw new JSONException("Invalid result"); }
    private static final class Failure extends Exception { Failure(String message) { super(message); } }
}
