package com.vktea.audio;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.UnstableApi;

import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import com.vktea.audio.catalog.VkAudioError;
import com.vktea.audio.catalog.VkAudioRepository;
import com.vktea.audio.catalog.VkAudioTrack;

import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Explicit, bounded, account-scoped offline storage for complete direct audio files. */
@UnstableApi
public final class AudioOfflineCache {
    private static final long MAX_TRACK = 128L * 1024 * 1024;
    private static final long MAX_TOTAL = 512L * 1024 * 1024;
    private static final Object LOCK = new Object();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    @Nullable private static Job job;
    private AudioOfflineCache() {}

    public interface Callback { void onComplete(boolean success, String error); }

    public static void download(Context context, MediaItem item, Callback callback) {
        Context app = context.getApplicationContext();
        String account = Session.getUserId(app);
        String token = Session.getToken(app);
        if (!VkAudioIds.isVk(item) || account == null || account.isEmpty() || token == null || token.isEmpty() || !Session.isAuthorized(app)) {
            callback.onComplete(false, "Требуется действующая сессия и запись VK"); return;
        }
        Job next = new Job(app, item, account, token, callback);
        synchronized (LOCK) {
            if (job != null) { callback.onComplete(false, "Дождитесь завершения текущей загрузки"); return; }
            job = next;
        }
        next.repository = VkAudioRepository.create(app);
        next.request = next.repository.getById(Collections.singletonList(AudioSources.apiId(app, item.mediaId)),
                new VkAudioRepository.Callback<List<VkAudioTrack>>() {
                    @Override public void onSuccess(List<VkAudioTrack> tracks) {
                        if (!active(next)) { finish(next, false, "Загрузка отменена"); return; }
                        VkAudioTrack selected = null;
                        for (VkAudioTrack track : tracks) if (item.mediaId.equals(track.mediaId())) { selected = track; break; }
                        if (selected == null || !selected.hasPlayableUrl()) { finish(next, false, "Аудиозапись недоступна"); return; }
                        Uri uri = Uri.parse(selected.url);
                        String path = uri.getPath();
                        if (path != null && path.toLowerCase(Locale.ROOT).endsWith(".m3u8")) {
                            finish(next, false, "Офлайн-сохранение HLS пока не поддерживается"); return;
                        }
                        String url = selected.url;
                        WORKER.execute(() -> transfer(next, url));
                    }
                    @Override public void onError(VkAudioError ignored) { finish(next, false, "Не удалось получить аудиозапись"); }
                });
    }

    private static void transfer(Job task, String url) {
        File temp = null;
        try {
            File dir = directory(task.app, task.account);
            if (!dir.exists() && !dir.mkdirs()) throw new Exception();
            // Clean interrupted downloads from a previous process before starting another.
            File[] stale = dir.listFiles((d, name) -> name.endsWith(".part"));
            if (stale != null) for (File partial : stale) partial.delete();
            temp = File.createTempFile("download-", ".part", dir);
            Call call = HTTP.newCall(new Request.Builder().url(url).header("User-Agent", VKApi.USER_AGENT).build());
            synchronized (LOCK) { if (!active(task)) throw new Exception(); task.call = call; }
            try (Response response = call.execute()) {
                if (response.code() != 200 || response.body() == null) throw new Exception();
                String type = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
                if (type.contains("mpegurl") || type.contains("text/html")) {
                    finish(task, false, "Этот формат не поддерживает офлайн-сохранение"); return;
                }
                long declared = response.body().contentLength();
                if (declared > MAX_TRACK) { finish(task, false, "Файл превышает 128 МБ"); return; }
                long total = 0;
                try (InputStream in = response.body().byteStream(); FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] header = new byte[16];
                    int headerLength = 0, read;
                    while (headerLength < header.length && (read = in.read(header, headerLength, header.length - headerLength)) > 0) headerLength += read;
                    if (!supportedAudio(header, headerLength)) {
                        finish(task, false, "Формат не поддерживает офлайн-сохранение"); return;
                    }
                    out.write(header, 0, headerLength);
                    total = headerLength;
                    byte[] buffer = new byte[32768]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (!active(task)) throw new Exception();
                        total += n;
                        if (total > MAX_TRACK) { finish(task, false, "Файл превышает 128 МБ"); return; }
                        out.write(buffer, 0, n);
                    }
                    out.getFD().sync();
                }
                if (total == 0 || (declared >= 0 && declared != total)) throw new Exception();
                synchronized (LOCK) {
                    if (!active(task)) throw new Exception();
                    File audio = audioFile(dir, task.item.mediaId);
                    long existing = audio.isFile() ? audio.length() : 0;
                    if (usedBytes(dir) - existing + total > MAX_TOTAL) {
                        finish(task, false, "Офлайн-хранилище заполнено (512 МБ)"); return;
                    }
                    JSONObject meta = new JSONObject();
                    meta.put("id", task.item.mediaId);
                    meta.put("title", str(task.item.mediaMetadata.title));
                    meta.put("artist", str(task.item.mediaMetadata.artist));
                    meta.put("duration", AudioUi.duration(task.item));
                    File metadata = metadataFile(dir, task.item.mediaId);
                    File metaTemp = File.createTempFile("metadata-", ".part", dir);
                    try {
                        try (FileOutputStream out = new FileOutputStream(metaTemp)) {
                            out.write(meta.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
                        }
                        // Interrupted replacement is never presented as complete.
                        if (metadata.exists() && !metadata.delete()) throw new Exception();
                        if (audio.exists() && !audio.delete()) throw new Exception();
                        if (!temp.renameTo(audio) || !metaTemp.renameTo(metadata)) {
                            audio.delete(); throw new Exception();
                        }
                    } finally { metaTemp.delete(); }
                }
                finish(task, true, "Сохранено для прослушивания офлайн");
            }
        } catch (Exception ignored) { finish(task, false, "Не удалось сохранить аудиозапись"); }
        finally { if (temp != null) temp.delete(); }
    }

    public static List<MediaItem> list(Context context) {
        String account = authorizedAccount(context);
        if (account == null) return Collections.emptyList();
        File dir = directory(context, account);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return Collections.emptyList();
        List<MediaItem> result = new ArrayList<>();
        for (File file : files) try {
            if (file.length() > 65536) continue;
            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) {
                int pos = 0, n; while (pos < bytes.length && (n = in.read(bytes, pos, bytes.length - pos)) > 0) pos += n;
                if (pos != bytes.length) continue;
            }
            JSONObject meta = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            String id = meta.getString("id");
            if (VkAudioIds.apiId(id) == null || !file.equals(metadataFile(dir, id)) || !audioFile(dir, id).isFile()) continue;
            Bundle extras = new Bundle(); extras.putLong(AudioUi.EXTRA_DURATION_MS, Math.max(0, meta.optLong("duration")));
            result.add(new MediaItem.Builder().setMediaId(id).setMediaMetadata(new MediaMetadata.Builder()
                    .setTitle(meta.optString("title", "")).setArtist(meta.optString("artist", ""))
                    .setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).setExtras(extras).build()).build());
        } catch (Exception ignored) { }
        return result;
    }

    @Nullable public static File findFile(Context context, String id) {
        String account = authorizedAccount(context);
        if (account == null || VkAudioIds.apiId(id) == null) return null;
        File dir = directory(context, account), audio = audioFile(dir, id);
        return metadataFile(dir, id).isFile() && audio.isFile() && audio.length() > 0 ? audio : null;
    }
    public static boolean isSaved(Context context, String id) { return findFile(context, id) != null; }
    public static boolean isDownloading(String id) { synchronized (LOCK) { return job != null && job.item.mediaId.equals(id); } }
    public static void cancel(String id) { synchronized (LOCK) { if (job != null && job.item.mediaId.equals(id)) cancelJob(job); } }
    public static void cancelAll() { synchronized (LOCK) { if (job != null) cancelJob(job); } }
    public static void remove(Context context, String id) {
        synchronized (LOCK) {
            cancel(id);
            String account = authorizedAccount(context);
            if (account == null || VkAudioIds.apiId(id) == null) return;
            File dir = directory(context, account);
            metadataFile(dir, id).delete(); audioFile(dir, id).delete();
        }
    }
    /** Invalidates pending downloads and removes private audio for every account. */
    public static void clearAll(Context context) {
        synchronized (LOCK) {
            if (job != null) cancelJob(job);
            deleteTree(new File(context.getNoBackupFilesDir(), "offline_audio"));
        }
    }
    private static void deleteTree(File f) { File[] children = f.listFiles(); if (children != null) for (File c : children) deleteTree(c); f.delete(); }
    private static void cancelJob(Job task) {
        task.cancelled = true;
        if (task.request != null) task.request.cancel();
        if (task.call != null) task.call.cancel();
        finish(task, false, "Загрузка отменена");
    }
    private static boolean active(Job task) {
        synchronized (LOCK) { return job == task && !task.cancelled && task.account.equals(Session.getUserId(task.app))
                && task.token.equals(Session.getToken(task.app)); }
    }
    private static void finish(Job task, boolean success, String message) {
        synchronized (LOCK) {
            if (job != task) return;
            job = null;
            if (task.repository != null) task.repository.close();
        }
        MAIN.post(() -> task.callback.onComplete(success, message));
    }
    @Nullable private static String authorizedAccount(Context c) {
        String id = Session.getUserId(c); return Session.isAuthorized(c) && id != null && !id.isEmpty() ? id : null;
    }
    private static File directory(Context c, String account) { return new File(new File(c.getNoBackupFilesDir(), "offline_audio"), hash(account)); }
    private static File audioFile(File dir, String id) { return new File(dir, hash(id) + ".audio"); }
    private static File metadataFile(File dir, String id) { return new File(dir, hash(id) + ".json"); }
    private static long usedBytes(File dir) { long size = 0; File[] files = dir.listFiles((d, n) -> n.endsWith(".audio")); if (files != null) for (File f : files) size += f.length(); return size; }
    static boolean supportedAudio(byte[] b, int n) {
        if (n < 4) return false;
        // Recognise common audio containers, not HTML, JSON, or HLS manifests,
        // even when the server labels them application/octet-stream.
        if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') return true;
        if ((b[0] & 255) == 0xff && (b[1] & 0xe0) == 0xe0) return true; // MP3 / ADTS
        if (b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') return true;
        if (b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C') return true;
        if (n >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E') return true;
        return n >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
    }
    private static String str(@Nullable CharSequence value) { return value == null ? "" : value.toString(); }
    private static String hash(String value) {
        try { byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : hash) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static final class Job {
        final Context app; final MediaItem item; final String account, token; final Callback callback;
        boolean cancelled; @Nullable VkAudioRepository repository; @Nullable VkAudioRepository.Request request; @Nullable Call call;
        Job(Context app, MediaItem item, String account, String token, Callback callback) { this.app = app; this.item = item; this.account = account; this.token = token; this.callback = callback; }
    }
}
