package com.vktea.msg;

/** URLs are retained exactly as provided; presentation must not rewrite CDN crop/signature parameters. */
public final class ChatAttachment {
    public final String type;
    public final String title;
    public final String subtitle;
    public final String previewUrl;
    public final String url;
    public final int width;
    public final int height;
    public final int duration;
    public final long id;
    public final long ownerId;
    public final String accessKey;

    public ChatAttachment(String type, String title, String subtitle, String previewUrl, String url,
                          int width, int height, int duration, long id, long ownerId, String accessKey) {
        this.type = type == null || type.isEmpty() ? "unknown" : type;
        this.title = title == null || title.trim().isEmpty() ? "Вложение" : title;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.previewUrl = previewUrl == null ? "" : previewUrl;
        this.url = url == null ? "" : url;
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        this.duration = Math.max(0, duration);
        this.id = id;
        this.ownerId = ownerId;
        this.accessKey = accessKey == null ? "" : accessKey;
    }
}
