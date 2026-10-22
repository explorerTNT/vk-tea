package com.vktea.feed;

public class Post {

    public final long sourceId;
    public final String sourceName;
    public final String sourcePhoto;
    public final String text;
    public final long date;
    public final String photoUrl;
    public final int likes;
    public final int comments;
    public final int reposts;
    public final int views;

    public Post(long sourceId, String sourceName, String sourcePhoto, String text, long date,
                String photoUrl, int likes, int comments, int reposts, int views) {
        this.sourceId = sourceId;
        this.sourceName = sourceName;
        this.sourcePhoto = sourcePhoto;
        this.text = text;
        this.date = date;
        this.photoUrl = photoUrl;
        this.likes = likes;
        this.comments = comments;
        this.reposts = reposts;
        this.views = views;
    }
}
