package com.vktea.bookmarks;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;

/** A bounded, VK-only transport for the newer favourites methods. */
public final class VKBookmarksApi {
    private static final Set<String> METHODS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "fave.get", "fave.getPages", "fave.addPost", "fave.removePost", "fave.removeVideo",
            "fave.removeProduct", "fave.removePage", "fave.removeLink")));
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long MAX_BYTES = 4 * 1024 * 1024;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor(chain -> {
                Response response = chain.proceed(chain.request());
                return response.code() == 503 ? response.newBuilder().header("Retry-After", "2147483647").build() : response;
            }).build();
    private VKBookmarksApi() { }

    public static void get(Context context, String method, String params, VKApi.Callback callback) {
        Context app = context.getApplicationContext();
        String token = Session.getToken(app), secret = Session.getSecret(app), user = Session.getUserId(app);
        if (!METHODS.contains(method) || token == null || token.isEmpty()) {
            MAIN.post(() -> callback.error(new Exception("Bookmarks unavailable"))); return;
        }
        new Thread(() -> {
            JSONObject value = null;
            try {
                String query = "v=5.100&lang=" + Locale.getDefault().getLanguage() + "&https=1&access_token=" + token
                        + (params == null || params.isEmpty() ? "" : "&" + params);
                String sig = secret == null || secret.isEmpty() ? "" : "&sig=" + md5("/method/" + method + "?" + query + secret);
                Request request = new Request.Builder().url("https://api.vk.com/method/" + method + "?" + query + sig)
                        .header("User-Agent", VKApi.USER_AGENT).build();
                try (Response response = CLIENT.newCall(request).execute()) {
                    ResponseBody body = response.body();
                    if (!response.isSuccessful() || body == null || body.source().request(MAX_BYTES + 1)
                            && body.source().buffer().size() > MAX_BYTES) throw new IllegalStateException();
                    JSONObject root = new JSONObject(body.string());
                    if (!root.has("response")) throw new IllegalStateException();
                    value = root;
                }
            } catch (Exception ignored) { }
            final JSONObject result = value;
            MAIN.post(() -> {
                if (!Objects.equals(token, Session.getToken(app)) || !Objects.equals(secret, Session.getSecret(app))
                        || !Objects.equals(user, Session.getUserId(app)) || result == null) {
                    callback.error(new Exception("Bookmarks unavailable"));
                } else callback.success(result);
            });
        }, "vk-bookmarks").start();
    }
    private static String md5(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(32);
        for (byte b : digest) result.append(hex[(b >>> 4) & 15]).append(hex[b & 15]);
        return result.toString();
    }
}
