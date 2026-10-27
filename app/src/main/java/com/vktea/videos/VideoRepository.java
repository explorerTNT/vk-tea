package com.vktea.videos;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Bounded, single-attempt read requests. No response bodies or credentials are logged. */
final class VideoRepository implements VideosViewModel.Source {
    static final int PAGE_SIZE = 50;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder()
            .protocols(Collections.singletonList(Protocol.HTTP_1_1)).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor(chain -> {
                Response response = chain.proceed(chain.request());
                return response.code() == 503 ? response.newBuilder().header("Retry-After", "2147483647").build() : response;
            }).build();
    private Call call;
    VideoRepository(Context context) { this.context = context.getApplicationContext(); }
    @Override public void cancel() { if (call != null) call.cancel(); call = null; }
    @Override public void load(long owner, long album, boolean albums, String query, int offset, VideosViewModel.Callback callback) {
        cancel();
        String token = Session.getToken(context), secret = Session.getSecret(context), user = Session.getUserId(context);
        if (token == null || token.isEmpty() || user == null || user.isEmpty()) { main.post(() -> callback.complete(null, "auth")); return; }
        if (secret == null) secret = "";
        final String savedToken = token, savedSecret = secret, savedUser = user;
        String method = albums ? "video.getAlbums" : query.isEmpty() ? "video.get" : "video.search";
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("v", "5.82"); params.put("lang", Locale.getDefault().getLanguage()); params.put("https", "1");
        params.put("access_token", token);
        if (!query.isEmpty()) params.put("q", query);
        else { params.put("owner_id", Long.toString(owner)); if (album != 0) params.put("album_id", Long.toString(album)); }
        if (albums) params.put("extended", "1");
        params.put("count", Integer.toString(PAGE_SIZE)); params.put("offset", Integer.toString(offset));
        HttpUrl.Builder url = HttpUrl.get("https://api.vk.com/").newBuilder().addPathSegments("method/" + method);
        for (Map.Entry<String, String> param : params.entrySet()) url.addQueryParameter(param.getKey(), param.getValue());
        if (!secret.isEmpty()) url.addQueryParameter("sig", signature(method, params, secret));
        Call started = client.newCall(new okhttp3.Request.Builder().url(url.build()).header("User-Agent", VKApi.USER_AGENT).build());
        call = started;
        started.enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call ignored, IOException failure) { deliver(started, savedToken, savedSecret, savedUser, null, "network", callback); }
            @Override public void onResponse(Call ignored, Response response) {
                VideoEntry.Page page = null; String error = null;
                try (Response received = response) {
                    if (!received.isSuccessful()) error = received.code() == 429 ? "rate" : received.code() == 401 ? "auth" : received.code() == 403 ? "access" : "network";
                    else {
                        ResponseBody body = received.body();
                        if (body == null || body.source().request(4 * 1024 * 1024L + 1) && body.source().buffer().size() > 4 * 1024 * 1024L) error = "generic";
                        else {
                            JSONObject root = new JSONObject(body.string());
                            JSONObject failure = root.optJSONObject("error");
                            if (failure != null) error = mapError(failure.optInt("error_code"));
                            else page = VideoEntry.parsePage(root, albums, owner);
                        }
                    }
                } catch (Exception failure) { error = "generic"; }
                deliver(started, savedToken, savedSecret, savedUser, page, error, callback);
            }
        });
    }
    private void deliver(Call started, String token, String secret, String user, VideoEntry.Page page, String error, VideosViewModel.Callback callback) {
        main.post(() -> {
            if (call != started || started.isCanceled()) return;
            call = null;
            String currentSecret = Session.getSecret(context);
            if (!token.equals(Session.getToken(context)) || !secret.equals(currentSecret == null ? "" : currentSecret)
                    || !user.equals(Session.getUserId(context))) callback.complete(null, "auth");
            else callback.complete(page, error);
        });
    }
    static String mapError(int code) {
        switch (code) {
            case 5: case 17: case 25: return "auth";
            case 7: case 15: case 20: case 23: case 200: case 201: case 204: case 260: return "access";
            case 6: case 9: case 29: return "rate";
            default: return "generic";
        }
    }
    static String signature(String method, LinkedHashMap<String, String> params, String secret) {
        StringBuilder raw = new StringBuilder("/method/").append(method).append('?');
        for (Map.Entry<String, String> p : params.entrySet()) {
            if (raw.charAt(raw.length() - 1) != '?') raw.append('&');
            raw.append(p.getKey()).append('=').append(p.getValue());
        }
        raw.append(secret);
        try {
            StringBuilder result = new StringBuilder();
            for (byte b : MessageDigest.getInstance("MD5").digest(raw.toString().getBytes(StandardCharsets.UTF_8)))
                result.append(String.format(Locale.US, "%02x", b & 255));
            return result.toString();
        } catch (Exception impossible) { throw new AssertionError(impossible); }
    }
}
