package com.vktea.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Dialog;
import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.widget.PopupMenu;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.vktea.R;

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
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowPopupMenu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Offline UI actions use real ExoPlayer timelines without preparing a media source. */
@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AudioUiTest {
    private Context context;
    private final List<ExoPlayer> timelines = new ArrayList<>();
    private ActivityController<AppCompatActivity> activityController;

    @Before
    public void clearSyntheticBookmarks() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("audio_marks", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After
    public void closeUiAndPlayers() {
        for (Dialog dialog : new ArrayList<>(ShadowDialog.getShownDialogs())) {
            if (dialog.isShowing()) dialog.dismiss();
        }
        PopupMenu popup = ShadowPopupMenu.getLatestPopupMenu();
        if (popup != null) popup.dismiss();
        for (ExoPlayer timeline : timelines) timeline.release();
        if (activityController != null) activityController.close();
        context.getSharedPreferences("audio_marks", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void elapsedTimeHandlesUnknownNegativeAndHourBoundaries() {
        assertEquals("0:00", AudioUi.formatTime(C.TIME_UNSET));
        assertEquals("0:00", AudioUi.formatTime(-1));
        assertEquals("0:00", AudioUi.formatTime(999));
        assertEquals("0:59", AudioUi.formatTime(59_999));
        assertEquals("1:00", AudioUi.formatTime(60_000));
        assertEquals("59:59", AudioUi.formatTime(3_599_999));
        assertEquals("1:00:00", AudioUi.formatTime(3_600_000));
        assertEquals("25:01:01", AudioUi.formatTime(90_061_000));
    }

    @Test
    public void bookmarksFollowStableSourceIdentityAcrossReimportAndMetadataChanges() {
        MediaItem imported = track("one", "Synthetic original title");
        MediaItem reimported = track("one", "Synthetic renamed title");
        MediaItem other = track("two", "Synthetic original title");

        assertEquals(imported.mediaId, reimported.mediaId);
        assertNotEquals(imported.mediaId, other.mediaId);
        assertFalse(AudioUi.isSaved(context, imported));

        AudioUi.toggleSaved(context, imported);

        assertTrue(AudioUi.isSaved(context, reimported));
        assertFalse(AudioUi.isSaved(context, other));

        AudioUi.toggleSaved(context, reimported);

        assertFalse(AudioUi.isSaved(context, imported));
        assertFalse(AudioUi.isSaved(context, other));
    }

    @Test
    public void repeatCyclesThroughOffAllAndOne() {
        RecordingPlayer player = newPlayer();
        assertEquals(Player.REPEAT_MODE_OFF, player.getRepeatMode());

        AudioUi.toggleRepeat(player);
        assertEquals(Player.REPEAT_MODE_ALL, player.getRepeatMode());
        AudioUi.toggleRepeat(player);
        assertEquals(Player.REPEAT_MODE_ONE, player.getRepeatMode());
        AudioUi.toggleRepeat(player);
        assertEquals(Player.REPEAT_MODE_OFF, player.getRepeatMode());
    }

    @Test
    public void emptyQueueDoesNotAttemptPlayback() {
        RecordingPlayer player = newPlayer();

        AudioUi.togglePlay(player);

        assertTrue(player.calls.isEmpty());
        assertFalse(player.getPlayWhenReady());
    }

    @Test
    public void idleItemIsPreparedBeforePlayingAndTheNextTapPauses() {
        RecordingPlayer player = newPlayer();
        player.setMediaItem(track("idle", "Synthetic idle track"));

        AudioUi.togglePlay(player);

        assertEquals(Arrays.asList("prepare", "play"), player.calls);
        assertTrue(player.getPlayWhenReady());
        player.calls.clear();
        AudioUi.togglePlay(player);
        assertEquals(Collections.singletonList("pause"), player.calls);
        assertFalse(player.getPlayWhenReady());
    }

    @Test
    public void endedItemRestartsAtItsDefaultPositionBeforePlaying() {
        RecordingPlayer player = newPlayer();
        player.setMediaItem(track("ended", "Synthetic ended track"));
        player.seekTo(12_000);
        player.reportedState = Player.STATE_ENDED;

        AudioUi.togglePlay(player);

        assertEquals(Arrays.asList("seek-default", "play"), player.calls);
        assertEquals(0, player.getCurrentPosition());
        assertTrue(player.getPlayWhenReady());
    }

    @Test
    public void endedAutoplayRestartsInOneTapWhenPlayWhenReadyWasRetained() {
        RecordingPlayer player = newPlayer();
        player.setMediaItem(track("ended-autoplay", "Synthetic ended autoplay"));
        player.seekTo(12_000);
        player.setPlayWhenReady(true);
        player.reportedState = Player.STATE_ENDED;

        AudioUi.togglePlay(player);

        assertEquals(Arrays.asList("seek-default", "play"), player.calls);
        assertEquals(0, player.getCurrentPosition());
        assertTrue(player.getPlayWhenReady());
    }

    @Test
    public void failedAutoplayRetriesInOneTapEvenWhenPlayWhenReadyWasRetained() {
        RecordingPlayer player = newPlayer();
        player.setMediaItem(track("failed", "Synthetic failed track"));
        player.setPlayWhenReady(true);
        player.reportedError = new PlaybackException("Synthetic playback failure", null,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED);

        AudioUi.togglePlay(player);

        assertEquals(Arrays.asList("prepare", "play"), player.calls);
        assertTrue(player.getPlayWhenReady());
    }

    @Test
    public void queueDialogResolvesSelectedIdentityAfterTimelinePositionsChange() {
        RecordingPlayer player = newPlayer();
        MediaItem first = track("first", "Synthetic first");
        MediaItem selected = track("selected", "Synthetic selected");
        MediaItem last = track("last", "Synthetic last");
        player.setMediaItems(Arrays.asList(first, selected, last));
        AudioUi.showQueue(activity(), player);
        AlertDialog dialog = latestQueueDialog();

        player.removeMediaItem(0);
        dialog.getListView().performItemClick(null, 1, 1);

        assertEquals(selected.mediaId, player.getCurrentMediaItem().mediaId);
        assertEquals(0, player.getCurrentMediaItemIndex());
        assertEquals(Arrays.asList("seek-default:0", "prepare", "play"), player.calls);
        assertFalse(dialog.isShowing());
    }

    @Test
    public void queueDialogDoesNotPlayAnotherItemWhenItsCapturedItemWasRemoved() {
        RecordingPlayer player = newPlayer();
        MediaItem first = track("first", "Synthetic first");
        player.setMediaItems(Arrays.asList(first, track("removed", "Synthetic removed")));
        AudioUi.showQueue(activity(), player);
        AlertDialog dialog = latestQueueDialog();

        player.removeMediaItem(1);
        dialog.getListView().performItemClick(null, 1, 1);

        assertEquals(first.mediaId, player.getCurrentMediaItem().mediaId);
        assertTrue(player.calls.isEmpty());
        assertFalse(player.getPlayWhenReady());
        assertFalse(dialog.isShowing());
    }

    @Test
    public void removalMenuResolvesCapturedIdentityAfterTimelinePositionsChange() {
        RecordingPlayer player = newPlayer();
        MediaItem selected = track("selected", "Synthetic selected");
        MediaItem last = track("last", "Synthetic last");
        player.setMediaItems(Arrays.asList(track("first", "Synthetic first"), selected, last));
        AppCompatActivity activity = activity();
        View anchor = new View(activity);
        activity.setContentView(anchor);
        activityController.visible();
        AudioUi.showActions(activity, anchor, player, 1);
        PopupMenu popup = ShadowPopupMenu.getLatestPopupMenu();
        assertNotNull(popup);

        player.removeMediaItem(0);
        assertTrue(shadowOf(popup).getOnMenuItemClickListener()
                .onMenuItemClick(popup.getMenu().findItem(6)));

        assertEquals(1, player.getMediaItemCount());
        assertEquals(last.mediaId, player.getMediaItemAt(0).mediaId);
    }

    private AppCompatActivity activity() {
        if (activityController == null) {
            activityController = Robolectric.buildActivity(AppCompatActivity.class);
            activityController.get().setTheme(R.style.Theme_VKTea);
            activityController.setup();
        }
        return activityController.get();
    }

    private static AlertDialog latestQueueDialog() {
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(dialog instanceof AlertDialog);
        return (AlertDialog) dialog;
    }

    private RecordingPlayer newPlayer() {
        ExoPlayer timeline = new ExoPlayer.Builder(context).setLooper(Looper.getMainLooper()).build();
        timelines.add(timeline);
        return new RecordingPlayer(timeline);
    }

    private static MediaItem track(String source, String title) {
        Uri uri = Uri.parse("content://synthetic.audio/" + source);
        return new MediaItem.Builder().setMediaId(AudioUi.mediaId(uri)).setUri(uri)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(title)
                        .setArtist("Synthetic artist").build()).build();
    }

    /** Keeps real timeline mutations while recording transport commands without I/O. */
    private static final class RecordingPlayer extends ForwardingPlayer {
        final List<String> calls = new ArrayList<>();
        int reportedState = Player.STATE_IDLE;
        PlaybackException reportedError;

        RecordingPlayer(Player player) { super(player); }

        @Override public int getPlaybackState() { return reportedState; }
        @Override public PlaybackException getPlayerError() { return reportedError; }
        @Override public void prepare() {
            calls.add("prepare");
            reportedState = Player.STATE_BUFFERING;
            reportedError = null;
        }
        @Override public void play() { calls.add("play"); super.play(); }
        @Override public void pause() { calls.add("pause"); super.pause(); }
        @Override public void seekToDefaultPosition() {
            calls.add("seek-default");
            super.seekToDefaultPosition();
        }
        @Override public void seekToDefaultPosition(int index) {
            calls.add("seek-default:" + index);
            super.seekToDefaultPosition(index);
        }
    }
}
