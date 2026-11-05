package com.vktea.feed;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.vktea.api.VKApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Публикация записи на стену: текст и фотографии.
 * Цепочка VK: photos.getWallUploadServer -> multipart upload -> photos.saveWallPhoto -> wall.post.
 */
public final class WallPostPublisher {

    public interface Callback {
        void onPublished(long postId);
        void onError(String message);
    }

    private static final long MAX_PHOTO_BYTES = 20L * 1024 * 1024;
    private final Context context;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    public WallPostPublisher(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Публикует запись: сначала грузит фото (если есть), затем wall.post. */
    public void publish(String message, List<Uri> photos, Callback callback) {
        new Thread(() -> {
            try {
                List<String> attachments = new ArrayList<>();
                for (Uri photo : photos) {
                    String attachment = uploadWallPhoto(photo);
                    if (attachment != null) attachments.add(attachment);
                }
                if (message.trim().isEmpty() && attachments.isEmpty()) {
                    deliverError(callback, "Пустая запись");
                    return;
                }
                post(message, attachments, callback);
            } catch (Exception e) {
                deliverError(callback, e.getMessage() == null ? "Не удалось опубликовать" : e.getMessage());
            }
        }, "wall-post").start();
    }

    private String uploadWallPhoto(Uri photo) throws Exception {
        JSONObject server = request("photos.getWallUploadServer", "");
        String uploadUrl = server.getJSONObject("response").optString("upload_url", "");
        if (uploadUrl.isEmpty()) throw new IllegalStateException("Нет адреса загрузки");

        byte[] bytes = read(photo);
        if (bytes.length > MAX_PHOTO_BYTES) throw new IllegalStateException("Фото больше 20 МБ");
        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("photo", "photo.jpg",
                        RequestBody.create(bytes, MediaType.parse("image/*")))
                .build();
        String uploaded;
        try (Response response = client.newCall(new Request.Builder()
                .url(uploadUrl).post(body).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("Загрузка не удалась");
            }
            uploaded = response.body().string();
        }
        JSONObject result = new JSONObject(uploaded);
        JSONObject saved = request("photos.saveWallPhoto",
                "server=" + result.optString("server")
                        + "&photo=" + Uri.encode(result.optString("photo"))
                        + "&hash=" + result.optString("hash"));
        JSONArray items = saved.optJSONArray("response");
        if (items == null || items.length() == 0) throw new IllegalStateException("Фото не сохранено");
        JSONObject item = items.getJSONObject(0);
        return "photo" + item.optLong("owner_id") + "_" + item.optLong("id");
    }

    private void post(String message, List<String> attachments, Callback callback) throws Exception {
        StringBuilder params = new StringBuilder("message=").append(Uri.encode(message));
        if (!attachments.isEmpty()) {
            params.append("&attachments=").append(String.join(",", attachments));
        }
        JSONObject response = request("wall.post", params.toString());
        long postId = response.optJSONObject("response") == null ? 0
                : response.getJSONObject("response").optLong("post_id", 0);
        new Handler(Looper.getMainLooper()).post(() -> callback.onPublished(postId));
    }

    /** Синхронный вызов VKApi: публикация идёт в своём потоке. */
    private JSONObject request(String method, String params) throws Exception {
        final Object lock = new Object();
        final JSONObject[] result = new JSONObject[1];
        final Exception[] failure = new Exception[1];
        VKApi.get(context, method, params, new VKApi.Callback() {
            @Override public void success(JSONObject response) {
                synchronized (lock) { result[0] = response; lock.notifyAll(); }
            }
            @Override public void error(Exception e) {
                synchronized (lock) { failure[0] = e; lock.notifyAll(); }
            }
        });
        synchronized (lock) {
            while (result[0] == null && failure[0] == null) lock.wait(30000);
        }
        if (failure[0] != null) throw failure[0];
        if (result[0] == null) throw new IllegalStateException("Таймаут запроса");
        JSONObject error = result[0].optJSONObject("error");
        if (error != null) throw new IllegalStateException(error.optString("error_msg", "Ошибка VK"));
        return result[0];
    }

    private byte[] read(Uri uri) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("Файл недоступен");
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_PHOTO_BYTES) throw new IllegalStateException("Фото больше 20 МБ");
            }
            return out.toByteArray();
        }
    }

    private void deliverError(Callback callback, String message) {
        new Handler(Looper.getMainLooper()).post(() -> callback.onError(message));
    }
}
