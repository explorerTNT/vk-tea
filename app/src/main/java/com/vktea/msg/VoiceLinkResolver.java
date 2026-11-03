package com.vktea.msg;

import android.os.Handler;
import android.os.Looper;

import com.vktea.api.VKApi;
import com.vktea.auth.Session;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Refreshes one document link on explicit play; never logs responses or credentials. */
final class VoiceLinkResolver {
    enum Kind { FOUND, UNAVAILABLE, FAILED }
    static final class Result {
        final Kind kind;
        final String url;
        Result(Kind kind, String url) { this.kind = kind; this.url = url; }
    }
    interface Completion { void complete(Result result); }
    interface Handle { void cancel(); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder()
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .addInterceptor(VoiceLinkResolver::proceedSafely)
            // OkHttp may retry 503 responses with Retry-After: 0 independently of connection retries.
            .addNetworkInterceptor(chain -> {
                Response response = chain.proceed(chain.request());
                return response.code() == 503
                        ? response.newBuilder().header("Retry-After", "2147483647").build() : response;
            })
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).build();

    /** Convert platform permission/runtime failures to the normal sanitized transport failure. */
    static Response proceedSafely(okhttp3.Interceptor.Chain chain) throws java.io.IOException {
        try {
            return chain.proceed(chain.request());
        } catch (RuntimeException failure) {
            // Exception details can contain private addresses or credentials.
            throw new java.io.IOException("Voice link request failed");
        }
    }

    Handle resolve(ChatAttachment attachment, Session.Snapshot session, Completion completion) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        // Порядок по алфавиту: sig считается от этой же последовательности
        fields.put("access_token", session.token);
        fields.put("docs", attachment.ownerId + "_" + attachment.id
                + (attachment.accessKey.isEmpty() ? "" : "_" + attachment.accessKey));
        fields.put("https", "1");
        fields.put("lang", Locale.getDefault().getLanguage());
        fields.put("v", "5.82");
        if (session.secret != null && !session.secret.isEmpty()) fields.put("sig", signature(fields, session.secret));
        FormBody.Builder form = new FormBody.Builder();
        for (Map.Entry<String, String> field : fields.entrySet()) form.add(field.getKey(), field.getValue());
        Request request = new Request.Builder().url("https://api.vk.com/method/docs.getById")
                .header("User-Agent", VKApi.USER_AGENT).post(form.build()).build();
        Call call = client.newCall(request);
        call.enqueue(new Callback() {
            @Override public void onFailure(Call ignored, java.io.IOException error) {
                deliver(call, completion, new Result(Kind.FAILED, ""));
            }
            @Override public void onResponse(Call ignored, Response response) {
                Result result;
                try (Response body = response) {
                    if (!body.isSuccessful() || body.body() == null) throw new IllegalStateException();
                    if (body.body().source().request(262145) && body.body().source().buffer().size() > 262144)
                        throw new IllegalStateException();
                    result = parse(new JSONObject(body.body().string()), attachment);
                } catch (Exception failure) {
                    result = new Result(Kind.FAILED, "");
                }
                deliver(call, completion, result);
            }
        });
        return call::cancel;
    }

    private void deliver(Call call, Completion completion, Result result) {
        main.post(() -> { if (!call.isCanceled()) completion.complete(result); });
    }

    static Result parse(JSONObject root, ChatAttachment attachment) {
        if (!root.has("response")) return new Result(Kind.FAILED, "");
        JSONArray documents = root.optJSONArray("response");
        if (documents == null || documents.length() != 1) return new Result(Kind.UNAVAILABLE, "");
        JSONObject doc = documents.optJSONObject(0);
        if (doc == null || doc.optLong("owner_id") != attachment.ownerId || doc.optLong("id") != attachment.id
                || restricted(doc)) return new Result(Kind.UNAVAILABLE, "");
        JSONObject preview = doc.optJSONObject("preview");
        JSONObject voice = preview == null ? null : preview.optJSONObject("audio_msg");
        if (voice == null || restricted(voice)) return new Result(Kind.UNAVAILABLE, "");
        String ogg = voice.optString("link_ogg", "");
        String mp3 = voice.optString("link_mp3", "");
        String url = VoiceMessagePlayer.isSafeUrl(ogg) ? ogg : VoiceMessagePlayer.isSafeUrl(mp3) ? mp3 : "";
        return url.isEmpty() ? new Result(Kind.UNAVAILABLE, "") : new Result(Kind.FOUND, url);
    }

    private static boolean restricted(JSONObject value) {
        return value.optInt("is_deleted", 0) != 0 || value.optBoolean("is_deleted", false)
                || value.optInt("deleted", 0) != 0 || value.optBoolean("deleted", false)
                || value.optInt("content_restricted", 0) != 0 || value.optBoolean("content_restricted", false);
    }

    private static String signature(LinkedHashMap<String, String> fields, String secret) {
        // VK требует параметры в sig-строке строго по алфавиту
        StringBuilder raw = new StringBuilder("/method/docs.getById?");
        for (Map.Entry<String, String> field : new java.util.TreeMap<>(fields).entrySet()) {
            if (raw.charAt(raw.length() - 1) != '?') raw.append('&');
            raw.append(field.getKey()).append('=').append(field.getValue());
        }
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(raw.append(secret).toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (Exception impossible) { throw new AssertionError(impossible); }
    }
}
