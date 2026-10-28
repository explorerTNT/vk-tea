package com.vktea.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import com.vktea.BuildConfig;
import com.vktea.auth.AuthLog;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BugReporter {

    public static void report(Context context) {
        String body = buildReport(context);
        if (BuildConfig.GH_TOKEN != null && !BuildConfig.GH_TOKEN.isEmpty()) {
            sendToGithub(context, body);
        } else {
            openBrowserFallback(context, body);
        }
    }

    private static String buildReport(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Автоматический отчёт из приложения\n\n");
        sb.append("| | |\n| --- | --- |\n");
        sb.append("| Время | ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append(" |\n");
        sb.append("| Версия | ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(") |\n");
        sb.append("| Android | ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(") |\n");
        sb.append("| Устройство | ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append(" |\n");
        try {
            sb.append("| Сессия | user ").append(com.vktea.auth.Session.getUserId(context)).append(" |\n");
        } catch (Exception ignored) {
        }
        sb.append("\n## Лог авторизации\n```\n").append(AuthLog.dump()).append("```\n");
        sb.append("\n## Последние события API\n```\n").append(com.vktea.util.AppLog.dump()).append("```\n");
        return sb.toString();
    }

    private static void sendToGithub(Context context, String body) {
        new Thread(() -> {
            try {
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                JSONObject payload = new JSONObject();
                payload.put("title", "Отчёт из приложения " + new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date()));
                payload.put("body", body);
                payload.put("labels", new org.json.JSONArray().put("from-app"));
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url("https://api.github.com/repos/explorerTNT/vk-tea/issues")
                        .header("Authorization", "Bearer " + BuildConfig.GH_TOKEN)
                        .header("Accept", "application/vnd.github+json")
                        .post(okhttp3.RequestBody.create(payload.toString(),
                                okhttp3.MediaType.parse("application/json")))
                        .build();
                okhttp3.Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    String url = new JSONObject(responseBody).optString("html_url", "");
                    toast(context, "Тикет создан: " + url);
                } else {
                    openBrowserFallback(context, body);
                }
            } catch (Exception e) {
                openBrowserFallback(context, body);
            }
        }).start();
    }

    private static void openBrowserFallback(Context context, String body) {
        try {
            String url = "https://github.com/explorerTNT/vk-tea/issues/new"
                    + "?title=" + URLEncoder.encode("Отчёт из приложения", "UTF-8")
                    + "&body=" + URLEncoder.encode(body.length() > 5000 ? body.substring(0, 5000) : body, "UTF-8");
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast(context, "Не удалось открыть браузер");
        }
    }

    private static void toast(Context context, String message) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }
}
