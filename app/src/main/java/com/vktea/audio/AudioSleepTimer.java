package com.vktea.audio;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

/** A service-owned sleep timer. A cancelled or destroyed service never resumes playback. */
@UnstableApi
public final class AudioSleepTimer implements Player.Listener {
    static final String ACTION_SET = "com.vktea.audio.action.SET_SLEEP_TIMER";
    static final String ACTION_EXPIRE = "com.vktea.audio.action.EXPIRE_SLEEP_TIMER";
    static final String EXTRA_DURATION = "duration_ms";
    public static final long OFF = 0;
    public static final long END_OF_TRACK = -1;
    static final long MAX_DURATION_MS = 12L * 60 * 60 * 1000;
    private static final String PREFS = "audio_sleep_timer";
    private static final String MODE = "mode";
    private static final String DEADLINE = "deadline_elapsed";
    private static final int MODE_OFF = 0, MODE_DURATION = 1, MODE_END = 2;

    private final Context context;
    private final ExoPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AlarmManager alarms;
    private final PendingIntent expiration;
    private final SharedPreferences prefs;
    private int mode;
    private long deadline;
    private final Runnable timeout = this::expireIfDue;

    AudioSleepTimer(Context context, ExoPlayer player) {
        this.context = context.getApplicationContext();
        this.player = player;
        alarms = (AlarmManager) this.context.getSystemService(Context.ALARM_SERVICE);
        expiration = PendingIntent.getBroadcast(this.context, 0,
                new Intent(ACTION_EXPIRE).setPackage(this.context.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // A service restart does not resurrect an earlier timer or playback.
        clear();
        player.addListener(this);
    }

    /** Requires an already-connected playback service; the receiver is not exported. */
    public static void request(Context context, long durationMs) {
        Context application = context.getApplicationContext();
        application.sendBroadcast(new Intent(ACTION_SET).setPackage(application.getPackageName())
                .putExtra(EXTRA_DURATION, durationMs));
    }

    /** 0 = off, -1 = end of track, positive = milliseconds remaining. */
    public static long remaining(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int mode = prefs.getInt(MODE, MODE_OFF);
        if (mode == MODE_END) return END_OF_TRACK;
        if (mode != MODE_DURATION) return OFF;
        return Math.max(0, prefs.getLong(DEADLINE, 0) - SystemClock.elapsedRealtime());
    }

    void handle(@Nullable Intent intent) {
        if (intent == null) return;
        if (ACTION_EXPIRE.equals(intent.getAction())) {
            expireIfDue();
        } else if (ACTION_SET.equals(intent.getAction())) {
            long duration = intent.getLongExtra(EXTRA_DURATION, OFF);
            if (duration == OFF) clear();
            else if (duration == END_OF_TRACK) setEndOfTrack();
            else if (duration > 0 && duration <= MAX_DURATION_MS) setDuration(duration);
        }
    }

    private void setDuration(long durationMs) {
        clear();
        mode = MODE_DURATION;
        deadline = SystemClock.elapsedRealtime() + durationMs;
        prefs.edit().putInt(MODE, mode).putLong(DEADLINE, deadline).apply();
        // The handler responds promptly while playback holds the CPU; the alarm
        // covers device sleep without requesting exact-alarm permission.
        handler.postDelayed(timeout, durationMs);
        if (alarms != null) alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, expiration);
    }

    private void setEndOfTrack() {
        clear();
        if (player.getCurrentMediaItem() == null) return;
        mode = MODE_END;
        player.setPauseAtEndOfMediaItems(true);
        prefs.edit().putInt(MODE, mode).remove(DEADLINE).apply();
    }

    private void expireIfDue() {
        if (mode != MODE_DURATION) return;
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining > 0) {
            handler.removeCallbacks(timeout);
            handler.postDelayed(timeout, remaining);
            return;
        }
        clear();
        player.pause();
    }

    @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
        if (mode == MODE_END && !playWhenReady
                && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) clear();
    }

    @Override public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
        // The promise was to stop after the item selected when the timer was set.
        // A manual skip or replacement invalidates that promise; a repeat does not.
        if (mode == MODE_END && (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)) clear();
    }

    private void clear() {
        mode = MODE_OFF;
        deadline = 0;
        handler.removeCallbacks(timeout);
        if (alarms != null) alarms.cancel(expiration);
        player.setPauseAtEndOfMediaItems(false);
        prefs.edit().clear().apply();
    }

    void close() {
        player.removeListener(this);
        clear();
    }
}
