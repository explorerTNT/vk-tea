package com.vktea.api;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.vktea.auth.Session;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Scanner;

public class VKApi {

    public interface Callback {
        void success(JSONObject response);

        void error(Exception e);
    }

    private static final String API_HOST = "api.vk.com";
    private static final String API_VERSION = "5.82";
    private static final String OFFICIAL_APP_VERSION = "8.185-55039";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final okhttp3.OkHttpClient CLIENT = new okhttp3.OkHttpClient();
    // Guarded by Session.class, the same lock used when credentials change.
    private static final java.util.Set<okhttp3.Call> ACTIVE_CALLS = new java.util.HashSet<>();

    public static final String USER_AGENT = String.format(Locale.US,
            "VKAndroidApp/%s (Android %s; SDK %d; %s; %s %s; %s)",
            OFFICIAL_APP_VERSION,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
            Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a",
            Build.MANUFACTURER,
            Build.MODEL,
            Locale.getDefault().getLanguage());

    public static void get(Context context, String method, String params, Callback callback) {
        Session.Snapshot snapshot = Session.snapshot(context);
        String token = snapshot.token;
        String secret = snapshot.secret;
        long generation = snapshot.generation;
        new Thread(() -> {
            if (generation != Session.generation()) return;
            okhttp3.Call call = null;
            try {
                String query = "v=" + API_VERSION
                        + "&lang=" + Locale.getDefault().getLanguage()
                        + "&https=1"
                        + "&access_token=" + token
                        + (params == null || params.isEmpty() ? "" : "&" + params);
                String url;
                if (secret != null && !secret.isEmpty()) {
                    String sigSource = "/method/" + method + "?" + query + secret;
                    url = "https://" + API_HOST + "/method/" + method + "?" + query + "&sig=" + md5(sigSource);
                } else {
                    url = "https://" + API_HOST + "/method/" + method + "?" + query;
                }
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .build();
                call = CLIENT.newCall(request);
                if (!registerCall(generation, call)) return;
                okhttp3.Response response = call.execute();
                String body = response.body() != null ? response.body().string() : "";
                JSONObject root = new JSONObject(body);
                if (root.has("response")) {
                    postIfCurrent(generation, () -> callback.success(root));
                } else {
                    // API errors may contain request parameters including credentials.
                    android.util.Log.e("VKTea", "api error " + method);
                    postIfCurrent(generation, () -> callback.error(new Exception(root.optString("error_description", "api error"))));
                }
            } catch (Exception e) {
                // Transport exception text can include the complete authenticated URL.
                android.util.Log.e("VKTea", "api fail " + method);
                postIfCurrent(generation, () -> callback.error(e));
            } finally {
                if (call != null) unregisterCall(call);
            }
        }).start();
    }

    public static boolean registerCall(long generation, okhttp3.Call call) {
        synchronized (Session.class) {
            if (generation != Session.generation()) {
                call.cancel();
                return false;
            }
            ACTIVE_CALLS.add(call);
            return true;
        }
    }

    public static void unregisterCall(okhttp3.Call call) {
        synchronized (Session.class) { ACTIVE_CALLS.remove(call); }
    }

    /** Best-effort cancellation: a server may already have received the request. */
    public static void cancelSessionCalls() {
        synchronized (Session.class) {
            for (okhttp3.Call call : ACTIVE_CALLS) call.cancel();
            ACTIVE_CALLS.clear();
        }
    }

    static void postIfCurrent(long generation, Runnable callback) {
        MAIN.post(() -> { if (generation == Session.generation()) callback.run(); });
    }

    private static String md5(String s) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] hash = digest.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
