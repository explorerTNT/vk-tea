package com.vktea.audio;

import android.net.Uri;
import android.os.Bundle;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.UnstableApi;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Validates a complete queue request before any item is passed to ExoPlayer. */
@UnstableApi
final class AudioMediaItems {

    private AudioMediaItems() {}

    static List<MediaItem> validate(List<MediaItem> requested) {
        List<MediaItem> playable = new ArrayList<>(requested.size());
        for (MediaItem item : requested) {
            if (item == null || (item.localConfiguration == null && !VkAudioIds.isVk(item))) {
                throw invalidSource();
            }
            boolean vk = VkAudioIds.isVk(item);
            Uri uri = vk ? null : item.localConfiguration.uri.normalizeScheme();
            if (uri != null) validateUri(uri);

            // Rebuild a narrow audio item. In particular, do not forward arbitrary
            // metadata extras, artwork, subtitle, ad, or DRM request URLs.
            MediaMetadata source = item.mediaMetadata;
            MediaMetadata metadata = new MediaMetadata.Builder()
                    .setTitle(source.title)
                    .setArtist(source.artist)
                    .setAlbumTitle(source.albumTitle)
                    .setDisplayTitle(source.displayTitle)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setExtras(safeDetails(source.extras))
                    .build();
            String mediaId = item.mediaId;
            if (mediaId.isEmpty() || mediaId.contains("://")) {
                // A signed source URL must not become a public queue identifier.
                mediaId = UUID.randomUUID().toString();
            }
            MediaItem.Builder sanitized = new MediaItem.Builder()
                    .setMediaId(mediaId)
                    .setMediaMetadata(metadata);
            if (uri != null) sanitized.setUri(uri).setMimeType(item.localConfiguration.mimeType);
            playable.add(sanitized.build());
        }
        return playable;
    }

    private static Bundle safeDetails(Bundle requested) {
        if (requested == null) return null;
        Bundle safe = new Bundle();
        copyPositiveLong(requested, safe, AudioUi.EXTRA_DURATION_MS);
        copyPositiveLong(requested, safe, AudioUi.EXTRA_BITRATE);
        copyPositiveLong(requested, safe, AudioUi.EXTRA_FILE_SIZE);
        return safe.isEmpty() ? null : safe;
    }

    private static void copyPositiveLong(Bundle source, Bundle target, String key) {
        Object value = source.get(key);
        if (value instanceof Long && (Long) value > 0) target.putLong(key, (Long) value);
    }

    private static void validateUri(Uri uri) {
        try {
            URI parsed = new URI(uri.toString());
            String scheme = parsed.getScheme();
            if (parsed.isOpaque() || parsed.getRawUserInfo() != null) {
                throw invalidSource();
            }
            if ("content".equals(scheme)) {
                if (parsed.getRawAuthority() == null || parsed.getRawAuthority().isEmpty()) {
                    throw invalidSource();
                }
            } else if ("https".equals(scheme)) {
                if (parsed.getHost() == null || parsed.getHost().isEmpty()
                        || parsed.getPort() > 65535) {
                    throw invalidSource();
                }
            } else {
                throw invalidSource();
            }
        } catch (URISyntaxException exception) {
            // The parser exception can contain the entire signed source URL.
            throw invalidSource();
        }
    }

    private static IllegalArgumentException invalidSource() {
        return new IllegalArgumentException("Use a content document or a valid HTTPS audio source");
    }
}
