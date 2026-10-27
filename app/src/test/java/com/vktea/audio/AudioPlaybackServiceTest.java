package com.vktea.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaSession;

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

@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AudioPlaybackServiceTest {

    private Context context;
    private ServiceController<AudioPlaybackService> controller;
    private AudioPlaybackService service;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Session.save(context, "synthetic-test-session", "42");
        shadowOf(Looper.getMainLooper()).idle();
        Application application = RuntimeEnvironment.getApplication();
        // Android grants the merged manifest's signature permission to the app;
        // Robolectric's API 28 permission shadow needs that grant explicitly.
        shadowOf(application).grantPermissions(
                context.getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        controller = Robolectric.buildService(AudioPlaybackService.class);
        controller.create();
        service = controller.get();
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.destroy();
        }
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void stopClearsQueueBeforeServiceIsDestroyed() {
        MediaSession session = service.onGetSession(owner());
        assertNotNull(session);
        Player player = session.getPlayer();
        player.setMediaItem(MediaItem.fromUri("content://synthetic.audio/queued"));
        player.play();
        assertTrue(player.getPlayWhenReady());

        AudioPlayback.stop(context);
        shadowOf(Looper.getMainLooper()).idle();

        // A bound service may outlive stopSelf. Playback must already be gone;
        // merely relying on a future onDestroy would leave audio playing.
        assertFalse(player.getPlayWhenReady());
        assertEquals(0, player.getMediaItemCount());
        assertNull(service.onGetSession(owner()));
        assertTrue(shadowOf(service).isStoppedBySelf());
    }

    @Test
    public void authorizationRemovalAlsoStopsPlaybackWithoutUiHelper() {
        Player player = service.onGetSession(owner()).getPlayer();
        player.setMediaItem(MediaItem.fromUri("content://synthetic.audio/queued"));
        player.play();

        // Deliberately bypass Session.clear's UI hook to exercise service-side
        // revocation instead of just testing the stop broadcast twice. Unlike
        // clear(), removing a key also notifies listeners before Android 11.
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit()
                .remove("access_token").commit();
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse(player.getPlayWhenReady());
        assertEquals(0, player.getMediaItemCount());
        assertNull(service.onGetSession(owner()));
        assertTrue(shadowOf(service).isStoppedBySelf());
    }

    @Test
    public void accountSwitchStopsPlaybackAndClearsQueue() {
        Player player = service.onGetSession(owner()).getPlayer();
        player.setMediaItem(MediaItem.fromUri("content://synthetic.audio/queued"));
        player.play();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit()
                .putString("account.73.token", "synthetic-other-account")
                .putString("account.73.secret", "").commit();

        assertTrue(Session.switchTo(context, "73"));
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse(player.getPlayWhenReady());
        assertEquals(0, player.getMediaItemCount());
        assertNull(service.onGetSession(owner()));
        assertTrue(shadowOf(service).isStoppedBySelf());
    }

    @Test
    public void delayedStopForPreviousAccountDoesNotStopNewService() {
        long previousGeneration = Session.generation();
        Session.save(context, "synthetic-new-session", "73");
        shadowOf(Looper.getMainLooper()).idle();
        controller.destroy();
        controller = Robolectric.buildService(AudioPlaybackService.class);
        controller.create();
        service = controller.get();
        Player player = service.onGetSession(owner()).getPlayer();
        player.setMediaItem(MediaItem.fromUri("content://synthetic.audio/new-queue"));
        AudioPlayback.stop(context, previousGeneration);
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(service.onGetSession(owner()));
        assertEquals(1, player.getMediaItemCount());
        assertFalse(shadowOf(service).isStoppedBySelf());
    }

    @Test
    public void dismissingIdleTaskDisconnectsSessionAndDropsItsQueue() {
        Player player = service.onGetSession(owner()).getPlayer();
        player.setMediaItem(MediaItem.fromUri("content://synthetic.audio/queued"));
        service.onTaskRemoved(new Intent());

        assertEquals(0, player.getMediaItemCount());
        assertNull(service.onGetSession(owner()));
        assertTrue(shadowOf(service).isStoppedBySelf());
    }

    @Test
    public void serviceDoesNotExposeSessionToAnUntrustedPackage() {
        assertNull(service.onGetSession(AudioSessionCallbackTest.controller(
                context.getPackageName(), false, false)));
        assertNotNull(service.onGetSession(AudioSessionCallbackTest.controller(
                "synthetic.system.controls", false, true)));
    }

    private MediaSession.ControllerInfo owner() {
        return AudioSessionCallbackTest.controller(context.getPackageName(), true, false);
    }
}
