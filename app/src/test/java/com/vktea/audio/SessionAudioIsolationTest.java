package com.vktea.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.vktea.audio.catalog.VkAudioTrack;
import com.vktea.auth.Session;
import com.vktea.msg.MessageLongPoll;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.util.Collections;

/** Account transitions use synthetic preferences and files; no VK transport is started. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class SessionAudioIsolationTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        // No real long-poll singleton or network traffic participates in this test.
        ReflectionHelpers.setStaticField(MessageLongPoll.class, "instance", null);
        Session.clear(context);
        Session.save(context, "synthetic-token-a", "42", "synthetic-secret-a");
    }

    @After public void tearDown() {
        Session.clear(context);
        ReflectionHelpers.setStaticField(MessageLongPoll.class, "instance", null);
    }

    @Test public void tokenRefreshAndAccountSwitchInvalidateOldGenerationsAndPrivateAudio() throws Exception {
        VkAudioTrack track = new VkAudioTrack(73, 42, "Synthetic artist", "Synthetic title",
                60, "", "synthetic-private-key", true);
        AudioSources.rememberTracks(context, Collections.singletonList(track));
        assertEquals("42_73_synthetic-private-key", AudioSources.apiId(context, track.mediaId()));
        context.getSharedPreferences("audio_queue", Context.MODE_PRIVATE).edit().putString("synthetic", "queued").commit();
        File cached = new File(new File(new File(context.getNoBackupFilesDir(), "offline_audio"),
                "synthetic-account"), "synthetic-track.audio");
        assertTrue(cached.getParentFile().mkdirs() || cached.getParentFile().isDirectory());
        assertTrue(cached.createNewFile());
        Session.Snapshot first = Session.snapshot(context);

        // Refreshing the same account revokes old callbacks and access keys, but preserves its cache.
        Session.save(context, "synthetic-token-a2", "42", "synthetic-secret-a");
        Session.Snapshot refreshed = Session.snapshot(context);
        assertNotEquals(first.generation, refreshed.generation);
        assertTrue(cached.exists());
        assertTrue(context.getSharedPreferences("audio_queue", Context.MODE_PRIVATE).contains("synthetic"));
        assertEquals("42_73", AudioSources.apiId(context, track.mediaId()));
        AudioSources.rememberTracks(context, Collections.singletonList(track));
        assertEquals("42_73_synthetic-private-key", AudioSources.apiId(context, track.mediaId()));

        Session.save(context, "synthetic-token-b", "84", "synthetic-secret-b");
        Session.Snapshot other = Session.snapshot(context);
        assertNotEquals(refreshed.generation, other.generation);
        assertFalse(cached.exists());
        assertFalse(context.getSharedPreferences("audio_queue", Context.MODE_PRIVATE).contains("synthetic"));
        assertEquals("42_73", AudioSources.apiId(context, track.mediaId()));

        // Returning to the former account must not make a previously captured callback valid.
        Session.save(context, "synthetic-token-a", "42", "synthetic-secret-a");
        assertNotEquals(first.generation, Session.generation());
        assertNotEquals(other.generation, Session.generation());
        assertEquals("42_73", AudioSources.apiId(context, track.mediaId()));
        Session.clear(context);
        assertNotEquals(other.generation, Session.generation());
    }
}
