package com.vktea.bookmarks;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Parses the two distinct VK favourites response shapes without hiding unknown types. */
final class BookmarksParser {
    static final class Item {
        String type, title, detail, image, url, accessKey;
        long owner, id;
    }
    static final class Page {
        final int count, consumed;
        final List<Item> items;
        Page(int count, int consumed, List<Item> items) { this.count = count; this.consumed = consumed; this.items = items; }
    }
    static Page parse(JSONObject root, boolean pages) throws JSONException {
        JSONObject response = root.getJSONObject("response");
        JSONArray rows = response.getJSONArray("items");
        int count = response.getInt("count");
        if (count < 0 || rows.length() > 50) throw new JSONException("Invalid bookmarks page");
        List<Item> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            Item item = new Item();
            item.type = row.optString("type", "");
            item.title = ""; item.detail = ""; item.image = ""; item.url = ""; item.accessKey = "";
            JSONObject object = row.optJSONObject(item.type);
            if (object == null && ("article".equals(item.type) || "link".equals(item.type))) object = row.optJSONObject("link");
            if (object == null) { item.title = "Сохранённый материал"; item.detail = "Тип: " + item.type; result.add(item); continue; }
            if (pages) {
                long id = object.optLong("id");
                if (id <= 0 || !("user".equals(item.type) || "group".equals(item.type))) continue;
                item.owner = "group".equals(item.type) ? -id : id;
                item.title = "user".equals(item.type) ? (object.optString("first_name", "") + " " + object.optString("last_name", "")).trim() : object.optString("name", "");
                item.detail = row.optString("description", "");
                item.image = object.optString("photo_100", object.optString("photo_50", ""));
            } else {
                item.owner = object.optLong("owner_id"); item.id = object.optLong("id");
                item.accessKey = object.optString("access_key", "");
                item.url = object.optString("url", "");
                switch (item.type) {
                    case "post":
                        item.title = object.optString("text", "").trim();
                        if (item.title.isEmpty()) item.title = "Запись";
                        item.detail = "Запись"; break;
                    case "video": case "clip":
                        item.title = object.optString("title", "Видео"); item.detail = "Видео";
                        JSONArray images = object.optJSONArray("image");
                        if (images != null && images.length() > 0) {
                            JSONObject image = images.optJSONObject(images.length() - 1);
                            if (image != null) item.image = image.optString("url", "");
                        }
                        break;
                    case "link": case "article":
                        item.title = object.optString("title", "Ссылка");
                        item.detail = "article".equals(item.type) ? "Статья" : "Ссылка"; break;
                    case "product":
                        item.title = object.optString("title", "Товар"); item.detail = "Товар";
                        item.image = object.optString("thumb_photo", ""); break;
                    default: item.title = object.optString("title", "Сохранённый материал"); item.detail = "Тип: " + item.type;
                }
            }
            if (item.title.trim().isEmpty()) item.title = "Сохранённый материал";
            result.add(item);
        }
        return new Page(count, rows.length(), result);
    }
}
