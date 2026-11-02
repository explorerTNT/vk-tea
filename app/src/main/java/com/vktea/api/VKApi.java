package com.vktea.api;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.vktea.auth.Session;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;

public class VKApi {

    public interface Callback {
        void success(JSONObject response);

        void error(Exception e);
    }

    /** The request belonged to a session that is no longer current. */
    public static final class SessionChangedException extends Exception {
        public SessionChangedException() { super("Session changed"); }
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
        get(context, method, params, callback, CLIENT);
    }

    /** Package-private transport injection is used only by offline synthetic tests. */
    static void get(Context context, String method, String params, Callback callback,
                    okhttp3.OkHttpClient client) {
        Session.Snapshot snapshot = Session.snapshot(context);
        if (snapshot.token == null || snapshot.token.isEmpty()) {
            dispatch(snapshot.generation, callback, null, new Exception("Authentication required"));
            return;
        }
        new Thread(() -> {
            try {
                if (snapshot.generation != Session.generation()) {
                    dispatch(snapshot.generation, callback, null, new SessionChangedException());
                    return;
                }
                String query = "v=" + API_VERSION
                        + "&lang=" + Locale.getDefault().getLanguage()
                        + "&https=1"
                        + "&access_token=" + snapshot.token
                        + (params == null || params.isEmpty() ? "" : "&" + params);
                String url;
                if (snapshot.secret != null && !snapshot.secret.isEmpty()) {
                    String sigSource = "/method/" + method + "?" + query + snapshot.secret;
                    url = "https://" + API_HOST + "/method/" + method + "?" + query + "&sig=" + md5(sigSource);
                } else {
                    url = "https://" + API_HOST + "/method/" + method + "?" + query;
                }
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .build();
                if (snapshot.generation != Session.generation()) {
                    dispatch(snapshot.generation, callback, null, new SessionChangedException());
                    return;
                }
                try (okhttp3.Response response = client.newCall(request).execute()) {
                    String body = response.body() != null ? response.body().string() : "";
                    JSONObject root = new JSONObject(body);
                    if (root.has("response")) {
                        dispatch(snapshot.generation, callback, root, null);
                    } else {
                        // Responses may echo request parameters including credentials.
                        android.util.Log.e("VKTea", "api error " + method);
                        com.vktea.util.AppLog.log("api", method + ": api error");
                        dispatch(snapshot.generation, callback, null, new Exception("VK API error"));
                    }
                }
            } catch (Exception e) {
                // Transport exception text can include the complete authenticated URL.
                android.util.Log.e("VKTea", "api fail " + method);
                com.vktea.util.AppLog.log("api", method + " fail: " + e.getClass().getSimpleName());
                dispatch(snapshot.generation, callback, null, safeFailure(e));
            }
        }).start();
    }

    private static Exception safeFailure(Exception error) {
        // Preserve the category used by LongPoll's bounded retry, never transport details.
        if (error instanceof IOException) return new IOException("Network request failed");
        if (error instanceof JSONException) return new JSONException("Invalid VK response");
        return new Exception("VK request failed");
    }

    private static void dispatch(long generation, Callback callback, JSONObject response, Exception error) {
        MAIN.post(() -> {
            if (generation != Session.generation()) callback.error(new SessionChangedException());
            else if (error != null) callback.error(error);
            else callback.success(response);
        });
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
