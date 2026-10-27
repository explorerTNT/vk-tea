package com.vktea.msg;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Parses display data only; avatar hydration belongs to the dialog screen. */
public final class DialogsParser {
    private static final long CHAT_BASE = 2_000_000_000L;
    private DialogsParser() {}

    public static List<Dialog> parse(JSONObject envelope) {
        if (envelope == null) return Collections.emptyList();
        JSONObject data = envelope.optJSONObject("response");
        if (data == null) data = envelope;
        Map<Long, JSONObject> people = new HashMap<>();
        people(people, data.optJSONArray("profiles"), false);
        people(people, data.optJSONArray("p"), false);
        people(people, data.optJSONArray("groups"), true);
        JSONObject itemsHolder = data.optJSONObject("a");
        JSONArray items = (itemsHolder == null ? data : itemsHolder).optJSONArray("items");
        if (items == null) return Collections.emptyList();
        List<Dialog> dialogs = new ArrayList<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject row = items.optJSONObject(index);
            if (row == null) continue;
            JSONObject conversation = row.optJSONObject("conversation");
            JSONObject message = row.optJSONObject("last_message");
            if (message == null) message = row.optJSONObject("message");
            if (message == null) {
                if (conversation == null) continue;
                message = new JSONObject();
            }
            long peerId = peerId(message, conversation);
            if (peerId == 0) continue;
            JSONObject profile = people.get(peerId);
            boolean chat = peerId > CHAT_BASE;
            JSONObject settings = conversation == null ? null : conversation.optJSONObject("chat_settings");
            String title = chat ? first(text(settings, "title"), text(message, "title"), "Беседа")
                    : first(name(profile), text(message, "title"), peerId < 0 ? "Сообщество" : "...");
            String photo = chat ? first(MessageAvatars.photoUrl(settings), MessageAvatars.photoUrl(message))
                    : first(MessageAvatars.photoUrl(profile), MessageAvatars.photoUrl(message));
            boolean unread = conversation != null && conversation.has("unread_count")
                    ? conversation.optInt("unread_count", 0) > 0 : row.has("unread")
                    ? row.optInt("unread", 0) > 0
                    : !flag(message, "out", false) && !flag(message, "read_state", true);
            int unreadCount = conversation != null && conversation.has("unread_count")
                    ? Math.max(0, conversation.optInt("unread_count", 0)) : row.has("unread")
                    ? Math.max(0, row.optInt("unread", 0)) : -1;
            dialogs.add(new Dialog(peerId, title, photo, first(text(message, "text"), text(message, "body")),
                    Math.max(0, message.optLong("date", 0)), unread, unreadCount));
        }
        return dialogs;
    }

    private static long peerId(JSONObject message, JSONObject conversation) {
        // A legacy chat message also contains the sender's user_id. It must not
        // turn the conversation into that sender's personal dialog/avatar.
        long chatId = message.optLong("chat_id", 0);
        if (chatId > 0) return chatId <= Long.MAX_VALUE - CHAT_BASE ? CHAT_BASE + chatId : 0;
        JSONObject peer = conversation == null ? null : conversation.optJSONObject("peer");
        long id = peer == null ? 0 : peer.optLong("id", 0);
        if (id == 0) id = message.optLong("peer_id", 0);
        return id != 0 ? id : message.optLong("user_id", message.optLong("uid", 0));
    }

    private static void people(Map<Long, JSONObject> people, JSONArray rows, boolean groups) {
        if (rows == null) return;
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row == null) continue;
            long id = row.optLong("id", 0);
            if (id == 0 || id == Long.MIN_VALUE) continue;
            people.put(groups ? -Math.abs(id) : id, row);
        }
    }

    private static String name(JSONObject profile) {
        String fullName = (text(profile, "first_name") + " " + text(profile, "last_name")).trim();
        return first(text(profile, "name"), fullName);
    }

    private static String text(JSONObject object, String key) {
        Object value = object == null ? null : object.opt(key);
        return value instanceof String ? (String) value : "";
    }

    private static String first(String... values) {
        for (String value : values) if (!value.trim().isEmpty()) return value;
        return "";
    }

    private static boolean flag(JSONObject object, String key, boolean fallback) {
        Object value = object.opt(key);
        return value instanceof Boolean ? (Boolean) value : value instanceof Number
                ? ((Number) value).intValue() != 0 : fallback;
    }
}
