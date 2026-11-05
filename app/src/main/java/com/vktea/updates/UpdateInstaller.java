package com.vktea.updates;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.vktea.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Скачивает APK из релиза (с проверкой целостности) и ставит через
 * PackageInstaller.Session. Системный установщик на части ROM не читает
 * content:// от FileProvider ("problem parsing the package"), сессионная
 * установка передает байты напрямую пакетному менеджеру.
 */
public class UpdateInstaller {

    public static void downloadAndInstall(Activity activity, String apkUrl, long expectedSize) {
        lastUrl.set(apkUrl);
        lastSize = expectedSize;
        Context context = activity.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 26
                && !context.getPackageManager().canRequestPackageInstalls()) {
            requestInstallPermission(activity);
            return;
        }
        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle(R.string.update_downloading)
                .setMessage("0%")
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> d.dismiss())
                .create();
        progress.show();

        File dir = context.getExternalFilesDir("updates");
        if (dir == null) dir = new File(context.getCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            progress.dismiss();
            Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_SHORT).show();
            return;
        }
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
                OutputStream out = new FileOutputStream(apk);
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
                installWithSession(activity, apk, apkUrl, expectedSize);
            } catch (Exception e) {
                Log.e("VKTea", "update download failed", e);
                apk.delete();
                runProgress(progress, null);
                runFailed(activity);
            }
        }, "update-download").start();
    }

    private static void requestInstallPermission(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
            Toast.makeText(activity, R.string.update_grant_install, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(activity, R.string.update_grant_install, Toast.LENGTH_LONG).show();
        }
    }

    private static void installWithSession(Activity activity, File apk, String apkUrl, long expectedSize) {
        Context context = activity.getApplicationContext();
        try {
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);
            OutputStream out = session.openWrite("vk-tea-update", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            byte[] buf = new byte[16384];
            int read;
            while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
            out.flush();
            session.fsync(out);
            out.close();
            in.close();
            // Логируем вердикт сессии; при отказе уходим в DownloadManager
            android.content.IntentFilter filter = new android.content.IntentFilter("com.vktea.UPDATE_RESULT");
            android.content.BroadcastReceiver verdict = new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent data) {
                    int status = data.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                    String message = data.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    Log.e("VKTea", "update session status=" + status + " msg=" + message);
                    try { context.unregisterReceiver(this); } catch (Exception ignored) { }
                    if (status != PackageInstaller.STATUS_SUCCESS) {
                        new Handler(Looper.getMainLooper()).post(() -> downloadViaSystem(activity));
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(verdict, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(verdict, filter);
            }
            Intent result = new Intent("com.vktea.UPDATE_RESULT")
                    .setPackage(context.getPackageName());
            android.app.PendingIntent pending = android.app.PendingIntent.getBroadcast(
                    context, 1, result,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
            session.close();
        } catch (Exception e) {
            Log.e("VKTea", "session install failed", e);
            downloadViaSystem(activity);
        }
    }

    private static final java.util.concurrent.atomic.AtomicReference<String> lastUrl =
            new java.util.concurrent.atomic.AtomicReference<>();
    private static volatile long lastSize;

    /** Фолбэк: качает через DownloadManager - системная нотификация, установщик открывает сам. */
    private static void downloadViaSystem(Activity activity) {
        String url = lastUrl.get();
        if (url == null || url.isEmpty()) {
            runFailed(activity);
            return;
        }
        try {
            android.app.DownloadManager.Request request =
                    new android.app.DownloadManager.Request(Uri.parse(url))
                            .setTitle("VK Tea update")
                            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalPublicDir(
                                    android.os.Environment.DIRECTORY_DOWNLOADS, "vk-tea-update.apk")
                            .setMimeType("application/vnd.android.package-archive");
            android.app.DownloadManager manager = (android.app.DownloadManager)
                    activity.getSystemService(Context.DOWNLOAD_SERVICE);
            manager.enqueue(request);
            Toast.makeText(activity, R.string.update_downloaded_notification, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e("VKTea", "download manager fallback failed", e);
            runFailed(activity);
        }
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
}
