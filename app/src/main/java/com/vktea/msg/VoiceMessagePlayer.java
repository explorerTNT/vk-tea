package com.vktea.msg;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.vktea.auth.Session;

/** Owns at most one foreground voice player; the chat owns its lifetime. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class VoiceMessagePlayer {
    interface Observer {
        void onVoicePlayback(@Nullable ChatAttachment attachment, boolean playing, long positionMs, long durationMs);
    }

    interface PlayerFactory { Player create(Context context); }
    interface LinkRequest {
        VoiceLinkResolver.Handle resolve(ChatAttachment attachment, Session.Snapshot session,
                                         VoiceLinkResolver.Completion completion);
    }

    private final AppCompatActivity activity;
    private Observer observer;
    private final PlayerFactory factory;
    private final LinkRequest linkRequest;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private Player player;
    @Nullable private ChatAttachment active;
    @Nullable private VoiceLinkResolver.Handle pendingRequest;
    private boolean resolving;
    private int generation;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            publish();
            Player current = player;
            if (current != null && current.getPlayWhenReady()
                    && current.getPlaybackState() != Player.STATE_ENDED) main.postDelayed(this, 250);
        }
    };

    public VoiceMessagePlayer(AppCompatActivity activity) {
        this(activity, (attachment, playing, position, duration) -> { });
    }

    /** Retains the isolated synthetic audio source seam used by offline UI QA. */
    VoiceMessagePlayer(AppCompatActivity activity, androidx.media3.datasource.DataSource.Factory source) {
        this(activity, (attachment, playing, position, duration) -> { }, context -> {
            ExoPlayer.Builder builder = new ExoPlayer.Builder(context);
            if (source != null) builder.setMediaSourceFactory(
                    new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(source));
            ExoPlayer result = builder.build();
            result.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true);
            return result;
        }, new VoiceLinkResolver()::resolve);
    }

    void setObserver(Observer observer) { this.observer = observer; }

    VoiceMessagePlayer(AppCompatActivity activity, Observer observer) {
        this(activity, observer, context -> {
            ExoPlayer result = new ExoPlayer.Builder(context).build();
            result.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true);
            return result;
        }, new VoiceLinkResolver()::resolve);
    }

    VoiceMessagePlayer(AppCompatActivity activity, Observer observer, PlayerFactory factory) {
        this(activity, observer, factory, new VoiceLinkResolver()::resolve);
    }

    VoiceMessagePlayer(AppCompatActivity activity, Observer observer, PlayerFactory factory, LinkRequest linkRequest) {
        this.activity = activity;
        this.observer = observer;
        this.factory = factory;
        this.linkRequest = linkRequest;
    }

    /** Uses document identity across refreshed message objects and falls back to the URL. */
    static boolean sameVoice(@Nullable ChatAttachment first, @Nullable ChatAttachment second) {
        if (first == null || second == null) return false;
        if (!"audio_message".equals(first.type) || !"audio_message".equals(second.type)) return false;
        if (first.id != 0 && first.ownerId != 0 && second.id != 0 && second.ownerId != 0)
            return first.id == second.id && first.ownerId == second.ownerId;
        return !first.url.isEmpty() && first.url.equals(second.url);
    }

    public void toggle(ChatAttachment attachment) {
        if (sameVoice(active, attachment) && resolving) { close(); return; }
        if (sameVoice(active, attachment) && player != null) {
            if (player.getPlaybackState() == Player.STATE_ENDED) {
                player.seekTo(0);
                player.play();
            } else if (player.getPlayWhenReady()) player.pause();
            else {
                player.play();
            }
            schedule();
            return;
        }
        releasePlayer();
        if (attachment == null || !"audio_message".equals(attachment.type)) return;
        active = attachment;
        Session.Snapshot snapshot = Session.snapshot(activity);
        // Прямая ссылка из истории сообщений уже валидна - играем сразу,
        // docs.getById используем только как фолбэк (он пустой для чужих голосовых).
        if (isSafeUrl(attachment.url)) {
            start(attachment.url);
            return;
        }
        if (attachment.ownerId != 0 && attachment.id != 0 && snapshot.token != null && !snapshot.token.isEmpty()) {
            resolving = true;
            int requestGeneration = generation;
            publish();
            pendingRequest = linkRequest.resolve(attachment, snapshot, result -> {
                if (requestGeneration != generation || !resolving) return;
                pendingRequest = null;
                resolving = false;
                if (snapshot.generation != Session.generation() || activity.isFinishing() || activity.isDestroyed()) {
                    releasePlayer(); publish(); return;
                }
                if (result.kind == VoiceLinkResolver.Kind.UNAVAILABLE) {
                    unavailable(); return;
                }
                String url = result.kind == VoiceLinkResolver.Kind.FOUND ? result.url : attachment.url;
                start(url);
            });
        } else start(attachment.url);
    }

    static boolean isSafeUrl(@Nullable String url) {
        Uri uri = Uri.parse(url == null ? "" : url);
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
    }

    private void unavailable() {
        releasePlayer();
        publish();
        if (!activity.isFinishing() && !activity.isDestroyed())
            Toast.makeText(activity, "Голосовое сообщение недоступно", Toast.LENGTH_SHORT).show();
    }

    private void start(String url) {
        if (!isSafeUrl(url)) { unavailable(); return; }
        Uri uri = Uri.parse(url);
        Player current = factory.create(activity);
        player = current;
        current.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (player == current) schedule();
            }
            @Override public void onPlayWhenReadyChanged(boolean ready, int reason) {
                if (player == current) schedule();
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (player != current) return;
                releasePlayer();
                publish();
                if (!activity.isFinishing() && !activity.isDestroyed()) Toast.makeText(activity,
                        "Не удалось воспроизвести голосовое сообщение", Toast.LENGTH_SHORT).show();
            }
        });
        current.setMediaItem(MediaItem.fromUri(uri));
        current.prepare();
        current.play();
        schedule();
    }

    /** Seek only the currently selected voice, leaving pause/play state untouched. */
    public void seek(ChatAttachment attachment, float fraction) {
        Player current = player;
        if (current == null || !sameVoice(active, attachment) || Float.isNaN(fraction)) return;
        long duration = duration(current);
        if (duration <= 0) return;
        float clamped = Math.max(0f, Math.min(1f, fraction));
        current.seekTo((long) (duration * (double) clamped));
        schedule();
    }

    private long duration(Player current) {
        long value = current.getDuration();
        return value > 0 && value != C.TIME_UNSET ? value
                : active == null ? 0 : active.duration * 1000L;
    }

    private void schedule() {
        main.removeCallbacks(ticker);
        ticker.run();
    }

    private void publish() {
        Player current = player;
        ChatAttachment attachment = active;
        if (resolving && attachment != null) {
            observer.onVoicePlayback(attachment, true, 0, attachment.duration * 1000L);
            return;
        }
        if (current == null || attachment == null) {
            observer.onVoicePlayback(null, false, 0, 0);
            return;
        }
        long duration = duration(current);
        long position = Math.max(0, current.getCurrentPosition());
        if (duration > 0) position = Math.min(position, duration);
        boolean playing = current.getPlayWhenReady() && current.getPlaybackState() != Player.STATE_ENDED;
        observer.onVoicePlayback(attachment, playing, position, duration);
    }

    private void releasePlayer() {
        generation++;
        resolving = false;
        VoiceLinkResolver.Handle request = pendingRequest;
        pendingRequest = null;
        if (request != null) request.cancel();
        main.removeCallbacks(ticker);
        Player old = player;
        player = null;
        active = null;
        if (old != null) old.release();
    }

    public void close() {
        releasePlayer();
        publish();
    }
}
