package com.vktea.audio.catalog;

import android.os.Bundle;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.UnstableApi;

import com.vktea.audio.AudioUi;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/** Immutable catalog identity and metadata. A missing URL can be resolved later by audio.getById. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class VkAudioTrack {
    public final long id;
    public final long ownerId;
    public final String artist;
    public final String title;
    public final long durationSeconds;
    public final String url;
    public final String accessKey;
    public final boolean available;

    public VkAudioTrack(long id, long ownerId, String artist, String title,
                        long durationSeconds, String url, String accessKey, boolean available) {
        if (id <= 0 || ownerId == 0) throw new IllegalArgumentException("Invalid VK audio identity");
        this.id = id;
        this.ownerId = ownerId;
        this.artist = artist == null ? "" : artist;
        this.title = title == null ? "" : title;
        this.durationSeconds = Math.max(0, Math.min(durationSeconds, Long.MAX_VALUE / 1000));
        this.url = isSafeHttps(url) ? url : "";
        this.accessKey = accessKey != null && accessKey.matches("[A-Za-z0-9_-]{1,256}") ? accessKey : "";
        this.available = available;
    }

    public String mediaId() {
        return "vk-audio:" + ownerId + "_" + id;
    }

    public String apiId() {
        return ownerId + "_" + id + (accessKey.isEmpty() ? "" : "_" + accessKey);
    }

    public boolean hasPlayableUrl() {
        return available && !url.isEmpty();
    }

    public MediaItem toMediaItem() {
        Bundle extras = new Bundle();
        extras.putLong(AudioUi.EXTRA_DURATION_MS, durationSeconds * 1000);
        MediaItem.Builder item = new MediaItem.Builder().setMediaId(mediaId())
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).setArtist(artist)
                        .setIsPlayable(available).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setExtras(extras).build());
        if (hasPlayableUrl()) item.setUri(url);
        return item.build();
    }

    static boolean isSafeHttps(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && !uri.isOpaque()
                    && uri.getHost() != null && !uri.getHost().isEmpty()
                    && uri.getRawUserInfo() == null && uri.getFragment() == null
                    && uri.getPort() <= 65535;
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof VkAudioTrack)) return false;
        VkAudioTrack track = (VkAudioTrack) other;
        return id == track.id && ownerId == track.ownerId && durationSeconds == track.durationSeconds
                && available == track.available && artist.equals(track.artist) && title.equals(track.title)
                && url.equals(track.url) && accessKey.equals(track.accessKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, artist, title, durationSeconds, url, accessKey, available);
    }
}
