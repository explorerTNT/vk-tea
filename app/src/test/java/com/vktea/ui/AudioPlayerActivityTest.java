package com.vktea.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.MediaSession;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import com.vktea.R;
import com.vktea.audio.AudioUi;
import com.vktea.auth.Session;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** A real in-process media session drives a deterministic player with no media or network I/O. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AudioPlayerActivityTest {
    private final List<ListenableFuture<MediaController>> connections = new ArrayList<>();
    private ActivityController<TestAudioPlayerActivity> activityController;
    private Context context;
    private OfflinePlayer player;
    private MediaSession session;
    private SettableFuture<MediaController> pendingConnection;

    @Before
    public void createOfflineSession() {
        context = RuntimeEnvironment.getApplication();
        clearPreferences();
        Session.save(context, "synthetic-player-ui-session", "1");
        player = new OfflinePlayer();
        session = new MediaSession.Builder(context, player).build();
        TestAudioPlayerActivity.connectCalls.set(0);
        TestAudioPlayerActivity.connectionFactory = this::connect;
    }

    @After
    public void releaseUiAndOfflineSession() {
        if (activityController != null) activityController.close();
        if (pendingConnection != null && !pendingConnection.isDone()) pendingConnection.cancel(false);
        for (ListenableFuture<MediaController> connection : connections) {
            MediaController.releaseFuture(connection);
        }
        session.release();
        player.release();
        shadowOf(Looper.getMainLooper()).idle();
        TestAudioPlayerActivity.connectionFactory = null;
        clearPreferences();
    }

    @Test
    public void unauthorizedActivityFinishesWithoutConnectingOrOpeningLogin() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        launch();

        assertTrue(activity().isFinishing());
        assertEquals(0, TestAudioPlayerActivity.connectCalls.get());
        assertNull(shadowOf(activity()).getNextStartedActivity());
    }

    @Test
    public void emptyQueueHasNoInventedMetadataAndDisablesPlaybackActions() throws Exception {
        launch();
        await(() -> connections.get(0).isDone());
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(context.getString(R.string.audio_untitled), text(R.id.audio_player_title));
        assertEquals("", text(R.id.audio_player_artist));
        assertEquals("0:00", text(R.id.audio_player_elapsed));
        assertEquals("0:00", text(R.id.audio_player_duration));
        for (int id : new int[]{R.id.audio_player_play, R.id.audio_player_previous,
                R.id.audio_player_next, R.id.audio_player_queue, R.id.audio_player_save,
                R.id.audio_player_mute, R.id.audio_player_shuffle, R.id.audio_player_repeat,
                R.id.audio_player_download, R.id.audio_player_menu, R.id.audio_player_seek}) {
            assertFalse("Empty queue action " + id, activity().findViewById(id).isEnabled());
        }
        assertTrue(activity().findViewById(R.id.audio_player_collapse).isEnabled());
        assertEquals(View.VISIBLE, activity().findViewById(R.id.audio_player_cover_placeholder).getVisibility());
    }

    @Test
    public void populatedUiShowsActualMetadataAndButtonsControlTheSession() throws Exception {
        player.setMediaItems(Arrays.asList(track("first", "Synthetic title"), track("second", "Other title")));
        player.setVolume(0.42f);
        launch();
        await(() -> text(R.id.audio_player_title).equals("Synthetic title"));

        assertTrue(text(R.id.audio_player_artist).contains("Synthetic artist"));
        assertTrue(text(R.id.audio_player_artist).contains("320 kbps"));
        assertTrue(text(R.id.audio_player_artist).contains("МБ"));
        assertEquals("3:00", text(R.id.audio_player_duration));
        assertNotNull(activity().findViewById(R.id.audio_player_download));
        assertFalse(AudioUi.isSaved(context, player.getCurrentMediaItem()));

        click(R.id.audio_player_save);
        assertTrue(AudioUi.isSaved(context, player.getCurrentMediaItem()));
        assertEquals(context.getString(R.string.audio_player_unsave),
                activity().findViewById(R.id.audio_player_save).getContentDescription());
        click(R.id.audio_player_play);
        await(player::getPlayWhenReady);
        await(() -> activity().findViewById(R.id.audio_player_play).getContentDescription()
                .equals(context.getString(R.string.audio_player_pause)));
        assertTrue("Controller must acknowledge play before a second explicit toggle",
                connections.get(0).get().getPlayWhenReady());
        click(R.id.audio_player_play);
        await(() -> !player.getPlayWhenReady());

        click(R.id.audio_player_next);
        await(() -> player.getCurrentMediaItemIndex() == 1);
        await(() -> text(R.id.audio_player_title).equals("Other title"));
        click(R.id.audio_player_previous);
        await(() -> player.getCurrentMediaItemIndex() == 0);
        click(R.id.audio_player_shuffle);
        await(player::getShuffleModeEnabled);
        click(R.id.audio_player_repeat);
        await(() -> player.getRepeatMode() == Player.REPEAT_MODE_ALL);
        click(R.id.audio_player_repeat);
        await(() -> player.getRepeatMode() == Player.REPEAT_MODE_ONE);
        await(() -> activity().findViewById(R.id.audio_player_repeat_one).getVisibility() == View.VISIBLE);

        click(R.id.audio_player_mute);
        await(() -> player.getVolume() == 0);
        click(R.id.audio_player_mute);
        await(() -> Math.abs(player.getVolume() - 0.42f) < 0.001f);
        assertEquals(1, TestAudioPlayerActivity.connectCalls.get());
    }

    @Test
    public void stopReleasesControllerButKeepsServicePlayerQueueAndPlaybackIntent() throws Exception {
        player.setMediaItem(track("first", "Synthetic title"));
        player.play();
        launch();
        await(() -> connections.get(0).isDone());
        MediaController connected = connections.get(0).get();
        assertTrue(connected.isConnected());

        activityController.pause().stop();
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse(connected.isConnected());
        assertEquals(1, player.getMediaItemCount());
        assertTrue(player.getPlayWhenReady());
        assertFalse(activity().isFinishing());
    }

    @Test
    public void stopCancelsPendingConnectionAndRejectsLateCompletionWithoutRebindingUi() {
        pendingConnection = SettableFuture.create();
        TestAudioPlayerActivity.connectionFactory = listener -> pendingConnection;
        launch();
        activityController.pause().stop();

        // MediaController.releaseFuture first cancels a pending builder future.
        // A plain SettableFuture has no MediaControllerHolder, so constructing an
        // unrelated real controller after cancellation would test the fixture's
        // resource ownership, not the Activity's lifecycle.
        assertTrue(pendingConnection.isCancelled());
        assertFalse(pendingConnection.setException(new IllegalStateException("Synthetic late result")));
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, TestAudioPlayerActivity.connectCalls.get());
        assertFalse(activity().findViewById(R.id.audio_player_play).isEnabled());
    }

    @Test
    @Config(sdk = 35, qualifiers = "w360dp-h800dp-mdpi")
    public void insetsKeepBlueNavigationAreaAndSquarePortraitCover() {
        launch();
        LinearLayout root = activity().findViewById(R.id.audio_player_root);
        ViewCompat.dispatchApplyWindowInsets(root, new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, dp(24), 0, dp(24)))
                .build());
        layout(root, dp(360), dp(800));

        assertEquals(dp(24), root.getPaddingBottom());
        assertEquals(ContextCompat.getColor(context, R.color.vk_blue),
                ((ColorDrawable) root.getBackground()).getColor());
        // Android 15 enforces a transparent gesture bar; the root paints blue
        // behind its bottom inset instead of relying on the deprecated setter.
        assertEquals(Color.TRANSPARENT, activity().getWindow().getNavigationBarColor());
        assertEquals(dp(360), activity().findViewById(R.id.audio_player_cover).getHeight());
        assertEquals(ContextCompat.getColor(context, R.color.audio_artwork),
                ((ColorDrawable) activity().findViewById(R.id.audio_player_cover).getBackground()).getColor());
    }

    @Test
    @Config(qualifiers = "land")
    public void shortLandscapeKeepsAllControlsInScrollableWhiteBody() {
        launch();
        LinearLayout root = activity().findViewById(R.id.audio_player_root);
        layout(root, dp(720), dp(320));

        assertEquals(LinearLayout.HORIZONTAL, root.getOrientation());
        assertTrue(activity().findViewById(R.id.audio_player_cover).getWidth() > 0);
        assertTrue(activity().findViewById(R.id.audio_player_body_scroll).getWidth() > 0);
        assertNotNull(activity().findViewById(R.id.audio_player_menu));
        assertNotNull(activity().findViewById(R.id.audio_player_seek));
    }

    private void launch() {
        activityController = Robolectric.buildActivity(TestAudioPlayerActivity.class).setup();
    }

    private TestAudioPlayerActivity activity() {
        return activityController.get();
    }

    private ListenableFuture<MediaController> connect(MediaController.Listener listener) {
        ListenableFuture<MediaController> connection = new MediaController.Builder(context, session.getToken())
                .setListener(listener).buildAsync();
        connections.add(connection);
        return connection;
    }

    private String text(int id) {
        return ((TextView) activity().findViewById(id)).getText().toString();
    }

    private void click(int id) {
        assertTrue("Expected enabled player button " + id, activity().findViewById(id).isEnabled());
        ((ImageButton) activity().findViewById(id)).performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static MediaItem track(String id, String title) {
        Bundle extras = new Bundle();
        extras.putLong(AudioUi.EXTRA_DURATION_MS, 180_000);
        extras.putLong(AudioUi.EXTRA_BITRATE, 320_000);
        extras.putLong(AudioUi.EXTRA_FILE_SIZE, 11_640_832);
        return new MediaItem.Builder().setMediaId(id)
                .setUri("content://synthetic.player.ui/" + id)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(title)
                        .setArtist("Synthetic artist").setExtras(extras).build()).build();
    }

    private void clearPreferences() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("audio_marks", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void layout(View view, int width, int height) {
        for (int pass = 0; pass < 2; pass++) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, width, height);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(1);
        }
        assertTrue("Offline player did not reach the requested UI state", condition.getAsBoolean());
    }

    interface ConnectionFactory {
        ListenableFuture<MediaController> connect(MediaController.Listener listener);
    }

    public static class TestAudioPlayerActivity extends AudioPlayerActivity {
        static ConnectionFactory connectionFactory;
        static final AtomicInteger connectCalls = new AtomicInteger();

        @Override
        protected ListenableFuture<MediaController> connectController(MediaController.Listener listener) {
            connectCalls.incrementAndGet();
            if (connectionFactory == null) throw new IllegalStateException("Offline session was not installed");
            return connectionFactory.connect(listener);
        }
    }

    /** SimpleBasePlayer supplies consistent timeline/state/events without a decoder or source. */
    private static final class OfflinePlayer extends SimpleBasePlayer {
        private State state = new State.Builder()
                .setAvailableCommands(new Commands.Builder().addAllCommands().build()).build();

        OfflinePlayer() {
            super(Looper.getMainLooper());
        }

        @Override
        protected State getState() {
            return state;
        }

        @Override
        protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> items, int startIndex,
                                                          long startPositionMs) {
            List<MediaItemData> playlist = new ArrayList<>();
            for (MediaItem item : items) {
                playlist.add(new MediaItemData.Builder(item.mediaId).setMediaItem(item)
                        .setMediaMetadata(item.mediaMetadata).setIsSeekable(true)
                        .setDurationUs(180_000_000L).build());
            }
            state = state.buildUpon().setPlaylist(playlist)
                    .setCurrentMediaItemIndex(items.isEmpty() ? C.INDEX_UNSET
                            : startIndex == C.INDEX_UNSET ? 0 : startIndex)
                    .setContentPositionMs(startPositionMs == C.TIME_UNSET ? 0 : startPositionMs)
                    .setPlaybackState(items.isEmpty() ? STATE_IDLE : STATE_READY).build();
            return Futures.immediateVoidFuture();
        }

        @Override
        protected ListenableFuture<?> handleSetPlayWhenReady(boolean value) {
            state = state.buildUpon().setPlayWhenReady(value, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build();
            return Futures.immediateVoidFuture();
        }

        @Override
        protected ListenableFuture<?> handlePrepare() {
            state = state.buildUpon().setPlaybackState(getMediaItemCount() == 0 ? STATE_IDLE : STATE_READY).build();
            return Futures.immediateVoidFuture();
        }

        @Override
        protected ListenableFuture<?> handleSetRepeatMode(int value) {
            state = state.buildUpon().setRepeatMode(value).build();
            return Futures.immediateVoidFuture();
        }

        @Override
        protected ListenableFuture<?> handleSetShuffleModeEnabled(boolean value) {
            state = state.buildUpon().setShuffleModeEnabled(value).build();
            return Futures.immediateVoidFuture();
        }

        @Override
        protected ListenableFuture<?> handleSetVolume(float value) {
            state = state.buildUpon().setVolume(value).build();
            return Futures.immediateVoidFuture();
        }

        @Override
        protected ListenableFuture<?> handleSeek(int index, long positionMs, int command) {
            state = state.buildUpon().setCurrentMediaItemIndex(index == C.INDEX_UNSET
                            ? getCurrentMediaItemIndex() : index)
                    .setContentPositionMs(positionMs == C.TIME_UNSET ? 0 : positionMs).build();
            return Futures.immediateVoidFuture();
        }

        @Override
        protected ListenableFuture<?> handleRelease() {
            return Futures.immediateVoidFuture();
        }
    }
}
