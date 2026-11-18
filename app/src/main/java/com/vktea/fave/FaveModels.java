package com.vktea.fave;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Закладки через единый fave.get (современный контракт VK):
 * смешанная лента post/photo/video/link/article/user/group.
 */
final class FaveModels {

    enum Kind { POST, PHOTO, VIDEO, LINK, USER, GROUP, UNKNOWN }

    static final class FaveEntry {
        final Kind kind;
        final String title;
        final String subtitle;
        final String previewUrl;
        final String openUrl;
        final long ownerId;
        final long itemId;
        FaveEntry(Kind kind, String title, String subtitle, String previewUrl, String openUrl,
                  long ownerId, long itemId) {
            this.kind = kind;
            this.title = title == null || title.trim().isEmpty() ? "Закладка" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.previewUrl = previewUrl == null ? "" : previewUrl;
            this.openUrl = openUrl == null ? "" : openUrl;
            this.ownerId = ownerId;
            this.itemId = itemId;
        }
    }

    static List<FaveEntry> parse(JSONObject response, Map<Long, String> names) {
        JSONObject data = response == null ? null : response.optJSONObject("response");
        JSONArray items = data == null ? null : data.optJSONArray("items");
        if (items == null) return Collections.emptyList();
        collectNames(data, names);
        List<FaveEntry> entries = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject row = items.optJSONObject(i);
            if (row == null) continue;
            String type = row.optString("type", "");
            JSONObject value = row.optJSONObject(type);
            if (value == null) {
                entries.add(new FaveEntry(Kind.UNKNOWN, "Закладка", type, "", "", 0, 0));
                continue;
            }
            switch (type) {
                case "post": {
                    String author = names.containsKey(value.optLong("from_id", 0))
                            ? names.get(value.optLong("from_id", 0)) : "Запись";
                    entries.add(new FaveEntry(Kind.POST, firstLine(value.optString("text", "")),
                            author, firstPhoto(value.optJSONArray("attachments")), "",
                            value.optLong("from_id", 0), value.optLong("id", 0)));
                    break;
                }
                case "photo": {
                    entries.add(new FaveEntry(Kind.PHOTO, "Фотография", photoDate(value),
                            bestUrl(value), "", value.optLong("owner_id", 0), value.optLong("id", 0)));
                    break;
                }
                case "video": {
                    entries.add(new FaveEntry(Kind.VIDEO, value.optString("title", "Видео"),
                            videoMeta(value), videoPreview(value), "",
                            value.optLong("owner_id", 0), value.optLong("id", 0)));
                    break;
                }
                case "link": {
                    entries.add(new FaveEntry(Kind.LINK,
                            value.optString("title", value.optString("url", "Ссылка")),
                            value.optString("url", ""), linkPreview(value), "", 0, 0));
                    break;
                }
                case "article": {
                    entries.add(new FaveEntry(Kind.LINK, value.optString("title", "Статья"),
                            value.optString("view_url", ""), linkPreview(value), "", 0, 0));
                    break;
                }
                case "user": {
                    entries.add(new FaveEntry(Kind.USER,
                            value.optString("first_name", "") + " " + value.optString("last_name", ""),
                            "Пользователь", value.optString("photo_100", ""), "", value.optLong("id", 0), 0));
                    break;
                }
                case "group": {
                    entries.add(new FaveEntry(Kind.GROUP, value.optString("name", "Сообщество"),
                            "Сообщество", value.optString("photo_100", ""), "", -Math.abs(value.optLong("id", 0)), 0));
                    break;
                }
                default:
                    entries.add(new FaveEntry(Kind.UNKNOWN, "Закладка", type, "", "", 0, 0));
            }
        }
        return entries;
    }

    private static void collectNames(JSONObject data, Map<Long, String> names) {
        JSONArray profiles = data.optJSONArray("profiles");
        if (profiles != null) for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.optJSONObject(i);
            if (profile == null) continue;
            names.put(profile.optLong("id", 0),
                    (profile.optString("first_name", "") + " " + profile.optString("last_name", "")).trim());
        }
        JSONArray groups = data.optJSONArray("groups");
        if (groups != null) for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            names.put(-Math.abs(group.optLong("id", 0)), group.optString("name", "Сообщество"));
        }
    }

    private static String firstLine(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.isEmpty() ? "Запись без текста"
                : flat.codePointCount(0, flat.length()) <= 120 ? flat
                : flat.substring(0, flat.offsetByCodePoints(0, 120)) + "…";
    }

    private static String firstPhoto(JSONArray attachments) {
        if (attachments == null) return "";
        for (int i = 0; i < attachments.length(); i++) {
            JSONObject row = attachments.optJSONObject(i);
            if (row != null && "photo".equals(row.optString("type", ""))) {
                return bestUrl(row.optJSONObject("photo"));
            }
        }
        return "";
    }

    private static String bestUrl(JSONObject photo) {
        if (photo == null) return "";
        JSONArray sizes = photo.optJSONArray("sizes");
        String best = "";
        long bestArea = 0;
        if (sizes != null) for (int i = 0; i < sizes.length(); i++) {
            JSONObject size = sizes.optJSONObject(i);
            if (size == null) continue;
            String url = size.optString("url", "");
            if (url.isEmpty()) continue;
            String type = size.optString("type", "");
            if ("o".equals(type) || "p".equals(type) || "q".equals(type) || "r".equals(type)) continue;
            long area = (long) size.optInt("width", 1) * size.optInt("height", 1);
            if (area >= bestArea) {
                best = url;
                bestArea = area;
            }
        }
        return best.isEmpty() ? photo.optString("photo_604", photo.optString("src", "")) : best;
    }

    private static String photoDate(JSONObject photo) {
        return photo == null ? "" : "";
    }

    private static String videoMeta(JSONObject video) {
        int views = video.optInt("views", 0);
        return views > 0 ? views + " просмотров" : "Видео";
    }

    private static String videoPreview(JSONObject video) {
        JSONArray images = video == null ? null : video.optJSONArray("image");
        if (images == null || images.length() == 0) images = video.optJSONArray("first_frame");
        if (images == null) return "";
        String best = "";
        int bestWidth = 0;
        for (int i = 0; i < images.length(); i++) {
            JSONObject image = images.optJSONObject(i);
            if (image == null) continue;
            String url = image.optString("url", "");
            if (!url.isEmpty() && image.optInt("width", 0) >= bestWidth) {
                best = url;
                bestWidth = image.optInt("width", 0);
            }
        }
        return best;
    }

    private static String linkPreview(JSONObject link) {
        if (link == null) return "";
        JSONObject photo = link.optJSONObject("photo");
        return photo != null ? bestUrl(photo) : link.optString("photo_100", "");
    }

    private FaveModels() {}
}
