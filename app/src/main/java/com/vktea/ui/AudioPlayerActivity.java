package com.vktea.ui;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.NestedScrollView;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;

import com.google.common.util.concurrent.ListenableFuture;
import com.vktea.R;
import com.vktea.audio.AudioPlayback;
import com.vktea.audio.AudioUi;
import com.vktea.auth.Session;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** A controller-only player screen; the service owns the queue and audio lifetime. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public class AudioPlayerActivity extends AppCompatActivity {
    private static final int SEEK_STEPS = 1000;
    private static final int MAX_ARTWORK_BYTES = 8 * 1024 * 1024;
    private static final String STATE_AUDIBLE_VOLUME = "audio_player_audible_volume";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();
    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onEvents(Player player, Player.Events events) {
            render();
        }
    };
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (started) {
                updateProgress();
                updateControls();
                handler.postDelayed(this, 500);
            }
        }
    };

    @Nullable private ListenableFuture<MediaController> controllerFuture;
    @Nullable private MediaController controller;
    @Nullable private Future<?> artworkTask;
    @Nullable private String artworkMediaId;
    @Nullable private byte[] artworkData;
    @Nullable private Uri artworkUri;
    private int artworkGeneration;
    private boolean started;
    private boolean connectionFailed;
    private boolean draggingSeek;
    private float audibleVolume = 1f;
    private int topInset;

    private AudioPlayerLayout root;
    private FrameLayout cover;
    private NestedScrollView body;
    private ImageView artwork;
    private ImageView placeholder;
    private ImageButton collapse;
    private TextView title;
    private TextView artist;
    private TextView elapsed;
    private TextView duration;
    private SeekBar seek;
    private ImageButton save;
    private ImageButton previous;
    private ImageButton play;
    private ImageButton next;
    private ImageButton queue;
    private ImageButton mute;
    private ImageButton shuffle;
    private ImageButton repeat;
    private View repeatOne;
    private ImageButton download;
    private ImageButton menu;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.isAuthorized(this)) {
            finish();
            return;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.vk_blue));
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightNavigationBars(false);
        setContentView(R.layout.activity_audio_player);
        if (savedInstanceState != null) {
            audibleVolume = savedInstanceState.getFloat(STATE_AUDIBLE_VOLUME, 1f);
        }
        bindViews();
        bindActions();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            topInset = bars.top;
            view.setPadding(bars.left, 0, bars.right, bars.bottom);
            FrameLayout.LayoutParams back = (FrameLayout.LayoutParams) collapse.getLayoutParams();
            back.topMargin = bars.top + dp(4);
            collapse.setLayoutParams(back);
            adjustLayout();
            return insets;
        });
        root.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop,
                                       oldRight, oldBottom) -> adjustLayout());
        ViewCompat.requestApplyInsets(root);
        render();
    }

    private void bindViews() {
        root = findViewById(R.id.audio_player_root);
        cover = findViewById(R.id.audio_player_cover);
        body = findViewById(R.id.audio_player_body_scroll);
        artwork = findViewById(R.id.audio_player_artwork);
        placeholder = findViewById(R.id.audio_player_cover_placeholder);
        collapse = findViewById(R.id.audio_player_collapse);
        title = findViewById(R.id.audio_player_title);
        artist = findViewById(R.id.audio_player_artist);
        elapsed = findViewById(R.id.audio_player_elapsed);
        duration = findViewById(R.id.audio_player_duration);
        seek = findViewById(R.id.audio_player_seek);
        save = findViewById(R.id.audio_player_save);
        previous = findViewById(R.id.audio_player_previous);
        play = findViewById(R.id.audio_player_play);
        next = findViewById(R.id.audio_player_next);
        queue = findViewById(R.id.audio_player_queue);
        mute = findViewById(R.id.audio_player_mute);
        shuffle = findViewById(R.id.audio_player_shuffle);
        repeat = findViewById(R.id.audio_player_repeat);
        repeatOne = findViewById(R.id.audio_player_repeat_one);
        download = findViewById(R.id.audio_player_download);
        menu = findViewById(R.id.audio_player_menu);
        root.bind(seek, body, this::finish);
        title.setSelected(true);
        artist.setSelected(true);
    }

    private void bindActions() {
        collapse.setOnClickListener(view -> finish());
        play.setOnClickListener(view -> {
            if (controller != null) AudioUi.togglePlay(controller);
        });
        previous.setOnClickListener(view -> {
            if (hasCommand(Player.COMMAND_SEEK_TO_PREVIOUS)) controller.seekToPrevious();
        });
        next.setOnClickListener(view -> {
            if (hasCommand(Player.COMMAND_SEEK_TO_NEXT)) controller.seekToNext();
        });
        queue.setOnClickListener(view -> {
            if (controller != null) AudioUi.showQueue(this, controller);
        });
        menu.setOnClickListener(view -> {
            if (controller != null) AudioUi.showActions(this, view, controller);
        });
        save.setOnClickListener(view -> {
            MediaItem item = currentItem();
            if (item != null) {
                AudioUi.toggleSaved(this, item);
                updateControls();
            }
        });
        download.setOnClickListener(view -> {
            MediaItem item = currentItem();
            if (item != null) AudioUi.manageOffline(this, item);
        });
        shuffle.setOnClickListener(view -> {
            if (hasCommand(Player.COMMAND_SET_SHUFFLE_MODE)) {
                controller.setShuffleModeEnabled(!controller.getShuffleModeEnabled());
            }
        });
        repeat.setOnClickListener(view -> {
            if (controller != null) AudioUi.toggleRepeat(controller);
        });
        mute.setOnClickListener(view -> {
            if (hasCommand(Player.COMMAND_GET_VOLUME) && hasCommand(Player.COMMAND_SET_VOLUME)) {
                float volume = controller.getVolume();
                if (volume > 0) {
                    audibleVolume = volume;
                    controller.setVolume(0);
                } else {
                    controller.setVolume(audibleVolume > 0 ? audibleVolume : 1f);
                }
            }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar view, int value, boolean fromUser) {
                if (fromUser) {
                    long position = positionForProgress(value);
                    elapsed.setText(AudioUi.formatTime(position));
                    // Keyboard and accessibility changes have no drag callbacks.
                    if (!draggingSeek && hasCommand(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                            && trackDuration() > 0) controller.seekTo(position);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar view) {
                draggingSeek = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar view) {
                if (!root.isSeekTouchCancelled() && hasCommand(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                        && trackDuration() > 0) {
                    controller.seekTo(positionForProgress(view.getProgress()));
                }
                draggingSeek = false;
                updateProgress();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (root == null || isFinishing()) return;
        started = true;
        connectionFailed = false;
        ListenableFuture<MediaController> pending = connectController(
                new MediaController.Listener() {
                    @Override
                    public void onDisconnected(MediaController disconnected) {
                        if (controller == disconnected) {
                            disconnected.removeListener(playerListener);
                            controller = null;
                            connectionFailed = true;
                            render();
                        }
                    }
                });
        controllerFuture = pending;
        pending.addListener(() -> {
            if (!started || controllerFuture != pending) return;
            try {
                controller = pending.get();
                controller.addListener(playerListener);
            } catch (CancellationException ignored) {
                return;
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                connectionFailed = true;
            } catch (ExecutionException ignored) {
                connectionFailed = true;
            }
            render();
        }, ContextCompat.getMainExecutor(this));
        handler.post(progressTick);
        render();
    }

    /** Allows an in-process session in offline UI tests; production uses the service. */
    protected ListenableFuture<MediaController> connectController(MediaController.Listener listener) {
        return AudioPlayback.connect(this, listener);
    }

    @Override
    protected void onStop() {
        started = false;
        if (root != null) root.resetGesture();
        handler.removeCallbacks(progressTick);
        if (controller != null) controller.removeListener(playerListener);
        controller = null;
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
        cancelArtwork();
        artworkMediaId = null;
        draggingSeek = false;
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putFloat(STATE_AUDIBLE_VOLUME, audibleVolume);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        cancelArtwork();
        artworkExecutor.shutdownNow();
        super.onDestroy();
    }

    private void render() {
        MediaItem item = currentItem();
        if (controller == null) {
            title.setText(connectionFailed ? R.string.audio_connection_failed : R.string.audio_connecting);
            artist.setText("");
        } else {
            title.setText(item == null ? getString(R.string.audio_untitled) : AudioUi.title(this, item));
            artist.setText(item == null ? "" : artistAndQuality(item));
        }
        updateArtwork(item);
        updateControls();
        updateProgress();
    }

    private void updateControls() {
        MediaItem item = currentItem();
        boolean hasItem = item != null;
        enable(play, hasItem && hasCommand(Player.COMMAND_PLAY_PAUSE));
        boolean playing = controller != null && controller.getPlayWhenReady();
        play.setImageResource(playing ? R.drawable.audio_player_pause : R.drawable.audio_player_play);
        play.setContentDescription(getString(playing ? R.string.audio_player_pause : R.string.audio_player_play));
        enable(previous, hasItem && hasCommand(Player.COMMAND_SEEK_TO_PREVIOUS));
        enable(next, hasItem && hasCommand(Player.COMMAND_SEEK_TO_NEXT));
        enable(queue, hasCommand(Player.COMMAND_GET_TIMELINE) && controller.getMediaItemCount() > 0);
        enable(save, AudioUi.canToggleSaved(item));
        boolean saved = hasItem && AudioUi.isSaved(this, item);
        save.setImageResource(saved ? R.drawable.audio_player_saved : R.drawable.audio_player_add);
        save.setContentDescription(getString(saved ? R.string.audio_player_unsave : R.string.audio_player_save));
        tint(save, saved);
        enable(menu, hasItem);
        enable(download, hasItem && (AudioUi.canManageOffline(item) || AudioUi.canExport(item)));
        download.setContentDescription(getString(AudioUi.offlineActionLabel(this, item)));
        enable(shuffle, hasItem && hasCommand(Player.COMMAND_SET_SHUFFLE_MODE));
        boolean shuffled = controller != null && controller.getShuffleModeEnabled();
        shuffle.setSelected(shuffled);
        tint(shuffle, shuffled);
        enable(repeat, hasItem && hasCommand(Player.COMMAND_SET_REPEAT_MODE));
        int repeatMode = controller == null ? Player.REPEAT_MODE_OFF : controller.getRepeatMode();
        repeat.setSelected(repeatMode != Player.REPEAT_MODE_OFF);
        tint(repeat, repeatMode != Player.REPEAT_MODE_OFF);
        repeatOne.setVisibility(repeatMode == Player.REPEAT_MODE_ONE ? View.VISIBLE : View.GONE);
        repeat.setContentDescription(getString(repeatMode == Player.REPEAT_MODE_ONE
                ? R.string.audio_player_repeat_one : repeatMode == Player.REPEAT_MODE_ALL
                ? R.string.audio_player_repeat_all : R.string.audio_player_repeat_off));
        boolean canReadVolume = hasCommand(Player.COMMAND_GET_VOLUME);
        float volume = canReadVolume ? controller.getVolume() : 1f;
        if (canReadVolume && volume > 0) audibleVolume = volume;
        enable(mute, hasItem && canReadVolume && hasCommand(Player.COMMAND_SET_VOLUME));
        mute.setImageResource(volume == 0 ? R.drawable.audio_player_muted : R.drawable.audio_player_volume);
        mute.setContentDescription(getString(volume == 0 ? R.string.audio_player_unmute : R.string.audio_player_mute));
    }

    private void updateProgress() {
        long total = trackDuration();
        duration.setText(AudioUi.formatTime(total));
        boolean canSeek = total > 0 && hasCommand(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM);
        if (draggingSeek && !canSeek) {
            // Cancel while SeekBar is still enabled so it delivers its stop callback.
            root.resetGesture();
            draggingSeek = false;
        }
        seek.setEnabled(canSeek);
        if (!draggingSeek) {
            long position = hasCommand(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                    ? Math.max(0, controller.getCurrentPosition()) : 0;
            elapsed.setText(AudioUi.formatTime(position));
            seek.setProgress(total > 0 ? (int) Math.min(SEEK_STEPS,
                    (double) position / total * SEEK_STEPS) : 0);
            long buffered = hasCommand(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                    ? Math.max(0, controller.getBufferedPosition()) : 0;
            seek.setSecondaryProgress(total > 0 ? (int) Math.min(SEEK_STEPS,
                    (double) buffered / total * SEEK_STEPS) : 0);
        }
    }

    private long trackDuration() {
        if (hasCommand(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) {
            long actual = controller.getDuration();
            if (actual != C.TIME_UNSET && actual > 0) return actual;
        }
        MediaItem item = currentItem();
        return item == null ? 0 : Math.max(0, AudioUi.duration(item));
    }

    private long positionForProgress(int value) {
        return Math.round(trackDuration() * (value / (double) SEEK_STEPS));
    }

    @Nullable
    private MediaItem currentItem() {
        return hasCommand(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) ? controller.getCurrentMediaItem() : null;
    }

    private boolean hasCommand(int command) {
        return controller != null && controller.isCommandAvailable(command);
    }

    private String artistAndQuality(MediaItem item) {
        String by = AudioUi.artist(this, item);
        Bundle extras = item.mediaMetadata.extras;
        if (extras == null) return by;
        long bitrate = extras.getLong(AudioUi.EXTRA_BITRATE, 0);
        long fileSize = extras.getLong(AudioUi.EXTRA_FILE_SIZE, 0);
        String quality = bitrate > 0 ? getString(R.string.audio_player_quality_bitrate,
                Math.round(bitrate / 1000d)) : "";
        if (fileSize > 0) {
            NumberFormat number = NumberFormat.getNumberInstance();
            number.setMinimumFractionDigits(1);
            number.setMaximumFractionDigits(1);
            String size = getString(R.string.audio_player_quality_size, number.format(fileSize / 1048576d));
            quality = quality.isEmpty() ? size : quality + " | " + size;
        }
        return quality.isEmpty() ? by : getString(R.string.audio_player_artist_quality, by, quality);
    }

    private void tint(ImageView view, boolean selected) {
        ImageViewCompat.setImageTintList(view, ColorStateList.valueOf(ContextCompat.getColor(this,
                selected ? R.color.audio_accent : R.color.audio_text)));
    }

    private static void enable(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.3f);
    }

    private void adjustLayout() {
        int width = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        int height = root.getHeight() - root.getPaddingBottom();
        if (width <= 0 || height <= 0) return;
        boolean landscape = width > height;
        int orientation = landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
        if (root.getOrientation() != orientation) root.setOrientation(orientation);
        LinearLayout.LayoutParams art = (LinearLayout.LayoutParams) cover.getLayoutParams();
        LinearLayout.LayoutParams controls = (LinearLayout.LayoutParams) body.getLayoutParams();
        int coverWidth = landscape ? Math.min(height, Math.round(width * 0.43f))
                : ViewGroup.LayoutParams.MATCH_PARENT;
        int minimumControls = dp(166 + 116 * Math.max(1f, getResources().getConfiguration().fontScale));
        int coverHeight = landscape ? ViewGroup.LayoutParams.MATCH_PARENT
                : Math.min(width, Math.max(Math.min(dp(96), height / 2), height - minimumControls));
        if (art.width != coverWidth || art.height != coverHeight) {
            art.width = coverWidth;
            art.height = coverHeight;
            art.weight = 0;
            cover.setLayoutParams(art);
        }
        int controlsWidth = landscape ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
        int controlsHeight = landscape ? ViewGroup.LayoutParams.MATCH_PARENT : 0;
        if (controls.width != controlsWidth || controls.height != controlsHeight) {
            controls.width = controlsWidth;
            controls.height = controlsHeight;
            controls.weight = 1;
            body.setLayoutParams(controls);
        }
        int paddingTop = landscape ? topInset : 0;
        if (body.getPaddingTop() != paddingTop) body.setPadding(0, paddingTop, 0, 0);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void updateArtwork(@Nullable MediaItem item) {
        MediaMetadata metadata = item == null ? MediaMetadata.EMPTY : item.mediaMetadata;
        String mediaId = item == null ? "" : item.mediaId;
        if (mediaId.equals(artworkMediaId) && Arrays.equals(artworkData, metadata.artworkData)
                && java.util.Objects.equals(artworkUri, metadata.artworkUri)) return;
        cancelArtwork();
        artworkMediaId = mediaId;
        artworkData = metadata.artworkData;
        artworkUri = metadata.artworkUri;
        artwork.setImageDrawable(null);
        artwork.setVisibility(View.GONE);
        placeholder.setVisibility(View.VISIBLE);
        byte[] embedded = artworkData;
        Uri local = artworkUri;
        if ((embedded == null || embedded.length == 0)
                && (local == null || !"content".equals(local.getScheme()))) return;
        int generation = artworkGeneration;
        artworkTask = artworkExecutor.submit(() -> {
            Bitmap bitmap = readArtwork(embedded, local);
            handler.post(() -> {
                if (generation == artworkGeneration && started && bitmap != null) {
                    artwork.setImageBitmap(bitmap);
                    artwork.setVisibility(View.VISIBLE);
                    placeholder.setVisibility(View.GONE);
                }
            });
        });
    }

    @Nullable
    private Bitmap readArtwork(@Nullable byte[] embedded, @Nullable Uri local) {
        try {
            byte[] bytes = embedded;
            if (bytes == null || bytes.length == 0) {
                // Artwork is embedded or granted local content only; this screen
                // never fetches remote artwork or touches authentication headers.
                if (local == null || !"content".equals(local.getScheme())) return null;
                try (InputStream input = getContentResolver().openInputStream(local);
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (input == null) return null;
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()
                                || output.size() + count > MAX_ARTWORK_BYTES) return null;
                        output.write(buffer, 0, count);
                    }
                    bytes = output.toByteArray();
                }
            }
            if (bytes.length > MAX_ARTWORK_BYTES) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1024) {
                options.inSampleSize *= 2;
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void cancelArtwork() {
        artworkGeneration++;
        if (artworkTask != null) {
            artworkTask.cancel(true);
            artworkTask = null;
        }
    }
}
