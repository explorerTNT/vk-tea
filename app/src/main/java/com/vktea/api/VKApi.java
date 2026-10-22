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
        String token = Session.getToken(context);
        String secret = Session.getSecret(context);
        new Thread(() -> {
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
                okhttp3.Response response = CLIENT.newCall(request).execute();
                String body = response.body() != null ? response.body().string() : "";
                JSONObject root = new JSONObject(body);
                if (root.has("response")) {
                    MAIN.post(() -> callback.success(root));
                } else {
                    android.util.Log.e("VKTea", "api error " + method + ": " + root);
                    MAIN.post(() -> callback.error(new Exception(root.optString("error_description", "api error"))));
                }
            } catch (Exception e) {
                android.util.Log.e("VKTea", "api fail " + method, e);
                MAIN.post(() -> callback.error(e));
            }
        }).start();
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
