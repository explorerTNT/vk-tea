package com.vktea.videos;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable video or album entry; only VK's data is displayed. */
final class VideoEntry {
    final long ownerId, id;
    final String title, image, accessKey;
    final int duration, count;
    final boolean album;
    VideoEntry(long ownerId, long id, String title, String image, String accessKey, int duration, int count, boolean album) {
        this.ownerId = ownerId; this.id = id; this.title = title; this.image = image;
        this.accessKey = accessKey; this.duration = duration; this.count = count; this.album = album;
    }
    static VideoEntry parse(JSONObject item, boolean album, long fallbackOwner) {
        if (item == null) return null;
        long id = item.optLong("id", 0), owner = item.optLong("owner_id", fallbackOwner);
        if (id == 0 || owner == 0) return null;
        String image = bestImage(item.optJSONArray("image"));
        if (image.isEmpty()) image = bestImage(item.optJSONArray("photo"));
        if (image.isEmpty()) image = bestImage(item.optJSONArray("first_frame"));
        if (image.isEmpty()) {
            for (String key : new String[]{"photo_320", "photo_800", "photo_640", "photo_160", "photo_130"}) {
                String value = item.optString(key, "");
                if (!value.isEmpty()) { image = value; break; }
            }
        }
        return new VideoEntry(owner, id, item.optString("title", ""), image,
                item.optString("access_key", ""), Math.max(0, item.optInt("duration", 0)),
                Math.max(0, item.optInt("count", 0)), album);
    }
    private static String bestImage(JSONArray images) {
        if (images == null) return "";
        String best = ""; long area = -1;
        for (int i = 0; i < images.length(); i++) {
            JSONObject image = images.optJSONObject(i); if (image == null) continue;
            String url = image.optString("url", "");
            long width = Math.max(0, image.optLong("width", 0));
            long height = Math.max(0, image.optLong("height", 0));
            long score = Math.min(width, 10000) * Math.min(height, 10000);
            if (!url.isEmpty() && score > area) { best = url; area = score; }
        }
        return best;
    }
    static Page parsePage(JSONObject root, boolean albums, long owner) throws JSONException {
        JSONObject response = root.getJSONObject("response");
        JSONArray rows = response.getJSONArray("items");
        long count = response.optLong("count", -1);
        if (count < 0 || count > 1000000 || rows.length() > VideoRepository.PAGE_SIZE) throw new JSONException("Invalid page");
        List<VideoEntry> entries = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            VideoEntry entry = parse(rows.optJSONObject(i), albums, owner);
            if (entry != null) entries.add(entry);
        }
        return new Page(Collections.unmodifiableList(entries), rows.length(), (int) count);
    }
    static final class Page {
        final List<VideoEntry> entries; final int consumed, total;
        Page(List<VideoEntry> entries, int consumed, int total) { this.entries = entries; this.consumed = consumed; this.total = total; }
    }
}
