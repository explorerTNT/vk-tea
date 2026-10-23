package com.vktea.audio;

import androidx.media3.common.MediaItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Private process-only source lookup. Source URIs never travel in public metadata extras. */
final class AudioSources {
    private static final Map<String, MediaItem> ITEMS = new LinkedHashMap<String, MediaItem>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, MediaItem> eldest) {
            return size() > 2048;
        }
    };

    private AudioSources() {}

    static synchronized void remember(List<MediaItem> items) {
        for (MediaItem item : items) ITEMS.put(item.mediaId, item);
    }

    static synchronized MediaItem find(String mediaId) { return ITEMS.get(mediaId); }

    static synchronized void clear() { ITEMS.clear(); }
}
