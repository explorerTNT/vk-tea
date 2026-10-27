package com.vktea.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;

import com.vktea.audio.catalog.VkAudioTrack;
import com.vktea.auth.Session;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AudioSourcesAccessTest {
    private Context context;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        Session.save(context, "synthetic-token", "42", "synthetic-secret");
        AudioSources.clearPrivateAccess();
    }

    @After public void cleanup() {
        AudioSources.clearPrivateAccess();
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void keysArePrivateAndUsedForCurrentSessionOnly() {
        VkAudioTrack track = track("synthetic-access-key");
        AudioSources.rememberTracks(context, Collections.singletonList(track));
        assertEquals("42_73_synthetic-access-key", AudioSources.apiId(context, track.mediaId()));
        assertEquals("vk-audio:42_73", track.toMediaItem().mediaId);
        assertNull(track.toMediaItem().localConfiguration);
        Session.save(context, "other-token", "42", "synthetic-secret");
        assertEquals("42_73", AudioSources.apiId(context, track.mediaId()));
        Session.save(context, "synthetic-token", "42", "synthetic-secret");
        assertEquals("42_73", AudioSources.apiId(context, track.mediaId()));
    }

    @Test public void emptyOrInvalidKeysCannotEraseOrCreatePrivateAccess() {
        AudioSources.rememberTracks(context, Collections.singletonList(track("good-key")));
        AudioSources.rememberTracks(context, Collections.singletonList(track("bad key")));
        assertEquals("42_73_good-key", AudioSources.apiId(context, "vk-audio:42_73"));
        AudioSources.clearPrivateAccess();
        assertEquals("42_73", AudioSources.apiId(context, "vk-audio:42_73"));
    }

    @Test public void registryIsBoundedAndLogoutRevokesKeys() {
        List<VkAudioTrack> tracks = new ArrayList<>();
        for (int id = 1; id <= 2049; id++) {
            tracks.add(new VkAudioTrack(id, 42, "", "", 0, "", "key", true));
        }
        AudioSources.rememberTracks(context, tracks);
        assertEquals("42_1", AudioSources.apiId(context, "vk-audio:42_1"));
        assertEquals("42_2049_key", AudioSources.apiId(context, "vk-audio:42_2049"));
        Session.clear(context);
        assertEquals("42_2049", AudioSources.apiId(context, "vk-audio:42_2049"));
    }

    private static VkAudioTrack track(String key) {
        return new VkAudioTrack(73, 42, "Artist", "Title", 60, "", key, true);
    }
}
