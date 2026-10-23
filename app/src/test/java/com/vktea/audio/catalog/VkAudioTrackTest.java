package com.vktea.audio.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.vktea.audio.AudioUi;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VkAudioTrackTest {
    @Test
    public void metadataOnlyTrackKeepsIdentityAndDurationWithoutDisclosingAccessKey() {
        VkAudioTrack track = new VkAudioTrack(8, -42, "Synthetic artist", "Synthetic title",
                180, "", "synthetic-key", true);
        assertEquals("vk-audio:-42_8", track.toMediaItem().mediaId);
        assertNull(track.toMediaItem().localConfiguration);
        assertEquals(Boolean.TRUE, track.toMediaItem().mediaMetadata.isPlayable);
        assertEquals(180_000, AudioUi.duration(track.toMediaItem()));
        assertEquals(1, track.toMediaItem().mediaMetadata.extras.size());
    }

    @Test
    public void unsafeSourcesBecomeResolvableMetadataAndRestrictionsRemainUnavailable() {
        for (String url : new String[]{"http://audio.invalid/track", "file:///private/track",
                "https://user:password@audio.invalid/track", "https://audio.invalid/track#fragment"}) {
            VkAudioTrack track = new VkAudioTrack(8, 42, "", "", -1, url, "", true);
            assertEquals("", track.url);
            assertTrue(track.available);
            assertNull(track.toMediaItem().localConfiguration);
        }
        VkAudioTrack restricted = new VkAudioTrack(8, 42, "", "", 180,
                "https://audio.invalid/track", "", false);
        assertFalse(restricted.hasPlayableUrl());
        assertEquals(Boolean.FALSE, restricted.toMediaItem().mediaMetadata.isPlayable);
        assertNull(restricted.toMediaItem().localConfiguration);
    }
}
