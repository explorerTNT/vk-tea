package com.vktea.search;

import com.vktea.feed.Post;
import com.vktea.feed.ViewerPhoto;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Converts the supported VK search response shapes. */
final class SearchParser {
    enum Kind { USERS, GROUPS, POSTS, VIDEOS }
    static final class Item {
        long id;
        String title = "", subtitle = "", photo = "";
        long videoId;
        String videoAccessKey = "";
        Post post;
    }
    static final class Page {
        final List<Item> items = new ArrayList<>();
        String next = "";
    }
    static Page parse(Kind kind, JSONObject root, int offset) throws JSONException {
        JSONObject response = root.getJSONObject("response");
        JSONArray rows = response.getJSONArray("items");
        Page page = new Page();
        Map<Long, String[]> authors = new HashMap<>();
        addAuthors(authors, response.optJSONArray("profiles"), false);
        addAuthors(authors, response.optJSONArray("groups"), true);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            Item item = new Item();
            if (kind == Kind.USERS) {
                item.id = row.optLong("id");
                item.title = (row.optString("first_name", "") + " " + row.optString("last_name", "")).trim();
                item.photo = row.optString("photo_100", "");
                JSONObject city = row.optJSONObject("city");
                item.subtitle = city == null ? "" : city.optString("title", "");
            } else if (kind == Kind.GROUPS) {
                item.id = -row.optLong("id");
                item.title = row.optString("name", "");
                item.photo = row.optString("photo_100", "");
                item.subtitle = row.optString("activity", "");
            } else if (kind == Kind.VIDEOS) {
                long owner = row.optLong("owner_id"), videoId = row.optLong("id");
                if (owner == 0 || videoId <= 0) continue;
                item.id = owner;
                item.videoId = videoId;
                item.videoAccessKey = row.optString("access_key", "");
                item.title = row.optString("title", "").trim();
                if (item.title.isEmpty()) item.title = "Видео без названия";
                item.photo = bestImage(row.optJSONArray("image"));
                if (item.photo.isEmpty()) item.photo = bestImage(row.optJSONArray("first_frame"));
                if (item.photo.isEmpty()) item.photo = row.optString("photo_320", row.optString("photo_130", ""));
                int duration = Math.max(0, row.optInt("duration"));
                if (duration > 0) item.subtitle = duration >= 3600
                        ? String.format(java.util.Locale.US, "%d:%02d:%02d", duration / 3600, duration / 60 % 60, duration % 60)
                        : String.format(java.util.Locale.US, "%d:%02d", duration / 60, duration % 60);
            } else {
                long owner = row.optLong("owner_id", row.optLong("source_id", 0));
                long postId = row.optLong("id", row.optLong("post_id", 0));
                if (owner == 0 || postId <= 0) continue;
                long authorId = row.optLong("from_id", owner);
                String[] author = authors.get(authorId);
                if (author == null) author = authors.get(owner);
                item.id = owner;
                item.title = author == null ? "Публикация" : author[0];
                item.photo = author == null ? "" : author[1];
                item.subtitle = row.optString("text", "");
                if (item.subtitle.trim().isEmpty()) item.subtitle = "Публикация с вложениями";
                List<String> photos = new ArrayList<>();
                List<ViewerPhoto> details = new ArrayList<>();
                JSONArray attachments = row.optJSONArray("attachments");
                if (attachments != null) for (int a = 0; a < attachments.length() && photos.size() < 10; a++) {
                    JSONObject attachment = attachments.optJSONObject(a);
                    if (attachment == null || !"photo".equals(attachment.optString("type"))) continue;
                    JSONObject value = attachment.optJSONObject("photo");
                    if (value == null) continue;
                    ViewerPhoto photo = ViewerPhoto.parse(value);
                    if (!photo.url.isEmpty()) { photos.add(photo.url); details.add(photo); }
                }
                JSONObject likes = row.optJSONObject("likes"), comments = row.optJSONObject("comments"), reposts = row.optJSONObject("reposts"), views = row.optJSONObject("views");
                item.post = new Post(authorId, item.title, item.photo, row.optString("text", ""), row.optLong("date"), photos,
                        count(likes), count(comments), count(reposts), count(views), likes != null && likes.optInt("user_likes") == 1,
                        Post.TYPE_POST, owner, postId);
                item.post.photoDetails.addAll(details);
                item.post.readShareMetadata(row);
            }
            if (item.id != 0 && !item.title.isEmpty()) page.items.add(item);
        }
        if (kind == Kind.POSTS) page.next = response.optString("next_from", "");
        else if (rows.length() > 0 && (long) offset + rows.length() < response.optLong("count", 0)
                && (long) offset + rows.length() <= Integer.MAX_VALUE) page.next = Integer.toString(offset + rows.length());
        return page;
    }
    private static String bestImage(JSONArray images) {
        if (images == null) return "";
        String best = ""; long bestArea = -1;
        for (int i = 0; i < images.length(); i++) {
            JSONObject image = images.optJSONObject(i);
            if (image == null) continue;
            String url = image.optString("url", "");
            long width = Math.min(10000, Math.max(0, image.optLong("width")));
            long height = Math.min(10000, Math.max(0, image.optLong("height")));
            long area = width * height;
            if (!url.isEmpty() && area > bestArea) { best = url; bestArea = area; }
        }
        return best;
    }
    private static int count(JSONObject obj) { return obj == null ? 0 : obj.optInt("count"); }
    private static void addAuthors(Map<Long, String[]> result, JSONArray rows, boolean group) {
        if (rows == null) return;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            long id = row.optLong("id");
            if (id <= 0) continue;
            String name = group ? row.optString("name", "") : (row.optString("first_name", "") + " " + row.optString("last_name", "")).trim();
            result.put(group ? -id : id, new String[]{name.isEmpty() ? "Публикация" : name, row.optString("photo_100", "")});
        }
    }
}
