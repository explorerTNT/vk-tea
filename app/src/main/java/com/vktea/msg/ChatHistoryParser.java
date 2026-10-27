package com.vktea.msg;

import com.vktea.feed.ViewerPhoto;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure display parser. It neither fetches missing attachments nor logs private message data. */
public final class ChatHistoryParser {
    private ChatHistoryParser() {}

    /** Accepts either the API envelope or its response object; rev=0 history is newest first. */
    public static List<ChatMessage> parse(JSONObject response, long peerId) {
        if (response == null) return Collections.emptyList();
        JSONObject data = response.optJSONObject("response");
        if (data == null) data = response;
        Map<Long, Person> people = people(data);
        JSONArray items = data.optJSONArray("items");
        if (items == null) return Collections.emptyList();
        List<ChatMessage> messages = new ArrayList<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject row = items.optJSONObject(index);
            if (row == null) continue;
            boolean out = flag(row, "out", false);
            long from = row.has("from_id") ? row.optLong("from_id", 0)
                    : out ? 0 : row.optLong("user_id", row.optLong("uid", peerId));
            Person person = person(people, from, out);
            List<ChatAttachment> attachments = attachments(row.optJSONArray("attachments"));
            JSONObject geo = row.optJSONObject("geo");
            if (geo != null) attachments.add(geo(geo));
            String body = string(row, "text", string(row, "body", ""));
            JSONObject reply = row.optJSONObject("reply_message");
            if (reply != null) body = append(body, "Ответ · " + summary(reply, people, 0));
            JSONArray forwarded = row.optJSONArray("fwd_messages");
            if (forwarded != null && forwarded.length() > 0) {
                for (int part = 0; part < Math.min(5, forwarded.length()); part++) {
                    JSONObject forwardedMessage = forwarded.optJSONObject(part);
                    body = append(body, "Переслано · " + (forwardedMessage == null
                            ? "Сообщение недоступно" : summary(forwardedMessage, people, 0)));
                }
                if (forwarded.length() > 5) body = append(body, "Ещё пересланных сообщений: " + (forwarded.length() - 5));
            }
            if (body.trim().isEmpty() && attachments.isEmpty()) {
                JSONObject action = row.optJSONObject("action");
                body = action != null ? action(action) : flag(row, "is_deleted", false)
                        || flag(row, "deleted", false) ? "Сообщение удалено" : "Сообщение недоступно";
            }
            messages.add(new ChatMessage(row.optLong("id", row.optLong("mid", 0)), out, from,
                    person.name, person.avatar, body, Math.max(0, row.optLong("date", 0)), attachments,
                    out && row.has("read_state") ? row.optInt("read_state", -1) : -1));
        }
        Collections.reverse(messages);
        // Stable sort preserves the reversed API order for messages with the same second.
        messages.sort(Comparator.comparingLong(message -> message.date));
        return Collections.unmodifiableList(messages);
    }

    private static Map<Long, Person> people(JSONObject data) {
        Map<Long, Person> people = new HashMap<>();
        JSONArray profiles = data.optJSONArray("profiles");
        if (profiles != null) for (int index = 0; index < profiles.length(); index++) {
            JSONObject row = profiles.optJSONObject(index);
            if (row == null) continue;
            long id = row.optLong("id", 0);
            if (id == 0) continue;
            String name = (string(row, "first_name", "") + " " + string(row, "last_name", "")).trim();
            people.put(id, new Person(name.isEmpty() ? "Пользователь" : name, avatar(row)));
        }
        JSONArray groups = data.optJSONArray("groups");
        if (groups != null) for (int index = 0; index < groups.length(); index++) {
            JSONObject row = groups.optJSONObject(index);
            if (row == null) continue;
            long id = row.optLong("id", 0);
            if (id == 0 || id == Long.MIN_VALUE) continue;
            people.put(-Math.abs(id), new Person(nonempty(string(row, "name", ""), "Сообщество"), avatar(row)));
        }
        return people;
    }

    private static String avatar(JSONObject row) {
        return MessageAvatars.photoUrl(row);
    }

    private static Person person(Map<Long, Person> people, long id, boolean out) {
        Person known = people.get(id);
        return known != null ? known : new Person(out ? "Вы" : id < 0 ? "Сообщество" : "Пользователь", "");
    }

    private static List<ChatAttachment> attachments(JSONArray array) {
        List<ChatAttachment> attachments = new ArrayList<>();
        if (array != null) for (int index = 0; index < array.length(); index++) {
            JSONObject row = array.optJSONObject(index);
            attachments.add(row == null ? unavailable("unknown") : attachment(row));
        }
        return attachments;
    }

    private static ChatAttachment attachment(JSONObject row) {
        String type = nonempty(string(row, "type", ""), "unknown");
        JSONObject value = row.optJSONObject(type);
        if (value == null) return unavailable(type);
        long id = value.optLong("id", value.optLong("video_id", value.optLong("sticker_id", value.optLong("pid", 0))));
        long owner = value.optLong("owner_id", value.optLong("from_id", value.optLong("to_id", 0)));
        String key = string(value, "access_key", "");
        int duration = positiveInt(value, "duration");
        Image image = null;
        String title = string(value, "title", "");
        String subtitle = "";
        String url = string(value, "url", "");
        switch (type) {
            case "photo": case "posted_photo":
                image = photo(value);
                title = "Фотография";
                url = image == null ? "" : image.url;
                break;
            case "video": case "clip":
                image = best(value.optJSONArray("image"), false);
                if (image == null) image = best(value.optJSONArray("first_frame"), false);
                if (image == null) image = legacy(value,
                        new String[]{"photo_1280", "photo_800", "photo_640", "photo_320", "photo_130",
                                "first_frame_1280", "first_frame_800", "first_frame_320", "first_frame_130", "image_medium", "image", "thumb"});
                title = nonempty(title, "Видео");
                subtitle = duration > 0 ? "Видео · " + time(duration) : "Видео";
                url = first(string(value, "player", ""), url,
                        id != 0 && owner != 0 ? "https://vk.com/video" + owner + "_" + id : "");
                break;
            case "sticker":
                image = best(value.optJSONArray("images"), false);
                if (image == null) image = best(value.optJSONArray("images_with_background"), false);
                if (image == null) image = legacy(value, new String[]{"photo_512", "photo_256", "photo_128", "photo_64"});
                title = "Стикер";
                url = image == null ? "" : image.url;
                break;
            case "doc":
                JSONObject preview = value.optJSONObject("preview");
                if (preview != null) {
                    image = photo(preview.optJSONObject("photo"));
                    if (image == null) image = legacy(preview.optJSONObject("graffiti"), new String[]{"src", "url"});
                    JSONObject voice = preview.optJSONObject("audio_msg");
                    if (voice != null) {
                        type = "audio_message";
                        duration = positiveInt(voice, "duration");
                        title = "Голосовое сообщение";
                        url = first(string(voice, "link_mp3", ""), string(voice, "link_ogg", ""), url);
                        image = null;
                    }
                }
                title = nonempty(title, "Документ");
                String ext = string(value, "ext", "").toUpperCase(Locale.ROOT);
                subtitle = join(ext, fileSize(value.optLong("size", 0)));
                if (duration > 0) subtitle = join(subtitle, time(duration));
                break;
            case "audio":
                title = nonempty(title, "Аудиозапись");
                subtitle = join(string(value, "artist", ""), duration > 0 ? time(duration) : "");
                break;
            case "audio_message":
                title = "Голосовое сообщение";
                subtitle = duration > 0 ? time(duration) : "";
                url = first(string(value, "link_mp3", ""), string(value, "link_ogg", ""), url);
                break;
            case "link":
                image = photo(value.optJSONObject("photo"));
                title = nonempty(title, "Ссылка");
                subtitle = string(value, "description", string(value, "caption", ""));
                break;
            case "wall": case "wall_reply":
                title = "wall".equals(type) ? "Запись на стене" : "Комментарий";
                subtitle = excerpt(string(value, "text", ""));
                url = first(url, owner != 0 && id != 0 ? "https://vk.com/wall" + owner + "_" + id : "");
                image = nestedPhoto(value.optJSONArray("attachments"));
                break;
            case "gift":
                title = "Подарок";
                image = legacy(value, new String[]{"thumb_512", "thumb_256", "thumb_96", "thumb_48"});
                break;
            case "graffiti":
                title = "Граффити";
                image = legacy(value, new String[]{"url", "src", "photo_586", "photo_200"});
                break;
            case "poll":
                title = nonempty(string(value, "question", ""), "Опрос");
                subtitle = "Опрос";
                break;
            case "album": case "market_album":
                title = nonempty(title, "Альбом");
                image = photo(value.optJSONObject("thumb"));
                break;
            case "market":
                title = nonempty(title, "Товар");
                image = photo(value.optJSONObject("main_photo"));
                if (image == null) image = legacy(value, new String[]{"thumb_photo"});
                JSONObject price = value.optJSONObject("price");
                subtitle = price == null ? "Товар" : string(price, "text", "Товар");
                break;
            case "article":
                title = nonempty(title, "Статья");
                image = photo(value.optJSONObject("photo"));
                subtitle = "Статья";
                url = first(url, string(value, "view_url", ""));
                break;
            default:
                title = nonempty(title, label(type));
                subtitle = string(value, "description", "");
                image = photo(value.optJSONObject("photo"));
                break;
        }
        boolean restricted = flag(value, "is_deleted", false) || flag(value, "deleted", false)
                || value.optInt("content_restricted", 0) != 0;
        if (restricted) {
            title = nonempty(title, label(type)) + " · недоступно";
            url = "";
            image = null;
            id = 0;
            owner = 0;
            key = "";
        }
        int width = image != null && image.width > 0 && image.height > 0 ? image.width : positiveInt(value, "width");
        int height = image != null && image.width > 0 && image.height > 0 ? image.height : positiveInt(value, "height");
        if (width == 0 || height == 0) { width = 0; height = 0; }
        return new ChatAttachment(type, title, subtitle, image == null ? "" : image.url,
                url, width, height, duration, id, owner, key, restricted ? null : viewerPhoto(type, value));
    }

    private static ViewerPhoto viewerPhoto(String type, JSONObject value) {
        if ("photo".equals(type) || "posted_photo".equals(type)) return ViewerPhoto.parse(value);
        JSONObject photo = value.optJSONObject("photo");
        if (photo == null && "market".equals(type)) photo = value.optJSONObject("main_photo");
        if (photo == null && ("album".equals(type) || "market_album".equals(type))) photo = value.optJSONObject("thumb");
        if (photo == null && ("wall".equals(type) || "wall_reply".equals(type))) {
            JSONArray attachments = value.optJSONArray("attachments");
            if (attachments != null) for (int i = 0; i < attachments.length(); i++) {
                JSONObject attachment = attachments.optJSONObject(i);
                if (attachment != null && "photo".equals(attachment.optString("type"))) {
                    photo = attachment.optJSONObject("photo");
                    if (photo != null) break;
                }
            }
        }
        return photo == null ? null : ViewerPhoto.parse(photo);
    }

    private static ChatAttachment unavailable(String type) {
        return new ChatAttachment(type, label(type) + " недоступно", "", "", "", 0, 0, 0, 0, 0, "");
    }

    private static String label(String type) {
        switch (type) {
            case "photo": case "posted_photo": return "Фотография";
            case "video": case "clip": return "Видео";
            case "sticker": return "Стикер";
            case "doc": return "Документ";
            case "audio": return "Аудиозапись";
            case "audio_message": return "Голосовое сообщение";
            case "wall": return "Запись на стене";
            case "wall_reply": return "Комментарий";
            case "gift": return "Подарок";
            case "link": return "Ссылка";
            case "poll": return "Опрос";
            case "story": return "История";
            case "note": return "Заметка";
            case "page": return "Страница";
            default: return "Вложение";
        }
    }

    private static Image nestedPhoto(JSONArray attachments) {
        if (attachments == null) return null;
        for (int index = 0; index < attachments.length(); index++) {
            JSONObject row = attachments.optJSONObject(index);
            if (row != null && "photo".equals(string(row, "type", ""))) {
                Image image = photo(row.optJSONObject("photo"));
                if (image != null) return image;
            }
        }
        return null;
    }

    private static Image photo(JSONObject photo) {
        if (photo == null) return null;
        Image sized = best(photo.optJSONArray("sizes"), true);
        return sized != null ? sized : legacy(photo, new String[]{"photo_2560", "photo_1280", "photo_807",
                "photo_604", "src_xxbig", "src_xbig", "src_big", "photo_130", "src", "photo_75", "url"});
    }

    private static Image best(JSONArray array, boolean preferOriginalAspect) {
        if (array == null) return null;
        Image best = null;
        for (int index = 0; index < array.length(); index++) {
            JSONObject row = array.optJSONObject(index);
            if (row == null) continue;
            String url = first(string(row, "url", ""), string(row, "src", ""));
            if (url.isEmpty()) continue;
            String type = string(row, "type", "");
            boolean cropped = preferOriginalAspect && ("o".equals(type) || "p".equals(type)
                    || "q".equals(type) || "r".equals(type));
            Image candidate = new Image(url, positiveInt(row, "width"), positiveInt(row, "height"), cropped);
            if (best == null || best.cropped && !candidate.cropped
                    || best.cropped == candidate.cropped && candidate.area() > best.area()) best = candidate;
        }
        return best;
    }

    private static Image legacy(JSONObject object, String[] keys) {
        if (object == null) return null;
        for (String key : keys) {
            String url = string(object, key, "");
            if (!url.isEmpty()) return new Image(url, positiveInt(object, "width"), positiveInt(object, "height"), false);
        }
        return null;
    }

    private static ChatAttachment geo(JSONObject geo) {
        JSONObject place = geo.optJSONObject("place");
        String title = place == null ? "Местоположение" : nonempty(string(place, "title", ""), "Местоположение");
        String subtitle = place == null ? "" : string(place, "address", "");
        return new ChatAttachment("geo", title, subtitle, "", "", 0, 0, 0, 0, 0, "");
    }

    private static String summary(JSONObject message, Map<Long, Person> people, int depth) {
        long from = message.optLong("from_id", message.optLong("user_id", message.optLong("uid", 0)));
        String author = person(people, from, flag(message, "out", false)).name;
        String text = excerpt(string(message, "text", string(message, "body", "")));
        if (text.isEmpty()) {
            List<ChatAttachment> attachments = attachments(message.optJSONArray("attachments"));
            for (ChatAttachment attachment : attachments) text = join(text, attachment.title);
        }
        if (text.isEmpty() && depth < 2) {
            JSONObject reply = message.optJSONObject("reply_message");
            if (reply != null) text = "Ответ: " + summary(reply, people, depth + 1);
            JSONArray forwarded = message.optJSONArray("fwd_messages");
            if (text.isEmpty() && forwarded != null && forwarded.length() > 0) {
                JSONObject first = forwarded.optJSONObject(0);
                text = first == null ? "Пересланное сообщение" : "Переслано: " + summary(first, people, depth + 1);
            }
        }
        return author + "\n" + nonempty(excerpt(text), "Сообщение недоступно");
    }

    private static String action(JSONObject action) {
        String text = string(action, "text", "");
        switch (string(action, "type", "")) {
            case "chat_create": return "Беседа создана" + (text.isEmpty() ? "" : ": " + text);
            case "chat_title_update": return "Название беседы изменено" + (text.isEmpty() ? "" : ": " + text);
            case "chat_photo_update": return "Фотография беседы обновлена";
            case "chat_photo_remove": return "Фотография беседы удалена";
            case "chat_invite_user": case "chat_invite_user_by_link": return "Участник добавлен в беседу";
            case "chat_kick_user": return "Участник покинул беседу";
            case "chat_pin_message": return "Сообщение закреплено";
            case "chat_unpin_message": return "Сообщение откреплено";
            default: return "Событие беседы";
        }
    }

    private static String string(JSONObject object, String key, String fallback) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : fallback;
    }

    private static boolean flag(JSONObject object, String key, boolean fallback) {
        Object value = object.opt(key);
        return value instanceof Boolean ? (Boolean) value : value instanceof Number
                ? ((Number) value).intValue() != 0 : fallback;
    }

    private static int positiveInt(JSONObject object, String key) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, object.optLong(key, 0)));
    }

    private static String nonempty(String value, String fallback) {
        return value.trim().isEmpty() ? fallback : value;
    }

    private static String first(String... values) {
        for (String value : values) if (!value.isEmpty()) return value;
        return "";
    }

    private static String append(String base, String line) {
        return base.isEmpty() ? line : base + "\n\n" + line;
    }

    private static String join(String left, String right) {
        return left.isEmpty() ? right : right.isEmpty() ? left : left + " · " + right;
    }

    private static String excerpt(String value) {
        String text = value.replaceAll("\\s+", " ").trim();
        return text.codePointCount(0, text.length()) <= 240 ? text
                : text.substring(0, text.offsetByCodePoints(0, 240)) + "…";
    }

    private static String time(int seconds) {
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String fileSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " Б";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f КБ", bytes / 1024d);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f МБ", bytes / (1024d * 1024));
        return String.format(Locale.ROOT, "%.1f ГБ", bytes / (1024d * 1024 * 1024));
    }

    private static final class Person {
        final String name;
        final String avatar;
        Person(String name, String avatar) { this.name = name; this.avatar = avatar; }
    }

    private static final class Image {
        final String url;
        final int width;
        final int height;
        final boolean cropped;
        Image(String url, int width, int height, boolean cropped) {
            this.url = url; this.width = width; this.height = height; this.cropped = cropped;
        }
        long area() { return (long) width * height; }
    }
}
