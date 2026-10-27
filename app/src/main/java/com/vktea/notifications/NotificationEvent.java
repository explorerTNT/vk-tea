package com.vktea.notifications;

/** Displayable event. An empty URL means there is no known safe target. */
public final class NotificationEvent {
    public final String type, actorName, actorPhoto, text, parentText, url;
    public final long date;
    public NotificationEvent(String type, long date, String actorName, String actorPhoto, String text, String parentText, String url) {
        this.type = type; this.date = date; this.actorName = actorName; this.actorPhoto = actorPhoto;
        this.text = text; this.parentText = parentText; this.url = url;
    }
}
