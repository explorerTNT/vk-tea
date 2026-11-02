package com.vktea.notifications;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.HttpUrl;

/** Parses the varying feedback shapes without trusting server-supplied destination URLs. */
final class NotificationsParser {
    static EventPage parse(JSONObject root) throws JSONException {
        JSONObject response = root.getJSONObject("response");
        JSONArray rows = response.getJSONArray("items");
        if (rows.length() > 100) throw new JSONException("Invalid page");
        Map<Long, Person> people = new HashMap<>();
        readPeople(response.optJSONArray("profiles"), false, people);
        readPeople(response.optJSONArray("groups"), true, people);
        List<NotificationEvent> events = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String type = row.optString("type", "");
            JSONObject feedback = row.optJSONObject("feedback");
            JSONObject parent = row.optJSONObject("parent");
            JSONObject first = feedback;
            int count = 0;
            if (feedback != null && feedback.optJSONArray("items") != null) {
                JSONArray actors = feedback.optJSONArray("items");
                count = Math.max(actors.length(), feedback.optInt("count", 0));
                first = actors.optJSONObject(0);
                if (first == null && actors.length() > 0) {
                    long id = actors.optLong(0, 0);
                    first = new JSONObject(); first.put("from_id", id);
                }
            }
            long actor = id(first, "from_id", "owner_id");
            Person person = people.get(actor);
            String name = person == null ? "" : person.name;
            String photo = person == null ? "" : person.photo;
            if (count > 1 && !name.isEmpty()) name += " и ещё " + (count - 1);
            String text = text(first, "text");
            JSONObject reply = row.optJSONObject("reply");
            if (text.isEmpty()) text = text(reply, "text");
            String parentText = text(parent, "text");
            if (parentText.isEmpty()) parentText = text(parent, "title");
            String url = url(type, parent, feedback, actor);
            events.add(new NotificationEvent(type, row.optLong("date", 0), name, photo, text, parentText, url));
        }
        String next = response.optString("next_from", "");
        if (next.length() > 4096) throw new JSONException("Invalid cursor");
        return new EventPage(events, next, response.optLong("last_viewed", 0));
    }
    private static void readPeople(JSONArray rows, boolean group, Map<Long, Person> people) {
        if (rows == null) return;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            long id = row.optLong("id", 0); if (id <= 0) continue;
            String name = group ? text(row, "name") : (text(row, "first_name") + " " + text(row, "last_name")).trim();
            String photo = safePhoto(text(row, "photo_100"));
            if (photo.isEmpty()) photo = safePhoto(text(row, "photo_50"));
            people.put(group ? -id : id, new Person(name, photo));
        }
    }
    private static long id(JSONObject value, String first, String second) {
        if (value == null) return 0;
        long result = value.optLong(first, 0); return result != 0 ? result : value.optLong(second, 0);
    }
    private static String text(JSONObject value, String key) {
        if (value == null || value.isNull(key)) return "";
        Object field = value.opt(key); return field instanceof String ? (String) field : "";
    }
    private static String safePhoto(String value) {
        HttpUrl parsed = HttpUrl.parse(value);
        return parsed != null && parsed.isHttps() && parsed.username().isEmpty() && parsed.password().isEmpty() ? parsed.toString() : "";
    }
    private static String url(String type, JSONObject parent, JSONObject feedback, long actor) {
        if ((type.equals("follow") || type.equals("friend_accepted")) && actor != 0)
            return "https://vk.com/" + (actor > 0 ? "id" + actor : "club" + -actor);
        JSONObject target = parent;
        String prefix = "";
        switch (type) {
            case "mention": case "wall": case "wall_publish": target = feedback; prefix = "wall"; break;
            case "mention_comments": case "comment_post": case "like_post": case "copy_post": prefix = "wall"; break;
            case "comment_photo": case "like_photo": case "copy_photo": case "mention_comment_photo": prefix = "photo"; break;
            case "comment_video": case "like_video": case "copy_video": case "mention_comment_video": prefix = "video"; break;
            case "reply_topic": prefix = "topic"; break;
            default: return "";
        }
        long owner = id(target, "owner_id", "to_id"), object = target == null ? 0 : target.optLong("id", 0);
        if (owner == 0 || object <= 0) return "";
        return "https://vk.com/" + prefix + owner + "_" + object;
    }
    private static final class Person {
        final String name, photo;
        Person(String name, String photo) { this.name = name; this.photo = photo; }
    }
}
