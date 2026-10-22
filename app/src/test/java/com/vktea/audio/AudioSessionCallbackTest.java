package com.vktea.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionResult;

import com.vktea.auth.Session;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AudioSessionCallbackTest {

    private Context context;
    private ExoPlayer player;
    private MediaSession session;
    private AudioSessionCallback callback;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Session.save(context, "synthetic-test-session", "test-user");
        player = new ExoPlayer.Builder(context).build();
        callback = new AudioSessionCallback(context, () -> true);
        session = new MediaSession.Builder(context, player).setCallback(callback).build();
    }

    @After
    public void tearDown() {
        session.release();
        player.release();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void spoofedPackageCannotConnectButTrustedTransportWorks() {
        MediaSession.ControllerInfo impostor = controller(context.getPackageName(), false, false);
        assertFalse(callback.onConnect(session, impostor).isAccepted);

        MediaSession.ConnectionResult remote = callback.onConnect(session,
                controller("synthetic.system.controls", false, true));
        assertTrue(remote.isAccepted);
        assertTrue(remote.availablePlayerCommands.contains(Player.COMMAND_PLAY_PAUSE));
        assertTrue(remote.availablePlayerCommands.contains(Player.COMMAND_SEEK_TO_NEXT));
        assertFalse(remote.availablePlayerCommands.contains(Player.COMMAND_SET_MEDIA_ITEM));
        assertFalse(remote.availablePlayerCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS));
        assertFalse(remote.availablePlayerCommands.contains(Player.COMMAND_SET_PLAYLIST_METADATA));
        assertTrue(callback.onConnect(session, owner()).availablePlayerCommands
                .contains(Player.COMMAND_CHANGE_MEDIA_ITEMS));
    }

    @Test
    public void trustedRemoteCannotResolveOrInjectAnArbitraryUri() throws Exception {
        MediaSession.ControllerInfo remote = controller("synthetic.system.controls", false, true);
        assertEquals(SessionResult.RESULT_ERROR_PERMISSION_DENIED,
                callback.onPlayerCommandRequest(session, remote, Player.COMMAND_CHANGE_MEDIA_ITEMS));
        try {
            callback.onAddMediaItems(session, remote,
                    Collections.singletonList(track("https://example.invalid/private.mp3"))).get();
            fail("A transport controller must not supply a source");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof SecurityException);
        }
    }

    @Test
    public void invalidItemRejectsEntireReplacementWithoutLeakingItsUri() throws Exception {
        MediaItem existing = track("content://synthetic.audio/original");
        player.setMediaItem(existing);
        String rejected = "file:///private/synthetic-secret.mp3";
        try {
            callback.onSetMediaItems(session, owner(), Arrays.asList(
                    track("content://synthetic.audio/valid"), track(rejected)), 0, 0).get();
            fail("A partially valid queue must be rejected atomically");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
            assertFalse(expected.getCause().getMessage().contains(rejected));
            assertNull(expected.getCause().getCause());
        }
        assertEquals(1, player.getMediaItemCount());
        assertEquals(existing, player.getCurrentMediaItem());
    }

    @Test
    public void acceptedSourcesKeepDisplayMetadataAndDiscardRequestExtras() throws Exception {
        Bundle extras = new Bundle();
        extras.putString("synthetic_private_value", "must-not-reach-system-controls");
        MediaItem incoming = new MediaItem.Builder()
                .setMediaId("https://example.invalid/audio.mp3?signature=synthetic")
                .setUri("https://example.invalid/audio.mp3?signature=synthetic")
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle("Synthetic track")
                        .setArtist("Test artist")
                        .setArtworkUri(Uri.parse("file:///private/artwork"))
                        .setExtras(extras)
                        .build())
                .build();
        List<MediaItem> accepted = callback.onAddMediaItems(session, owner(), Arrays.asList(
                incoming, track("content://synthetic.audio/2"))).get();
        assertEquals(2, accepted.size());
        assertEquals(incoming.localConfiguration.uri, accepted.get(0).localConfiguration.uri);
        assertEquals("Synthetic track", accepted.get(0).mediaMetadata.title);
        assertEquals("Test artist", accepted.get(0).mediaMetadata.artist);
        assertFalse(accepted.get(0).mediaId.contains("://"));
        assertNull(accepted.get(0).mediaMetadata.extras);
        assertNull(accepted.get(0).mediaMetadata.artworkUri);
    }

    @Test
    public void revokedAuthorizationBlocksAlreadyConnectedController() throws Exception {
        assertTrue(callback.onConnect(session, owner()).isAccepted);
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
        assertEquals(SessionResult.RESULT_ERROR_PERMISSION_DENIED,
                callback.onPlayerCommandRequest(session, owner(), Player.COMMAND_PLAY_PAUSE));
        assertFalse(callback.onConnect(session, owner()).isAccepted);
        try {
            callback.onAddMediaItems(session, owner(),
                    Collections.singletonList(track("content://synthetic.audio/1"))).get();
            fail("Logged out clients must not change the queue");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof SecurityException);
        }
    }

    @Test
    public void malformedAndUnsafeSourcesAreRejected() throws Exception {
        for (String source : Arrays.asList("http://example.invalid/audio.mp3",
                "https://user:synthetic@example.invalid/audio.mp3", "https:///audio.mp3",
                "https://example.invalid:70000/audio.mp3", "content:opaque",
                "content:///audio", "https://example.invalid/a b.mp3", "data:audio/mp3;base64,AA")) {
            try {
                callback.onAddMediaItems(session, owner(),
                        Collections.singletonList(track(source))).get();
                fail("Unsafe or malformed source accepted");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalArgumentException);
            }
        }
    }

    private MediaSession.ControllerInfo owner() {
        return controller(context.getPackageName(), true, false);
    }

    static MediaSession.ControllerInfo controller(String packageName, boolean owner, boolean trusted) {
        return MediaSession.ControllerInfo.createTestOnlyControllerInfo(packageName, Process.myPid(),
                owner ? Process.myUid() : Process.myUid() + 1, 1, 1, trusted, Bundle.EMPTY);
    }

    private static MediaItem track(String uri) {
        return new MediaItem.Builder().setUri(uri).build();
    }
}
