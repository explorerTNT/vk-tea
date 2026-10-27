package com.vktea.search;

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
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Bounded read-only requests; credentials and exception details never reach the UI or logs. */
final class SearchRepository {
    interface Callback { void done(SearchParser.Page page, String error); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder().protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor(chain -> {
                Response response = chain.proceed(chain.request());
                return response.code() == 503 ? response.newBuilder().header("Retry-After", "2147483647").build() : response;
            }).build();
    private Call active;
    private int generation;
    SearchRepository(Context context) { this.context = context.getApplicationContext(); }
    void cancel() { generation++; if (active != null) active.cancel(); active = null; }
    void search(SearchParser.Kind kind, String query, String cursor, Callback callback) {
        cancel();
        final int request = generation;
        String token = Session.getToken(context), secret = Session.getSecret(context), user = Session.getUserId(context);
        if (token == null || token.isEmpty()) { callback.done(null, "Войдите в аккаунт, чтобы воспользоваться поиском."); return; }
        HttpUrl url = requestUrl(kind, query, cursor, token, secret);
        active = http.newCall(new okhttp3.Request.Builder().url(url).header("User-Agent", VKApi.USER_AGENT).get().build());
        active.enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException ignored) { deliver(request, token, secret, user, callback, null, "Не удалось подключиться. Проверьте сеть и повторите поиск."); }
            @Override public void onResponse(Call call, Response response) {
                SearchParser.Page page = null;
                String error = null;
                try (Response received = response) {
                    if (!received.isSuccessful()) error = received.code() == 429 ? "Слишком много запросов. Попробуйте позже." : "Сервис поиска недоступен. Попробуйте позже.";
                    else {
                        ResponseBody body = received.body();
                        if (body == null || (body.source().request(4 * 1024 * 1024 + 1L) && body.source().buffer().size() > 4 * 1024 * 1024)) throw new IOException();
                        JSONObject root = new JSONObject(body.string());
                        JSONObject apiError = root.optJSONObject("error");
                        if (apiError != null) error = errorFor(apiError.optInt("error_code"));
                        else page = SearchParser.parse(kind, root, kind == SearchParser.Kind.POSTS || cursor.isEmpty() ? 0 : Integer.parseInt(cursor));
                    }
                } catch (Exception ignored) { error = "Не удалось обработать ответ поиска. Попробуйте позже."; }
                deliver(request, token, secret, user, callback, page, error);
            }
        });
    }
    static HttpUrl requestUrl(SearchParser.Kind kind, String query, String cursor, String token, String secret) {
        String method = kind == SearchParser.Kind.USERS ? "users.search" : kind == SearchParser.Kind.GROUPS ? "groups.search"
                : kind == SearchParser.Kind.VIDEOS ? "video.search" : "newsfeed.search";
        HttpUrl.Builder builder = HttpUrl.get("https://api.vk.com/").newBuilder().addPathSegments("method/" + method)
                .addQueryParameter("v", "5.82").addQueryParameter("lang", Locale.getDefault().getLanguage())
                .addQueryParameter("https", "1").addQueryParameter("access_token", token)
                .addQueryParameter("q", query).addQueryParameter("count", "30");
        if (kind == SearchParser.Kind.USERS) builder.addQueryParameter("fields", "photo_100,city");
        if (kind == SearchParser.Kind.GROUPS) builder.addQueryParameter("fields", "activity");
        if (kind == SearchParser.Kind.POSTS) builder.addQueryParameter("extended", "1");
        if (!cursor.isEmpty()) builder.addQueryParameter(kind == SearchParser.Kind.POSTS ? "start_from" : "offset", cursor);
        HttpUrl unsigned = builder.build();
        if (secret != null && !secret.isEmpty()) builder.addQueryParameter("sig", signature(unsigned.encodedPath() + "?" + unsigned.encodedQuery() + secret));
        return builder.build();
    }
    private void deliver(int request, String token, String secret, String user, Callback callback, SearchParser.Page page, String error) {
        main.post(() -> {
            if (request != generation) return;
            active = null;
            if (!same(token, Session.getToken(context)) || !same(secret, Session.getSecret(context)) || !same(user, Session.getUserId(context))) {
                callback.done(null, "Аккаунт изменился. Повторите поиск."); return;
            }
            callback.done(page, error);
        });
    }
    static String errorFor(int code) {
        switch (code) {
            case 5: case 17: case 25: return "Сессия недоступна. Войдите в аккаунт заново.";
            case 6: case 9: case 29: return "Слишком много запросов. Попробуйте позже.";
            case 7: case 15: case 20: case 23: case 200: case 201: return "VK ограничил доступ к этому виду поиска для текущего аккаунта.";
            default: return "VK не смог выполнить поиск. Попробуйте позже.";
        }
    }
    private static boolean same(String a, String b) { return java.util.Objects.equals(a, b); }
    private static String signature(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (byte b : hash) result.append(String.format(Locale.US, "%02x", b & 255));
            return result.toString();
        } catch (Exception impossible) { throw new IllegalStateException("MD5 unavailable"); }
    }
}
