package com.vktea.msg;

public class Dialog {

    public final long peerId;
    public String title;
    public String photo;
    public final String excerpt;
    public final long date;
    public final boolean unread;

    public Dialog(long peerId, String title, String photo, String excerpt, long date, boolean unread) {
        this.peerId = peerId;
        this.title = title;
        this.photo = photo;
        this.excerpt = excerpt;
        this.date = date;
        this.unread = unread;
    }
}
