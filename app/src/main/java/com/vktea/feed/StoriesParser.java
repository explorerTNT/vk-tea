package com.vktea.feed;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Normalizes VK's grouped stories and extended owner metadata without assuming count is an array. */
final class StoriesParser {
    static final class Owner {
        final String name, avatar;
        final JSONArray stories;
        Owner(String name, String avatar, JSONArray stories) { this.name = name; this.avatar = avatar; this.stories = stories; }
    }
    static List<Owner> parse(JSONObject root) {
        List<Owner> result = new ArrayList<>();
        JSONObject data = root == null ? null : root.optJSONObject("response");
        if (data == null) return result;
        Map<Long, JSONObject> owners = new HashMap<>();
        readOwners(owners, data.optJSONArray("profiles"), false);
        readOwners(owners, data.optJSONArray("groups"), true);
        readItems(result, data.optJSONArray("items"), owners);
        return result;
    }
    private static void readOwners(Map<Long, JSONObject> owners, JSONArray rows, boolean group) {
        if (rows == null) return;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            long id = row.optLong("id");
            if (id > 0) owners.put(group ? -id : id, row);
        }
    }
    private static void readItems(List<Owner> result, JSONArray items, Map<Long, JSONObject> owners) {
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            JSONArray grouped = item.optJSONArray("grouped");
            if (grouped != null) readItems(result, grouped, owners);
            JSONArray stories = item.optJSONArray("stories");
            if (stories == null || stories.length() == 0) continue;
            JSONObject first = stories.optJSONObject(0);
            if (first == null) continue;
            long ownerId = item.optLong("owner_id", first.optLong("owner_id"));
            JSONObject owner = owners.get(ownerId);
            if (owner == null) owner = item; // legacy responses may embed owner fields
            String name = owner.optString("name", "").trim();
            if (name.isEmpty()) name = (owner.optString("first_name", "") + " " + owner.optString("last_name", "")).trim();
            if (name.isEmpty()) name = "История";
            JSONArray normalized = new JSONArray();
            for (int j = 0; j < stories.length() && normalized.length() < 100; j++) {
                JSONObject story = stories.optJSONObject(j);
                if (story == null) continue;
                long id = story.optLong("id"), sid = story.optLong("owner_id", ownerId);
                // The group's displayed name must describe the actual message recipient.
                if (id <= 0 || sid == 0 || sid != ownerId) continue;
                JSONObject entry = new JSONObject();
                try {
                    entry.put("id", id); entry.put("owner_id", sid);
                    entry.put("access_key", story.optString("access_key", ""));
                    entry.put("can_reply", flag(story, "can_reply"));
                    entry.put("can_like", flag(story, "can_like"));
                    Object userReaction = story.opt("user_reaction_id");
                    if (userReaction instanceof Number && ((Number) userReaction).intValue() >= 0)
                        entry.put("user_reaction_id", ((Number) userReaction).intValue());
                    entry.put("seen", flag(story, "seen"));
                    String type = story.optString("type", "");
                    entry.put("type", type);
                    boolean unavailable = flag(story, "is_deleted") || flag(story, "is_expired") || (story.has("can_see") && !flag(story, "can_see"));
                    String url = "";
                    if (!unavailable && "photo".equals(type)) url = safeUrl(PostPhoto.bestUrl(story.optJSONObject("photo")));
                    if (!unavailable && "video".equals(type)) {
                        JSONObject video = story.optJSONObject("video");
                        JSONObject files = video == null ? null : video.optJSONObject("files");
                        if (files != null) for (String key : new String[]{"mp4_720", "mp4_480", "mp4_360", "mp4_240", "hls", "mp4_1080"}) {
                            url = safeUrl(files.optString(key, "")); if (!url.isEmpty()) break;
                        }
                    }
                    entry.put("url", url); normalized.put(entry);
                } catch (org.json.JSONException ignored) { }
            }
            if (normalized.length() > 0) result.add(new Owner(name, safeUrl(owner.optString("photo_100", "")), normalized));
        }
    }
    private static boolean flag(JSONObject value, String name) {
        Object v = value.opt(name);
        return Boolean.TRUE.equals(v) || (v instanceof Number && ((Number) v).intValue() != 0);
    }
    static String safeUrl(String url) {
        if (url == null || url.length() > 2048) return "";
        Uri uri = Uri.parse(url);
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null ? url : "";
    }
}
