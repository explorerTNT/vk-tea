package com.vktea.videos;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Video card rows for the videos section. */
final class VideoModels {

    static final class VideoEntry {
        final long id;
        final long ownerId;
        final String title;
        final String previewUrl;
        final int duration;
        final int views;
        final String accessKey;
        VideoEntry(long id, long ownerId, String title, String previewUrl, int duration,
                   int views, String accessKey) {
            this.id = id;
            this.ownerId = ownerId;
            this.title = title == null || title.trim().isEmpty() ? "Видео" : title;
            this.previewUrl = previewUrl == null ? "" : previewUrl;
            this.duration = Math.max(0, duration);
            this.views = Math.max(0, views);
            this.accessKey = accessKey == null ? "" : accessKey;
        }
    }

    static List<VideoEntry> parse(JSONArray items) {
        if (items == null) return Collections.emptyList();
        List<VideoEntry> videos = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject video = items.optJSONObject(i);
            if (video == null) continue;
            String preview = firstFrame(video);
            videos.add(new VideoEntry(video.optLong("id", 0), video.optLong("owner_id", 0),
                    video.optString("title", ""), preview,
                    video.optInt("duration", 0), video.optInt("views", video.optInt("views_count", 0)),
                    video.optString("access_key", "")));
        }
        return videos;
    }

    static String duration(int seconds) {
        return seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String firstFrame(JSONObject video) {
        String url = bestImage(video.optJSONArray("image"));
        if (url.isEmpty()) url = bestImage(video.optJSONArray("first_frame"));
        if (url.isEmpty()) {
            String[] legacy = {"photo_800", "photo_640", "photo_320", "photo_130"};
            for (String key : legacy) {
                String candidate = video.optString(key, "");
                if (!candidate.isEmpty()) return candidate;
            }
        }
        return url;
    }

    private static String bestImage(JSONArray images) {
        if (images == null) return "";
        String best = "";
        int bestWidth = 0;
        for (int i = 0; i < images.length(); i++) {
            JSONObject image = images.optJSONObject(i);
            if (image == null) continue;
            String url = image.optString("url", "");
            int width = image.optInt("width", 0);
            if (!url.isEmpty() && width >= bestWidth) {
                best = url;
                bestWidth = width;
            }
        }
        return best;
    }

    private VideoModels() {}
}
