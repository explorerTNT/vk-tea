package com.vktea.auth;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

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
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            // Keep auth on HTTP/1.1: HTTP/2 coalescing can trigger an automatic
            // retry of HTTP 421 independently of retryOnConnectionFailure.
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor(chain -> {
                Response response = chain.proceed(chain.request());
                // OkHttp can otherwise follow a 503 + Retry-After: 0 even with
                // connection recovery disabled. Never repeat an auth request.
                if (response.code() == 503) {
                    return response.newBuilder().header("Retry-After", "2147483647").build();
                }
                return response;
            })
            .build();

    AuthResponse password(HttpUrl url) throws IOException {
        try (Response response = HTTP.newCall(new Request.Builder().url(url)
                .header("User-Agent", PASSWORD_USER_AGENT).get().build()).execute()) {
            return AuthResponse.password(response.code(), readJson(response));
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
        try (Response response = HTTP.newCall(new Request.Builder().url(url).get().build()).execute()) {
            return AuthResponse.token(response.code(), readJson(response), token, secret);
        }
    }

    AuthResponse vkId(String endpoint, FormBody form) throws IOException {
        try (Response response = HTTP.newCall(new Request.Builder()
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
