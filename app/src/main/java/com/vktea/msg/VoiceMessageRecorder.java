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
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
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
    enum SendStage { SERVER, UPLOAD, SAVE, SEND }
    static final class FailureStatus extends Exception {
        final boolean vk;
        final int code;
        FailureStatus(boolean vk, int code) { this.vk = vk; this.code = code; }
    }

    static String safeStatus(Exception failure) {
        if (!(failure instanceof FailureStatus)) return "";
        FailureStatus status = (FailureStatus) failure;
        if (status.code <= 0 || status.code > 9999 || (!status.vk && (status.code < 100 || status.code > 599))) return "";
        return " (код " + (status.vk ? "VK " : "HTTP ") + status.code + ")";
    }
    private final AppCompatActivity activity;
    private final long peerId;
    private final Runnable sent;
    private final PreviewFactory previewFactory;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String> permission;
    private final OkHttpClient client;

    private static OkHttpClient defaultClient() {
        return new OkHttpClient.Builder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .addNetworkInterceptor(chain -> {
                Response response = chain.proceed(chain.request());
                return response.code() == 503 ? response.newBuilder().header("Retry-After", "2147483647").build() : response;
            })
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS).build();
    }
    private MediaRecorder recorder;
    private final VoiceWaveform waveform = new VoiceWaveform();
    private Runnable waveformSampler;
    private volatile String recordedWaveform;
    private Preview preview;
    private AlertDialog dialog;
    private volatile File file;
    private Runnable recordingTimeout;
    private volatile Call upload;
    private long started;
    private volatile int generation;
    private boolean sending;
    @Nullable private SendStage sendingStage;
    private String token, secret, user;
    private long sessionGeneration;

    /** Construct in onCreate, before the activity reaches STARTED. */
    public VoiceMessageRecorder(AppCompatActivity activity, long peerId, Runnable sent) {
        this(activity, peerId, sent, defaultClient());
    }

    VoiceMessageRecorder(AppCompatActivity activity, long peerId, Runnable sent, OkHttpClient client) {
        this(activity, peerId, sent, client, () -> new ExoPreview(activity));
    }

    VoiceMessageRecorder(AppCompatActivity activity, long peerId, Runnable sent, OkHttpClient client, PreviewFactory previewFactory) {
        this.client = client;
        this.activity = activity;
        this.peerId = peerId;
        this.sent = sent;
        this.previewFactory = previewFactory;
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
            waveform.clear();
            recordedWaveform = null;
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
            created.prepare();
            created.start();
            started = SystemClock.elapsedRealtime();
            startWaveformSampling(created);
            // Stop ourselves: setMaxDuration stops asynchronously, so calling stop() in
            // its info callback can race with the platform's own stop and discard audio.
            recordingTimeout = () -> { if (recorder == created) finishRecording(); };
            main.postDelayed(recordingTimeout, 120_000);
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
        clearRecordingTimeout();
        stopWaveformSampling();
        sampleWaveform(current);
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
        recordedWaveform = waveform.toJson();
        waveform.clear();
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
            Preview candidate = null;
            try {
                candidate = previewFactory.create();
                preview = candidate;
                review.getButton(AlertDialog.BUTTON_NEUTRAL).setText("Остановить");
                Preview playing = candidate;
                candidate.start(file, () -> finishPreview(playing, false), () -> finishPreview(playing, true));
            } catch (RuntimeException failure) {
                if (candidate == null || preview == candidate) {
                    stopPreview();
                    toast("Не удалось воспроизвести запись");
                }
            }
        }));
        review.setOnDismissListener(ignored -> stopPreview());
        dialog.show();
    }

    private void send() {
        send(null);
    }

    private void send(@Nullable String expectedUser) {
        dialog = null;
        final int operation = ++generation;
        sending = true;
        Session.Snapshot session = Session.snapshot(activity);
        token = session.token;
        secret = session.secret;
        user = session.userId;
        sessionGeneration = session.generation;
        if (expectedUser != null && !expectedUser.equals(user)) {
            sending = false;
            deleteFile();
            toast("Аккаунт изменился. Запись удалена");
            return;
        }
        if (token == null || token.isEmpty()) {
            sending = false;
            if (file != null && file.isFile()) showRetryDialog("Требуется вход в аккаунт", null);
            else { deleteFile(); toast("Запись больше недоступна"); }
            return;
        }
        toast("Отправка голосового сообщения…");
        sendingStage = SendStage.SERVER;
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("type", "audio_message"); params.put("peer_id", Long.toString(peerId));
        api(operation, SendStage.SERVER, "docs.getMessagesUploadServer", params, new VKApi.Callback() {
            @Override public void success(JSONObject response) {
                if (!active(operation)) { fail(operation, SendStage.SERVER); return; }
                JSONObject data = response.optJSONObject("response");
                String url = data == null ? "" : data.optString("upload_url", "");
                HttpUrl parsed = HttpUrl.parse(url);
                if (!allowedUpload(parsed)) { fail(operation, SendStage.SERVER); return; }
                File recorded = file;
                if (recorded == null) { fail(operation, SendStage.SERVER); return; }
                sendingStage = SendStage.UPLOAD;
                new Thread(() -> upload(operation, parsed, recorded), "voice-upload").start();
            }
            @Override public void error(Exception error) { fail(operation, SendStage.SERVER, error); }
        });
    }

    static boolean allowedUpload(HttpUrl url) {
        if (url == null || !url.isHttps() || url.port() != 443 || !url.username().isEmpty() || !url.password().isEmpty()) return false;
        String host = url.host();
        return host.equals("vk.com") || host.endsWith(".vk.com") || host.equals("vk.ru")
                || host.endsWith(".vk.ru") || host.equals("userapi.com") || host.endsWith(".userapi.com");
    }

    private void startWaveformSampling(MediaRecorder recording) {
        stopWaveformSampling();
        waveformSampler = new Runnable() {
            @Override public void run() {
                if (recorder != recording || waveformSampler != this) return;
                if (sampleWaveform(recording) && !waveform.isFull()) main.postDelayed(this, 250);
            }
        };
        main.postDelayed(waveformSampler, 250);
    }

    private void stopWaveformSampling() {
        Runnable old = waveformSampler;
        waveformSampler = null;
        if (old != null) main.removeCallbacks(old);
    }

    private boolean sampleWaveform(MediaRecorder recording) {
        try {
            waveform.add(recording.getMaxAmplitude());
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    static RequestBody uploadBody(File recorded, String recordedWaveform) {
        MultipartBody.Builder body = new MultipartBody.Builder().setType(MultipartBody.FORM);
        if (recordedWaveform != null) body.addFormDataPart("waveform", recordedWaveform);
        return body.addFormDataPart("file", "Audio Message",
                RequestBody.create(recorded, MediaType.parse("audio/ogg"))).build();
    }

    private void upload(int operation, HttpUrl url, File recorded) {
        Call call = null;
        try {
            RequestBody body = uploadBody(recorded, recordedWaveform);
            call = client.newCall(new Request.Builder().url(url)
                    .header("User-Agent", VKApi.USER_AGENT).post(body).build());
            if (!registerCall(operation, call)) { call.cancel(); return; }
            if (!active(operation)) { call.cancel(); main.post(() -> fail(operation, SendStage.UPLOAD)); return; }
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) throw new FailureStatus(false, response.code());
                if (response.body() == null) throw new IllegalStateException();
                if (response.body().source().request(65537) && response.body().source().buffer().size() > 65536)
                    throw new IllegalStateException();
                String bodyText = response.body().string();
                String uploaded = new JSONObject(bodyText).optString("file", "");
                if (uploaded.isEmpty()) throw new IllegalStateException();
                main.post(() -> save(operation, uploaded));
            }
        } catch (Exception failure) { main.post(() -> fail(operation, SendStage.UPLOAD, failure)); }
        finally { clearCall(call); if (operation != generation && recorded != file) recorded.delete(); }
    }

    private void save(int operation, String uploaded) {
        if (!active(operation)) { fail(operation, SendStage.SAVE); return; }
        sendingStage = SendStage.SAVE;
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("file", uploaded);
        api(operation, SendStage.SAVE, "docs.save", params, new VKApi.Callback() {
            @Override public void success(JSONObject result) {
                if (!active(operation)) { fail(operation, SendStage.SAVE); return; }
                JSONObject audio = savedVoice(result);
                long owner = audio == null ? 0 : audio.optLong("owner_id"), id = audio == null ? 0 : audio.optLong("id");
                if (owner == 0 || id <= 0) { fail(operation, SendStage.SAVE); return; }
                String access = audio.optString("access_key", "");
                String attachment = "doc" + owner + "_" + id + (access.isEmpty() ? "" : "_" + access);
                int random = UUID.randomUUID().hashCode() & Integer.MAX_VALUE;
                if (random == 0) random = 1;
                LinkedHashMap<String, String> params = new LinkedHashMap<>();
                params.put("peer_id", Long.toString(peerId));
                params.put("random_id", Integer.toString(random));
                params.put("attachment", attachment);
                sendingStage = SendStage.SEND;
                api(operation, SendStage.SEND, "messages.send", params, new VKApi.Callback() {
                    @Override public void success(JSONObject sentResponse) {
                        if (!active(operation)) { fail(operation, SendStage.SEND); return; }
                        if (sentResponse.optLong("response") <= 0) { fail(operation, SendStage.SEND); return; }
                        sending = false;
                        sendingStage = null;
                        deleteFile();
                        toast("Голосовое сообщение отправлено");
                        if (sent != null) sent.run();
                    }
                    @Override public void error(Exception error) { fail(operation, SendStage.SEND, error); }
                });
            }
            @Override public void error(Exception error) { fail(operation, SendStage.SAVE, error); }
        });
    }

    private boolean active(int operation) {
        Session.Snapshot session = Session.snapshot(activity);
        return operation == generation && !activity.isFinishing() && !activity.isDestroyed()
                && sessionGeneration == session.generation
                && token != null && token.equals(session.token)
                && java.util.Objects.equals(secret, session.secret)
                && java.util.Objects.equals(user, session.userId);
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
    private void api(int operation, SendStage stage, String method, LinkedHashMap<String, String> args, VKApi.Callback callback) {
        if (!active(operation)) { fail(operation, stage); return; }
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
                    if (!response.isSuccessful()) throw new FailureStatus(false, response.code());
                    if (response.body() == null) throw new IllegalStateException();
                    if (response.body().source().request(65537) && response.body().source().buffer().size() > 65536)
                        throw new IllegalStateException();
                    JSONObject root = new JSONObject(response.body().string());
                    if (!root.has("response")) {
                        JSONObject error = root.optJSONObject("error");
                        throw new FailureStatus(true, error == null ? 0 : error.optInt("error_code", 0));
                    }
                    main.post(() -> { if (active(operation)) callback.success(root); else fail(operation, stage); });
                }
            } catch (Exception failure) {
                Exception safe = failure instanceof FailureStatus ? failure : new Exception("Request failed");
                main.post(() -> { if (operation == generation) callback.error(safe); });
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

    private void fail(int operation, SendStage stage) {
        fail(operation, stage, null);
    }

    private void fail(int operation, SendStage stage, Exception failure) {
        if (operation != generation) return;
        if (!active(operation) && canRecoverSameUser()) {
            onSessionRefreshed();
            return;
        }
        sending = false;
        sendingStage = null;
        if (!active(operation)) {
            deleteFile();
            return;
        }
        if (stage == SendStage.SEND) {
            deleteFile();
            showUnconfirmed(failure);
            return;
        }
        if (file == null || !file.isFile()) {
            deleteFile();
            toast("Запись больше недоступна");
            return;
        }
        String reason;
        switch (stage) {
            case SERVER: reason = "Не удалось получить сервер загрузки"; break;
            case UPLOAD: reason = "Не удалось загрузить запись"; break;
            case SAVE: reason = "Не удалось сохранить запись во ВКонтакте"; break;
            default: throw new AssertionError(stage);
        }
        showRetryDialog(reason, failure);
    }

    private void showRetryDialog(String reason, Exception failure) {
        final String originalUser = user;
        dialog = new AlertDialog.Builder(activity)
                .setTitle("Голосовое сообщение")
                .setMessage(reason + safeStatus(failure) + ". Запись сохранена до выхода из чата.")
                .setPositiveButton("Повторить", (ignored, which) -> {
                    dialog = null;
                    Session.Snapshot current = Session.snapshot(activity);
                    if (originalUser == null || !originalUser.equals(current.userId)
                            || current.token == null || current.token.isEmpty()) {
                        deleteFile();
                        toast("Аккаунт изменился. Запись удалена");
                        return;
                    }
                    send(originalUser);
                })
                .setNegativeButton("Удалить", (ignored, which) -> { dialog = null; deleteFile(); })
                .setOnCancelListener(ignored -> { dialog = null; deleteFile(); })
                .create();
        dialog.show();
    }

    private boolean canRecoverSameUser() {
        return canRecoverSameUser(Session.snapshot(activity));
    }

    private boolean canRecoverSameUser(Session.Snapshot current) {
        return sending && !activity.isFinishing() && !activity.isDestroyed() && user != null
                && user.equals(current.userId) && current.token != null && !current.token.isEmpty();
    }

    /** Cancel an in-flight upload on a same-account token refresh, without retrying it. */
    void onSessionRefreshed() {
        Session.Snapshot current = Session.snapshot(activity);
        if (!canRecoverSameUser(current)) return;
        if (sessionGeneration == current.generation && java.util.Objects.equals(token, current.token)
                && java.util.Objects.equals(secret, current.secret)) return;
        SendStage oldStage = sendingStage;
        Call old;
        synchronized (this) { generation++; old = upload; upload = null; }
        sending = false;
        sendingStage = null;
        if (old != null) old.cancel();
        token = current.token;
        secret = current.secret;
        user = current.userId;
        sessionGeneration = current.generation;
        if (oldStage == SendStage.SEND) {
            deleteFile();
            showUnconfirmed(null);
        } else if (file != null && file.isFile()) {
            showRetryDialog("Авторизация обновилась, отправка остановлена", null);
        }
    }

    private void showUnconfirmed(Exception failure) {
        AlertDialog shown = new AlertDialog.Builder(activity)
                .setTitle("Голосовое сообщение")
                .setMessage("Не удалось подтвердить отправку" + safeStatus(failure)
                        + ". Проверьте чат перед повтором.")
                .setPositiveButton("Понятно", null)
                .create();
        dialog = shown;
        shown.setOnDismissListener(ignored -> { if (dialog == shown) dialog = null; });
        shown.show();
    }

    private void cancelRecording() {
        clearRecordingTimeout();
        stopWaveformSampling();
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
        sendingStage = null;
        if (call != null) call.cancel();
        cancelRecording();
    }

    private synchronized boolean registerCall(int operation, Call call) {
        if (operation != generation) return false;
        upload = call;
        return true;
    }

    private synchronized void clearCall(Call call) { if (upload == call) upload = null; }

    interface PreviewFactory { Preview create(); }
    interface Preview {
        void start(File file, Runnable completed, Runnable failed);
        void release();
    }

    private static final class ExoPreview implements Preview {
        private final ExoPlayer player;
        ExoPreview(AppCompatActivity activity) {
            player = new ExoPlayer.Builder(activity).build();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true);
        }
        @Override public void start(File file, Runnable completed, Runnable failed) {
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_ENDED) completed.run();
                }
                @Override public void onPlayerError(PlaybackException error) { failed.run(); }
            });
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
            player.prepare();
            player.play();
        }
        @Override public void release() { player.release(); }
    }

    private void finishPreview(Preview current, boolean failed) {
        if (preview != current) return;
        stopPreview();
        if (failed) toast("Не удалось воспроизвести запись");
    }

    private void stopPreview() {
        Preview old = preview;
        preview = null;
        if (old != null) old.release();
        if (dialog != null && dialog.isShowing() && dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setText("Прослушать");
    }

    private void clearRecordingTimeout() {
        if (recordingTimeout != null) main.removeCallbacks(recordingTimeout);
        recordingTimeout = null;
    }

    private void deleteFile() {
        File old = file;
        file = null;
        recordedWaveform = null;
        waveform.clear();
        if (old != null) old.delete();
    }
    private void toast(String value) { Toast.makeText(activity, value, Toast.LENGTH_SHORT).show(); }
}
