package com.vktea.audio;

import androidx.media3.common.MediaItem;

/** Public queue identities contain only the two numeric VK audio IDs. */
final class VkAudioIds {
    private static final String PREFIX = "vk-audio:";
    private VkAudioIds() {}

    static boolean isVk(MediaItem item) { return item != null && apiId(item.mediaId) != null; }

    static String apiId(String mediaId) {
        if (mediaId == null || !mediaId.startsWith(PREFIX)) return null;
        String pair = mediaId.substring(PREFIX.length());
        if (!pair.matches("-?[1-9][0-9]{0,18}_[1-9][0-9]{0,18}")) return null;
        int separator = pair.indexOf('_');
        try {
            if (Long.parseLong(pair.substring(0, separator)) == 0
                    || Long.parseLong(pair.substring(separator + 1)) <= 0) return null;
            return pair;
        } catch (NumberFormatException ignored) { return null; }
    }
}
