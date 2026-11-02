package com.vktea.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.AlarmManager;
import android.content.Context;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.vktea.auth.Session;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.Arrays;

@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AudioSleepTimerTest {
    private Context context;
    private ServiceController<AudioPlaybackService> controller;
    private AudioPlaybackService service;
    private ExoPlayer player;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Session.save(context, "synthetic-session", "synthetic-user");
        shadowOf((Application) context).grantPermissions(
                context.getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        controller = Robolectric.buildService(AudioPlaybackService.class).create();
        service = controller.get();
        player = ReflectionHelpers.getField(service, "player");
        assertNotNull(player);
        player.setMediaItem(MediaItem.fromUri("content://synthetic.audio/track"));
        player.play();
        assertTrue(player.getPlayWhenReady());
    }

    @After public void tearDown() {
        if (controller != null) controller.destroy();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void timedSleepPausesPlaybackWithoutClearingQueue() {
        AudioSleepTimer.request(context, 1_000);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(AudioSleepTimer.remaining(context) > 0);
        assertNotNull(shadowOf((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).peekNextScheduledAlarm());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_001));
        assertFalse(player.getPlayWhenReady());
        assertEquals(1, player.getMediaItemCount());
        assertEquals(AudioSleepTimer.OFF, AudioSleepTimer.remaining(context));
        assertNull(shadowOf((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).peekNextScheduledAlarm());
    }

    @Test public void cancellationAndReplacementDoNotFireAnOldTimer() {
        AudioSleepTimer.request(context, 1_000);
        shadowOf(Looper.getMainLooper()).idle();
        AudioSleepTimer.request(context, 5_000);
        shadowOf(Looper.getMainLooper()).idle();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100));
        assertTrue(player.getPlayWhenReady());
        AudioSleepTimer.request(context, AudioSleepTimer.OFF);
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).peekNextScheduledAlarm());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        assertTrue(player.getPlayWhenReady());
        assertEquals(AudioSleepTimer.OFF, AudioSleepTimer.remaining(context));
    }

    @Test public void endOfTrackUsesPlayerBoundaryAndClearsItAfterward() {
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        AudioSleepTimer.request(context, AudioSleepTimer.END_OF_TRACK);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(player.getPauseAtEndOfMediaItems());
        assertEquals(Player.REPEAT_MODE_ONE, player.getRepeatMode());
        assertEquals(AudioSleepTimer.END_OF_TRACK, AudioSleepTimer.remaining(context));
        AudioSleepTimer timer = ReflectionHelpers.getField(service, "sleepTimer");
        timer.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM);
        assertFalse(player.getPauseAtEndOfMediaItems());
        assertEquals(Player.REPEAT_MODE_ONE, player.getRepeatMode());
        assertEquals(AudioSleepTimer.OFF, AudioSleepTimer.remaining(context));
    }

    @Test public void manualTrackChangeCancelsEndOfTrackTimer() {
        player.setMediaItems(Arrays.asList(MediaItem.fromUri("content://synthetic.audio/first"),
                MediaItem.fromUri("content://synthetic.audio/second")));
        shadowOf(Looper.getMainLooper()).idle();
        AudioSleepTimer.request(context, AudioSleepTimer.END_OF_TRACK);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(player.getPauseAtEndOfMediaItems());
        player.seekTo(1, 0);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(AudioSleepTimer.OFF, AudioSleepTimer.remaining(context));
        assertFalse(player.getPauseAtEndOfMediaItems());
        assertTrue(player.getPlayWhenReady());
    }

    @Test public void logoutOrServiceDestructionClearsTimer() {
        AudioSleepTimer.request(context, 1_000);
        shadowOf(Looper.getMainLooper()).idle();
        Session.clear(context);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(AudioSleepTimer.OFF, AudioSleepTimer.remaining(context));
        assertNull(shadowOf((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).peekNextScheduledAlarm());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(AudioSleepTimer.OFF, AudioSleepTimer.remaining(context));
    }
}
