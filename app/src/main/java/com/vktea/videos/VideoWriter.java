package com.vktea.videos;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.MimeTypeMap;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import okhttp3.Dns;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;

/** One explicit attempt. Uploads run in bounded chunks; credentials never reach the upload host. */
final class VideoWriter {
    interface Callback { void done(String error); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder().protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .dns(hostname -> {
                List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
                for (InetAddress address : addresses) if (!isPublicAddress(address)) throw new UnknownHostException("Unsafe address");
                return addresses;
            }).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
            .addNetworkInterceptor(chain -> { Response response = chain.proceed(chain.request());
                return response.code() == 503 ? response.newBuilder().header("Retry-After", "2147483647").build() : response; }).build();
    private volatile Call call;
    private volatile boolean cancelled;
    private final String token, secret, user;
    VideoWriter(Context c) { context = c.getApplicationContext(); token = Session.getToken(context);
        String value = Session.getSecret(context); secret = value == null ? "" : value; user = Session.getUserId(context); }
    void cancel() { cancelled = true; Call active = call; if (active != null) active.cancel(); }
    private boolean valid() { String current = Session.getSecret(context); return !cancelled && token != null && !token.isEmpty()
            && user != null && !user.isEmpty() && token.equals(Session.getToken(context)) && user.equals(Session.getUserId(context))
            && secret.equals(current == null ? "" : current); }
    interface Work { void run() throws Exception; }
    void start(Work work, Callback cb) { new Thread(() -> {
        String error = null;
        try { if (!valid()) throw new Failure("auth"); work.run(); if (!valid()) throw new Failure("uncertain"); }
        catch (Failure e) { error = e.code; } catch (Exception e) { error = "uncertain"; }
        final String result = error;
        main.post(() -> { if (!cancelled) cb.done(result); });
    }, "vk-video-write").start(); }
    void edit(VideoEntry entry, String title, String description, Callback cb) {
        start(() -> { LinkedHashMap<String,String> p = new LinkedHashMap<>(); p.put("owner_id", Long.toString(entry.ownerId));
            p.put("video_id", Long.toString(entry.id)); p.put("name", title);
            if (description != null) p.put("desc", description);
            requireSuccess(api("video.edit", p)); }, cb);
    }
    void album(long id, String title, Callback cb) {
        start(() -> { LinkedHashMap<String,String> p = new LinkedHashMap<>();
            if (id != 0) p.put("album_id", Long.toString(id)); p.put("title", title);
            JSONObject root = api(id == 0 ? "video.addAlbum" : "video.editAlbum", p);
            if (id == 0) { JSONObject data = root.optJSONObject("response"); if (data == null || data.optLong("album_id") <= 0) throw new Failure("uncertain"); }
            else requireSuccess(root);
        }, cb);
    }
    void upload(Uri uri, String title, String description, Callback cb) { uploadSource(uri, null, title, description, cb); }
    void uploadEdited(File file, String title, String description, Callback cb) { uploadSource(null, file, title, description, cb); }
    private void uploadSource(Uri uri, File edited, String title, String description, Callback cb) {
        start(() -> {
            if (edited == null && (uri == null || !"content".equals(uri.getScheme()))) throw new Failure("file");
            long length;
            if (edited != null) {
                if (!edited.getCanonicalFile().getParentFile().equals(context.getCacheDir().getCanonicalFile()) || !edited.getName().startsWith("vk-video-trim-")) throw new Failure("file");
                length = edited.length();
            } else try (AssetFileDescriptor fd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (fd == null) throw new Failure("file"); length = fd.getLength();
            } catch (Failure e) { throw e; } catch (Exception e) { throw new Failure("file"); }
            if (length <= 0) throw new Failure("file");
            String extension = edited != null ? "mp4" : MimeTypeMap.getSingleton().getExtensionFromMimeType(context.getContentResolver().getType(uri));
            if (extension == null || !extension.matches("[a-zA-Z0-9]{1,10}")) extension = "bin";
            String filename = "video." + extension;
            VideoUploadProtocol.upload(() -> edited == null ? context.getContentResolver().openInputStream(uri) : new FileInputStream(edited),
                    length, title, description, filename, new VideoUploadProtocol.Transport() {
                @Override public boolean valid() { return VideoWriter.this.valid(); }
                @Override public JSONObject save(String name, String desc) throws Exception {
                    LinkedHashMap<String,String> p = new LinkedHashMap<>(); p.put("name", name); p.put("description", desc); p.put("preview", "1");
                    return api("video.save", p).optJSONObject("response");
                }
                @Override public VideoUploadProtocol.Reply send(HttpUrl url, String session, String file, long from, long to, long total, byte[] bytes) throws Exception {
                    Request request = new Request.Builder().url(url).header("Session-ID", session)
                            .header("Content-Disposition", "attachment; filename=\"" + file + "\"")
                            .header("Content-Range", "bytes " + from + "-" + to + "/" + total)
                            .post(RequestBody.create(bytes, MediaType.parse("application/octet-stream"))).build();
                    try (Response response = execute(request)) {
                        JSONObject body = response.code() == 200 ? readJson(response) : null;
                        return new VideoUploadProtocol.Reply(response.code(), response.header("Range"), body);
                    }
                }
            });
        }, cb);
    }
    static boolean confirmedUpload(JSONObject response, long videoId, long size) {
        if (response == null || response.has("error")) return false;
        if (response.has("video_id") && response.optLong("video_id", 0) != videoId) return false;
        if (response.has("size") && response.optLong("size", 0) != size) return false;
        String link = response.optString("direct_link", "");
        HttpUrl direct = HttpUrl.parse(link);
        if (direct != null && direct.isHttps() && direct.username().isEmpty() && direct.password().isEmpty()) return true;
        return videoId > 0 && size > 0 && response.optLong("video_id", 0) == videoId && response.optLong("size", 0) == size;
    }
    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] ip = address.getAddress();
        if (ip.length == 16) return (ip[0] & 0xfe) != 0xfc;
        int first = ip[0] & 255, second = ip[1] & 255;
        return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127);
    }
    static HttpUrl safeUploadUrl(String value) {
        HttpUrl u = HttpUrl.parse(value); if (u == null || !u.isHttps() || !u.username().isEmpty() || !u.password().isEmpty() || u.port() != 443) return null;
        String h = u.host(); for (String domain : new String[]{"vk.com", "userapi.com", "vkuser.net", "vk-cdn.net"})
            if (h.equals(domain) || h.endsWith("." + domain)) return u;
        return null;
    }
    static boolean confirmedRange(String value, long end) {
        if (value == null) return false;
        String normalized = value.trim();
        if (normalized.startsWith("bytes=")) normalized = normalized.substring(6);
        int slash = normalized.indexOf('/'); if (slash >= 0) normalized = normalized.substring(0, slash);
        return normalized.equals("0-" + end);
    }
    private JSONObject api(String method, LinkedHashMap<String,String> extra) throws Exception {
        if (!valid()) throw new Failure("auth");
        LinkedHashMap<String,String> params = new LinkedHashMap<>(); params.put("v", "5.82"); params.put("lang", Locale.getDefault().getLanguage());
        params.put("https", "1"); params.put("access_token", token); params.putAll(extra);
        FormBody.Builder form = new FormBody.Builder(); for (Map.Entry<String,String> e : params.entrySet()) form.add(e.getKey(), e.getValue());
        if (!secret.isEmpty()) form.add("sig", VideoRepository.signature(method, params, secret));
        Request request = new Request.Builder().url("https://api.vk.com/method/" + method).header("User-Agent", VKApi.USER_AGENT).post(form.build()).build();
        try (Response response = execute(request)) {
            if (!response.isSuccessful()) throw new Failure(response.code() == 429 ? "rate" : "uncertain");
            JSONObject body = readJson(response); JSONObject failure = body.optJSONObject("error");
            if (failure != null) throw new Failure(VideoRepository.mapError(failure.optInt("error_code")));
            if (!body.has("response")) throw new Failure("uncertain"); return body;
        }
    }
    private Response execute(Request request) throws IOException, Failure {
        if (!valid()) throw new Failure("auth"); Call next = client.newCall(request); call = next;
        if (!valid()) { next.cancel(); throw new Failure("auth"); }
        try { return next.execute(); } finally { call = null; }
    }
    private static JSONObject readJson(Response response) throws Exception {
        ResponseBody body = response.body(); if (body == null || body.source().request(1024 * 1024L + 1) && body.source().buffer().size() > 1024 * 1024L) throw new Failure("uncertain");
        return new JSONObject(body.string());
    }
    private static void requireSuccess(JSONObject root) throws Failure { if (root.optInt("response", 0) != 1) throw new Failure("uncertain"); }
    static final class Failure extends Exception { final String code; Failure(String c) { code = c; } }
}
