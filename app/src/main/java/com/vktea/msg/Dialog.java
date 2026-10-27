package com.vktea.msg;

public class Dialog {

    public final long peerId;
    public String title;
    public String photo;
    public final String excerpt;
    public final long date;
    public final boolean unread;
    /** Exact count, or -1 when the API only supplies an unread flag. */
    public final int unreadCount;

    public Dialog(long peerId, String title, String photo, String excerpt, long date, boolean unread) {
        this(peerId, title, photo, excerpt, date, unread, -1);
    }

    public Dialog(long peerId, String title, String photo, String excerpt, long date, boolean unread, int unreadCount) {
        this.peerId = peerId;
        this.title = title;
        this.photo = photo;
        this.excerpt = excerpt;
        this.date = date;
        this.unread = unread;
        this.unreadCount = unreadCount;
    }
}
