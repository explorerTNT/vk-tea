package com.vktea.msg;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;

import com.vktea.api.VKApi;
import com.vktea.auth.Session;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Foreground-only, explicit voice recording and sending. Temporary audio never leaves cache. */
public final class VoiceMessageRecorder {
    private final AppCompatActivity activity;
    private final long peerId;
    private final Runnable sent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String> permission;
    private final OkHttpClient client = new OkHttpClient.Builder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .addNetworkInterceptor(chain -> {
                Response response = chain.proceed(chain.request());
                return response.code() == 503 ? response.newBuilder().header("Retry-After", "2147483647").build() : response;
            })
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS).build();
    private MediaRecorder recorder;
    private ExoPlayer preview;
    private AlertDialog dialog;
    private File file;
    private volatile Call upload;
    private long started;
    private volatile int generation;
    private boolean sending;
    private String token, secret, user;

    /** Construct in onCreate, before the activity reaches STARTED. */
    public VoiceMessageRecorder(AppCompatActivity activity, long peerId, Runnable sent) {
        this.activity = activity;
        this.peerId = peerId;
        this.sent = sent;
        permission = activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted && !activity.isFinishing() && !activity.isDestroyed()) begin();
            else if (!granted) toast("Для записи нужно разрешение на микрофон");
        });
    }

    public void start() {
        if (peerId == 0 || recorder != null || dialog != null || sending) return;
        if (Build.VERSION.SDK_INT < 29) { toast("Запись голосовых доступна с Android 10"); return; }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO);
        } else begin();
    }

    private void begin() {
        if (Build.VERSION.SDK_INT < 29) return;
        if (dialog != null || recorder != null || peerId == 0) return;
        try {
            file = File.createTempFile("voice-", ".ogg", activity.getCacheDir());
            MediaRecorder created = new MediaRecorder();
            recorder = created;
            created.setAudioSource(MediaRecorder.AudioSource.MIC);
            created.setOutputFormat(MediaRecorder.OutputFormat.OGG);
            created.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
            created.setAudioChannels(1);
            created.setAudioSamplingRate(48000);
            created.setAudioEncodingBitRate(32000);
            created.setOutputFile(file.getAbsolutePath());
            created.setMaxDuration(120_000);
            created.setOnInfoListener((ignored, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) main.post(this::finishRecording);
            });
            created.prepare();
            created.start();
            started = SystemClock.elapsedRealtime();
            dialog = new AlertDialog.Builder(activity).setTitle("Запись голосового сообщения")
                    .setMessage("Запись идёт. Максимум — 2 минуты.")
                    .setPositiveButton("Остановить", (ignored, which) -> finishRecording())
                    .setNegativeButton("Отмена", (ignored, which) -> cancelRecording())
                    .setOnCancelListener(ignored -> cancelRecording()).create();
            dialog.show();
        } catch (Exception failure) {
            cancelRecording();
            toast("Не удалось начать запись");
        }
    }

    private void finishRecording() {
        MediaRecorder current = recorder;
        if (current == null) return;
        recorder = null;
        boolean valid = SystemClock.elapsedRealtime() - started >= 500;
        try { current.stop(); } catch (RuntimeException failure) { valid = false; }
        current.release();
        AlertDialog old = dialog;
        dialog = null;
        if (old != null) old.dismiss();
        if (!valid || file == null || file.length() == 0) {
            deleteFile();
            toast("Запись слишком короткая или не удалась");
            return;
        }
        dialog = new AlertDialog.Builder(activity).setTitle("Голосовое сообщение")
                .setMessage("Отправить запись?")
                .setPositiveButton("Отправить", (ignored, which) -> send())
                .setNeutralButton("Прослушать", null)
                .setNegativeButton("Удалить", (ignored, which) -> { dialog = null; deleteFile(); })
                .setOnCancelListener(ignored -> { dialog = null; deleteFile(); }).create();
        AlertDialog review = dialog;
        review.setOnShowListener(ignored -> review.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            if (preview != null) { stopPreview(); return; }
            if (file == null) return;
            preview = new ExoPlayer.Builder(activity).build();
            preview.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true);
            preview.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
            preview.prepare();
            preview.play();
        }));
        review.setOnDismissListener(ignored -> stopPreview());
        dialog.show();
    }

    private void send() {
        dialog = null;
        final int operation = ++generation;
        sending = true;
        token = Session.getToken(activity);
        secret = Session.getSecret(activity);
        user = Session.getUserId(activity);
        if (token == null || token.isEmpty()) { sending = false; deleteFile(); toast("Требуется вход в аккаунт"); return; }
        toast("Отправка голосового сообщения…");
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("type", "audio_message"); params.put("peer_id", Long.toString(peerId));
        api(operation, "docs.getMessagesUploadServer", params, new VKApi.Callback() {
            @Override public void success(JSONObject response) {
                if (!active(operation)) return;
                JSONObject data = response.optJSONObject("response");
                String url = data == null ? "" : data.optString("upload_url", "");
                HttpUrl parsed = HttpUrl.parse(url);
                if (!allowedUpload(parsed)) { fail(operation); return; }
                File recorded = file;
                if (recorded == null) { fail(operation); return; }
                new Thread(() -> upload(operation, parsed, recorded), "voice-upload").start();
            }
            @Override public void error(Exception ignored) { fail(operation); }
        });
    }

    static boolean allowedUpload(HttpUrl url) {
        if (url == null || !url.isHttps() || url.port() != 443 || !url.username().isEmpty() || !url.password().isEmpty()) return false;
        String host = url.host();
        return host.equals("vk.com") || host.endsWith(".vk.com") || host.equals("vk.ru")
                || host.endsWith(".vk.ru") || host.equals("userapi.com") || host.endsWith(".userapi.com");
    }

    private void upload(int operation, HttpUrl url, File recorded) {
        Call call = null;
        try {
            RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "voice.ogg", RequestBody.create(recorded, MediaType.parse("audio/ogg"))).build();
            call = client.newCall(new Request.Builder().url(url).post(body).build());
            if (!registerCall(operation, call)) { call.cancel(); return; }
            if (!active(operation)) { call.cancel(); main.post(() -> fail(operation)); return; }
            try (Response response = call.execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException();
                if (response.body().source().request(65537) && response.body().source().buffer().size() > 65536)
                    throw new IllegalStateException();
                String bodyText = response.body().string();
                String uploaded = new JSONObject(bodyText).optString("file", "");
                if (uploaded.isEmpty()) throw new IllegalStateException();
                main.post(() -> save(operation, uploaded));
            }
        } catch (Exception ignored) { main.post(() -> fail(operation)); }
        finally { clearCall(call); if (operation != generation) recorded.delete(); }
    }

    private void save(int operation, String uploaded) {
        if (!active(operation)) { fail(operation); return; }
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("file", uploaded);
        api(operation, "docs.save", params, new VKApi.Callback() {
            @Override public void success(JSONObject result) {
                if (!active(operation)) return;
                JSONObject audio = savedVoice(result);
                long owner = audio == null ? 0 : audio.optLong("owner_id"), id = audio == null ? 0 : audio.optLong("id");
                if (owner == 0 || id <= 0) { fail(operation); return; }
                String access = audio.optString("access_key", "");
                String attachment = "doc" + owner + "_" + id + (access.isEmpty() ? "" : "_" + access);
                int random = UUID.randomUUID().hashCode() & Integer.MAX_VALUE;
                if (random == 0) random = 1;
                LinkedHashMap<String, String> params = new LinkedHashMap<>();
                params.put("peer_id", Long.toString(peerId));
                params.put("random_id", Integer.toString(random));
                params.put("attachment", attachment);
                api(operation, "messages.send", params, new VKApi.Callback() {
                    @Override public void success(JSONObject sentResponse) {
                        if (!active(operation)) return;
                        if (sentResponse.optLong("response") <= 0) { fail(operation); return; }
                        sending = false;
                        deleteFile();
                        toast("Голосовое сообщение отправлено");
                        if (sent != null) sent.run();
                    }
                    @Override public void error(Exception ignored) { fail(operation); }
                });
            }
            @Override public void error(Exception ignored) { fail(operation); }
        });
    }

    private boolean active(int operation) {
        return operation == generation && !activity.isFinishing() && !activity.isDestroyed()
                && token != null && token.equals(Session.getToken(activity))
                && java.util.Objects.equals(secret, Session.getSecret(activity))
                && java.util.Objects.equals(user, Session.getUserId(activity));
    }

    /** The older Coffee API also returned an array of document objects. */
    static JSONObject savedVoice(JSONObject root) {
        JSONObject response = root == null ? null : root.optJSONObject("response");
        if (response != null) return response.optJSONObject("audio_message");
        JSONArray legacy = root == null ? null : root.optJSONArray("response");
        JSONObject document = legacy == null ? null : legacy.optJSONObject(0);
        JSONObject preview = document == null ? null : document.optJSONObject("preview");
        return preview != null && preview.optJSONObject("audio_msg") != null ? document : null;
    }

    /** All stages use one session snapshot and no redirects, retries, or response logging. */
    private void api(int operation, String method, LinkedHashMap<String, String> args, VKApi.Callback callback) {
        if (!active(operation)) { fail(operation); return; }
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("v", "5.82"); fields.put("lang", Locale.getDefault().getLanguage());
        fields.put("https", "1"); fields.put("access_token", token); fields.putAll(args);
        if (secret != null && !secret.isEmpty()) fields.put("sig", signature(method, fields, secret));
        FormBody.Builder form = new FormBody.Builder();
        for (Map.Entry<String, String> field : fields.entrySet()) form.add(field.getKey(), field.getValue());
        Request request = new Request.Builder().url("https://api.vk.com/method/" + method)
                .header("User-Agent", VKApi.USER_AGENT).post(form.build()).build();
        new Thread(() -> {
            Call call = client.newCall(request);
            if (!registerCall(operation, call)) { call.cancel(); return; }
            try {
                if (!active(operation)) { call.cancel(); throw new IllegalStateException(); }
                try (Response response = call.execute()) {
                    if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException();
                    if (response.body().source().request(65537) && response.body().source().buffer().size() > 65536)
                        throw new IllegalStateException();
                    JSONObject root = new JSONObject(response.body().string());
                    if (!root.has("response")) throw new IllegalStateException();
                    main.post(() -> { if (active(operation)) callback.success(root); else fail(operation); });
                }
            } catch (Exception ignored) {
                main.post(() -> { if (operation == generation) callback.error(new Exception("Request failed")); });
            } finally { clearCall(call); }
        }, "voice-api").start();
    }

    private static String signature(String method, LinkedHashMap<String, String> fields, String secret) {
        StringBuilder source = new StringBuilder("/method/").append(method).append('?');
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (source.charAt(source.length() - 1) != '?') source.append('&');
            source.append(field.getKey()).append('=').append(field.getValue());
        }
        try {
            StringBuilder result = new StringBuilder();
            for (byte value : MessageDigest.getInstance("MD5").digest(source.append(secret).toString().getBytes(StandardCharsets.UTF_8))) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (Exception impossible) { throw new AssertionError(impossible); }
    }

    private void fail(int operation) {
        if (operation != generation) return;
        sending = false;
        deleteFile();
        if (!activity.isFinishing() && !activity.isDestroyed()) toast("Не удалось подтвердить отправку голосового сообщения");
    }

    private void cancelRecording() {
        stopPreview();
        MediaRecorder current = recorder;
        recorder = null;
        if (current != null) { try { current.stop(); } catch (RuntimeException ignored) { } current.release(); }
        AlertDialog old = dialog;
        dialog = null;
        if (old != null) old.dismiss();
        deleteFile();
    }

    /** Call from onStop; never keep the microphone or a private recording in the background. */
    public void close() {
        Call call;
        synchronized (this) { generation++; call = upload; upload = null; }
        sending = false;
        if (call != null) call.cancel();
        cancelRecording();
    }

    private synchronized boolean registerCall(int operation, Call call) {
        if (operation != generation) return false;
        upload = call;
        return true;
    }

    private synchronized void clearCall(Call call) { if (upload == call) upload = null; }

    private void stopPreview() { if (preview != null) { preview.release(); preview = null; } }

    private void deleteFile() { File old = file; file = null; if (old != null) old.delete(); }
    private void toast(String value) { Toast.makeText(activity, value, Toast.LENGTH_SHORT).show(); }
}
