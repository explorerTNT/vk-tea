package com.vktea.msg;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.vktea.api.VKApi;
import com.vktea.auth.Session;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** One explicit upload at a time. The system picker grants access only to the selected item. */
public final class ChatFileSender {
    static final long MAX_BYTES = 20L * 1024 * 1024;
    private final AppCompatActivity activity;
    private final long peerId;
    private final Runnable sent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .addNetworkInterceptor(chain -> {
                Response response = chain.proceed(chain.request());
                return response.code() == 503 ? response.newBuilder().header("Retry-After", "2147483647").build() : response;
            }).connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build();
    private final ActivityResultLauncher<Intent> picker;
    private volatile int generation;
    private volatile Call call;
    private boolean busy;
    private String token, secret, user;

    public ChatFileSender(AppCompatActivity activity, long peerId, Runnable sent) {
        this.activity = activity;
        this.peerId = peerId;
        this.sent = sent;
        picker = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            Intent data = result.getData();
            Uri uri = result.getResultCode() == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (uri != null && "content".equals(uri.getScheme())) confirm(uri);
        });
    }

    public void choose() {
        if (peerId == 0 || busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { picker.launch(intent); }
        catch (RuntimeException ignored) { toast("Не удалось открыть выбор файла"); }
    }

    private void confirm(Uri uri) {
        if (busy || activity.isFinishing() || activity.isDestroyed()) return;
        String name = "Файл";
        long size = -1;
        try (Cursor cursor = activity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                if (!cursor.isNull(0)) name = cursor.getString(0);
                if (!cursor.isNull(1)) size = cursor.getLong(1);
            }
        } catch (RuntimeException ignored) { }
        name = safeName(name);
        if (size > MAX_BYTES) { toast("Файл больше 20 МБ"); return; }
        String filename = name;
        String mime;
        try { mime = activity.getContentResolver().getType(uri); } catch (RuntimeException ignored) { mime = null; }
        final String type = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        new AlertDialog.Builder(activity).setTitle("Отправить вложение?")
                .setMessage(filename + "\nМаксимальный размер — 20 МБ")
                .setPositiveButton("Отправить", (dialog, which) -> send(uri, filename, type))
                .setNegativeButton("Отмена", null).show();
    }

    private void send(Uri uri, String filename, String mime) {
        if (busy || peerId == 0) return;
        token = Session.getToken(activity); secret = Session.getSecret(activity); user = Session.getUserId(activity);
        if (token == null || token.isEmpty()) { toast("Требуется вход в аккаунт"); return; }
        busy = true;
        int op = ++generation;
        toast("Отправка вложения…");
        new Thread(() -> execute(op, uri, filename, mime), "chat-file-upload").start();
    }

    private void execute(int op, Uri uri, String filename, String mime) {
        File file = null;
        boolean sentOk = false;
        boolean sendAttempted = false;
        try {
            file = File.createTempFile("chat-file-", ".tmp", activity.getCacheDir());
            long total;
            try (InputStream input = activity.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(file)) {
                if (input == null) throw new IllegalStateException();
                total = copyLimited(input, output, MAX_BYTES, () -> active(op));
            }
            if (total == 0 || !active(op)) throw new IllegalStateException();
            boolean photo = mime.equals("image/jpeg") || mime.equals("image/png");
            LinkedHashMap<String, String> params = new LinkedHashMap<>();
            params.put("peer_id", Long.toString(peerId));
            if (!photo) params.put("type", "doc");
            JSONObject server = api(op, photo ? "photos.getMessagesUploadServer" : "docs.getMessagesUploadServer", params)
                    .optJSONObject("response");
            HttpUrl url = HttpUrl.parse(server == null ? "" : server.optString("upload_url", ""));
            if (!VoiceMessageRecorder.allowedUpload(url)) throw new IllegalStateException();
            String partName = photo ? "photo" : "file";
            MediaType media = MediaType.parse(photo ? mime : "application/octet-stream");
            RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart(partName, filename, RequestBody.create(file, media)).build();
            JSONObject upload = executeRequest(op, new Request.Builder().url(url).post(body).build());
            params.clear();
            JSONObject item;
            if (photo) {
                String image = upload.optString("photo", ""), hash = upload.optString("hash", "");
                if (image.isEmpty() || hash.isEmpty() || !upload.has("server")) throw new IllegalStateException();
                params.put("server", upload.optString("server")); params.put("photo", image); params.put("hash", hash);
                JSONArray photos = api(op, "photos.saveMessagesPhoto", params).optJSONArray("response");
                item = photos == null ? null : photos.optJSONObject(0);
            } else {
                String uploaded = upload.optString("file", "");
                if (uploaded.isEmpty()) throw new IllegalStateException();
                params.put("file", uploaded); params.put("title", filename);
                JSONObject saved = api(op, "docs.save", params);
                JSONObject savedObject = saved.optJSONObject("response");
                JSONArray savedArray = saved.optJSONArray("response");
                item = savedObject != null ? savedObject.optJSONObject("doc")
                        : savedArray == null ? null : savedArray.optJSONObject(0);
            }
            long owner = item == null ? 0 : item.optLong("owner_id"), id = item == null ? 0 : item.optLong("id");
            if (owner == 0 || id <= 0) throw new IllegalStateException();
            String access = item.optString("access_key", "");
            String attachment = (photo ? "photo" : "doc") + owner + "_" + id + (access.isEmpty() ? "" : "_" + access);
            params.clear(); params.put("peer_id", Long.toString(peerId));
            int random = UUID.randomUUID().hashCode() & Integer.MAX_VALUE;
            params.put("random_id", Integer.toString(random == 0 ? 1 : random)); params.put("attachment", attachment);
            sendAttempted = true;
            sentOk = api(op, "messages.send", params).optLong("response") > 0;
        } catch (Exception ignored) { }
        finally {
            if (file != null) file.delete();
            final boolean success = sentOk, uncertain = sendAttempted;
            main.post(() -> {
                if (op != generation) return;
                busy = false;
                if (activity.isFinishing() || activity.isDestroyed()) return;
                toast(success ? "Вложение отправлено" : uncertain
                        ? "Не удалось подтвердить отправку. Проверьте чат перед повтором"
                        : "Не удалось загрузить вложение (максимум 20 МБ)");
                if (success && sent != null) sent.run();
            });
        }
    }

    private JSONObject api(int op, String method, LinkedHashMap<String, String> args) throws Exception {
        if (!active(op)) throw new IllegalStateException();
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("v", "5.82"); fields.put("lang", Locale.getDefault().getLanguage());
        fields.put("https", "1"); fields.put("access_token", token); fields.putAll(args);
        if (secret != null && !secret.isEmpty()) fields.put("sig", signature(method, fields, secret));
        FormBody.Builder form = new FormBody.Builder();
        for (Map.Entry<String, String> field : fields.entrySet()) form.add(field.getKey(), field.getValue());
        JSONObject root = executeRequest(op, new Request.Builder().url("https://api.vk.com/method/" + method)
                .header("User-Agent", VKApi.USER_AGENT).post(form.build()).build());
        if (!root.has("response")) throw new IllegalStateException();
        return root;
    }

    private JSONObject executeRequest(int op, Request request) throws Exception {
        Call current = client.newCall(request);
        if (!registerCall(op, current)) throw new IllegalStateException();
        try {
            if (!active(op)) throw new IllegalStateException();
            try (Response response = current.execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException();
                if (response.body().source().request(65537) && response.body().source().buffer().size() > 65536)
                    throw new IllegalStateException();
                return new JSONObject(response.body().string());
            }
        } finally { clearCall(current); }
    }

    private boolean active(int op) {
        return generation == op && !activity.isFinishing() && !activity.isDestroyed()
                && Objects.equals(token, Session.getToken(activity)) && Objects.equals(secret, Session.getSecret(activity))
                && Objects.equals(user, Session.getUserId(activity));
    }

    static String safeName(String value) {
        if (value == null) return "file";
        String clean = value.replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) return "file";
        return clean.length() > 120 ? clean.substring(clean.length() - 120) : clean;
    }

    static long copyLimited(InputStream input, OutputStream output, long limit, BooleanSupplier active) throws Exception {
        long total = 0;
        byte[] buffer = new byte[16384];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (!active.getAsBoolean() || count > limit - total) throw new IllegalStateException();
            total += count;
            output.write(buffer, 0, count);
        }
        return total;
    }

    private static String signature(String method, LinkedHashMap<String, String> fields, String secret) throws Exception {
        StringBuilder source = new StringBuilder("/method/").append(method).append('?');
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (source.charAt(source.length() - 1) != '?') source.append('&');
            source.append(field.getKey()).append('=').append(field.getValue());
        }
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("MD5").digest(source.append(secret).toString().getBytes(StandardCharsets.UTF_8))) {
            result.append(Character.forDigit((value >>> 4) & 15, 16)); result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    private synchronized boolean registerCall(int op, Call current) {
        if (!active(op)) return false;
        call = current;
        return true;
    }

    private synchronized void clearCall(Call current) { if (call == current) call = null; }

    public void close() {
        Call current;
        synchronized (this) { generation++; current = call; call = null; }
        busy = false;
        if (current != null) current.cancel();
    }
    private void toast(String value) { Toast.makeText(activity, value, Toast.LENGTH_SHORT).show(); }
}
