package com.vktea.audio.catalog;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VkAudioPlaylistTest {
    @Test
    public void webRouteUsesPlaylistIdentityAndOnlyValidAccessKey() {
        assertEquals("https://m.vk.com/audio?z=audio_playlist42_7",
                new VkAudioPlaylist(42, 7, "test", "").webUrl());
        assertEquals("https://m.vk.com/audio?z=audio_playlist-42_7/key_ABC-123",
                new VkAudioPlaylist(-42, 7, "test", "key_ABC-123").webUrl());
        assertEquals("https://m.vk.com/audio?z=audio_playlist42_7",
                new VkAudioPlaylist(42, 7, "test", "key&redirect=evil").webUrl());
    }
}
