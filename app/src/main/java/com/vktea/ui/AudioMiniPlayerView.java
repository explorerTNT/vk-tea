package com.vktea.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;

import com.google.common.util.concurrent.ListenableFuture;
import com.vktea.R;
import com.vktea.audio.AudioPlayback;
import com.vktea.audio.AudioUi;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/** A compact controller shared by all main sections. It never owns or clears the queue. */
public class AudioMiniPlayerView extends FrameLayout {
    private final TextView title;
    private final TextView artist;
    private final ImageButton play;
    private boolean started;
    private ListenableFuture<MediaController> future;
    private MediaController controller;
    private final Player.Listener listener = new Player.Listener() {
        @Override public void onEvents(Player player, Player.Events events) { render(player); }
    };

    public AudioMiniPlayerView(Context context, @Nullable AttributeSet attributes) {
        super(context, attributes);
        LayoutInflater.from(context).inflate(R.layout.view_audio_mini_player, this, true);
        title = findViewById(R.id.audio_mini_title);
        artist = findViewById(R.id.audio_mini_artist);
        play = findViewById(R.id.audio_mini_play);
        findViewById(R.id.audio_mini_open).setOnClickListener(v -> AudioUi.openPlayer(getContext()));
        play.setOnClickListener(v -> { if (controller != null) AudioUi.togglePlay(controller); });
        setVisibility(GONE);
    }

    public void start() {
        if (started) return;
        started = true;
        ListenableFuture<MediaController> pending = AudioPlayback.connect(getContext(), new MediaController.Listener() {
            @Override public void onDisconnected(MediaController disconnected) {
                if (controller == disconnected) {
                    disconnected.removeListener(listener);
                    controller = null;
                    render(null);
                }
            }
        });
        future = pending;
        pending.addListener(() -> {
            if (!started || future != pending) return;
            try {
                controller = pending.get();
                controller.addListener(listener);
                render(controller);
            } catch (CancellationException ignored) {
                render(null);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                render(null);
            } catch (ExecutionException ignored) { render(null); }
        }, ContextCompat.getMainExecutor(getContext()));
    }

    public void stop() {
        started = false;
        if (controller != null) controller.removeListener(listener);
        controller = null;
        if (future != null) MediaController.releaseFuture(future);
        future = null;
    }

    void render(@Nullable Player player) {
        if (player == null || player.getCurrentMediaItem() == null) {
            setVisibility(GONE);
            return;
        }
        setVisibility(View.VISIBLE);
        title.setText(AudioUi.title(getContext(), player.getCurrentMediaItem()));
        artist.setText(AudioUi.artist(getContext(), player.getCurrentMediaItem()));
        boolean playing = player.getPlayWhenReady();
        play.setImageResource(playing ? R.drawable.audio_mini_pause : R.drawable.audio_mini_play);
        play.setContentDescription(getContext().getString(playing
                ? R.string.audio_player_pause : R.string.audio_player_play));
        play.setEnabled(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE));
    }
}
