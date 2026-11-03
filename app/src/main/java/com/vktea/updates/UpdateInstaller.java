package com.vktea.updates;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.vktea.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Скачивает APK из релиза (с проверкой целостности) и запускает системный установщик. */
public class UpdateInstaller {

    public static void downloadAndInstall(Activity activity, String apkUrl, long expectedSize) {
        Context context = activity.getApplicationContext();
        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle(R.string.update_downloading)
                .setMessage("0%")
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> d.dismiss())
                .create();
        progress.show();

        File dir = new File(context.getCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            progress.dismiss();
            Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        // чистим старые обрезки, качаем в уникальное имя (защита от параллельных загрузок)
        File[] stale = dir.listFiles();
        if (stale != null) for (File f : stale) f.delete();
        final File apk = new File(dir, "vk-tea-update-" + System.currentTimeMillis() + ".apk");

        new Thread(() -> {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build();
            try (Response response = client.newCall(new Request.Builder().url(apkUrl).build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("HTTP " + response.code());
                }
                long total = response.body().contentLength();
                InputStream in = response.body().byteStream();
                FileOutputStream out = new FileOutputStream(apk);
                byte[] buf = new byte[16384];
                long done = 0;
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    done += read;
                    if (total > 0) {
                        runProgress(progress, (int) (done * 100 / total) + "%");
                    }
                }
                out.flush();
                out.close();
                in.close();
                // Целостность: обрыв соединения без исключения даёт короткий файл -
                // установщик тогда падает с "problem parsing the package"
                if ((total > 0 && done < total)
                        || (expectedSize > 0 && apk.length() != expectedSize)) {
                    Log.e("VKTea", "update size mismatch: got " + apk.length()
                            + ", expected " + (expectedSize > 0 ? expectedSize : total));
                    apk.delete();
                    runProgress(progress, null);
                    runFailed(activity);
                    return;
                }
                runProgress(progress, null);
                runInstall(activity, apk);
            } catch (Exception e) {
                Log.e("VKTea", "update download failed", e);
                apk.delete();
                runProgress(progress, null);
                runFailed(activity);
            }
        }, "update-download").start();
    }

    private static void runFailed(Activity activity) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_SHORT).show());
    }

    private static void runProgress(AlertDialog dialog, String text) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (dialog.isShowing()) {
                if (text != null) dialog.setMessage(text);
                else dialog.dismiss();
            }
        });
    }

    private static void runInstall(Activity activity, File apk) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (apk == null || !apk.exists()) {
                Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".update-files", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(intent);
            } catch (Exception e) {
                Log.e("VKTea", "installer start failed", e);
                Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
