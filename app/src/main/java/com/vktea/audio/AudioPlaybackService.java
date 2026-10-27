package com.vktea.audio;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.vktea.ui.AudioPlayerActivity;
import com.vktea.auth.Session;
import com.vktea.audio.catalog.VkAudioRepository;

/** Owns the player independently of Activity and Fragment lifetimes. */
@UnstableApi
public class AudioPlaybackService extends MediaSessionService {

    @Nullable private ExoPlayer player;
    @Nullable private MediaSession session;
    @Nullable private SharedPreferences sessionPreferences;
    @Nullable private VkAudioRepository audioRepository;
    @Nullable private AudioQueueStore queueStore;
    @Nullable private String accountId;
    private final Handler checkpointHandler = new Handler(Looper.getMainLooper());
    private final Runnable checkpoint = new Runnable() {
        @Override public void run() {
            persistQueue();
            if (!stopping) checkpointHandler.postDelayed(this, 5000);
        }
    };
    private final Player.Listener queueListener = new Player.Listener() {
        @Override public void onEvents(Player player, Player.Events events) { persistQueue(); }
    };
    private boolean stopping;
    private boolean stopReceiverRegistered;

    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioPlayback.ACTION_STOP.equals(intent.getAction())) {
                stopPlayback();
            }
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener authListener =
            (preferences, key) -> {
                if (!Session.isAuthorized(this) || accountId == null
                        || !accountId.equals(Session.getUserId(this))) {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        stopPlayback();
                    } else {
                        new Handler(Looper.getMainLooper()).post(this::stopPlayback);
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        if (!Session.isAuthorized(this)) {
            stopPlayback();
            return;
        }

        // Session currently keeps its private preferences in this file. Observe
        // revocation as well as the explicit stop helper. On Android 11+ clear()
        // reports a null key; older Android versions do not notify clear() at
        // all, so Session.clear must keep calling AudioPlayback.stop.
        sessionPreferences = getSharedPreferences("session", MODE_PRIVATE);
        sessionPreferences.registerOnSharedPreferenceChangeListener(authListener);
        ContextCompat.registerReceiver(this, stopReceiver,
                new IntentFilter(AudioPlayback.ACTION_STOP), ContextCompat.RECEIVER_NOT_EXPORTED);
        stopReceiverRegistered = true;

        accountId = Session.getUserId(this);
        queueStore = new AudioQueueStore(this);
        audioRepository = VkAudioRepository.create(this);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new VkAudioMediaSourceFactory(this, audioRepository,
                        this::sessionAvailable))
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();
        queueStore.restore(player, accountId);
        // Prepare without playing so external transport controls can resume after restoration.
        if (player.getMediaItemCount() > 0) player.prepare();
        player.addListener(queueListener);
        checkpointHandler.postDelayed(checkpoint, 5000);
        Intent openPlayer = new Intent(this, AudioPlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent sessionActivity = PendingIntent.getActivity(this, 1, openPlayer,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        session = new MediaSession.Builder(this, player)
                .setSessionActivity(sessionActivity)
                .setCallback(new AudioSessionCallback(this, this::sessionAvailable))
                .build();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        if (!sessionAvailable() || !AudioSessionCallback.allowsController(controllerInfo)) {
            return null;
        }
        return session;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        if (!isPlaybackOngoing()) {
            // Disconnect even bound external controllers when playback is idle.
            stopPlayback();
        }
    }

    private boolean sessionAvailable() {
        return !stopping && accountId != null && Session.isAuthorized(this)
                && accountId.equals(Session.getUserId(this));
    }

    private void persistQueue() {
        if (!stopping && player != null && queueStore != null && accountId != null
                && Session.isAuthorized(this) && accountId.equals(Session.getUserId(this))) {
            queueStore.save(player, accountId);
        }
    }

    private void stopPlayback() {
        if (stopping) {
            return;
        }
        persistQueue();
        stopping = true;
        checkpointHandler.removeCallbacks(checkpoint);
        if (player != null) player.removeListener(queueListener);
        AudioSources.clear();
        if (audioRepository != null) {
            audioRepository.close();
            audioRepository = null;
        }
        if (player != null) {
            player.pause();
            player.stop();
            player.clearMediaItems();
        }
        if (session != null) {
            session.release();
            session = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (stopReceiverRegistered) {
            unregisterReceiver(stopReceiver);
            stopReceiverRegistered = false;
        }
        if (sessionPreferences != null) {
            sessionPreferences.unregisterOnSharedPreferenceChangeListener(authListener);
            sessionPreferences = null;
        }
        stopPlayback();
        super.onDestroy();
    }
}
