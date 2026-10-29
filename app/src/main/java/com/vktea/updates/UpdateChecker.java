package com.vktea.updates;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.vktea.BuildConfig;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Проверка новых релизов на GitHub (explorerTNT/vk-tea). */
public class UpdateChecker {

    public static final String REPO_API = "https://api.github.com/repos/explorerTNT/vk-tea/releases/latest";

    public interface Callback {
        void onUpdateAvailable(String version, String apkUrl, String notes);
        void onUpToDate();
        void onError(Exception e);
    }

    public static void check(Context context, Callback callback) {
        // stage-сборка (com.vktea.stage) не обновляется продовым APK
        if (BuildConfig.APPLICATION_ID.endsWith(".stage")) {
            callback.onUpToDate();
            return;
        }
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url(REPO_API)
                .header("Accept", "application/vnd.github+json")
                .build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                post(() -> callback.onError(e));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (response.code() == 404) {
                        // Релизов ещё не публиковали - это не ошибка
                        post(() -> callback.onUpToDate());
                        return;
                    }
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("GitHub API HTTP " + response.code());
                    }
                    JSONObject json = new JSONObject(response.body().string());
                    String tag = json.optString("tag_name", "");
                    String version = tag.startsWith("v") ? tag.substring(1) : tag;
                    String notes = json.optString("body", "");
                    String apkUrl = null;
                    org.json.JSONArray assets = json.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            String name = a.optString("name", "");
                            if (name.endsWith(".apk")) {
                                apkUrl = a.optString("browser_download_url", null);
                                if (name.contains("release")) break;
                            }
                        }
                    }
                    final String url = apkUrl;
                    if (version.isEmpty() || url == null) {
                        post(() -> callback.onUpToDate());
                        return;
                    }
                    if (isNewer(version, BuildConfig.VERSION_NAME)) {
                        post(() -> callback.onUpdateAvailable(version, url, notes.trim()));
                    } else {
                        post(() -> callback.onUpToDate());
                    }
                } catch (Exception e) {
                    Log.e("VKTea", "update check failed", e);
                    post(() -> callback.onError(e));
                }
            }

            private void post(Runnable r) {
                new Handler(Looper.getMainLooper()).post(r);
            }
        });
    }

    /** true, если remote (0.5.0) новее local (0.4.97 / 0.4.97-stage). */
    static boolean isNewer(String remote, String local) {
        long[] r = parse(remote);
        long[] l = parse(local);
        for (int i = 0; i < Math.max(r.length, l.length); i++) {
            long rv = i < r.length ? r[i] : 0;
            long lv = i < l.length ? l[i] : 0;
            if (rv != lv) return rv > lv;
        }
        return false;
    }

    private static long[] parse(String version) {
        String clean = version.split("-")[0];
        String[] parts = clean.split("\\.");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Long.parseLong(parts[i].replaceAll("[^0-9]", ""));
            } catch (Exception e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
