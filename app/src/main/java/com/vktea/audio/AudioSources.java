package com.vktea.audio;

import android.content.Context;

import androidx.media3.common.MediaItem;

import com.vktea.auth.Session;
import com.vktea.audio.catalog.VkAudioTrack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

/** Private process-only source lookup. Source URIs never travel in public metadata extras. */
public final class AudioSources {
    private static final Map<String, MediaItem> ITEMS = new LinkedHashMap<String, MediaItem>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, MediaItem> eldest) {
            return size() > 2048;
        }
    };
    private static final Map<String, String> ACCESS_KEYS = new LinkedHashMap<String, String>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 2048;
        }
    };
    private static byte[] accessSession;

    private AudioSources() {}

    static synchronized void remember(List<MediaItem> items) {
        for (MediaItem item : items) ITEMS.put(item.mediaId, item);
    }

    static synchronized MediaItem find(String mediaId) { return ITEMS.get(mediaId); }

    /** Keep access keys private to this process; never place them in public media metadata. */
    public static synchronized void rememberTracks(Context context, List<VkAudioTrack> tracks) {
        if (!selectSession(context) || tracks == null) return;
        for (VkAudioTrack track : tracks) {
            if (track != null && !track.accessKey.isEmpty() && VkAudioIds.apiId(track.mediaId()) != null) {
                ACCESS_KEYS.put(track.mediaId(), track.accessKey);
            }
        }
    }

    /** Resolve an identity for the current session, falling back to its public numeric pair. */
    static synchronized String apiId(Context context, String mediaId) {
        String pair = VkAudioIds.apiId(mediaId);
        if (pair == null || !selectSession(context)) return pair;
        String key = ACCESS_KEYS.get(mediaId);
        return key == null ? pair : pair + "_" + key;
    }

    public static synchronized void clearPrivateAccess() {
        ACCESS_KEYS.clear();
        accessSession = null;
    }

    static synchronized void clear() {
        ITEMS.clear();
    }

    private static boolean selectSession(Context context) {
        String token = Session.getToken(context);
        String user = Session.getUserId(context);
        String secret = Session.getSecret(context);
        if (token == null || token.isEmpty() || user == null || user.isEmpty()) {
            clearPrivateAccess();
            return false;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{user, token, secret == null ? "" : secret}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            byte[] current = digest.digest();
            if (!Arrays.equals(accessSession, current)) {
                ACCESS_KEYS.clear();
                accessSession = current;
            }
            return true;
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable");
        }
    }
}
