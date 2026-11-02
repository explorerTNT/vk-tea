package com.vktea.audio;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import com.vktea.audio.catalog.VkAudioRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AudioQueueStoreTest {
    Context context;
    ExoPlayer player;
    AudioQueueStore store;
    VkAudioRepository repository;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("audio_queue", Context.MODE_PRIVATE).edit().clear().commit();
        store = new AudioQueueStore(context);
        repository = VkAudioRepository.create(context);
        player = new ExoPlayer.Builder(context).setMediaSourceFactory(
                new VkAudioMediaSourceFactory(context, repository, () -> true)).build();
    }
    @After public void cleanup() { player.release(); repository.close(); store.clear(); }
    private MediaItem item(String id, boolean playable) {
        return new MediaItem.Builder().setMediaId(id).setUri("https://example.com/audio?secret=synthetic")
                .setMediaMetadata(new MediaMetadata.Builder().setTitle("Track").setArtist("Artist")
                        .setIsPlayable(playable).build()).build();
    }
    @Test public void restoresQueuePositionAndModesWithoutUrlsOrAutoplay() {
        player.setMediaItems(Arrays.asList(item("vk-audio:1_2", true), item("vk-audio:1_3", false)), 1, 1234);
        player.setShuffleModeEnabled(true); player.setRepeatMode(Player.REPEAT_MODE_ALL);
        store.save(player, "42");
        String raw = context.getSharedPreferences("audio_queue", Context.MODE_PRIVATE).getString("state", "");
        assertFalse(raw.contains("synthetic")); assertFalse(raw.contains("https"));
        player.clearMediaItems(); store.restore(player, "42");
        assertEquals(2, player.getMediaItemCount());
        assertEquals(1, player.getCurrentMediaItemIndex());
        assertEquals(1234, player.getCurrentPosition());
        assertFalse(player.getPlayWhenReady());
        assertTrue(player.getShuffleModeEnabled()); assertEquals(Player.REPEAT_MODE_ALL, player.getRepeatMode());
        assertEquals(Boolean.FALSE, player.getMediaItemAt(1).mediaMetadata.isPlayable);
        assertNull(player.getMediaItemAt(0).localConfiguration);
    }
    @Test public void rejectsOtherAccountsAndClearsStaleQueueForUnsupportedSource() {
        player.setMediaItem(item("vk-audio:1_2", true)); store.save(player, "42");
        player.clearMediaItems(); store.restore(player, "43"); assertEquals(0, player.getMediaItemCount());
        player.setMediaItem(MediaItem.fromUri("content://example/audio")); store.save(player, "42");
        player.clearMediaItems(); store.restore(player, "42"); assertEquals(0, player.getMediaItemCount());
    }
    @Test public void onlyRecognizesAudioHeaders() {
        assertTrue(AudioOfflineCache.supportedAudio("ID3synthetic audio".getBytes(StandardCharsets.US_ASCII), 16));
        assertTrue(AudioOfflineCache.supportedAudio("OggSsynthetic data".getBytes(StandardCharsets.US_ASCII), 16));
        assertFalse(AudioOfflineCache.supportedAudio("#EXTM3U\n#EXTINF:".getBytes(StandardCharsets.US_ASCII), 16));
        assertFalse(AudioOfflineCache.supportedAudio("<html>not audio".getBytes(StandardCharsets.US_ASCII), 15));
    }
}
