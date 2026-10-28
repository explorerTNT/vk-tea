package com.vktea.auth;

import android.content.Context;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Auth transport never retries credentials, follows redirects, or logs response bodies. */
class AuthHttpClient {
    static final String PASSWORD_USER_AGENT = "VKAndroidApp/8.09-1113 (Android 13; SDK 33; arm64-v8a; samsung SM-S908N; ru)";
    private static volatile OkHttpClient client;

    static void installCookieJar(Context context) {
        if (client == null) {
            synchronized (AuthHttpClient.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .cookieJar(new PersistentCookieJar(context))
                            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                            .retryOnConnectionFailure(false)
                            .followRedirects(false)
                            .followSslRedirects(false)
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(15, TimeUnit.SECONDS)
                            .callTimeout(30, TimeUnit.SECONDS)
                            .addNetworkInterceptor(chain -> {
                                okhttp3.Response response = chain.proceed(chain.request());
                                if (response.code() == 503) {
                                    return response.newBuilder().header("Retry-After", "2147483647").build();
                                }
                                return response;
                            })
                            .build();
                }
            }
        }
    }

    private static OkHttpClient http() {
        OkHttpClient c = client;
        if (c != null) {
            return c;
        }
        return new OkHttpClient();
    }

    AuthResponse password(HttpUrl url) throws IOException {
        AuthLog.log("req", AuthLog.maskUrl(url.toString()));
        try (Response response = http().newCall(new Request.Builder().url(url)
                .header("User-Agent", PASSWORD_USER_AGENT).get().build()).execute()) {
            JSONObject json = readJson(response);
            AuthLog.log("resp", response.code() + " " + AuthLog.maskBody(json.toString()));
            return AuthResponse.password(response.code(), json);
        } catch (IOException e) {
            AuthLog.log("iofail", e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    AuthResponse token(String token, String secret) throws Exception {
        HttpUrl url = HttpUrl.get("https://api.vk.com/method/users.get").newBuilder()
                .addQueryParameter("v", "5.82")
                .addQueryParameter("lang", "ru")
                .addQueryParameter("https", "1")
                .addQueryParameter("access_token", token)
                .build();
        if (secret != null && !secret.isEmpty()) {
            byte[] hash = MessageDigest.getInstance("MD5").digest(
                    (url.encodedPath() + "?" + url.encodedQuery() + secret)
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder signature = new StringBuilder();
            for (byte value : hash) {
                signature.append(Character.forDigit((value >> 4) & 15, 16));
                signature.append(Character.forDigit(value & 15, 16));
            }
            url = url.newBuilder().addQueryParameter("sig", signature.toString()).build();
        }
        try (Response response = http().newCall(new Request.Builder().url(url).get().build()).execute()) {
            return AuthResponse.token(response.code(), readJson(response), token, secret);
        }
    }

    AuthResponse vkId(String endpoint, FormBody form) throws IOException {
        try (Response response = http().newCall(new Request.Builder()
                .url(endpoint).post(form).build()).execute()) {
            return AuthResponse.vkId(response.code(), readJson(response));
        }
    }

    private static JSONObject readJson(Response response) throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            return new JSONObject();
        }
        String text = body.string();
        try {
            return new JSONObject(text);
        } catch (org.json.JSONException ignored) {
            // Retain the HTTP status (especially 429), never expose an HTML body.
            return new JSONObject();
        }
    }
}
