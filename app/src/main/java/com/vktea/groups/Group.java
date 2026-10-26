package com.vktea.groups;

import com.vktea.msg.MessageAvatars;
import org.json.JSONObject;

/** Immutable extended VK community; management uses actual account roles. */
public final class Group {
    public final long id, membersCount;
    public final String name, photo, screenName, activity, type;
    public final int isClosed, adminLevel;
    public final boolean isAdmin, verified;

    public Group(long id, String name, String photo, String screenName, String activity, String type,
                 int isClosed, boolean isAdmin, int adminLevel, long membersCount, boolean verified) {
        this.id = id;
        String cleanName = clean(name);
        this.name = cleanName.isEmpty() ? "Сообщество" : cleanName;
        this.photo = clean(photo); this.screenName = clean(screenName);
        this.activity = clean(activity); this.type = clean(type);
        this.isClosed = Math.max(0, isClosed); this.isAdmin = isAdmin;
        this.adminLevel = Math.max(0, adminLevel); this.membersCount = Math.max(-1, membersCount);
        this.verified = verified;
    }

    public boolean isManaged() { return isAdmin || adminLevel > 0; }

    static Group parse(JSONObject row) {
        if (row == null || row.optLong("id", 0) <= 0 || !text(row, "deactivated").isEmpty()) return null;
        return new Group(row.optLong("id"), text(row, "name"), MessageAvatars.photoUrl(row),
                text(row, "screen_name"), text(row, "activity"), text(row, "type"),
                row.optInt("is_closed", 0), flag(row, "is_admin"), row.optInt("admin_level", 0),
                row.optLong("members_count", -1), flag(row, "verified"));
    }
    private static boolean flag(JSONObject row, String key) {
        Object value = row.opt(key);
        return Boolean.TRUE.equals(value) || value instanceof Number && ((Number) value).intValue() == 1;
    }
    private static String text(JSONObject row, String key) { Object value = row.opt(key); return value instanceof String ? (String) value : ""; }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
