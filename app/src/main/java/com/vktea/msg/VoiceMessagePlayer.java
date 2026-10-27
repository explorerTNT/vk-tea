package com.vktea.msg;

import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

/** One foreground voice player per chat; dismissal and activity stop release audio focus. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class VoiceMessagePlayer {
    private final AppCompatActivity activity;
    @Nullable private AlertDialog dialog;
    @Nullable private ExoPlayer player;

    public VoiceMessagePlayer(AppCompatActivity activity) { this.activity = activity; }

    public void open(String url) {
        Uri uri = Uri.parse(url == null ? "" : url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            Toast.makeText(activity, "Голосовое сообщение недоступно", Toast.LENGTH_SHORT).show();
            return;
        }
        close();
        ExoPlayer current = new ExoPlayer.Builder(activity).build();
        player = current;
        current.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true);
        current.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                if (player == current) Toast.makeText(activity,
                        "Не удалось воспроизвести голосовое сообщение", Toast.LENGTH_SHORT).show();
            }
        });
        PlayerView view = new PlayerView(activity);
        view.setMinimumHeight(Math.round(112 * activity.getResources().getDisplayMetrics().density));
        view.setUseController(true);
        view.setControllerShowTimeoutMs(0);
        view.setPlayer(current);
        AlertDialog shown = new AlertDialog.Builder(activity)
                .setTitle("Голосовое сообщение")
                .setView(view)
                .setNegativeButton("Закрыть", (ignored, which) -> { })
                .create();
        dialog = shown;
        shown.setOnDismissListener(ignored -> {
            view.setPlayer(null);
            if (dialog == shown) close();
        });
        shown.show();
        view.showController();
        current.setMediaItem(MediaItem.fromUri(uri));
        current.prepare();
        current.play();
    }

    public void close() {
        AlertDialog oldDialog = dialog;
        dialog = null;
        if (oldDialog != null) oldDialog.dismiss();
        ExoPlayer oldPlayer = player;
        player = null;
        if (oldPlayer != null) oldPlayer.release();
    }
}
