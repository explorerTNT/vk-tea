package com.vktea.msg;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.Player;
import com.vktea.auth.Session;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Fake players never create network connections or access the microphone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class VoiceMessagePlayerTest {
    private ActivityController<AppCompatActivity> controller;
    private VoiceMessagePlayer subject;
    private final List<FakePlayer> players = new ArrayList<>();
    private ChatAttachment shown;
    private boolean playing;
    private long position, duration;
    private int notifications;

    @Before public void setup() {
        controller = Robolectric.buildActivity(AppCompatActivity.class).setup();
        subject = new VoiceMessagePlayer(controller.get(), (a, p, pos, d) -> {
            shown = a; playing = p; position = pos; duration = d; notifications++;
        }, context -> {
            FakePlayer fake = new FakePlayer(); players.add(fake); return fake.player;
        });
    }

    @After public void close() { subject.close(); controller.close(); }

    private static ChatAttachment voice(long owner, long id, String url) {
        return new ChatAttachment("audio_message", "Voice", "", "", url,
                0, 0, 12, id, owner, "");
    }

    @Test public void togglesSameVoiceAcrossReloadsAndUpdatesProgress() {
        ChatAttachment original = voice(1, 42, "https://example.invalid/old");
        ChatAttachment refreshed = voice(1, 42, "https://example.invalid/new");
        subject.toggle(original);
        assertTrue(playing);
        assertEquals(1, players.size());
        FakePlayer fake = players.get(0);
        fake.position = 3100;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        assertEquals(3100, position);
        assertEquals(12000, duration);
        subject.toggle(refreshed);
        assertFalse(playing);
        assertEquals(1, players.size());
        assertEquals(1, fake.pauses);
        subject.seek(refreshed, .5f);
        assertEquals(6000, fake.position);
        assertEquals(6000, position);
        subject.toggle(refreshed);
        assertTrue(playing);
    }

    @Test public void changingVoiceReleasesPreviousAndIgnoresItsLateCallbacks() {
        ChatAttachment first = voice(1, 1, "https://example.invalid/first");
        ChatAttachment second = voice(1, 2, "https://example.invalid/second");
        subject.toggle(first);
        FakePlayer previous = players.get(0);
        subject.toggle(second);
        assertTrue(previous.released);
        assertSame(second, shown);
        int before = notifications;
        previous.listener.onPlaybackStateChanged(Player.STATE_ENDED);
        assertEquals(before, notifications);
        subject.close();
        assertTrue(players.get(1).released);
        assertNull(shown);
        before = notifications;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(before, notifications);
    }

    @Test public void seekClampsAndRejectsDifferentVoice() {
        ChatAttachment first = voice(1, 1, "https://example.invalid/first");
        subject.toggle(first);
        FakePlayer fake = players.get(0);
        subject.seek(voice(1, 2, "https://example.invalid/other"), .8f);
        assertEquals(0, fake.position);
        subject.seek(first, 2f);
        assertEquals(12000, fake.position);
        subject.seek(first, Float.NaN);
        assertEquals(12000, fake.position);
        subject.seek(first, -1f);
        assertEquals(0, fake.position);
    }

    @Test public void playbackErrorClearsVisibleStateAndEndedVoiceCanBeReplayed() {
        ChatAttachment first = voice(1, 1, "https://example.invalid/first");
        subject.toggle(first);
        FakePlayer fake = players.get(0);
        fake.position = 12000;
        fake.state = Player.STATE_ENDED;
        fake.listener.onPlaybackStateChanged(Player.STATE_ENDED);
        assertFalse(playing);
        subject.toggle(first);
        assertEquals(0, fake.position);
        assertTrue(playing);
        fake.listener.onPlayerError(null);
        assertTrue(fake.released);
        assertNull(shown);
    }

    @Test public void unsafeUriDoesNotCreatePlayer() {
        subject.toggle(voice(1, 1, "https://user@example.invalid/private"));
        assertTrue(players.isEmpty());
        assertFalse(VoiceMessagePlayer.sameVoice(null, null));
        assertTrue(VoiceMessagePlayer.sameVoice(voice(0, 0, "https://example.invalid/a"),
                voice(0, 0, "https://example.invalid/a")));
    }

    @Test public void refreshUsesOneRequestAndIgnoresCancelledOrOldSessionCallbacks() {
        Session.save(controller.get(), "synthetic-token", "101");
        List<VoiceLinkResolver.Completion> replies = new ArrayList<>();
        List<Boolean> cancelled = new ArrayList<>();
        subject = new VoiceMessagePlayer(controller.get(), (a, p, pos, d) -> {
            shown = a; playing = p; position = pos; duration = d; notifications++;
        }, context -> { FakePlayer fake = new FakePlayer(); players.add(fake); return fake.player; },
                (attachment, session, reply) -> {
                    int index = replies.size();
                    replies.add(reply); cancelled.add(false);
                    return () -> cancelled.set(index, true);
                });
        ChatAttachment first = voice(1, 1, "https://example.invalid/old");
        ChatAttachment second = voice(1, 2, "https://example.invalid/second");
        subject.toggle(first);
        assertTrue(playing);
        assertTrue(players.isEmpty());
        subject.toggle(second);
        assertTrue(cancelled.get(0));
        replies.get(0).complete(new VoiceLinkResolver.Result(VoiceLinkResolver.Kind.FOUND, "https://example.invalid/late"));
        assertTrue(players.isEmpty());
        replies.get(1).complete(new VoiceLinkResolver.Result(VoiceLinkResolver.Kind.FAILED, ""));
        assertEquals(1, players.size()); // The existing HTTPS URL is the offline fallback.
        subject.toggle(second);
        assertEquals(2, replies.size()); // Pausing never refreshes again.
        subject.toggle(first);
        Session.save(controller.get(), "synthetic-other-token", "102");
        replies.get(2).complete(new VoiceLinkResolver.Result(VoiceLinkResolver.Kind.FAILED, ""));
        assertNull(shown); // Never play a previous account's fallback.
        assertEquals(1, players.size());
    }

    @Test public void explicitlyUnavailableDocumentNeverUsesOldLink() {
        Session.save(controller.get(), "synthetic-token", "101");
        List<VoiceLinkResolver.Completion> replies = new ArrayList<>();
        subject = new VoiceMessagePlayer(controller.get(), (a, p, pos, d) -> shown = a,
                context -> { FakePlayer fake = new FakePlayer(); players.add(fake); return fake.player; },
                (attachment, session, reply) -> { replies.add(reply); return () -> { }; });
        subject.toggle(voice(1, 1, "https://example.invalid/old"));
        replies.get(0).complete(new VoiceLinkResolver.Result(VoiceLinkResolver.Kind.UNAVAILABLE, ""));
        assertTrue(players.isEmpty());
        assertNull(shown);
    }

    private static final class FakePlayer {
        boolean ready, released;
        long position;
        int pauses;
        int state = Player.STATE_READY;
        Player.Listener listener;
        final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "addListener": listener = (Player.Listener) args[0]; return null;
                        case "getPlayWhenReady": return ready;
                        case "getPlaybackState": return state;
                        case "getCurrentPosition": return position;
                        case "getDuration": return 12000L;
                        case "play": ready = true; return null;
                        case "pause": ready = false; pauses++; return null;
                        case "seekTo":
                            position = (Long) args[args.length - 1];
                            if (state == Player.STATE_ENDED) state = Player.STATE_READY;
                            return null;
                        case "release": released = true; return null;
                        default:
                            Class<?> type = method.getReturnType();
                            if (type == boolean.class) return false;
                            if (type == int.class) return 0;
                            if (type == long.class) return 0L;
                            return null;
                    }
                });
    }
}
