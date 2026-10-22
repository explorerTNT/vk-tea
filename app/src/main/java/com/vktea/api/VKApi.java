package com.vktea.api;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.vktea.auth.Session;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.Scanner;

public class VKApi {

    public interface Callback {
        void success(JSONObject response);

        void error(Exception e);
    }

    private static final String API_HOST = "api.vk.com";
    private static final String API_VERSION = "5.199";
    private static final String OFFICIAL_APP_VERSION = "8.185-55039";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

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
        new Thread(() -> {
            try {
                StringBuilder url = new StringBuilder("https://")
                        .append(API_HOST).append("/method/").append(method)
                        .append("?access_token=").append(token)
                        .append("&lang=").append(Locale.getDefault().getLanguage())
                        .append("&https=1")
                        .append("&v=").append(API_VERSION);
                if (params != null && !params.isEmpty()) {
                    url.append("&").append(params);
                }
                HttpURLConnection conn = (HttpURLConnection) new URL(url.toString()).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                int code = conn.getResponseCode();
                Scanner scanner = new Scanner(code < 400 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8").useDelimiter("\\A");
                String body = scanner.hasNext() ? scanner.next() : "";
                scanner.close();
                JSONObject root = new JSONObject(body);
                if (root.has("response")) {
                    MAIN.post(() -> callback.success(root));
                } else {
                    MAIN.post(() -> callback.error(new Exception(root.optString("error_description", "api error"))));
                }
            } catch (Exception e) {
                MAIN.post(() -> callback.error(e));
            }
        }).start();
    }
}
