package com.vktea.friends;

import com.vktea.msg.MessageAvatars;
import org.json.JSONObject;

/** An immutable friend row from VK; online state is never inferred from a timestamp. */
public final class Friend {
    public final long id, lastSeen;
    public final String firstName, lastName, name, photo, status;
    public final boolean online, onlineMobile;
    public final int platform;

    public Friend(long id, String firstName, String lastName, String photo, boolean online,
                  boolean onlineMobile, long lastSeen, int platform, String status) {
        this.id = id;
        this.firstName = clean(firstName);
        this.lastName = clean(lastName);
        String name = (this.firstName + " " + this.lastName).trim();
        this.name = name.isEmpty() ? "Пользователь" : name;
        this.photo = clean(photo);
        this.online = online;
        this.onlineMobile = onlineMobile;
        this.lastSeen = Math.max(0, lastSeen);
        this.platform = Math.max(0, platform);
        this.status = clean(status);
    }

    static Friend parse(JSONObject row) {
        if (row == null || row.optLong("id", 0) <= 0 || !text(row, "deactivated").isEmpty()) return null;
        JSONObject seen = row.optJSONObject("last_seen");
        return new Friend(row.optLong("id"), text(row, "first_name"), text(row, "last_name"),
                MessageAvatars.photoUrl(row), flag(row, "online"), flag(row, "online_mobile"),
                seen == null ? 0 : seen.optLong("time", 0), seen == null ? 0 : seen.optInt("platform", 0), text(row, "status"));
    }
    private static boolean flag(JSONObject row, String key) {
        Object value = row.opt(key);
        return Boolean.TRUE.equals(value) || value instanceof Number && ((Number) value).intValue() == 1;
    }
    private static String text(JSONObject row, String key) { Object value = row.opt(key); return value instanceof String ? (String) value : ""; }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
